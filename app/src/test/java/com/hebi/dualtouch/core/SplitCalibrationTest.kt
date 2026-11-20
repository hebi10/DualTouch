package com.hebi.dualtouch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitCalibrationTest {
    private val left = WindowSnapshot("one", ScreenRect(0, 35, 450, 1000), 0, 10)
    private val right = WindowSnapshot("two", ScreenRect(475, 35, 925, 1000), 0, 11)
    private val upper = WindowSnapshot("one", ScreenRect(0, 30, 900, 470), 0, 10)
    private val lower = WindowSnapshot("two", ScreenRect(0, 490, 900, 930), 0, 11)

    @Test fun horizontalAndVerticalSplitDetected() {
        val horizontal = SplitGeometry.detect(right, left)
        assertNotNull(horizontal)
        assertEquals(SplitOrientation.HORIZONTAL, horizontal!!.orientation)
        assertEquals(left.packageName, horizontal.left.packageName)

        val vertical = SplitGeometry.detect(lower, upper)
        assertNotNull(vertical)
        assertEquals(SplitOrientation.VERTICAL, vertical!!.orientation)
        assertEquals(upper.packageName, vertical.left.packageName)
    }

    @Test fun verticalSplitReverseDirectionUsesBottomAsSource() {
        val pair = SplitGeometry.detect(upper, lower)!!
        assertEquals(lower.bounds, pair.source(TouchSide.RIGHT).bounds)
        assertEquals(upper.bounds, pair.target(TouchSide.RIGHT).bounds)
        val mapped = CoordinateMapper.map(lower.bounds, upper.bounds, 450f, 710f, 24f)
        assertNotNull(mapped)
        assertEquals(450f, mapped!!.x, .01f)
        assertEquals(250f, mapped.y, .01f)
    }

    @Test fun wrongOverlapAndDistinctDisplaysRejected() {
        assertNull(SplitGeometry.detect(left, WindowSnapshot(
            "two", ScreenRect(200, 800, 650, 1200), 0, 11)))
        assertNull(SplitGeometry.detect(upper, lower.copy(displayId=2)))
    }

    @Test fun defaultGuideAxisMatchesSplit() {
        assertEquals(GuideAxis.VERTICAL, CalibrationGuide.defaultAxis(SplitOrientation.HORIZONTAL))
        assertEquals(GuideAxis.HORIZONTAL, CalibrationGuide.defaultAxis(SplitOrientation.VERTICAL))
    }

    @Test fun adjustableGuideLineMovesWithinBounds() {
        val h = CalibrationGuide.reference(upper.bounds, GuideAxis.HORIZONTAL, 70)!!
        assertEquals(450f, h.x, 0.01f)
        assertEquals(338f, h.y, 0.01f)
        val verticalLine = CalibrationGuide.line(left.bounds, GuideAxis.VERTICAL, 50, 3)!!
        assertEquals(3, verticalLine.width)
        assertEquals(left.bounds.height, verticalLine.height)
        val horizontalLine = CalibrationGuide.line(upper.bounds, GuideAxis.HORIZONTAL, 50, 3)!!
        assertEquals(3, horizontalLine.height)
        assertEquals(upper.bounds.width, horizontalLine.width)
    }

    @Test fun guidePercentClampedAndInvalidBoundsRejected() {
        assertEquals(20, CalibrationGuide.clampPercent(0))
        assertEquals(80, CalibrationGuide.clampPercent(100))
        assertEquals(50, CalibrationGuide.clampPercent(50))
        assertNull(CalibrationGuide.reference(ScreenRect(2, 2, 2, 20), GuideAxis.VERTICAL, 50))
    }

    @Test fun redAndWhiteMoveIndependentlyInVerticalLayout() {
        val red = TapPoint(450f, 300f)
        val offset = CalibrationOffset(.04f, -.05f)
        val points = ThumbReferenceMotion.moveSourceTogether(
            upper.bounds, lower.bounds, red, offset, 24f
        )
        assertNotNull(points)
        assertEquals(red, points!!.first)
        val whiteOnly = TapPoint(points.second.x + 18f, points.second.y)
        val changedOffset = ThumbReferenceMotion.moveAutomaticOnly(
            upper.bounds, lower.bounds, red, whiteOnly, 24f
        )
        assertNotNull(changedOffset)
        assertEquals(red, points.first)
        assertEquals(offset.dx + .02f, changedOffset!!.dx, .0001f)
    }
}
