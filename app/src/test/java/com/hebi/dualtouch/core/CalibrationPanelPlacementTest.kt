package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPanelPlacementTest {
    private val safe = ScreenRect(0, 114, 1080, 2098)
    private val direct = ScreenRect(472, 465, 608, 601)
    private val automatic = ScreenRect(472, 1558, 608, 1694)
    private val thumb = ScreenRect(31, 283, 178, 430)

    @Test fun compactFoldedPanelDoesNotObstructActualMarkerBounds() {
        val result = CalibrationPanelPlacement.find(safe, 724, 180,
            listOf(direct, automatic, thumb), 26, 146, 10)
        assertNotNull(result)
        val panel = requireNotNull(result)
        assertTrue(listOf(direct, automatic, thumb).none { panel.overlaps(it) })
        assertTrue(panel.left >= safe.left && panel.right <= safe.right)
        assertTrue(panel.top >= safe.top && panel.bottom <= safe.bottom)
    }

    @Test fun tallerPanelMovesAwayFromPreviouslyCoveredDirectMarker() {
        val result = CalibrationPanelPlacement.find(safe, 724, 342,
            listOf(direct, automatic, thumb), 26, 146, 10)
        assertNotNull(result)
        val panel = requireNotNull(result)
        assertTrue(panel.top != 146 || panel.left != 26)
        assertTrue(listOf(direct, automatic, thumb).none { panel.overlaps(it) })
    }

    @Test fun leavesAlreadySafePositionUnchanged() {
        assertEquals(ScreenRect(0, 700, 400, 820),
            CalibrationPanelPlacement.find(safe, 400, 120,
                listOf(direct, automatic), 0, 700, 10))
    }

    @Test fun impossiblePlacementDoesNotOverlapOrMoveMarkers() {
        assertNull(CalibrationPanelPlacement.find(ScreenRect(0, 0, 320, 400),
            320, 160, listOf(ScreenRect(0, 100, 320, 300)), 0, 0, 8))
        assertNull(CalibrationPanelPlacement.find(safe, 1200, 100,
            listOf(direct, automatic), 0, 0))
    }
}
