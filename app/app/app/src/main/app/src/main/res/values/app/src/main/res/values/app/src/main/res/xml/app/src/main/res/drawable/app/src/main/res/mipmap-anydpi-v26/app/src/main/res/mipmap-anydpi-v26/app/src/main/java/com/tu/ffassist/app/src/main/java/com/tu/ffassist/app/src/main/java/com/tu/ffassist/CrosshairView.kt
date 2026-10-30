package com.tu.ffassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class CrosshairView(context: Context) : View(context) {

    var color = Color.parseColor("#FF2D2D")
    var radius = 15f
    var gap = 9f
    var thickness = 3f
    var dot = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        paint.strokeWidth = thickness
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawLine(cx, cy - radius - gap, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + radius + gap, paint)
        canvas.drawLine(cx - radius - gap, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + radius + gap, cy, paint)

        if (dot) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, thickness, paint)
            paint.style = Paint.Style.STROKE
        }
    }
}
