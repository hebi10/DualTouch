package com.hebi.dualtouch.probe

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

class ProbeActivity : Activity() {
    private val counts = mutableMapOf<String, Int>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = if (packageName.endsWith("probeleft")) "LEFT" else "RIGHT"
        val background = if (label == "LEFT") Color.rgb(245, 245, 245) else Color.WHITE

        val screen = FrameLayout(this).apply { setBackgroundColor(background) }
        setContentView(screen)

        status = TextView(this).apply {
            text = "$label · 탭 0회\n5개 테스트 위치의 카운터를 확인하세요."
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(226, 226, 226))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        screen.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(95),
                Gravity.TOP,
            ),
        )

        addTarget(screen, "좌상", Gravity.TOP or Gravity.START, 0, 112)
        addTarget(screen, "우상", Gravity.TOP or Gravity.END, 0, 112)
        addTarget(screen, "중앙", Gravity.CENTER, 0, 0)
        addTarget(screen, "좌하", Gravity.BOTTOM or Gravity.START, 0, 28)
        addTarget(screen, "우하", Gravity.BOTTOM or Gravity.END, 0, 28)
    }

    private fun addTarget(
        parent: FrameLayout,
        location: String,
        gravity: Int,
        marginX: Int,
        marginY: Int,
    ) {
        val button = Button(this).apply {
            text = location
            textSize = 16f
            setOnClickListener {
                counts[location] = (counts[location] ?: 0) + 1
                val total = counts.values.sum()
                status.text = "총 $total 회 · 마지막 $location\n" +
                    "좌상 ${counts["좌상"] ?: 0}, 우상 ${counts["우상"] ?: 0}, " +
                    "중앙 ${counts["중앙"] ?: 0}, 좌하 ${counts["좌하"] ?: 0}, 우하 ${counts["우하"] ?: 0}"
                status.contentDescription = "총 $total 회, 마지막 터치 $location"
            }
        }

        val params = FrameLayout.LayoutParams(dp(92), dp(58), gravity)
        params.setMargins(
            dp(if (gravity and Gravity.START == Gravity.START) 12 + marginX else 0),
            dp(if (gravity and Gravity.TOP == Gravity.TOP) marginY else 0),
            dp(if (gravity and Gravity.END == Gravity.END) 12 + marginX else 0),
            dp(if (gravity and Gravity.BOTTOM == Gravity.BOTTOM) marginY else 0),
        )
        parent.addView(button, params)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
