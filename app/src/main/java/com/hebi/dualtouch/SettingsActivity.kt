package com.hebi.dualtouch

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var palette: UiPalette
    private lateinit var store: AppearanceSettings
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var previewRow: LinearLayout
    private lateinit var viewport: AdaptiveSpec
    private var savedScrollY = 0
    private var appearance = TouchAppearance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = UiPalette(this)
        store = AppearanceSettings(this)
        appearance = store.get()
        savedScrollY = savedInstanceState?.getInt("scrollY") ?: 0
        render(savedScrollY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::scroll.isInitialized) outState.putInt("scrollY", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun render(scrollTo: Int = 0) {
        viewport = AdaptiveLayout.specFor(this)
        scroll = ScrollView(this).apply {
            setBackgroundColor(palette.background)
            isFillViewport = true
            clipToPadding = false
        }
        root = UiKit.centeredScrollContent(
            this, scroll, viewport, maxReadingWidth = !viewport.useTwoPanes
        )
        UiKit.applyWindowInsets(scroll)
        setContentView(scroll)

        val back = UiKit.action(this, palette, "설정 닫기", false)
        back.setOnClickListener { finish() }
        UiKit.add(root, back, bottomDp = 18)
        UiKit.add(root, UiKit.text(this, palette, "터치 표시 설정", 25f, true))
        UiKit.add(root, UiKit.text(this, palette,
            "플로팅 Thumb와 영점 기준점의 모양을 조절합니다. 변경 내용은 즉시 저장됩니다.",
            14f, secondary = true), topDp = 6, bottomDp = 18)

        val pageRoot = root
        var controlsRoot: LinearLayout? = null
        if (viewport.useTwoPanes) {
            val panes = UiKit.panePair(this, viewport.paneGapDp, 0.40f)
            UiKit.add(pageRoot, panes.row, topDp = 10)
            root = panes.primary
            controlsRoot = panes.secondary
        }

        UiKit.add(root, UiKit.sectionHeader(this, palette, "미리보기"), bottomDp = 10)
        val preview = UiKit.card(this, palette, 18)
        previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        redrawPreview()
        val previewScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "Thumb와 기준점 미리보기. 크기가 크면 가로로 움직여 확인합니다."
            addView(previewRow)
        }
        UiKit.add(preview, previewScroll)
        UiKit.add(preview, UiKit.text(this, palette,
            "Thumb · 직접 터치점 · 자동 터치점", 12f, secondary = true), topDp = 10)
        UiKit.add(root, preview, bottomDp = 22)
        if (controlsRoot != null) root = controlsRoot

        UiKit.add(root, UiKit.sectionHeader(this, palette, "플로팅 Thumb"), bottomDp = 8)
        addSlider("Thumb 크기", "48–88dp, 터치 영역을 충분히 확보합니다.",
            AppearanceRules.MIN_SIZE_DP, AppearanceRules.MAX_SIZE_DP,
            appearance.thumbSizeDp, "dp") { appearance.copy(thumbSizeDp = it) }
        addSlider("Thumb 투명도", "완전히 사라지지 않도록 최소 40%로 제한합니다.",
            AppearanceRules.MIN_OPACITY, AppearanceRules.MAX_OPACITY,
            appearance.thumbOpacityPercent, "%") { appearance.copy(thumbOpacityPercent = it) }
        addColors("Thumb 색상", appearance.thumbColor) {
            appearance.copy(thumbColor = it)
        }

        UiKit.add(root, UiKit.sectionHeader(this, palette, "영점 기준점"), topDp = 24, bottomDp = 8)
        UiKit.add(root, UiKit.text(this, palette,
            "직접 터치는 기본 빨강, 자동 터치는 기본 흰색입니다. 색상을 바꿔도 각 점의 역할은 유지됩니다.",
            13f, secondary = true), bottomDp = 10)
        addSlider("터치 점 크기", "빨강과 흰색 기준점에 공통 적용됩니다.",
            AppearanceRules.MIN_SIZE_DP, AppearanceRules.MAX_SIZE_DP,
            appearance.markerSizeDp, "dp") { appearance.copy(markerSizeDp = it) }
        addSlider("터치 점 투명도", "빨강과 흰색 기준점에 공통 적용됩니다.",
            AppearanceRules.MIN_OPACITY, AppearanceRules.MAX_OPACITY,
            appearance.markerOpacityPercent, "%") { appearance.copy(markerOpacityPercent = it) }
        addColors("직접 터치점 색상", appearance.directColor) {
            appearance.copy(directColor = it)
        }
        addColors("자동 터치점 색상", appearance.automaticColor) {
            appearance.copy(automaticColor = it)
        }

        UiKit.add(root, UiKit.sectionHeader(this, palette, "초기화"), topDp = 24)
        UiKit.add(root, UiKit.text(this, palette,
            "표시 크기·색상·투명도만 초기화합니다. 터치 방향과 영점 보정값은 유지됩니다.",
            13f, secondary = true), topDp = 8)
        val reset = UiKit.action(this, palette, "표시 설정 기본값 복원", false)
        reset.setOnClickListener {
            store.reset()
            appearance = store.get()
            TouchMirrorService.current?.refreshFloatingAppearance()
            render()
        }
        UiKit.add(root, reset, topDp = 12, bottomDp = 28)
        root = pageRoot
        scroll.post { scroll.scrollTo(0, scrollTo) }
    }

    private fun addSlider(
        title: String,
        description: String,
        min: Int,
        max: Int,
        current: Int,
        suffix: String,
        modify: (Int) -> TouchAppearance,
    ) {
        val headingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headingRow.addView(UiKit.text(this, palette, title, 15f, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val number = UiKit.text(this, palette, "$current$suffix", 14f, true)
        headingRow.addView(number)
        UiKit.add(root, headingRow, topDp = 13)
        UiKit.add(root, UiKit.text(this, palette, description, 12f,
            secondary = true), topDp = 3)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
            contentDescription = title
            minHeight = UiKit.dp(this@SettingsActivity, 48)
            progressTintList = ColorStateList.valueOf(palette.text)
            thumbTintList = ColorStateList.valueOf(palette.text)
            progressBackgroundTintList = ColorStateList.valueOf(palette.line)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = min + progress
                    number.text = "$value$suffix"
                    appearance = modify(value)
                    persist()
                    redrawPreview()
                }
            }
        })
        UiKit.add(root, seek, topDp = 4)
    }

    private fun addColors(
        title: String,
        active: Int,
        modify: (Int) -> TouchAppearance,
    ) {
        UiKit.add(root, UiKit.text(this, palette, title, 15f, true), topDp = 16)
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "$title 색상 선택 목록. 가로로 이동해 다른 색을 선택할 수 있습니다."
        }
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chips = mutableListOf<Pair<ColorChoice, TextView>>()
        TouchColors.choices.forEach { choice ->
            val group = UiKit.column(this, 3)
            val marker = previewDot(
                if (choice.argb == active) "✓" else "",
                choice.argb, 48, 100,
                choice.argb == active
            )
            marker.isClickable = true
            marker.isFocusable = true
            setChipState(marker, title, choice, choice.argb == active)
            marker.setOnClickListener {
                appearance = modify(choice.argb)
                persist()
                redrawPreview()
                chips.forEach { (option, chip) ->
                    setChipState(chip, title, option, option.argb == choice.argb)
                }
            }
            group.addView(marker, LinearLayout.LayoutParams(
                UiKit.dp(this, 48), UiKit.dp(this, 48)
            ))
            group.addView(UiKit.centeredLabel(
                this, choice.title, palette.secondary, 11f))
            choices.addView(group, LinearLayout.LayoutParams(
                UiKit.dp(this, 68), ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            chips.add(choice to marker)
        }
        horizontal.addView(choices)
        UiKit.add(root, horizontal, topDp = 8)
    }

    private fun setChipState(
        marker: TextView,
        title: String,
        choice: ColorChoice,
        selected: Boolean,
    ) {
        marker.text = if (selected) "✓" else ""
        marker.isSelected = selected
        marker.contentDescription = "$title: ${choice.title}" +
            if (selected) ", 선택됨" else ", 선택 가능"
        marker.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(choice.argb)
            setStroke(UiKit.dp(this@SettingsActivity, if (selected) 3 else 1),
                if (selected) palette.text else palette.line)
        }
    }

    private fun redrawPreview() {
        previewRow.removeAllViews()
        previewRow.addView(previewDot("◎", appearance.thumbColor,
            appearance.thumbSizeDp, appearance.thumbOpacityPercent),
            LinearLayout.LayoutParams(UiKit.dp(this, appearance.thumbSizeDp),
                UiKit.dp(this, appearance.thumbSizeDp)))
        previewRow.addView(space(18))
        previewRow.addView(previewDot("직접", appearance.directColor,
            appearance.markerSizeDp, appearance.markerOpacityPercent),
            LinearLayout.LayoutParams(UiKit.dp(this, appearance.markerSizeDp),
                UiKit.dp(this, appearance.markerSizeDp)))
        previewRow.addView(space(12))
        previewRow.addView(previewDot("자동", appearance.automaticColor,
            appearance.markerSizeDp, appearance.markerOpacityPercent),
            LinearLayout.LayoutParams(UiKit.dp(this, appearance.markerSizeDp),
                UiKit.dp(this, appearance.markerSizeDp)))
    }

    private fun persist() {
        store.save(appearance)
        TouchMirrorService.current?.refreshFloatingAppearance()
    }

    private fun previewDot(
        label: String,
        color: Int,
        sizeDp: Int,
        percent: Int,
        selected: Boolean = false,
    ): TextView {
        return UiKit.centeredLabel(this, label,
            AppearanceRules.readableTextColor(color),
            if (sizeDp <= 44) 16f else 11f).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(UiKit.dp(this@SettingsActivity, if (selected) 3 else 1),
                    if (selected) palette.text else palette.line)
            }
            alpha = percent / 100f
            minimumWidth = UiKit.dp(this@SettingsActivity, sizeDp)
            minimumHeight = UiKit.dp(this@SettingsActivity, sizeDp)
        }
    }

    private fun space(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(UiKit.dp(this@SettingsActivity, dp), 1)
    }
}
