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
    private var stage = "setup"
    private val captures = JSONArray()
    private val observations = JSONArray()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        source = arguments?.getString("source_sha").orEmpty()
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
            automation = checkNotNull(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
            val info = checkNotNull(automation.serviceInfo)
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            runCapture()
        } catch (failure: Exception) {
            error = "$stage: ${failure.message ?: failure.javaClass.simpleName}"
            if (::automation.isInitialized) {
                runCatching {
                    observations.put(JSONObject().put("failed_stage", stage)
                        .put("fixture_foreground", activeApplicationPackage()?.takeIf { it in setOf(APP, SETTINGS, LEFT, RIGHT) } ?: "other_or_unknown")
                        .put("service_enabled_in_settings", serviceEnabledInSettings())
                        .put("fixture_visible_labels", JSONArray(visibleNodes()
                            .filter { it.pkg in setOf(APP, SETTINGS, LEFT, RIGHT) }
                            .map { it.text }.filter { it.isNotBlank() }.distinct().take(60))))
                }
                runCatching { screenshot("diagnostic-failure", "diagnostic only; not a design match", false) }
            }
        } finally {
            if (::automation.isInitialized) {
                // Stop any armed session before leaving the disposable test emulator.
                runCatching { shell("input keyevent KEYCODE_VOLUME_DOWN") }
                runCatching { shell("am force-stop $APP") }
            }
            val report = JSONObject()
                .put("source_commit", source)
                .put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("environment", "disposable Android emulator; real probe app windows")
                .put("physical_foldable_verified", false)
                .put("touch_mirroring_verified", false)
                .put("captures", captures)
                .put("observations", observations)
                .put("completed", error == null)
                .put("error", error ?: JSONObject.NULL)
            File(output, "report.json").writeText(report.toString(2), Charsets.UTF_8)
            finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", "\nOverlay capture: ${error ?: "completed"}\n") })
        }
    }

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

    private fun configureService() {
        stage = "permission flow / onboarding"
        startApp(APP, ".MainActivity")
        tapWithScroll(APP, "내용을 이해하고 계속")
        stage = "permission flow / disclosure"
        tapWithScroll(APP, "접근성 권한 설정")
        tapText(APP, "동의하고 설정 열기")
        // Traverse the real system Settings UI, including its confirmation.
        // No settings-put, run-as preference writes, or direct service activation.
        stage = "permission flow / system service list"
        fun isServiceEntry(node: VisibleNode) =
            node.text in setOf("DualTouch 실험 서비스", "DualTouch POC")
        val entry = awaitWithScroll(SETTINGS, "DualTouch or installed accessibility services") {
            isServiceEntry(it) || it.text in setOf(
                "Downloaded apps", "Installed apps", "Installed services",
                "설치된 앱", "설치된 서비스", "다운로드한 앱",
            )
        }
        if (isServiceEntry(entry)) tap(entry) else {
            tap(entry)
            tap(awaitWithScroll(SETTINGS, "DualTouch service list entry", ::isServiceEntry))
        }
        stage = "permission flow / system toggle"
        val toggle = awaitWithScroll(SETTINGS, "service switch") {
            it.text.startsWith("Use DualTouch") ||
                it.text.startsWith("DualTouch 실험 서비스 사용") ||
                it.text == "Use service" || it.text == "서비스 사용"
        }
        tap(toggle)
        stage = "permission flow / system confirmation"
        tap(awaitNode("system permission confirmation") {
            it.pkg == SETTINGS && it.text in setOf("Allow", "OK", "허용", "확인")
        })
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
            "DES-10-calibration" -> text("직접") && text("자동") && nodes.any { it.text.startsWith("중앙 기준선 위치:") }
            else -> false
        }
        check(valid) { "Actual UI no longer matches $name" }
    }

    private fun screenshot(name: String, state: String, designMatch: Boolean = true) {
        if (designMatch) verifyDesignState(name)
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
    }

    private fun runCapture() {
        stage = "permission flow"
        configureService()
        stage = "real split"
        openSplit()
        stage = "app connection"
        tap(thumb("연결"))
        tapText(APP, "좌/우 선택 · 앱 연결")
        awaitNode("pair selection title") { it.pkg == APP && it.text == "현재 분할 앱 연결" }
        requireSplit()
        val approval = awaitNode("explicit direction approval") {
            it.pkg == APP && it.text in setOf("왼쪽 직접 → 오른쪽 자동 연결", "위쪽 직접 → 아래쪽 자동 연결")
        }
        val pairNodes = visibleNodes().filter { it.pkg == APP }
        check(pairNodes.any { it.text.contains(LEFT) } && pairNodes.any { it.text.contains(RIGHT) }) {
            "The approval panel must identify both actual probe packages"
        }
        screenshot("DES-08-pair-selection", "two actual probe windows and explicit approval")
        tap(approval)

        stage = "ready thumb and menu"
        thumb("대기")
        requireSplit()
        screenshot("DES-06-thumb-ready", "approved pair; input not running")
        tap(thumb("대기"))
        awaitNode("ready menu") { it.pkg == APP && it.text.startsWith("연결됨 · 실행 대기") }
        awaitNode("execute button") { it.pkg == APP && it.text == "실행" }
        awaitNode("calibration button") { it.pkg == APP && it.text == "영점 조절" }
        requireSplit()
        screenshot("DES-07-thumb-menu", "approved pair; three actions")

        stage = "calibration"
        tapText(APP, "영점 조절")
        awaitNode("direct reference") { it.pkg == APP && it.text == "직접" && it.description.startsWith("직접 터치 보정점") }
        awaitNode("automatic reference") { it.pkg == APP && it.text == "자동" && it.description.startsWith("자동 터치 보정점") }
        awaitNode("guide position") { it.pkg == APP && it.text.startsWith("중앙 기준선 위치:") }
        requireSplit()
        screenshot("DES-10-calibration", "real calibration overlay; both reference markers")
        tapText(APP, "취소")
        thumb("대기")

        stage = "countdown"
        tap(thumb("대기"))
        requireSplit()
        tapText(APP, "실행")
        awaitNode("actual active countdown", 7000) {
            it.pkg == APP && Regex("(?:[1-9]|1[0-9]|20)초").matches(it.text) &&
                it.description.startsWith("DualTouch Thumb.") && it.description.contains("실행 중")
        }
        requireSplit()
        screenshot("DES-09-thumb-running", "active real countdown; no mirrored taps issued")
        stage = "emergency stop"
        shell("input keyevent KEYCODE_VOLUME_DOWN")
        waitFor("thumb removed by volume-down") {
            visibleNodes().none { it.pkg == APP && it.description.startsWith("DualTouch Thumb.") }
        }
        requireSplit()
        screenshot("QA-stopped", "emergency stop; both probe windows remain", false)
        observations.put(JSONObject().put("emergency_stop_overlay_removed", true))

        // The controller's own activity is forbidden as a target. Do not fake DES-04.
        observations.put(JSONObject().put("DES-04", "not captured: opening controller home can invalidate the approved pair"))
    }
}
