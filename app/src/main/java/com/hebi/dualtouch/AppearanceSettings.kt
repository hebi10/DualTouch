package com.hebi.dualtouch

import android.content.Context

data class TouchAppearance(
    val thumbSizeDp: Int = 56,
    val thumbColor: Int = 0xFF292929.toInt(),
    val thumbOpacityPercent: Int = 90,
    val markerSizeDp: Int = 52,
    val directColor: Int = 0xFFC62828.toInt(),
    val automaticColor: Int = 0xFFFFFFFF.toInt(),
    val markerOpacityPercent: Int = 90,
)

data class ColorChoice(val title: String, val argb: Int)

object AppearanceRules {
    const val MIN_SIZE_DP = 48
    const val MAX_SIZE_DP = 88
    const val MIN_OPACITY = 40
    const val MAX_OPACITY = 100

    fun size(value: Int): Int = value.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
    fun opacity(value: Int): Int = value.coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun normalize(value: TouchAppearance): TouchAppearance = value.copy(
        thumbSizeDp = size(value.thumbSizeDp),
        markerSizeDp = size(value.markerSizeDp),
        thumbOpacityPercent = opacity(value.thumbOpacityPercent),
        markerOpacityPercent = opacity(value.markerOpacityPercent),
        thumbColor = value.thumbColor or 0xFF000000.toInt(),
        directColor = value.directColor or 0xFF000000.toInt(),
        automaticColor = value.automaticColor or 0xFF000000.toInt(),
    )

    fun readableTextColor(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        return if (brightness >= 150) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
    }
}

object TouchColors {
    val choices = listOf(
        ColorChoice("검정", 0xFF252525.toInt()),
        ColorChoice("회색", 0xFF777777.toInt()),
        ColorChoice("흰색", 0xFFFFFFFF.toInt()),
        ColorChoice("빨강", 0xFFC62828.toInt()),
        ColorChoice("파랑", 0xFF2563EB.toInt()),
        ColorChoice("하늘", 0xFF60C5EE.toInt()),
        ColorChoice("주황", 0xFFEA8B36.toInt()),
        ColorChoice("노랑", 0xFFF4D35E.toInt()),
    )

    fun displayChoice(choice: ColorChoice, active: Int, isThumb: Boolean): ColorChoice =
        if (isThumb && active == TouchAppearance().thumbColor && choice == choices.first()) {
            choice.copy(title = "기본 검정", argb = active)
        } else choice

    fun labelOf(color: Int): String =
        choices.firstOrNull { it.argb == color }?.title ?: "선택 색상"
}

class AppearanceSettings(context: Context) {
    private val preferences =
        context.getSharedPreferences("dualtouch_appearance_v1", Context.MODE_PRIVATE)

    fun get(): TouchAppearance {
        val initial = TouchAppearance()
        return AppearanceRules.normalize(TouchAppearance(
            thumbSizeDp = preferences.getInt("thumb_size", initial.thumbSizeDp),
            thumbColor = preferences.getInt("thumb_color", initial.thumbColor),
            thumbOpacityPercent = preferences.getInt("thumb_opacity", initial.thumbOpacityPercent),
            markerSizeDp = preferences.getInt("marker_size", initial.markerSizeDp),
            directColor = preferences.getInt("direct_color", initial.directColor),
            automaticColor = preferences.getInt("automatic_color", initial.automaticColor),
            markerOpacityPercent = preferences.getInt("marker_opacity", initial.markerOpacityPercent),
        ))
    }

    fun save(next: TouchAppearance) {
        val value = AppearanceRules.normalize(next)
        preferences.edit()
            .putInt("thumb_size", value.thumbSizeDp)
            .putInt("thumb_color", value.thumbColor)
            .putInt("thumb_opacity", value.thumbOpacityPercent)
            .putInt("marker_size", value.markerSizeDp)
            .putInt("direct_color", value.directColor)
            .putInt("automatic_color", value.automaticColor)
            .putInt("marker_opacity", value.markerOpacityPercent)
            .apply()
    }

    fun reset() {
        save(TouchAppearance())
    }

    var accessibilityDisclosureAccepted: Boolean
        get() = preferences.getBoolean("accessibility_disclosure_accepted", false) &&
            preferences.getInt("accessibility_disclosure_version", 0) == 2
        set(value) {
            preferences.edit()
                .putBoolean("accessibility_disclosure_accepted", value)
                .putInt("accessibility_disclosure_version", if (value) 2 else 0)
                .apply()
        }

    var onboardingRead: Boolean
        get() = preferences.getBoolean("onboarding_read", false)
        set(value) { preferences.edit().putBoolean("onboarding_read", value).apply() }
}

