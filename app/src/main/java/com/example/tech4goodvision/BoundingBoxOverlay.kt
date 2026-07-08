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
    private var currentText: String = "" // Stores the W & H text

    // This is our "Green Marker" to draw the box
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 10f // Makes the line nice and thick
    }

    // Paint for the dark background behind the text so it's always readable
    private val textBgPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    // Paint for the white text itself
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        isAntiAlias = true
        isFakeBoldText = true
    }

    // MainActivity now passes BOTH the box coordinates AND the text!
    fun drawBox(box: RectF?, text: String = "") {
        currentBox = box
        currentText = text
        invalidate() // This magic word forces the screen to instantly redraw itself!
    }

    // This actually draws the rectangle and text on the glass
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        currentBox?.let { rect ->
            // 1. Draw the Green Box
            canvas.drawRect(rect, boxPaint)

            // 2. Draw the Text (if there is any)
            if (currentText.isNotEmpty()) {
                // Figure out how wide the text is so we can draw a perfect background box for it
                val textWidth = textPaint.measureText(currentText)

                // NEW MATH: Draw the dark background block inside the TOP-LEFT corner of the box
                canvas.drawRect(
                    rect.left,
                    rect.top,                      // Start exactly at the top line of the box
                    rect.left + textWidth + 20f,   // Add 20px padding to the right
                    rect.top + 70f,                // Go down 70px from the top for the background height
                    textBgPaint
                )

                // NEW MATH: Draw the actual White Text inside the background block
                canvas.drawText(
                    currentText,
                    rect.left + 10f,   // 10px padding from the left edge
                    rect.top + 50f,    // Drop down 50px from the top so it sits neatly inside the dark box
                    textPaint
                )
            }
        }
    }
}