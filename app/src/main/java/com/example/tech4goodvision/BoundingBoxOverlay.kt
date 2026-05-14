package com.example.tech4goodvision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// This class acts as a transparent piece of glass over your camera
class BoundingBoxOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var currentBox: RectF? = null

    // This is our "Green Marker" to draw the box
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 10f // Makes the line nice and thick
    }

    // MainActivity will call this function to hand over the coordinates
    fun drawBox(box: RectF?) {
        currentBox = box
        invalidate() // This magic word forces the screen to instantly redraw itself!
    }

    // This actually draws the rectangle on the glass
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        currentBox?.let {
            canvas.drawRect(it, boxPaint)
        }
    }
}