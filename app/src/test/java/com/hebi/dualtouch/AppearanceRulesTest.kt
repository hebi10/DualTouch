package com.hebi.dualtouch

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceRulesTest {
    @Test fun sizeIsRestrictedToSafeTouchTargets() {
        assertEquals(48, AppearanceRules.size(2))
        assertEquals(88, AppearanceRules.size(500))
        assertEquals(56, AppearanceRules.size(56))
    }

    @Test fun opacityCannotHideTouchControlsCompletely() {
        assertEquals(40, AppearanceRules.opacity(0))
        assertEquals(100, AppearanceRules.opacity(101))
        assertEquals(75, AppearanceRules.opacity(75))
    }

    @Test fun defaultMarkersRemainRedAndWhite() {
        val a = TouchAppearance()
        assertEquals(0xFFC62828.toInt(), a.directColor)
        assertEquals(0xFFFFFFFF.toInt(), a.automaticColor)
    }

    @Test fun opacityAndAlphaAreNormalized() {
        val a = AppearanceRules.normalize(TouchAppearance(
            thumbSizeDp = 10,
            markerSizeDp = 500,
            thumbOpacityPercent = 1,
            markerOpacityPercent = 130,
            thumbColor = 0x00333333,
        ))
        assertEquals(48, a.thumbSizeDp)
        assertEquals(88, a.markerSizeDp)
        assertEquals(40, a.thumbOpacityPercent)
        assertEquals(100, a.markerOpacityPercent)
        assertEquals(0xFF333333.toInt(), a.thumbColor)
    }

    @Test fun textColorIsReadableOnDarkAndLightMarkers() {
        assertEquals(0xFFFFFFFF.toInt(),
            AppearanceRules.readableTextColor(0xFF252525.toInt()))
        assertEquals(0xFF111111.toInt(),
            AppearanceRules.readableTextColor(0xFFFFFFFF.toInt()))
    }
}
