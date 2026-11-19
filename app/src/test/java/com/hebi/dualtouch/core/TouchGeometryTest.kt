package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TouchGeometryTest {
    private val left = ScreenRect(0, 40, 500, 1160)
    private val right = ScreenRect(520, 40, 1020, 1160)

    @Test fun mapsCenterToOtherWindow() {
        val result = CoordinateMapper.map(left, right, 250f, 600f, 24f)
        assertNotNull(result)
        assertEquals(770f, result!!.x, 0.01f)
        assertEquals(600f, result.y, 0.01f)
    }

    @Test fun preservesRelativeCoordinatesForUnequalWidths() {
        val result = CoordinateMapper.map(
            left, ScreenRect(600, 100, 1000, 1220),
            125f, 320f, 24f
        )
        assertNotNull(result)
        assertEquals(700f, result!!.x, 0.01f)
        assertEquals(380f, result.y, 0.01f)
    }

    @Test fun excludesUnsafeEdge() {
        assertNull(CoordinateMapper.map(left, right, 5f, 500f, 24f))
        assertNull(CoordinateMapper.map(left, right, 250f, 50f, 24f))
    }

    @Test fun excludesOutOfWindowTap() {
        assertNull(CoordinateMapper.map(left, right, 505f, 600f, 24f))
    }

    @Test fun excludesInvalidRectangle() {
        assertNull(
            CoordinateMapper.map(
                ScreenRect(0, 0, 0, 40), right, 0f, 10f, 1f
            )
        )
    }

    @Test fun detectsValidWindowPair() {
        val pair = WindowPairResolver.resolve(
            listOf(
                WindowSnapshot(TestPackages.RIGHT, right),
                WindowSnapshot(TestPackages.LEFT, left),
                WindowSnapshot("com.other.app", ScreenRect(0, 0, 1000, 1000)),
            )
        )
        assertNotNull(pair)
        assertEquals(TestPackages.LEFT, pair!!.left.packageName)
        assertEquals(TestPackages.RIGHT, pair.right.packageName)
    }

    @Test fun rejectsMissingOrDuplicateProbe() {
        val l = WindowSnapshot(TestPackages.LEFT, left)
        val r = WindowSnapshot(TestPackages.RIGHT, right)
        assertNull(WindowPairResolver.resolve(listOf(l)))
        assertNull(WindowPairResolver.resolve(listOf(l, l, r)))
    }

    @Test fun rejectsOverlappingAndPoorlyAlignedWindows() {
        val l = WindowSnapshot(TestPackages.LEFT, left)
        assertNull(
            WindowPairResolver.resolve(
                listOf(l, WindowSnapshot(TestPackages.RIGHT, ScreenRect(400, 40, 900, 1160)))
            )
        )
        assertNull(
            WindowPairResolver.resolve(
                listOf(l, WindowSnapshot(TestPackages.RIGHT, ScreenRect(520, 850, 1020, 1800)))
            )
        )
    }
}
