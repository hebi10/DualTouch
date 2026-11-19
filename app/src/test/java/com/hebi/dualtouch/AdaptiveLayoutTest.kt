package com.hebi.dualtouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun compactPhonesKeepOneColumn() {
        val portrait = AdaptiveLayout.specFor(360, 780)
        assertEquals(WidthCategory.COMPACT, portrait.widthCategory)
        assertFalse(portrait.useTwoPanes)
        assertEquals(336, portrait.contentWidthDp)
    }

    @Test fun mediumTabletsKeepReadableSingleColumn() {
        val tablet = AdaptiveLayout.specFor(700, 1080)
        assertEquals(WidthCategory.MEDIUM, tablet.widthCategory)
        assertFalse(tablet.useTwoPanes)
        assertEquals(652, tablet.contentWidthDp)
    }

    @Test fun largeTabletUsesTwoPanesWithCappedWidth() {
        val tablet = AdaptiveLayout.specFor(1320, 900)
        assertEquals(WidthCategory.EXPANDED, tablet.widthCategory)
        assertTrue(tablet.useTwoPanes)
        assertEquals(1180, tablet.contentWidthDp)
        assertEquals(720, tablet.contentWidthForReadingDp)
    }

    @Test fun wideButShortLandscapeIsOneColumn() {
        val landscape = AdaptiveLayout.specFor(900, 440)
        assertEquals(WidthCategory.EXPANDED, landscape.widthCategory)
        assertFalse(landscape.useTwoPanes)
        assertEquals(720, landscape.contentWidthDp)
    }

    @Test fun breakpointsUseCurrentWindowNotDeviceCategory() {
        assertEquals(WidthCategory.COMPACT, AdaptiveLayout.specFor(599, 800).widthCategory)
        assertEquals(WidthCategory.MEDIUM, AdaptiveLayout.specFor(600, 800).widthCategory)
        assertEquals(WidthCategory.MEDIUM, AdaptiveLayout.specFor(839, 800).widthCategory)
        assertTrue(AdaptiveLayout.specFor(840, 480).useTwoPanes)
        assertFalse(AdaptiveLayout.specFor(840, 479).useTwoPanes)
    }

    @Test fun tinyResizedWindowsNeverProduceNegativeContentWidth() {
        val small = AdaptiveLayout.specFor(150, 280)
        assertTrue(small.contentWidthDp > 0)
        assertFalse(small.useTwoPanes)
    }
}
