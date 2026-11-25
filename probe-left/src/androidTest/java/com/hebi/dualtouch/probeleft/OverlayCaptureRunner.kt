package com.hebi.dualtouch.probeleft

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Debug instrumentation installed with the optional counter APK only. */
class OverlayCaptureRunner : Instrumentation() {
    companion object {
        private const val APP = "com.hebi.dualtouch"
        private const val LEFT = "com.hebi.dualtouch.probeleft"
        private const val RIGHT = "com.hebi.dualtouch.proberight"
        private const val SERVICE = "$APP/.TouchMirrorService"
        private const val SETTINGS = "com.android.settings"
        private const val PROBE_ACTIVITY = "com.hebi.dualtouch.probe.ProbeActivity"
        private const val WMSHELL = "dumpsys activity service SystemUIService WMShell"
    }

    private lateinit var automation: UiAutomation
    private lateinit var output: File
    private var source = ""
    private var scenario = ""
    private var stage = "setup"
    private val captures = JSONArray()
    private val observations = JSONArray()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        source = arguments?.getString("source_sha").orEmpty()
        scenario = arguments?.getString("scenario").orEmpty()
        start()
    }

    override fun onStart() {
        output = File(targetContext.filesDir, "overlay-capture").apply {
            deleteRecursively()
            mkdirs()
        }
        var error: String? = null
        try {
            check(Regex("[a-f0-9]{40}").matches(source)) { "Missing exact source SHA" }
            check(targetContext.packageName == LEFT)
            check(scenario in setOf("countdown", "calibration", "home-return")) { "Unknown scenario" }
            automation = checkNotNull(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
            val info = checkNotNull(automation.serviceInfo)
            val observerOriginallyRequestedKeys =
                (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
            info.flags = (info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) and
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            automation.serviceInfo = info
            val observerFlags = checkNotNull(automation.serviceInfo).flags
            check((observerFlags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0) {
                "Fixture observer must not request key filtering"
            }
            observations.put(JSONObject().put("observer_requested_key_filter_before", observerOriginallyRequestedKeys)
                .put("observer_requests_key_filter_after", false))
            runCapture()
        } catch (failure: Exception) {
            error = "$stage: ${failure.message ?: failure.javaClass.simpleName}"
            if (::automation.isInitialized) {
                runCatching {
                    observations.put(JSONObject().put("failed_stage", stage)
                        .put("fixture_foreground", activeApplicationPackage()?.takeIf { it in setOf(APP, SETTINGS, LEFT, RIGHT) } ?: "other_or_unknown")
                        .put("service_enabled_in_settings", serviceEnabledInSettings())
                        .put("controller_thumb_state", visibleNodes().firstOrNull {
                            it.pkg == APP && it.description.startsWith("DualTouch Thumb.")
                        }?.description ?: JSONObject.NULL)
                        .put("fixture_visible_labels", JSONArray(visibleNodes()
                            .filter { it.pkg in setOf(APP, SETTINGS, LEFT, RIGHT) }
                            .map { it.text }.filter { it.isNotBlank() }.distinct().take(60))))
                }
                runCatching { screenshot("diagnostic-failure", "diagnostic only; not a design match", false) }
            }
        } finally {
            if (::automation.isInitialized) {
                // Cleanup only, never evidence of emergency-stop success.
                runCatching { shell("am force-stop $APP") }
            }
            writeCaptureReport("report.json", error == null, error)
            finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", "\nOverlay capture: ${error ?: "completed"}\n") })
        }
    }

    private fun writeCaptureReport(filename: String, completed: Boolean, error: String?) {
        val report = JSONObject()
            .put("source_commit", source)
            .put("scenario", scenario)
            .put("android_api", android.os.Build.VERSION.SDK_INT)
            .put("environment", "disposable Android emulator; real probe app windows")
            .put("physical_foldable_verified", false)
            .put("touch_mirroring_verified", false)
            .put("captures", captures)
            .put("observations", observations)
            .put("completed", completed)
            .put("error", error ?: JSONObject.NULL)
        val atomic = android.util.AtomicFile(File(output, filename))
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun checkpoint() =
        writeCaptureReport("checkpoint.json", false, "Scenario has not completed")

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun waitFor(description: String, timeoutMs: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timeout: $description")
    }

    private data class VisibleNode(val pkg: String, val text: String, val description: String, val bounds: Rect)

    private fun visibleNodes(): List<VisibleNode> {
        val result = mutableListOf<VisibleNode>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || result.size >= 3000) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (node.isVisibleToUser && !rect.isEmpty) {
                result.add(VisibleNode(node.packageName?.toString().orEmpty(),
                    node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), rect))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        for (window in automation.windows) {
            val root = window.root ?: continue
            try { visit(root, 0) } finally { root.recycle() }
        }
        return result
    }

    private fun awaitNode(description: String, timeoutMs: Long = 15000, predicate: (VisibleNode) -> Boolean): VisibleNode {
        var found: VisibleNode? = null
        waitFor(description, timeoutMs) {
            found = visibleNodes().firstOrNull(predicate)
            found != null
        }
        return requireNotNull(found)
    }

    private fun tap(node: VisibleNode) {
        shell("input tap ${node.bounds.centerX()} ${node.bounds.centerY()}")
        SystemClock.sleep(450)
    }

    private fun tapText(pkg: String, value: String) = tap(awaitNode(value) { it.pkg == pkg && it.text == value })

    // Use only an actual scrollable widget in the expected package, before enabling
    // touch mirroring. This avoids blind swipes or injected app preference changes.
    private fun scrollForward(pkg: String): Boolean {
        fun scroll(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 40) return false
            if (node.packageName?.toString() == pkg && node.isVisibleToUser &&
                node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (scroll(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == pkg && scroll(root, 0)) return true
            } finally { root.recycle() }
        }
        return false
    }

    private fun awaitWithScroll(pkg: String, description: String, predicate: (VisibleNode) -> Boolean): VisibleNode {
        repeat(8) { attempt ->
            val found = runCatching {
                awaitNode(description, 1800) { it.pkg == pkg && predicate(it) }
            }.getOrNull()
            if (found != null) return found
            if (attempt < 7) {
                val moved = scrollForward(pkg)
                observations.put(JSONObject().put("scroll_stage", stage)
                    .put("attempt", attempt + 1).put("scroll_action_accepted", moved))
                SystemClock.sleep(500)
            }
        }
        error("Control not visible after bounded scroll: $description")
    }

    private fun tapWithScroll(pkg: String, value: String) =
        tap(awaitWithScroll(pkg, value) { it.text == value })

    private fun thumb(label: String) = awaitNode("Thumb $label") {
        it.pkg == APP && it.text == label && it.description.startsWith("DualTouch Thumb.")
    }

    private fun startApp(pkg: String, activity: String, extraFlags: String = "") {
        val result = shell("am start -W $extraFlags -n $pkg/$activity")
        check(!result.contains("Error:", ignoreCase = true)) { "Activity could not be launched: $pkg" }
        SystemClock.sleep(900)
    }

    private fun probeWindows(): Map<String, Rect> {
        val pairs = mutableMapOf<String, Rect>()
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val pkg = try { root.packageName?.toString() } finally { root.recycle() }
            if (pkg != LEFT && pkg != RIGHT) continue
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) pairs[pkg!!] = bounds
        }
        return pairs
    }

    private fun splitVisible(): Boolean {
        val windows = probeWindows()
        val left = windows[LEFT] ?: return false
        val right = windows[RIGHT] ?: return false
        if (Rect.intersects(left, right)) return false
        return left.width() >= 200 && left.height() >= 200 && right.width() >= 200 && right.height() >= 200
    }

    private fun requireSplit() = check(splitVisible()) { "Two disjoint, visible probe windows required" }

    private fun taskId(pkg: String): Int {
        val dump = shell("dumpsys activity activities")
        val candidates = dump.lineSequence()
            .filter { it.contains("Task{") && Regex("[:=]" + Regex.escape(pkg) + "(?:[ /}])").containsMatchIn(it) }
            .mapNotNull { Regex("#(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct().toList()
        check(candidates.size == 1) { "Unable to identify a unique probe task for $pkg" }
        return candidates.single()
    }

    private fun openSplit() {
        startApp(RIGHT, PROBE_ACTIVITY)
        startApp(LEFT, PROBE_ACTIVITY)
        // Try the OS's supported launch-adjacent path first.
        startApp(RIGHT, PROBE_ACTIVITY, "-f 0x10001000")
        SystemClock.sleep(1200)
        if (!splitVisible()) {
            // Some emulator builds expose a test-only WMShell command.
            // Discover its availability before using it; never alter app safety state.
            startApp(LEFT, PROBE_ACTIVITY)
            val help = shell("$WMSHELL help") + "\n" + shell("$WMSHELL splitscreen help")
            observations.put(JSONObject().put("wmshell_move_to_side_stage_available", help.contains("moveToSideStage")))
            if (help.contains("moveToSideStage")) {
                val id = taskId(RIGHT)
                shell("$WMSHELL splitscreen moveToSideStage $id 1")
                SystemClock.sleep(1800)
            }
        }
        waitFor("actual Android split with both probe apps", 8000) { splitVisible() }
        observations.put(JSONObject().put("split_window_bounds",
            JSONObject().apply { for ((pkg, bounds) in probeWindows()) put(pkg, bounds.flattenToString()) }))
    }

    private fun activeApplicationPackage(): String? {
        val windows = automation.windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
        }.sortedByDescending { it.isActive }
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = try { root.packageName?.toString() } finally { root.recycle() }
            if (!pkg.isNullOrBlank()) return pkg
        }
        val root = automation.rootInActiveWindow ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }

    private fun serviceEnabledInSettings(): Boolean {
        val enabled = shell("settings get secure enabled_accessibility_services")
        return enabled.split(':').any {
            it.trim() == SERVICE || it.trim() == "$APP/$APP.TouchMirrorService"
        }
    }

    // Android Settings can be in the same task as MainActivity. A plain am start
    // may only bring that task forward, leaving Settings at the top.
    private fun returnToControllerHome() {
        check(serviceEnabledInSettings()) { "Service was not explicitly enabled" }
        repeat(6) { attempt ->
            val foreground = activeApplicationPackage()
            if (foreground == APP) return
            if (foreground != SETTINGS) return@repeat
            shell("input keyevent KEYCODE_BACK")
            SystemClock.sleep(700)
            observations.put(JSONObject().put("return_back_attempt", attempt + 1)
                .put("settings_still_foreground", activeApplicationPackage() == SETTINGS))
        }
        if (activeApplicationPackage() != APP) {
            // Leave the existing Settings task without force-stopping DualTouch;
            // the accepted system service and app disclosure must remain intact.
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(700)
            startApp(APP, ".MainActivity", "-f 0x14000000")
        }
        waitFor("DualTouch foreground after permission grant", 12000) {
            activeApplicationPackage() == APP
        }
    }

    // The Settings list may move after a scroll. Resolve the current native
    // row by exact label and invoke its real click action, rather than tap a
    // previously observed coordinate that may now belong to another row.
    private fun clickSettingsControl(labels: Set<String>): Boolean {
        if (activeApplicationPackage() != SETTINGS) return false

        fun clickRow(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            var ownsCurrent = false
            try {
                repeat(6) {
                    val item = current ?: return false
                    if (item.packageName?.toString() != SETTINGS ||
                        !item.isVisibleToUser || !item.isEnabled) return false
                    if (item.isClickable && item.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_CLICK
                    }) {
                        return item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    val parent = item.parent
                    if (ownsCurrent) item.recycle()
                    current = parent
                    ownsCurrent = parent != null
                }
                return false
            } finally {
                if (ownsCurrent) current?.recycle()
            }
        }

        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 40) return false
            if (node.packageName?.toString() == SETTINGS && node.isVisibleToUser &&
                node.text?.toString() in labels && clickRow(node)) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == SETTINGS && visit(root, 0)) return true
            } finally { root.recycle() }
        }
        return false
    }

    private fun openActualServiceDetails() {
        val services = setOf("DualTouch 실험 서비스", "DualTouch POC")
        val categories = setOf("Downloaded apps", "Installed apps", "Installed services",
            "설치된 앱", "설치된 서비스", "다운로드한 앱")
        var categoryOpened = false
        repeat(8) { attempt ->
            check(activeApplicationPackage() == SETTINGS) { "Android Settings is not foreground" }
            if (clickSettingsControl(services)) {
                observations.put(JSONObject().put("system_service_row_action_accepted", true))
                return
            }
            if (!categoryOpened && clickSettingsControl(categories)) {
                categoryOpened = true
                observations.put(JSONObject().put("system_service_category_action_accepted", true))
                SystemClock.sleep(900)
            } else if (attempt < 7) {
                observations.put(JSONObject().put("system_service_search_attempt", attempt + 1)
                    .put("scroll_action_accepted", scrollForward(SETTINGS)))
                SystemClock.sleep(600)
            }
        }
        error("Exact DualTouch service row could not be opened through Android Settings")
    }

    private fun recoverOnboardingLauncherAnr(): Boolean {
        // Isolated emulator, before any accessibility permission is granted.
        // Do not dismiss arbitrary dialogs or alter actual permission settings.
        if (shell("getprop ro.kernel.qemu").trim() != "1" || serviceEnabledInSettings()) return false
        val focus = shell("dumpsys window windows")
        val launcherAnr = Regex(
            """(?m)^\s*mCurrentFocus=Window\{[^\n]*Application Not Responding: com\.google\.android\.apps\.nexuslauncher\}"""
        )
        if (!launcherAnr.containsMatchIn(focus)) return false
        screenshot("diagnostic-launcher-anr", "confirmed launcher ANR before permission; not a design match", false)
        shell("am force-stop com.google.android.apps.nexuslauncher")
        SystemClock.sleep(1800)
        startApp(APP, ".MainActivity", "-f 0x14000000")
        observations.put(JSONObject().put("onboarding_launcher_anr_recovered", true))
        return true
    }

    private fun confirmActualDualTouchConsent() {
        check(!serviceEnabledInSettings()) { "Unexpected permission state before explicit consent" }
        // The confirmation may be a system dialog rather than TYPE_APPLICATION.
        // Identify the actual dialog and its live Allow button in the same
        // focused window. Use one explicit native touch, never a setting write.
        val title = awaitNode("DualTouch-specific system consent") {
            it.pkg == SETTINGS && it.text.contains("DualTouch") &&
                (it.text.contains("full control", ignoreCase = true) ||
                    it.text.contains("전체 제어") || it.text.contains("완전한 제어"))
        }
        val consent = awaitNode("system permission confirmation") {
            it.pkg == SETTINGS && it.text in setOf("Allow", "OK", "허용", "확인")
        }
        val current = visibleNodes().filter { it.pkg == SETTINGS }
        check(current.any { it.text == title.text } &&
            current.any { it.text == consent.text && it.bounds == consent.bounds }) {
            "Actual DualTouch permission dialog changed before confirmation"
        }
        check(activeApplicationPackage() == SETTINGS) { "Settings is no longer active" }
        tap(consent)
        observations.put(JSONObject().put("actual_consent_dialog_tapped", true))
    }

    private fun configureService() {
        stage = "permission flow / onboarding"
        startApp(APP, ".MainActivity")
        try {
            waitFor("DualTouch foreground before onboarding", 15000) {
                activeApplicationPackage() == APP && visibleNodes().any { it.pkg == APP }
            }
        } catch (failure: Exception) {
            if (!recoverOnboardingLauncherAnr()) throw failure
            waitFor("DualTouch foreground after exact launcher recovery", 15000) {
                activeApplicationPackage() == APP && visibleNodes().any { it.pkg == APP }
            }
        }
        observations.put(JSONObject().put("onboarding_foreground_verified", true))
        tapWithScroll(APP, "내용을 이해하고 계속")
        stage = "permission flow / disclosure"
        tapWithScroll(APP, "접근성 권한 설정")
        tapText(APP, "동의하고 설정 열기")
        // Traverse the real system Settings UI, including its confirmation.
        // No settings-put, run-as preference writes, or direct service activation.
        stage = "permission flow / system service list"
        waitFor("Android accessibility Settings foreground") { activeApplicationPackage() == SETTINGS }
        openActualServiceDetails()
        stage = "permission flow / system toggle"
        // Confirm that the service-specific screen opened before scrolling;
        // a category header or a failed navigation is not an enabled service.
        val toggle = awaitNode("DualTouch service-specific switch", 12000) {
            it.pkg == SETTINGS && (
                it.text.startsWith("Use DualTouch") ||
                it.text.startsWith("DualTouch 실험 서비스 사용")
            )
        }
        check(clickSettingsControl(setOf(toggle.text))) { "Actual service switch action was rejected" }
        stage = "permission flow / system confirmation"
        confirmActualDualTouchConsent()
        waitFor("system accessibility service enabled") { serviceEnabledInSettings() }
        observations.put(JSONObject().put("system_service_enabled_after_confirmation", true))
        stage = "permission flow / return from Settings"
        returnToControllerHome()
        stage = "permission flow / bound service on home"
        waitFor("connected permission on actual home", 20000) {
            activeApplicationPackage() == APP && visibleNodes().any {
                it.pkg == APP && it.text == "연결됨"
            }
        }
        check(serviceEnabledInSettings()) { "Service permission changed before Thumb setup" }
        observations.put(JSONObject().put("controller_home_permission_connected", true))
        stage = "permission flow / show Thumb"
        tapWithScroll(APP, "플로팅 표시")
        thumb("연결")
    }

    private fun verifyDesignState(name: String) {
        requireSplit()
        val nodes = visibleNodes().filter { it.pkg == APP }
        val thumb = nodes.firstOrNull { it.description.startsWith("DualTouch Thumb.") }
        fun text(value: String) = nodes.any { it.text == value }
        val valid = when (name) {
            "DES-06-thumb-ready" -> thumb != null && thumb.text == "대기" && thumb.description.contains("연결됨 · 실행 대기")
            "DES-07-thumb-menu" -> text("실행") && text("좌/우 선택 · 앱 연결") && text("영점 조절") && nodes.any { it.text.startsWith("연결됨 · 실행 대기") }
            "DES-08-pair-selection" -> text("현재 분할 앱 연결") && nodes.any { it.text.contains(LEFT) } && nodes.any { it.text.contains(RIGHT) } && nodes.any { it.text.endsWith("자동 연결") }
            "DES-09-thumb-running" -> thumb != null && Regex("(?:[1-9]|1[0-9]|20)초").matches(thumb.text) && thumb.description.contains("실행 중")
            "DES-10-calibration" -> text("영점 조절") &&
                nodes.any { it.text == "직접" && it.description.startsWith("직접 터치 보정점") } &&
                nodes.any { it.text == "자동" && it.description.startsWith("자동 터치 보정점") } &&
                nodes.any { it.text.startsWith("중앙 기준선 위치:") } &&
                nodes.none { it.text == "오류" && it.description.startsWith("DualTouch Thumb.") }
            else -> false
        }
        check(valid) { "Actual UI no longer matches $name" }
    }

    private fun screenshot(name: String, state: String, designMatch: Boolean = true) {
        if (designMatch) {
            verifyDesignState(name)
            // Accessibility state can be published before the corresponding
            // Surface frame is drawn. Require a second valid sample after a
            // render interval; visual review is still needed for pixel state.
            if (name != "DES-09-thumb-running") SystemClock.sleep(500L)
            verifyDesignState(name)
        }
        val image = checkNotNull(automation.takeScreenshot()) { "Screenshot unavailable" }
        val file = File(output, "$name.png")
        val width = image.width
        val height = image.height
        try {
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (designMatch) {
            try { verifyDesignState(name) } catch (failure: Exception) {
                file.delete()
                throw failure
            }
        }
        captures.put(JSONObject().put("file", file.name).put("state", state)
            .put("sha256", sha).put("width", width).put("height", height)
            .put("design_state_verified", designMatch))
        // Persist the verified state and digest before requesting any further
        // interaction; the host can recover partial evidence after failure.
        checkpoint()
    }

    // Fixture-only window evidence from the observer, not a substitute for the
    // application's independent ownership check. Never record arbitrary app names.
    private fun recordOverlayInventory(point: String) {
        runCatching {
            val result = JSONArray()
            for (window in automation.windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    window.type != AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY) continue
                val root = window.getRoot(0)
                val owner = try { root?.packageName?.toString() } finally { root?.recycle() }
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                result.put(JSONObject()
                    .put("type", window.type)
                    .put("window_id", window.id)
                    .put("layer", window.layer)
                    .put("bounds", bounds.flattenToString())
                    .put("owner_class", when (owner) {
                        APP -> "controller"
                        LEFT, RIGHT -> "probe"
                        null, "" -> "unavailable"
                        else -> "other"
                    }))
            }
            observations.put(JSONObject().put("overlay_inventory_stage", point)
                .put("observer_windows", result))
        }.onFailure {
            observations.put(JSONObject().put("overlay_inventory_stage", point)
                .put("observer_inventory_unavailable", true))
        }
    }

    private fun awaitPairApprovalAfterSearch(): VisibleNode {
        val directions = setOf("왼쪽 직접 → 오른쪽 자동 연결", "위쪽 직접 → 아래쪽 자동 연결")
        repeat(3) { attempt ->
            requireSplit()
            val result = awaitNode("pair selection outcome", 12000) {
                it.pkg == APP && (
                    it.text in directions ||
                    it.text.contains("화면 구조 검사 시간이 초과되었습니다.") ||
                    it.text.contains("앱의 화면 구조를 읽을 수 없어") ||
                    it.text.contains("다른 접근성 오버레이가 있어")
                )
            }
            if (result.text in directions) {
                observations.put(JSONObject().put("candidate_search_attempt", attempt + 1)
                    .put("candidate_outcome", "direction_visible"))
                return result
            }
            val timeout = result.text.contains("화면 구조 검사 시간이 초과되었습니다.")
            val overlayBlocked = result.text.contains("다른 접근성 오버레이가 있어")
            observations.put(JSONObject().put("candidate_search_attempt", attempt + 1)
                .put("candidate_outcome", when {
                    timeout -> "inspection_timeout"
                    overlayBlocked -> "overlay_ownership_blocked"
                    else -> "inspection_incomplete"
                }))
            if (overlayBlocked) recordOverlayInventory("pair_selection_rejected")
            // Retry only an explicit pre-approval timeout through the existing UI.
            // Never re-arm input or accept incomplete/sensitive/unknown windows.
            if (!timeout || attempt == 2) error("Candidate inspection blocked: " + result.text)
            SystemClock.sleep(1500)
            requireSplit()
            tapText(APP, "다시 검색")
        }
        error("No actual pair approval available")
    }

    private fun connectAndOpenReadyMenu(captureDesigns: Boolean) {
        stage = "app connection"
        SystemClock.sleep(1500)
        requireSplit()
        tap(thumb("연결"))
        awaitNode("connection menu") { it.pkg == APP && it.text == "좌/우 선택 · 앱 연결" }
        recordOverlayInventory("menu_before_pair_selection")
        tapText(APP, "좌/우 선택 · 앱 연결")
        awaitNode("pair selection title") { it.pkg == APP && it.text == "현재 분할 앱 연결" }
        requireSplit()
        val approval = awaitPairApprovalAfterSearch()
        val pairNodes = visibleNodes().filter { it.pkg == APP }
        check(pairNodes.any { it.text.contains(LEFT) } && pairNodes.any { it.text.contains(RIGHT) }) {
            "The approval panel must identify both actual probe packages"
        }
        if (captureDesigns) screenshot("DES-08-pair-selection", "two actual probe windows and explicit approval")
        tap(approval)

        stage = "ready thumb and menu"
        val result = awaitNode("actual approval result", 15000) {
            it.pkg == APP && (
                (it.description.startsWith("DualTouch Thumb.") &&
                    (it.text == "오류" || (it.text == "대기" && it.description.contains("연결됨 · 실행 대기"))))
                || it.text == "현재 분할 앱 연결"
            )
        }
        if (result.text != "대기") {
            recordOverlayInventory("after_explicit_approval_blocked")
            observations.put(JSONObject().put("approval_outcome", "blocked")
                .put("controller_thumb_state", visibleNodes().firstOrNull {
                    it.pkg == APP && it.description.startsWith("DualTouch Thumb.")
                }?.description ?: JSONObject.NULL))
            error("Explicit approval did not reach ready state: " + result.description.ifBlank { result.text })
        }
        observations.put(JSONObject().put("approval_outcome", "ready"))
        requireSplit()
        if (captureDesigns) screenshot("DES-06-thumb-ready", "approved pair; input not running")
        tap(thumb("대기"))
        awaitNode("ready menu") { it.pkg == APP && it.text.startsWith("연결됨 · 실행 대기") }
        awaitNode("execute button") { it.pkg == APP && it.text == "실행" }
        awaitNode("calibration button") { it.pkg == APP && it.text == "영점 조절" }
        requireSplit()
        if (captureDesigns) screenshot("DES-07-thumb-menu", "approved pair; three actions")
    }

    private fun captureCalibration() {
        stage = "calibration"
        recordOverlayInventory("before_calibration")
        tapText(APP, "영점 조절")
        val calibrationResult = awaitNode("calibration entry outcome", 15000) {
            it.pkg == APP && (
                it.text == "직접" && it.description.startsWith("직접 터치 보정점") ||
                it.text == "오류" && it.description.startsWith("DualTouch Thumb.")
            )
        }
        if (calibrationResult.text == "오류") {
            recordOverlayInventory("calibration_entry_blocked")
            error("Calibration blocked: " + calibrationResult.description)
        }
        awaitNode("automatic reference") { it.pkg == APP && it.text == "자동" && it.description.startsWith("자동 터치 보정점") }
        awaitNode("guide position") { it.pkg == APP && it.text.startsWith("중앙 기준선 위치:") }
        awaitNode("calibration title") { it.pkg == APP && it.text == "영점 조절" }
        recordOverlayInventory("calibration_visible")
        requireSplit()
        screenshot("DES-10-calibration", "real calibration overlay; both reference markers")
        stage = "calibration / cancel"
        tapText(APP, "취소")
        thumb("대기")
    }

    private fun captureCountdown() {
        stage = "countdown"
        requireSplit()
        tapText(APP, "실행")
        val running = awaitNode("actual active countdown or safety block", 7000) {
            it.pkg == APP && it.description.startsWith("DualTouch Thumb.") &&
                (it.text == "오류" || (Regex("(?:[1-9]|1[0-9]|20)초").matches(it.text) &&
                    it.description.contains("실행 중")))
        }
        check(running.text != "오류") { "Countdown blocked: " + running.description }
        requireSplit()
        screenshot("DES-09-thumb-running", "active real countdown; no mirrored taps issued")
    }

    private fun capturesContains(filename: String): Boolean =
        (0 until captures.length()).any { captures.getJSONObject(it).optString("file") == filename }

    private fun recordKeyFilterConfiguration() {
        // Read only enabled-service metadata. Do not record other service IDs
        // or change their flags. Enabled metadata is not proof of dispatch.
        runCatching {
            val manager = targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                ?: error("AccessibilityManager unavailable")
            val enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val own = enabled.filter { it.resolveInfo?.serviceInfo?.packageName == APP }
            val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            val capability = AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS
            observations.put(JSONObject().put("key_filter_metadata",
                JSONObject().put("controller_enabled_count", own.size)
                    .put("controller_requests_filter", own.any { (it.flags and flag) != 0 })
                    .put("controller_has_filter_capability", own.any { (it.capabilities and capability) != 0 })
                    .put("other_enabled_filter_requests", enabled.count {
                        it.resolveInfo?.serviceInfo?.packageName != APP && (it.flags and flag) != 0
                    })
                    .put("observer_requests_filter", (checkNotNull(automation.serviceInfo).flags and flag) != 0)))
        }.onFailure {
            observations.put(JSONObject().put("key_filter_metadata_unavailable", true))
        }
    }

    private fun homeAfterWindowChangeVisible(): Boolean {
        if (activeApplicationPackage() != APP || splitVisible()) return false
        val nodes = visibleNodes().filter { it.pkg == APP }
        val texts = nodes.map { it.text }
        return "현재 상태" in texts && "분할 앱 연결" in texts &&
            "연결 필요" in texts && "연결됨 · 실행 대기" !in texts &&
            texts.none { it == LEFT || it == RIGHT || it.endsWith("직접 → 오른쪽 자동") ||
                it.endsWith("직접 → 왼쪽 자동") || it.endsWith("직접 → 위쪽 자동") ||
                it.endsWith("직접 → 아래쪽 자동") } &&
            nodes.none { it.description.startsWith("DualTouch Thumb.") &&
                it.description.contains("실행 중") }
    }

    private fun controllerOverlayRemoved(): Boolean =
        automation.windows.none { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) false
            else {
                val root = window.getRoot(0)
                try { root == null || root.packageName?.toString() == APP }
                finally { root?.recycle() }
            }
        }

    private fun captureHomeReturn() {
        // This is a separate idle scenario. Never start mirroring, and never
        // keep a stale approval just to reproduce the original DES-04 draft.
        stage = "home-return / close ready menu"
        tap(thumb("대기"))
        waitFor("closed ready menu") {
            visibleNodes().none { it.pkg == APP && it.text == "좌/우 선택 · 앱 연결" }
        }
        requireSplit()
        stage = "home-return / actual window change"
        returnToControllerHome()
        waitFor("actual home without a stale approved pair", 15000) { homeAfterWindowChangeVisible() }
        SystemClock.sleep(500)
        check(homeAfterWindowChangeVisible()) { "Home state changed before capture" }
        screenshot("QA-home-after-window-change",
            "actual controller home after leaving approved split; not connected-home draft", false)
        check(homeAfterWindowChangeVisible()) { "Home state changed after capture" }
        observations.put(JSONObject().put("home_return_safe_state_verified", true)
            .put("original_connected_home_design_verified", false))
        stage = "home-return / hide floating"
        val labels = visibleNodes().filter { it.pkg == APP }.map { it.text }
        if ("플로팅 숨기기" in labels) {
            tapWithScroll(APP, "플로팅 숨기기")
            observations.put(JSONObject().put("hide_action_used", true))
        } else {
            check("플로팅 표시" in labels && controllerOverlayRemoved()) { "Hide state is unavailable" }
            observations.put(JSONObject().put("hide_action_used", false))
        }
        waitFor("home after floating control removed") {
            activeApplicationPackage() == APP && controllerOverlayRemoved() &&
                visibleNodes().any { it.pkg == APP && it.text == "플로팅 표시" } &&
                homeAfterWindowChangeVisible()
        }
        screenshot("QA-home-after-hide", "actual idle home; controller overlay removed", false)
        check(controllerOverlayRemoved() && homeAfterWindowChangeVisible()) { "Hidden state changed" }
        observations.put(JSONObject().put("home_hide_verified", true))
    }

    private fun stopScenario(name: String) {
        stage = "$name / emergency stop"
        // Never start another scenario until the controller and any armed input
        // have actually stopped. No automatic reactivation of a failed run.
        recordOverlayInventory("before_emergency_stop_$name")
        val currentThumb = awaitNode("actual controller before emergency stop", 2000) {
            it.pkg == APP && it.description.startsWith("DualTouch Thumb.")
        }
        observations.put(JSONObject().put("stop_requested_scenario", name)
            .put("thumb_before_hardware_key", currentThumb.description))
        if (name == "countdown" && capturesContains("DES-09-thumb-running.png")) {
            check(currentThumb.description.contains("실행 중") &&
                Regex("(?:[1-9]|1[0-9]|20)초").matches(currentThumb.text)) {
                "Countdown is no longer active before the hardware key"
            }
        }
        recordKeyFilterConfiguration()
        checkpoint()
        // The host sends one emulator hardware-button press/release when this
        // instrumentation status arrives. A shell-injected key is not used as
        // evidence of the real accessibility key callback.
        sendStatus(1, Bundle().apply { putString("dualtouch_stop_ready", "$source:$name") })
        try {
            waitFor("all controller overlays removed by volume-down", 8000) {
            automation.windows.none { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) false
                else {
                    val root = window.getRoot(0)
                    try { root == null || root.packageName?.toString() == APP }
                    finally { root?.recycle() }
                }
            }
            }
        } catch (failure: Exception) {
            recordOverlayInventory("emergency_stop_failed_$name")
            observations.put(JSONObject().put("scenario", name)
                .put("emergency_stop_overlay_removed", false)
                .put("thumb_state", visibleNodes().firstOrNull {
                    it.pkg == APP && it.description.startsWith("DualTouch Thumb.")
                }?.description ?: JSONObject.NULL))
            throw failure
        }
        recordOverlayInventory("after_emergency_stop_$name")
        requireSplit()
        observations.put(JSONObject().put("scenario", name).put("emergency_stop_overlay_removed", true))
        if (name == "countdown") screenshot("QA-stopped", "emergency stop; both probe windows remain", false)
    }

    private fun runCapture() {
        // Each scenario runs in its own disposable emulator. A failed
        // emergency stop never permits another session in the same emulator.
        stage = "permission flow"
        configureService()
        stage = "real split"
        openSplit()
        connectAndOpenReadyMenu(captureDesigns = true)
        if (scenario == "home-return") {
            captureHomeReturn()
            return
        }

        val errors = mutableListOf<String>()
        try {
            when (scenario) {
                "countdown" -> captureCountdown()
                "calibration" -> captureCalibration()
                else -> error("Unknown scenario")
            }
            observations.put(JSONObject().put("scenario", scenario).put("capture_completed", true))
        } catch (failure: Exception) {
            val message = "$stage: ${failure.message ?: failure.javaClass.simpleName}"
            errors.add(message)
            observations.put(JSONObject().put("scenario", scenario).put("error", message))
            runCatching { screenshot("diagnostic-$scenario", "diagnostic only; not a design match", false) }
        }
        try {
            stopScenario(scenario)
        } catch (failure: Exception) {
            val message = "$stage: ${failure.message ?: failure.javaClass.simpleName}"
            errors.add(message)
            observations.put(JSONObject().put("scenario", scenario).put("stop_error", message))
            runCatching { screenshot("diagnostic-stop-failure", "diagnostic only; not a design match", false) }
        }
        observations.put(JSONObject().put("DES-04", "not captured: opening controller home can invalidate the approved pair"))
        stage = "scenario results"
        check(errors.isEmpty()) { errors.joinToString("; ") }
    }

}
