package com.hebi.dualtouch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

enum class LineIcon { BACK, CHEVRON, GEAR, ACCESSIBILITY, FLOATING, SPLIT, BOOK, INFO, WARNING, SHIELD, WARNING_BADGE, CHECK_BADGE }

class UiLineIcon(private val kind: LineIcon, private val color: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var opacity = 255
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun path(canvas: Canvas, vararg coordinates: Float, close: Boolean = false) {
        val outline = Path()
        outline.moveTo(coordinates[0], coordinates[1])
        var i = 2
        while (i < coordinates.size) {
            outline.lineTo(coordinates[i], coordinates[i + 1])
            i += 2
        }
        if (close) outline.close()
        canvas.drawPath(outline, paint)
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color
        paint.alpha = opacity
        paint.style = Paint.Style.STROKE
        when (kind) {
            LineIcon.BACK -> {
                path(canvas, 11f, 5f, 4f, 12f, 11f, 19f)
                canvas.drawLine(4f, 12f, 20f, 12f, paint)
            }
            LineIcon.CHEVRON -> path(canvas, 9f, 6f, 15f, 12f, 9f, 18f)
            LineIcon.GEAR -> {
                val teeth = Path()
                for (i in 0 until 32) {
                    val radius = if (i % 4 == 1 || i % 4 == 2) 10f else 8f
                    val angle = i * Math.PI / 16.0
                    val x = 12f + radius * cos(angle).toFloat()
                    val y = 12f + radius * sin(angle).toFloat()
                    if (i == 0) teeth.moveTo(x, y) else teeth.lineTo(x, y)
                }
                teeth.close()
                canvas.drawPath(teeth, paint)
                canvas.drawCircle(12f, 12f, 3.3f, paint)
            }
            LineIcon.ACCESSIBILITY -> {
                canvas.drawCircle(12f, 4.5f, 2.1f, paint)
                path(canvas, 3f, 9f, 12f, 10f, 21f, 9f)
                path(canvas, 12f, 10f, 12f, 15f, 8f, 21f)
                path(canvas, 12f, 15f, 16f, 21f)
            }
            LineIcon.FLOATING -> {
                path(canvas, 8f, 3f, 20f, 3f, 20f, 16f)
                canvas.drawRoundRect(3f, 8f, 16f, 21f, 1f, 1f, paint)
            }
            LineIcon.SPLIT -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 1f, 1f, paint)
                canvas.drawLine(12f, 4f, 12f, 20f, paint)
            }
            LineIcon.BOOK -> {
                path(canvas, 3f, 5f, 7f, 4f, 12f, 6f, 17f, 4f, 21f, 5f, 21f, 19f, 17f, 18f, 12f, 20f, 7f, 18f, 3f, 19f, close = true)
                canvas.drawLine(12f, 6f, 12f, 20f, paint)
            }
            LineIcon.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(12f, 11f, 12f, 17f, paint)
                canvas.drawPoint(12f, 7.5f, paint)
            }
            LineIcon.WARNING -> {
                path(canvas, 12f, 3f, 22f, 21f, 2f, 21f, close = true)
                canvas.drawLine(12f, 9f, 12f, 14f, paint)
                canvas.drawPoint(12f, 17.5f, paint)
            }
            LineIcon.SHIELD -> {
                path(canvas, 12f, 3f, 21f, 7f, 19.5f, 15.5f, 16f, 19f, 12f, 22f, 8f, 19f, 4.5f, 15.5f, 3f, 7f, close = true)
            }
            LineIcon.WARNING_BADGE, LineIcon.CHECK_BADGE -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 12f, 11f, paint)
                paint.color = AppearanceRules.readableTextColor(color)
                paint.alpha = opacity
                paint.style = Paint.Style.STROKE
                if (kind == LineIcon.WARNING_BADGE) {
                    canvas.drawLine(12f, 6f, 12f, 13f, paint)
                    canvas.drawPoint(12f, 17f, paint)
                } else {
                    path(canvas, 6.5f, 12f, 10.5f, 16f, 18f, 8f)
                }
            }
        }
        canvas.restore()
    }
}
