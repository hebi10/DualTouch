package com.hebi.dualtouch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    private val refresh = object : Runnable {
        override fun run() {
            val service = TouchMirrorService.current
            status.text = service?.statusText ?: "접근성 서비스 OFF — 시스템 설정에서 직접 켜야 합니다."
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(32))
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        setContentView(scroll)

        fun heading(value: String, sp: Float = 23f): TextView {
            return TextView(this).apply {
                text = value
                textSize = sp
                setTextColor(Color.BLACK)
                setPadding(0, dp(10), 0, dp(10))
                container.addView(this)
            }
        }

        fun button(label: String, action: () -> Unit) {
            Button(this).apply {
                text = label
                setOnClickListener { action() }
                container.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

        heading("DualTouch · DT-002")
        heading("양쪽 테스트 앱 터치 실험", 18f)
        heading(
            "기술 검증 전용입니다. 터치 입력을 수집하는 동안 기존 화면 조작이 차단될 수 있으며, 화면 스크롤/드래그는 지원하지 않습니다.\n" +
                "금융·결제·비밀번호·타사 앱에는 입력하지 않습니다.\n" +
                "긴급 중지: 물리 볼륨 아래 버튼. 활성화 후 20초에 자동 종료.",
            14f,
        )

        status = heading("상태 확인 중", 16f)
        button("1. 접근성 권한 설정 직접 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        heading("2. 테스트 모드 선택", 16f)
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modes = listOf(
            "플로팅 썸 드래그·탭 (권장)" to MirrorMode.THUMB,
            "동시 두 스트로크 (실험)" to MirrorMode.DUAL_STROKE,
            "순차 두 탭 (대안)" to MirrorMode.SEQUENTIAL,
            "접근성 노드 클릭 (제한형)" to MirrorMode.NODE_CLICK,
        )
        val radios = modes.map { (label, _) ->
            RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.DKGRAY)
                id = android.view.View.generateViewId()
                group.addView(this)
            }
        }
        group.check(radios.first().id)
        container.addView(group)

        heading(
            "Thumb를 누르면 '실행·중지 / 좌·우 선택 / 영점 조절' 메뉴가 표시됩니다. " +
                "선택한 쪽을 직접 터치하면 반대쪽으로 자동 입력을 시도합니다. " +
                "영점 조절에서는 빨간 점을 움직이면 흰 점도 함께 이동하고, " +
                "흰 점을 움직이면 빨간 점은 고정됩니다. 저장/취소/초기화를 선택하세요. " +
                "원본 입력 재현은 Android 제약에 따라 실패할 수 있으므로 테스트용으로만 사용하세요.",
            14f,
        )
        button("3. Thumb 컨트롤 표시 (창 준비 대기)") {
            val selected = modes[radios.indexOfFirst { it.id == group.checkedRadioButtonId }]
                .second
            val service = TouchMirrorService.current
            if (service == null) {
                status.text = "접근성 서비스가 꺼져 있습니다. 설정에서 먼저 활성화하세요."
            } else if (!service.startExperiment(selected)) {
                status.text = "현재 실험이 실행 중입니다. 먼저 중지하세요."
            } else {
                status.text = service.statusText
            }
        }
        button("동기화 중지 (OFF)") {
            TouchMirrorService.current?.stop("제어 앱의 중지 버튼")
            status.text = TouchMirrorService.current?.statusText ?: "서비스 OFF"
        }
        heading("4. 두 테스트 앱을 좌우 분할로 열기", 16f)
        heading(
            "먼저 Test Left / Test Right APK 두 개를 설치하세요.\n" +
                "Fold를 펼쳐 두 앱을 좌우 5:5로 배치한 뒤, 제어 앱에서 시작을 누르고 " +
                "두 테스트 앱의 분할 화면으로 돌아갑니다. Thumb를 눌러 실행·중지 및 방향을 선택하고, " +
                "선택한 쪽 테스트 앱의 버튼을 직접 누릅니다.\n" +
                "두 창의 카운터가 각각 1회씩 증가하는지 직접 확인합니다.\n" +
                "Thumb 모드에서는 실행 버튼을 누른 시점부터 20초 후 자동 종료합니다. " +
                "실험을 중단할 때는 물리 볼륨 아래 버튼을 사용하세요.",
            14f,
        )
        heading(
            "참고: Android가 제스처 전송 완료를 알리더라도 실제 두 앱이 클릭되었다는 뜻은 아닙니다. " +
                "시연 영상과 좌우 카운터를 확인해야 합니다.",
            13f,
        )
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
