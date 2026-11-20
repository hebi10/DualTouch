package com.hebi.dualtouch

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.net.Uri
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class Page { INTRO, HOME, PERMISSION, GUIDE, CONFIRM, ADVANCED, HELP }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val updateStatus = object : Runnable {
        override fun run() {
            if (page == Page.HOME && !isFinishing) {
                statusLabel?.text = serviceStatus()
                floatingButton?.text = floatingButtonLabel()
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
    private var floatingButton: Button? = null
    private var chosenMode: MirrorMode = MirrorMode.DUAL_STROKE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = UiPalette(this)
        settings = AppearanceSettings(this)
        restoreScrollY = savedInstanceState?.getInt("scrollY") ?: 0
        page = savedInstanceState?.getString("page")?.let {
            runCatching { Page.valueOf(it) }.getOrNull()
        } ?: if (settings.onboardingRead) Page.HOME else Page.INTRO
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        if (::scrollContainer.isInitialized) outState.putInt("scrollY", scrollContainer.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(updateStatus)
        if (::root.isInitialized) {
            statusLabel?.text = serviceStatus()
            floatingButton?.text = floatingButtonLabel()
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
        statusLabel = null
        floatingButton = null

        when (page) {
            Page.INTRO -> intro()
            Page.HOME -> home()
            Page.PERMISSION -> permission()
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
        if (page == Page.HOME && hasWindowFocus()) uiHandler.post(updateStatus)
    }

    private fun header(title: String, subtitle: String? = null, back: Boolean = true) {
        if (back) addAction("← 홈으로", false, top = 0) { go(Page.HOME) }
        addTitle(title)
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

    private fun floatingButtonLabel(): String =
        if (TouchMirrorService.current?.isFloatingVisible == true) {
            "플로팅 숨기기"
        } else {
            "플로팅 표시"
        }

    private fun intro() {
        header("DualTouch", "사용자가 연결한 두 분할 앱의 터치 동기화", false)
        section("한 번의 터치, 두 화면의 반응", 26)
        card("이 앱의 사용 범위",
            "현재 열려 있는 두 분할 앱을 사용자가 직접 연결하면 한쪽의 탭을 다른 쪽에도 재생합니다. 동일 앱은 Android가 별도 창 2개를 지원할 때만 연결됩니다.")
        card("접근성 서비스 사용",
            "화면 위에 작은 조절점(Thumb)을 띄우고, 사용자가 실행을 선택하면 Android 입력 이벤트를 수신해 상대 창으로 가상 터치를 재생합니다.")
        card("개인정보와 주의사항",
            "화면 내용·비밀번호·터치 기록을 서버에 전송하거나 저장하지 않습니다. " +
                "입력 수신 중 원래 터치가 차단되거나 재현에 실패할 수 있습니다. 금융·결제·비밀번호·민감한 화면은 차단하거나 지원하지 않습니다.")
        addAction("내용을 이해하고 계속", top = 26) {
            settings.onboardingRead = true
            go(Page.HOME)
        }
        addAction("나중에", false) { finish() }
    }

    private fun home() {
        header("DualTouch", "실제 분할 앱 연결 · 터치 동기화", false)

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
        section("서비스 상태", if (viewport.useTwoPanes) 0 else 22)
        val service = TouchMirrorService.current
        val stateCard = UiKit.card(this, palette, 18)
        UiKit.add(stateCard, UiKit.text(this, palette, "현재 상태", 13f, secondary = true))
        statusLabel = UiKit.text(this, palette, serviceStatus(), 17f, true)
        UiKit.add(stateCard, statusLabel!!, topDp = 8)
        UiKit.add(stateCard, UiKit.text(this, palette,
            if (service == null) {
                "권한을 설정하면 플로팅 Thumb를 표시할 수 있습니다."
            } else {
                "Thumb 메뉴에서 두 앱의 연결·방향·영점을 조절합니다."
            }, 13f, secondary = true), topDp = 8)
        UiKit.add(root, stateCard, topDp = 10)

        section("플로팅 컨트롤")
        addBody("플로팅 표시를 누르면 원형 Thumb가 나타납니다. " +
            "앱 두 개를 좌우 또는 상하로 분할한 뒤 Thumb에서 앱을 연결하세요.", secondary = true)
        floatingButton = addAction(floatingButtonLabel(), top = 14) {
            val currentService = TouchMirrorService.current
            when {
                !settings.accessibilityDisclosureAccepted -> go(Page.PERMISSION)
                currentService == null -> go(Page.PERMISSION)
                currentService.isFloatingVisible -> {
                    currentService.hideFloatingControl()
                    statusLabel?.text = serviceStatus()
                    floatingButton?.text = floatingButtonLabel()
                }
                else -> {
                    val shown = currentService.showFloatingControl()
                    statusLabel?.text = if (shown) serviceStatus() else {
                        "Thumb를 표시할 수 없습니다. 접근성 설정을 확인하세요."
                    }
                    floatingButton?.text = floatingButtonLabel()
                }
            }
        }
        addAction("표시 설정", false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        root = secondary
        section("사용 준비", if (viewport.useTwoPanes) 0 else 22)
        card("01 · 실제 앱 두 개 분할",
            "Android 분할 화면에 원하는 앱 두 개를 좌우 또는 상하로 배치합니다. 동일 앱은 멀티 인스턴스를 지원해야 합니다.")
        card("02 · 좌/우 앱 연결",
            "Thumb → 좌/우 선택 · 앱 연결 → 표시된 앱을 확인하고 직접 누를 방향을 승인합니다.")
        card("03 · 실행·영점",
            "연결 후 Thumb의 실행을 누릅니다. 필요하면 영점을 조절하고 볼륨 아래로 긴급 중지합니다.")
        addAction("분할 화면 안내", false) { go(Page.GUIDE) }
        addAction("접근성 권한 안내", false) { go(Page.PERMISSION) }
        addAction("개인정보처리방침", false) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "웹 브라우저를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }

        section("기술 검증")
        card("Android 입력 처리 제한",
            "실제 앱의 원본 터치가 취소될 수 있습니다. 명시적으로 연결한 앱 두 개만 제어하며 " +
                "감지한 키보드·비밀번호·보호 앱에서는 입력을 차단합니다. " +
                "모든 민감 화면을 판별할 수 없으므로 결제·송금·삭제 등에는 사용하지 마세요.")
        addAction("오류·중지 안내", false) { go(Page.HELP) }
        root = pageRoot
    }

    private fun permission() {
        header("접근성 권한", "사용자가 직접 Android 시스템 설정에서 허용합니다.")
        card("권한이 필요한 이유",
            "사용자가 선택한 두 앱의 창 위치와 터치 입력을 확인하기 위해 접근성 서비스를 사용합니다. " +
                "화면 위에 Thumb 메뉴를 표시하고, 사용자가 실행을 선택한 동안에만 입력합니다.")
        card("데이터 처리",
            "패키지명·창 식별자·위치·포커스, 터치 좌표·시간, 비밀번호·편집 가능 여부 등 화면 구조를 " +
                "기기 안에서 일시 확인합니다. 이 정보를 저장하거나 외부로 전송하지 않습니다. " +
                "표시·방향·영점 설정과 소개 확인·동의 여부만 기기에 저장합니다.")
        card("사용 범위",
            "실제 분할 화면의 두 앱은 현재 세션에서 사용자가 직접 승인해야 합니다. " +
                "감지한 위험 화면은 차단하지만 모든 결제·금융·보안 화면의 식별은 보장하지 않습니다. " +
                "원래 터치가 취소될 수 있으며 민감한 작업에는 사용하지 마세요.")
        card("사용자 동의 안내",
            "접근성 서비스는 사용자가 선택한 두 앱의 패키지명·창 위치·터치 이벤트를 확인합니다. " +
                "원래 터치가 취소될 수 있으니 민감한 화면에서는 사용하지 않고 볼륨 아래 키로 중지하세요.",
            top = 16)
        val accepted = CheckBox(this).apply {
            text = "접근성 서비스가 터치를 수신하고 가상 터치를 실행하는 목적·위험을 이해했으며 동의합니다."
            textSize = 14f
            setTextColor(palette.text)
            isChecked = settings.accessibilityDisclosureAccepted
            minHeight = UiKit.dp(this@MainActivity, 52)
        }
        UiKit.add(root, accepted, topDp = 20)
        val configure = addAction("동의하고 Android 접근성 설정 열기", top = 12) {
            settings.accessibilityDisclosureAccepted = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        configure.isEnabled = accepted.isChecked
        accepted.setOnCheckedChangeListener { _, checked ->
            configure.isEnabled = checked
        }
        addAction("권한 없이 돌아가기", false) { go(Page.HOME) }
    }

    private fun guide() {
        header("실제 분할 앱 연결", "DualTouch 본 앱 하나로 열려 있는 앱 두 개를 제어합니다.")
        card("1 · 플로팅 표시",
            "접근성 서비스 목적에 동의하고 Android 설정에서 활성화한 뒤 DualTouch 홈에서 플로팅 표시를 선택합니다.")
        card("2 · 실제 앱 두 개 분할",
            "Android의 좌우 또는 상하 분할 화면에서 원하는 앱을 엽니다. 같은 앱을 두 번 실행하려면 앱·OS의 독립 실행 지원이 필요합니다.")
        card("3 · 앱 연결과 방향 선택",
            "Thumb를 누른 다음 '좌/우 선택 · 앱 연결'에서 좌우 앱 패키지를 확인하고 직접 터치할 방향을 선택합니다.")
        card("4 · 실행·영점 조절",
            "Thumb의 영점 조절에서 가로·세로 기준선 방향과 위치(20~80%)를 바꾸고, 빨강/흰색 점으로 위치를 보정합니다. " +
                "실제 분할 방향은 Android 분할 화면에서 변경한 뒤 앱을 다시 연결해야 합니다.")
        card("5 · 긴급 중지",
            "Thumb의 중지 또는 기기 볼륨 아래 버튼으로 종료합니다. 화면 구성이 바뀌면 앱 연결을 다시 승인해야 합니다.")
        addAction("홈에서 플로팅 표시", top = 20) { go(Page.HOME) }
    }

    private fun confirm() {
        header("입력 실험 확인", "직접 터치 재생과 자동 입력이 정상 작동하는지는 아직 미검증입니다.")
        card("실험 대상",
            "전용 Test Left와 Test Right 두 개의 분할 창에서만 동작합니다. " +
                "실제 개인 앱에서는 사용할 수 없습니다.")
        card("중지 방법",
            "실행 후 20초 또는 볼륨 아래 키로 중단됩니다. " +
                "실험 중 스크롤·멀티터치 사용 시 입력이 취소될 수 있습니다.")
        addAction("선택 실험 시작", top = 20) {
            val service = TouchMirrorService.current
            when {
                !settings.accessibilityDisclosureAccepted -> go(Page.PERMISSION)
                service == null -> go(Page.PERMISSION)
                service.startExperiment(chosenMode) -> go(Page.HOME)
                else -> go(Page.HELP)
            }
        }
        addAction("취소", false) { go(Page.HOME) }
    }

    private fun advanced() {
        header("추가 입력 방식", "Thumb 외 Android 접근성 입력 경로를 비교하는 기술 실험입니다.")
        card("기본 Thumb와 구분",
            "다음 방식은 실제 터치 이벤트를 가로챌 수 있습니다. 사용자가 명시적으로 선택한 경우에만 실행합니다.")
        val choices = listOf(
            MirrorMode.DUAL_STROKE to "동시 두 스트로크",
            MirrorMode.SEQUENTIAL to "순차 두 탭",
            MirrorMode.NODE_CLICK to "접근성 노드 클릭",
        )
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
        UiKit.add(root, group, topDp = 14)
        addAction("실험 진행", top = 20) { go(Page.CONFIRM) }
    }

    private fun help() {
        header("오류·중지 안내", "실험 범위를 벗어나면 안전을 위해 동기화를 중단합니다.")
        card("플로팅이 보이지 않을 때",
            "접근성 서비스가 활성인지 확인하고, 홈에서 '플로팅 표시'를 선택하세요.")
        card("두 창을 인식하지 못할 때",
            "실제 앱 두 개를 분할로 열고 Thumb의 앱 연결 메뉴에서 두 창과 방향을 승인하세요. " +
                "키보드·시스템·보안 UI나 창 변경이 감지되면 입력이 중단됩니다.")
        card("긴급 중지",
            "실행 중 볼륨 아래 키로 중지할 수 있습니다. 원래 터치가 작동하지 않거나 " +
                "두 앱 외에 입력이 발생하면 Android 접근성 설정에서 서비스를 비활성화하세요.")
        val status = TouchMirrorService.current?.statusText ?: "접근성 서비스 OFF"
        card("마지막 서비스 상태", status)
        addAction("플로팅 숨기기", false) {
            TouchMirrorService.current?.hideFloatingControl()
            go(Page.HOME)
        }
        addAction("Android 접근성 설정 열기", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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

