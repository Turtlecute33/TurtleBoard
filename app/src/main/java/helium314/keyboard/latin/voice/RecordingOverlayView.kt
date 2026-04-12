// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Minimal recording indicator with pulsing dots animation.
 * Shows in the suggestion strip area during voice recording/transcription.
 */
class RecordingOverlayView(context: Context) : LinearLayout(context) {

    private val dotView: PulsingDotsView
    private val statusText: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        dotView = PulsingDotsView(context)
        dotView.layoutParams = LayoutParams(dp(48), dp(24)).apply {
            marginEnd = dp(8)
        }

        statusText = TextView(context).apply {
            textSize = 14f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        addView(dotView)
        addView(statusText)
    }

    fun setColors(textColor: Int) {
        statusText.setTextColor(textColor)
        dotView.dotColor = textColor
    }

    fun showRecording() {
        statusText.text = context.getString(R.string.voice_recording)
        dotView.visibility = View.VISIBLE
        dotView.startAnimation()
    }

    fun showTranscribing() {
        statusText.text = context.getString(R.string.voice_transcribing)
        dotView.stopAnimation()
        dotView.visibility = View.GONE
    }

    fun stopAnimation() {
        dotView.stopAnimation()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class PulsingDotsView(context: Context) : View(context) {

        var dotColor: Int = 0xFF888888.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun startAnimation() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    phase = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dotRadius = height / 6f
            val spacing = width / 4f
            val centerY = height / 2f

            for (i in 0..2) {
                val dotPhase = (phase + i * 0.33f) % 1f
                val alpha = (kotlin.math.sin(dotPhase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
                    .coerceIn(0.2f, 1f)
                paint.color = dotColor
                paint.alpha = (alpha * 255).toInt()
                canvas.drawCircle(spacing * (i + 0.5f), centerY, dotRadius, paint)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopAnimation()
        }
    }
}
