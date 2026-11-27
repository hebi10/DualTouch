package com.hebi.dualtouch

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.res.Configuration
import com.hebi.dualtouch.core.SplitOrientation
import com.hebi.dualtouch.core.TouchSide
import com.hebi.dualtouch.core.InteractionPhase
import com.hebi.dualtouch.core.homeNextStep
import com.hebi.dualtouch.core.WindowPair
import android.view.View
import android.os.Bundle
import android.net.Uri
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class Page { INTRO, HOME, GUIDE, CONFIRM, ADVANCED, HELP }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val updateStatus = object : Runnable {
        override fun run() {
            if (page == Page.HOME && !isFinishing) {
                refreshHomeStatus()
                uiHandler.postDelayed(this, 900L)
            }
        }
    }
    private lateinit var palette: UiPalette
    private lateinit var settings: AppearanceSettings
    private lateinit var root: LinearLayout
    private lateinit var viewport: AdaptiveSpec
    private lateinit var scrollContainer: ScrollView
    private var restoreScrollY = 0
    private var page = Page.HOME
    private var statusLabel: TextView? = null
    private var permissionStatus: TextView? = null
    private var floatingStatus: TextView? = null
    private var pairStatus: TextView? = null
    private var nextStepStatus: TextView? = null
    private var floatingButton: Button? = null
    private var permissionButton: Button? = null
    private var stateDetail: TextView? = null
    private var pairDetails: LinearLayout? = null
    private var displayedPairKey: Pair<WindowPair, TouchSide>? = null
    private var lastPermissionState: Boolean? = null
    private var lastStatusIcon: Pair<LineIcon, Int>? = null
    private var chosenMode: MirrorMode = MirrorMode.DUAL_STROKE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = UiPalette(this)
        settings = AppearanceSettings(this)
        restoreScrollY = savedInstanceState?.getInt("scrollY") ?: 0
        chosenMode = savedInstanceState?.getString("experimentMode")?.let { name ->
            runCatching { MirrorMode.valueOf(name) }.getOrNull()
                ?.takeIf { it != MirrorMode.THUMB }
        } ?: MirrorMode.DUAL_STROKE
        page = savedInstanceState?.getString("page")?.let {
            runCatching { Page.valueOf(it) }.getOrNull()
        } ?: if (settings.onboardingRead) Page.HOME else Page.INTRO
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        if (page == Page.ADVANCED || page == Page.CONFIRM) {
            outState.putString("experimentMode", chosenMode.name)
        }
        if (::scrollContainer.isInitialized) outState.putInt("scrollY", scrollContainer.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(updateStatus)
        if (::root.isInitialized) {
            refreshHomeStatus()
        }
        if (page == Page.HOME) uiHandler.post(updateStatus)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(updateStatus)
        super.onPause()
    }

    private fun render() {
        viewport = AdaptiveLayout.specFor(this)
        scrollContainer = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
            clipToPadding = false
        }
        root = UiKit.centeredScrollContent(
            this, scrollContainer, viewport, maxReadingWidth = page != Page.HOME
        )
        UiKit.applyWindowInsets(scrollContainer)
        setContentView(scrollContainer)
        UiKit.applySystemBarAppearance(this)
        statusLabel = null
        permissionStatus = null
        floatingStatus = null
        pairStatus = null
        nextStepStatus = null
        floatingButton = null
        permissionButton = null
        stateDetail = null
        pairDetails = null
        displayedPairKey = null
        lastPermissionState = null
        lastStatusIcon = null

        when (page) {
            Page.INTRO -> intro()
            Page.HOME -> home()
            Page.GUIDE -> guide()
            Page.CONFIRM -> confirm()
            Page.ADVANCED -> advanced()
            Page.HELP -> help()
        }
        if (restoreScrollY > 0) {
            val previousPosition = restoreScrollY
            restoreScrollY = 0
            scrollContainer.post { scrollContainer.scrollTo(0, previousPosition) }
        }
    }

    private fun go(destination: Page) {
        restoreScrollY = 0
        page = destination
        uiHandler.removeCallbacks(updateStatus)
        render()
        if (page == Page.HOME) uiHandler.post(updateStatus)
    }

    private fun header(title: String, subtitle: String? = null, back: Boolean = true) {
        if (back) UiKit.add(root, UiKit.toolbar(this, palette, title, back = { go(Page.HOME) }))
        else addTitle(title)
        if (subtitle != null) addBody(subtitle, secondary = true, top = 7)
    }

    private fun addTitle(text: String) {
        UiKit.add(root, UiKit.text(this, palette, text, 26f, true), topDp = 12)
    }

    private fun addBody(text: String, secondary: Boolean = false, top: Int = 10) {
        UiKit.add(root,
            UiKit.text(this, palette, text, 14f, secondary = secondary),
            topDp = top)
    }

    private fun section(text: String, top: Int = 22) {
        UiKit.add(root, UiKit.sectionHeader(this, palette, text), topDp = top)
    }

    private fun card(title: String, body: String, top: Int = 12) {
        val container = UiKit.card(this, palette, 16)
        UiKit.add(container, UiKit.text(this, palette, title, 15f, true))
        UiKit.add(container, UiKit.text(this, palette, body, 13f, secondary = true), topDp = 7)
        UiKit.add(root, container, topDp = top)
    }

    private fun addAction(
        text: String,
        primary: Boolean = true,
        top: Int = 12,
        action: () -> Unit,
    ): Button {
        val view = UiKit.action(this, palette, text, primary)
        view.setOnClickListener { action() }
        UiKit.add(root, view, topDp = top)
        return view
    }

    private fun serviceStatus(): String {
        val service = TouchMirrorService.current
        return if (service == null) {
            "접근성 권한 필요 · 플로팅 OFF"
        } else {
            "${service.phase.description} · ${service.statusText}"
        }
    }

    private fun updateText(view: TextView?, value: String) {
        if (view != null && view.text.toString() != value) view.text = value
    }

    private fun updateStatusRow(view: TextView?, label: String, color: Int) {
        updateText(view, label)
        view?.setTextColor(color)
    }

    private fun refreshHomeStatus() {
        if (page != Page.HOME) return
        val service = TouchMirrorService.current
        val permission = settings.accessibilityDisclosureAccepted && service != null
        val floating = permission && service?.isFloatingVisible == true
        val pair = if (floating) service?.connectedPair else null
        val side = service?.connectedSourceSide ?: TouchSide.LEFT
        val phase = service?.phase
        val display = if (floating) service?.displayedThumbState else null
        val status = when {
            !permission -> "접근성 권한 필요"
            !floating -> "플로팅 표시 대기"
            display?.label == "오류" -> "입력 중지"
            display?.label == "보정" -> "영점 조절 중"
            pair == null -> "분할 앱 연결 필요"
            phase == InteractionPhase.ACTIVE -> "실행 중"
            phase == InteractionPhase.PAUSED -> "일시 중지"
            else -> "연결됨 · 실행 대기"
        }
        val statusColor = when {
            !permission -> palette.warning
            display?.label == "오류" -> palette.danger
            pair == null && floating -> palette.warning
            pair != null -> palette.success
            else -> palette.text
        }
        updateText(statusLabel, status)
        val statusIcon = when {
            !permission || display?.label == "오류" || pair == null && floating -> LineIcon.WARNING_BADGE
            pair != null && display?.label != "보정" && phase != InteractionPhase.PAUSED -> LineIcon.CHECK_BADGE
            else -> LineIcon.INFO
        }
        val iconKey = statusIcon to statusColor
        if (lastStatusIcon != iconKey) {
            lastStatusIcon = iconKey
            statusLabel?.setCompoundDrawablesRelativeWithIntrinsicBounds(UiKit.icon(this, statusIcon, statusColor, 32), null, null, null)
        }
        updateText(stateDetail, when {
            !permission || !floating -> "터치 실행 중지"
            else -> display?.description ?: "터치 실행 중지"
        })
        updateStatusRow(permissionStatus, if (permission) "연결됨" else "설정 필요", if (permission) palette.success else palette.warning)
        updateStatusRow(floatingStatus, if (floating) "표시 중" else "대기", if (floating) palette.success else palette.secondary)
        updateStatusRow(pairStatus, if (pair != null) "연결됨" else "연결 필요", if (pair != null) palette.success else palette.warning)
        val key = pair?.let { it to side }
        if (displayedPairKey != key) {
            displayedPairKey = key
            pairDetails?.let { container ->
                container.removeAllViews()
                container.visibility = if (pair != null) View.VISIBLE else View.GONE
                if (pair != null) {
                    val horizontal = pair.orientation == SplitOrientation.HORIZONTAL
                    val first = if (horizontal) "왼쪽" else "위쪽"
                    val second = if (horizontal) "오른쪽" else "아래쪽"
                    val source = if (side == TouchSide.LEFT) first else second
                    val target = if (side == TouchSide.LEFT) second else first
                    val firstTile = appIdentityTile(this, palette, pair.left.packageName,
                        if (side == TouchSide.LEFT) "$first · 직접" else "$first · 자동")
                    val secondTile = appIdentityTile(this, palette, pair.right.packageName,
                        if (side == TouchSide.RIGHT) "$second · 직접" else "$second · 자동")
                    if (viewport.stackToolbarActions || !horizontal) {
                        UiKit.add(container, firstTile)
                        UiKit.add(container, secondTile, topDp = 8)
                    } else {
                        val row = UiKit.panePair(this, 8, 0.5f)
                        UiKit.add(row.primary, firstTile)
                        UiKit.add(row.secondary, secondTile)
                        UiKit.add(container, row.row)
                    }
                    UiKit.add(container, UiKit.centeredLabel(this, "$source 직접 → $target 자동", palette.text, 13f), topDp = 8)
                }
            }
        }
        updateText(nextStepStatus, homeNextStep(permission, floating, pair != null, phase, display))
        nextStepStatus?.visibility = if (permission) View.VISIBLE else View.GONE
        updateText(floatingButton, floatingButtonLabel())
        if (lastPermissionState != permission) {
            lastPermissionState = permission
            permissionButton?.visibility = if (permission) View.GONE else View.VISIBLE
            floatingButton?.let { UiKit.styleAction(it, palette, primary = permission) }
        }
    }

    private fun floatingButtonLabel(): String =
        if (TouchMirrorService.current?.isFloatingVisible == true) {
            "플로팅 숨기기"
        } else {
            "플로팅 표시"
        }

    private fun intro() {
        header("DualTouch", "사용자가 연결한 두 분할 앱의 터치 동기화", false)
        section("두 분할 앱의 짧은 탭 제어", 20)
        addSplitExample()
        card("이 앱의 사용 범위",
            "현재 열려 있는 두 분할 앱을 사용자가 직접 연결하면 한쪽의 탭을 다른 쪽에도 재생합니다. 동일 앱은 Android가 별도 창 2개를 지원할 때만 연결됩니다.")
        card("접근성 서비스 사용",
            "화면 위에 작은 조절점(Thumb)을 띄우고, 사용자가 실행을 선택하면 Android 입력 이벤트를 수신해 상대 창으로 가상 터치를 재생합니다.")
        card("개인정보와 주의사항",
            "화면 내용·비밀번호·터치 기록을 서버에 전송하거나 저장하지 않습니다. " +
                "입력 수신 중 원래 터치가 차단되거나 재현에 실패할 수 있습니다. 금융·결제·비밀번호·민감한 화면은 차단하거나 지원하지 않습니다.")
        section("사용 순서", 20)
        addSteps(listOf(
            "접근성 권한 설정" to "고지 내용을 확인하고 Android 설정에서 직접 허용합니다.",
            "플로팅 표시" to "홈에서 Thumb를 표시합니다.",
            "두 앱 분할 및 연결" to "Android 분할 화면을 열고 두 앱과 방향을 승인합니다.",
            "Thumb에서 실행" to "한 손가락의 짧은 탭만 지원하며 최대 20초 후 중지됩니다."
        ))
        addAction("내용을 이해하고 계속", top = 26) {
            settings.onboardingRead = true
            go(Page.HOME)
        }
        addAction("나중에", false) { finish() }
    }

    private fun statusRow(parent: LinearLayout, label: String, kind: LineIcon): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = UiKit.dp(this@MainActivity, 48)
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 6), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 6))
        }
        val icon = UiKit.iconView(this, kind, palette.text, 24)
        row.addView(icon, LinearLayout.LayoutParams(UiKit.dp(this, 28), UiKit.dp(this, 28)))
        val title = UiKit.text(this, palette, label, 15f, true)
        val value = UiKit.text(this, palette, "", 14f, true)
        if (viewport.stackSettingValues) {
            val labels = UiKit.column(this)
            UiKit.add(labels, title)
            UiKit.add(labels, value, topDp = 4)
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = UiKit.dp(this@MainActivity, 8)
            })
        } else {
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = UiKit.dp(this@MainActivity, 8)
            })
            value.gravity = Gravity.END
            row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (parent.childCount > 0) {
            parent.addView(View(this).apply { setBackgroundColor(palette.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)))
        }
        UiKit.add(parent, row)
        return value
    }

    private fun helpLink(parent: LinearLayout, label: String, kind: LineIcon, action: () -> Unit) {
        if (parent.childCount > 0) {
            parent.addView(View(this).apply { setBackgroundColor(palette.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = UiKit.dp(this@MainActivity, 48)
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 8))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(UiKit.iconView(this, kind, palette.text, 21), LinearLayout.LayoutParams(UiKit.dp(this, 24), UiKit.dp(this, 24)))
        row.addView(UiKit.text(this, palette, label, 15f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = UiKit.dp(this@MainActivity, 12) })
        row.addView(UiKit.iconView(this, LineIcon.CHEVRON, palette.secondary, 18), LinearLayout.LayoutParams(UiKit.dp(this, 18), UiKit.dp(this, 24)))
        UiKit.add(parent, row)
    }

    private fun openDisplaySettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun home() {
        if (viewport.widthCategory == WidthCategory.COMPACT) {
            val padding = UiKit.dp(this, 4)
            root.setPadding(padding, UiKit.dp(this, 6), padding, UiKit.dp(this, 20))
        }
        UiKit.add(root, UiKit.toolbar(this, palette, "DualTouch", trailing = "표시 설정", onTrailing = { openDisplaySettings() }, trailingIcon = LineIcon.GEAR, stackTrailing = viewport.stackToolbarActions))
        val pageRoot = root
        val primary: LinearLayout
        val secondary: LinearLayout
        if (viewport.useTwoPanes) {
            val panes = UiKit.panePair(this, viewport.paneGapDp, 0.46f)
            UiKit.add(pageRoot, panes.row, topDp = 16)
            primary = panes.primary
            secondary = panes.secondary
        } else {
            primary = pageRoot
            secondary = pageRoot
        }

        root = primary
        val stateCard = UiKit.card(this, palette, 14)
        UiKit.add(stateCard, UiKit.text(this, palette, "현재 상태", 14f, true))
        statusLabel = UiKit.text(this, palette, "", 21f, true).apply { compoundDrawablePadding = UiKit.dp(this@MainActivity, 12) }
        UiKit.add(stateCard, requireNotNull(statusLabel), topDp = 8)
        stateDetail = UiKit.text(this, palette, "", 14f, secondary = true).apply { setPaddingRelative(UiKit.dp(this@MainActivity, 44), 0, 0, 0) }
        UiKit.add(stateCard, requireNotNull(stateDetail), topDp = 2)
        pairDetails = UiKit.column(this).apply { visibility = View.GONE }
        UiKit.add(stateCard, requireNotNull(pairDetails), topDp = 12)
        UiKit.add(root, stateCard, topDp = 10)
        val readiness = UiKit.column(this).apply { background = UiKit.shape(palette.background, palette.line) }
        permissionStatus = statusRow(readiness, "접근성 권한", LineIcon.ACCESSIBILITY)
        floatingStatus = statusRow(readiness, "플로팅 컨트롤", LineIcon.FLOATING)
        pairStatus = statusRow(readiness, "분할 앱 연결", LineIcon.SPLIT)
        UiKit.add(root, readiness, topDp = 12)

        permissionButton = addAction("접근성 권한 설정", top = 16) { requestAccessibilitySettings() }
        floatingButton = addAction(floatingButtonLabel(), top = 8) {
            val currentService = TouchMirrorService.current
            when {
                !settings.accessibilityDisclosureAccepted || currentService == null -> requestAccessibilitySettings()
                currentService.isFloatingVisible -> {
                    currentService.hideFloatingControl()
                    refreshHomeStatus()
                }
                else -> {
                    val shown = currentService.showFloatingControl()
                    refreshHomeStatus()
                    if (!shown) Toast.makeText(this, "Thumb를 표시할 수 없습니다. 접근성 설정을 확인하세요.", Toast.LENGTH_LONG).show()
                }
            }
        }
        addAction("표시 설정", false, top = 8) { openDisplaySettings() }
        nextStepStatus = UiKit.text(this, palette, "", 13f, secondary = true)
        UiKit.add(root, requireNotNull(nextStepStatus), topDp = 12)

        root = secondary
        val links = UiKit.column(this).apply { background = UiKit.shape(palette.background, palette.line) }
        helpLink(links, "분할 화면 안내", LineIcon.BOOK) { go(Page.GUIDE) }
        helpLink(links, "접근성 권한 안내", LineIcon.INFO) { showAccessibilityConsent() }
        helpLink(links, "오류·중지 안내", LineIcon.WARNING) { go(Page.HELP) }
        helpLink(links, "개인정보처리방침", LineIcon.SHIELD) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "웹 브라우저를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }
        UiKit.add(root, links, topDp = if (viewport.useTwoPanes) 10 else 12)
        if (viewport.useTwoPanes) {
            card("사용 준비", "실제 앱 두 개를 Android 분할 화면으로 열고 Thumb에서 앱과 방향을 승인하세요. 영점은 현재 연결과 방향에만 적용됩니다.")
        }
        card("안전한 사용", "한 손가락 짧은 탭만 지원합니다. 최대 20초 후 자동 중지, 볼륨 아래 긴급 중지. 연속 입력이 누락될 수 있으며 원래 터치가 취소될 수 있습니다. 결제·송금·삭제 등 민감한 작업에는 사용하지 마세요.", top = 16)
        root = pageRoot
        refreshHomeStatus()
    }

    private fun requestAccessibilitySettings() {
        if (settings.accessibilityDisclosureAccepted) {
            openAccessibilitySettings()
        } else {
            showAccessibilityConsent()
        }
    }

    private fun showAccessibilityConsent() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val theme = if (dark) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert
        val disclosure = SpannableStringBuilder()
        fun disclosureSection(title: String, body: String) {
            if (disclosure.isNotEmpty()) disclosure.append("\n\n")
            val start = disclosure.length
            disclosure.append(title)
            disclosure.setSpan(StyleSpan(Typeface.BOLD), start, disclosure.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            disclosure.append("\n").append(body)
        }
        disclosureSection("사용 목적",
            "DualTouch는 연결한 두 앱에 터치를 전달하고 플로팅 Thumb를 표시하기 위해 접근성 서비스를 사용합니다.")
        disclosureSection("일시적으로 확인하는 정보",
            "선택한 앱의 패키지명, 창 식별자·위치·포커스, 터치 좌표·시간, 비밀번호 칸과 편집 영역 등 화면 구조를 기기에서 일시 확인합니다. " +
                "이 정보는 저장하거나 외부로 보내지 않습니다.")
        disclosureSection("자동 입력의 제한과 위험",
            "자동 입력은 사용자가 두 앱을 승인하고 실행을 누른 동안에만 작동합니다. " +
                "원래 터치가 취소될 수 있고 모든 민감 화면을 판별할 수 없으니 결제나 송금에는 사용하지 마세요. " +
                "볼륨 아래 키로 중지할 수 있습니다.")
        disclosureSection("Android 설정",
            "동의하면 Android 접근성 설정이 열립니다. 설치된 앱에서 DualTouch를 직접 켜세요.")
        val dialog = AlertDialog.Builder(this, theme)
            .setTitle("접근성 권한")
            .setMessage(disclosure)
            .setNegativeButton("나중에", null)
            .setPositiveButton("동의하고 설정 열기") { _, _ ->
                settings.accessibilityDisclosureAccepted = true
                openAccessibilitySettings()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            setTextColor(palette.text)
            minimumHeight = UiKit.dp(this@MainActivity, 48)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setTextColor(palette.text)
            minimumHeight = UiKit.dp(this@MainActivity, 48)
        }
    }

    private fun openAccessibilitySettings() {
        if (launchSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)) return
        val opened = launchSystemSettings(Settings.ACTION_SETTINGS)
        val path = "설정 → 접근성 → 설치된 앱 → DualTouch"
        val message = if (opened) "$path 순서로 이동하세요." else "설정을 열 수 없습니다. ${path}에서 직접 설정하세요."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun launchSystemSettings(action: String): Boolean {
        return try {
            startActivity(Intent(action))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun addSteps(steps: List<Pair<String, String>>) {
        val container = UiKit.card(this, palette, 12)
        steps.forEachIndexed { index, (title, detail) ->
            if (index > 0) {
                UiKit.add(container, View(this).apply {
                    setBackgroundColor(palette.line)
                    minimumHeight = UiKit.dp(this@MainActivity, 1)
                }, topDp = 8, bottomDp = 8)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            val number = UiKit.centeredLabel(this, (index + 1).toString(), palette.buttonText, 14f).apply {
                background = UiKit.shape(palette.button)
                setPadding(UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 5),
                    UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 5))
            }
            row.addView(number, LinearLayout.LayoutParams(UiKit.dp(this, 32), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = UiKit.dp(this@MainActivity, 10)
            })
            val body = UiKit.column(this)
            UiKit.add(body, UiKit.text(this, palette, title, 15f, true))
            UiKit.add(body, UiKit.text(this, palette, detail, 14f, secondary = true), topDp = 4)
            row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            UiKit.add(container, row)
        }
        UiKit.add(root, container, topDp = 10)
    }

    private fun addSplitExample() {
        val sample = UiKit.card(this, palette)
        UiKit.add(sample, UiKit.text(this, palette, "동작 원리 예시", 15f, true))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun examplePane(label: String, color: Int): LinearLayout {
            return UiKit.column(this, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = UiKit.shape(palette.background, palette.line)
                UiKit.add(this, UiKit.centeredLabel(this@MainActivity, label, palette.text, 14f))
                val marker = View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                        setStroke(UiKit.dp(this@MainActivity, 1).coerceAtLeast(1), 0xFF191919.toInt())
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(marker, LinearLayout.LayoutParams(UiKit.dp(this@MainActivity, 24),
                    UiKit.dp(this@MainActivity, 24)).apply { topMargin = UiKit.dp(this@MainActivity, 8) })
            }
        }
        row.addView(examplePane("앱 A\n직접점", 0xFFC62828.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(UiKit.centeredLabel(this, "→", palette.text, 20f),
            LinearLayout.LayoutParams(UiKit.dp(this, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(examplePane("앱 B\n자동점", 0xFFFFFFFF.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        UiKit.add(sample, row, topDp = 10)
        UiKit.add(sample, UiKit.text(this, palette, "동작 원리 예시입니다. 짧은 탭을 반대편 위치에 순차 재현하며 실제 입력 성공을 보장하지 않습니다. 상하 분할도 검증 대상입니다.", 13f, secondary = true), topDp = 10)
        UiKit.add(root, sample, topDp = 12)
    }

    private fun addLayoutExamples() {
        section("분할 배치 예시", 20)
        val container = UiKit.card(this, palette, 12)
        val examples = listOf("5:5" to 0.5f, "6:4" to 0.6f, "7:3" to 0.7f, "상하" to null)
        val columns = if (viewport.contentWidthForReadingDp >= 370 &&
            resources.configuration.fontScale < 1.3f) 4 else 2
        examples.chunked(columns).forEachIndexed { index, items ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBaselineAligned(false)
            }
            items.forEachIndexed { column, (label, fraction) ->
                val tile = UiKit.column(this)
                val diagram = LinearLayout(this).apply {
                    orientation = if (fraction == null) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    background = UiKit.shape(palette.background, palette.line)
                    contentDescription = if (fraction == null) "상하 분할 예시" else "$label 좌우 분할 예시"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                val diagramHeight = UiKit.dp(this, maxOf(52, (52 * resources.configuration.fontScale).toInt()))
                listOf("A", "B").forEachIndexed { side, name ->
                    val pane = UiKit.centeredLabel(this, name, palette.text, 12f).apply {
                        background = UiKit.shape(palette.background, palette.actionLine)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    val share = if (side == 0) fraction ?: 0.5f else 1f - (fraction ?: 0.5f)
                    val params = if (fraction == null)
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, share)
                    else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, share)
                    diagram.addView(pane, params)
                }
                tile.addView(diagram, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, diagramHeight))
                UiKit.add(tile, UiKit.centeredLabel(this, label, palette.text, 12f), topDp = 4)
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (column < items.lastIndex) marginEnd = UiKit.dp(this@MainActivity, 8)
                })
            }
            UiKit.add(container, row, topDp = if (index == 0) 0 else 10)
        }
        UiKit.add(root, container, topDp = 10)
    }

    private fun guide() {
        header("실제 분할 앱 연결", "Android에서 두 앱을 분할한 뒤 연결하세요.")
        addBody("먼저 접근성 서비스 목적에 동의하고 Android 설정에서 활성화한 뒤 홈에서 Thumb를 표시하세요.", secondary = true)
        section("연결 순서", 20)
        addSteps(listOf(
            "최근 앱에서 분할 화면 열기" to "최근 앱에서 첫 번째 앱 아이콘을 눌러 분할 화면 열기를 선택합니다.",
            "두 번째 앱 선택" to "Android 분할 화면으로 두 창을 엽니다. 삼성 기기의 지원되는 Edge 패널도 이용할 수 있습니다. 메뉴 이름은 기기마다 다릅니다.",
            "앱 연결 및 방향 승인" to "Thumb의 '좌/우 선택 · 앱 연결'에서 앱 이름과 패키지를 확인하고 직접 터치할 방향을 승인합니다. 감지 실패 시 이유를 확인하고 다시 검색하세요.",
            "영점 조절 후 실행" to "직접점은 두 점을 함께, 자동점은 자동점만 이동합니다. 가로·세로 기준선 위치(20~80%)를 조절하고 저장하세요. 보정은 현재 연결과 방향에만 적용됩니다. 실제 분할 방향을 바꾼 뒤에는 앱을 다시 연결해야 합니다."
        ))
        addLayoutExamples()
        addBody("배치 지원은 실제 창 조건과 기기에 따라 다릅니다. 같은 앱 두 창은 앱과 OS가 허용할 때만 가능합니다.", secondary = true)
        card("긴급 중지",
            "최대 20초 후 자동 중지됩니다. Thumb의 중지 또는 볼륨 아래 버튼으로 새 입력을 중단할 수 있습니다. 스크롤·길게 누르기·여러 손가락 입력은 지원하지 않습니다. 화면 구성이 바뀌면 다시 연결하세요.")
        addAction("홈에서 플로팅 표시", top = 20) { go(Page.HOME) }
    }

    private fun experimentLabel(mode: MirrorMode): String = when (mode) {
        MirrorMode.DUAL_STROKE -> "동시 두 스트로크"
        MirrorMode.SEQUENTIAL -> "순차 두 탭"
        MirrorMode.NODE_CLICK -> "접근성 노드 클릭"
        else -> "지원하지 않는 실험"
    }

    private fun confirm() {
        header("입력 실험 확인", "직접 터치 재생과 자동 입력이 정상 작동하는지는 아직 미검증입니다.")
        card("미검증 개발 실험", "일반 사용자용 기능이 아니며 실제 작동을 보장하지 않습니다.")
        card("선택한 실험", experimentLabel(chosenMode))
        card("실험 대상",
            "전용 Test Left와 Test Right 두 개의 분할 창에서만 동작합니다. " +
                "실제 개인 앱에서는 사용할 수 없습니다.")
        card("중지 방법",
            "실행 후 20초 또는 볼륨 아래 키로 중단됩니다. " +
                "실험 중 스크롤·멀티터치 사용 시 입력이 취소될 수 있습니다. 원래 터치가 재현되지 않을 수 있습니다.")
        addBody("권한이 없으면 고지 및 설정을 먼저 확인합니다. 실제 테스트 창의 안전 조건을 충족해야 입력이 시작됩니다.", secondary = true)
        addAction("선택 실험 시작", top = 20) {
            val service = TouchMirrorService.current
            when {
                !settings.accessibilityDisclosureAccepted || service == null -> requestAccessibilitySettings()
                service.startExperiment(chosenMode) -> go(Page.HOME)
                else -> go(Page.HELP)
            }
        }
        addAction("취소", false) { go(Page.HOME) }
    }

    private fun advanced() {
        header("추가 입력 방식", "Thumb 외 Android 접근성 입력 경로를 비교하는 기술 실험입니다.")
        card("개발 실험 · 미검증",
            "일반 사용자 화면에는 진입점이 없습니다. 전용 Test Left / Test Right 두 창에만 한정하며 실제 작동을 보장하지 않습니다.")
        section("입력 방식 선택", 18)
        val choices = listOf(MirrorMode.DUAL_STROKE, MirrorMode.SEQUENTIAL, MirrorMode.NODE_CLICK)
            .map { it to experimentLabel(it) }
        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val radioButtons = choices.map { (mode, label) ->
            RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = label
                setTextColor(palette.text)
                textSize = 15f
                minHeight = UiKit.dp(this@MainActivity, 48)
                isChecked = mode == chosenMode
                group.addView(this)
            }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val found = radioButtons.indexOfFirst { it.id == checkedId }
            if (found >= 0) chosenMode = choices[found].first
        }
        group.background = UiKit.shape(palette.background, palette.line)
        UiKit.add(root, group, topDp = 14)
        card("기본 Thumb와 구분",
            "다음 방식은 실제 터치 이벤트를 가로챌 수 있습니다. 사용자가 명시적으로 선택한 경우에만 실행합니다.")
        addAction("실험 진행", top = 20) { go(Page.CONFIRM) }
    }

    private fun help() {
        header("오류·중지 안내", "실험 범위를 벗어나면 안전을 위해 동기화를 중단합니다.")
        val service = TouchMirrorService.current
        val status = service?.statusText ?: "접근성 서비스 OFF"
        card("마지막 서비스 상태", status, top = 12)
        section("문제 해결", 20)
        card("플로팅이 보이지 않을 때",
            "접근성 서비스가 활성인지 확인하고, 홈에서 '플로팅 표시'를 선택하세요.")
        card("두 창을 인식하지 못할 때",
            "실제 앱 두 개를 분할로 열고 Thumb의 앱 연결 메뉴에서 두 창과 방향을 승인하세요. " +
                "키보드·시스템·보안 UI나 창 변경이 감지되면 입력이 중단됩니다.")
        card("이전 터치 처리 종료를 확인할 수 없을 때",
            "새 입력을 실행하지 마세요. Android 접근성 설정에서 서비스를 끈 뒤 필요한 경우 다시 허용하고 두 창을 다시 승인하세요.")
        card("긴급 중지",
            "실행 중 볼륨 아래 키로 중지할 수 있습니다. 원래 터치가 작동하지 않거나 " +
                "두 앱 외에 입력이 발생하면 Android 접근성 설정에서 서비스를 비활성화하세요.")
        addAction("플로팅 숨기기", false) {
            TouchMirrorService.current?.hideFloatingControl()
            go(Page.HOME)
        }
        addAction("Android 접근성 설정 열기", false) { requestAccessibilitySettings() }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page != Page.HOME && page != Page.INTRO) {
            go(Page.HOME)
        } else {
            super.onBackPressed()
        }
    }
}

