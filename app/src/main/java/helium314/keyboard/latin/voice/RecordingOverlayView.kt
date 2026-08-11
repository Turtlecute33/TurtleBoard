// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import kotlin.reflect.KMutableProperty0

/**
 * Recording indicator with live amplitude meter, elapsed time, cancel, and stop button.
 * Shown in the suggestion strip area during voice recording/transcription.
 */
class RecordingOverlayView(context: Context) : LinearLayout(context) {

    private val meterView: AmplitudeMeterView
    private val timerText: TextView
    private val statusText: TextView
    private val cancelButton: ImageView
    private val stopButton: ImageView
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    // Per-button debounce: Stop and Cancel must not share a window, or tapping Stop then Cancel in
    // quick succession would silently swallow the Cancel.
    private var lastStopClickMs = 0L
    private var lastCancelClickMs = 0L

    var onStopClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    /** Supplier for live amplitude (0..32767) and elapsed ms. Set by the controller. */
    var telemetryProvider: (() -> Pair<Double, Long>)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), 0, dp(12), 0)

        meterView = AmplitudeMeterView(context).apply {
            layoutParams = LayoutParams(dp(44), dp(20)).apply { marginEnd = dp(12) }
        }
        timerText = TextView(context).apply {
            textSize = 12f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
        }
        statusText = TextView(context).apply {
            textSize = 13f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        cancelButton = makeRoundButton(isCancel = true, descRes = R.string.voice_cancel) {
            debounceClick(::lastCancelClickMs) { onCancelClick?.invoke() }
        }
        stopButton = makeRoundButton(isCancel = false, descRes = R.string.voice_stop_recording) {
            debounceClick(::lastStopClickMs) { onStopClick?.invoke() }
        }

        addView(meterView)
        addView(timerText)
        addView(statusText)
        addView(cancelButton)
        addView(stopButton)
    }

    // The suggestion strip is at least 48dp tall, so these critical controls meet Android's
    // minimum touch-target size without relying on a parent TouchDelegate.
    private fun makeRoundButton(isCancel: Boolean, descRes: Int, onClick: () -> Unit): ImageView {
        val size = dp(48)
        return ImageView(context).apply {
            layoutParams = LayoutParams(size, size).apply { marginStart = dp(8) }
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            background = bg
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val innerSize = dp(11)
            val icon = GradientDrawable().apply {
                shape = if (isCancel) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                if (!isCancel) cornerRadius = dp(2).toFloat()
                setSize(innerSize, innerSize)
            }
            setImageDrawable(icon)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(descRes)
            setOnClickListener { onClick() }
        }
    }

    fun setColors(textColor: Int) {
        statusText.setTextColor(textColor)
        timerText.setTextColor((textColor and 0x00FFFFFF) or 0xAA000000.toInt())
        meterView.meterColor = textColor
        // Stop button gets normal text color; cancel gets a muted hue so users can tell them apart.
        (stopButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x22000000)
        (stopButton.drawable as? GradientDrawable)?.setColor(textColor)
        (cancelButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x11000000)
        (cancelButton.drawable as? GradientDrawable)?.setColor((textColor and 0x00FFFFFF) or 0x99000000.toInt())
    }

    fun showRecording() {
        statusText.text = context.getString(R.string.voice_recording)
        meterView.visibility = View.VISIBLE
        meterView.startAnimation()
        timerText.visibility = View.VISIBLE
        stopButton.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        startTicking()
        announceForAccessibility(statusText.text)
    }

    fun showTranscribing() {
        statusText.text = context.getString(R.string.voice_transcribing)
        meterView.stopAnimation()
        meterView.visibility = View.GONE
        timerText.visibility = View.GONE
        stopButton.visibility = View.GONE
        // Cancel remains visible so the user can abort the upload.
        cancelButton.visibility = View.VISIBLE
        stopTicking()
        announceForAccessibility(statusText.text)
    }

    fun stopAnimation() {
        meterView.stopAnimation()
        stopTicking()
    }

    private fun startTicking() {
        stopTicking()
        val r = object : Runnable {
            override fun run() {
                val telemetry = telemetryProvider?.invoke()
                if (telemetry != null) {
                    meterView.setAmplitude(telemetry.first)
                    timerText.text = formatElapsed(telemetry.second)
                }
                tickHandler.postDelayed(this, 80L)
            }
        }
        tickRunnable = r
        tickHandler.post(r)
    }

    private fun stopTicking() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun debounceClick(lastClickMs: KMutableProperty0<Long>, action: () -> Unit) {
        // Stop/Cancel both have heavy side effects (stop the recorder, cancel an in-flight upload).
        // A spammed double-tap of the *same* button can race the state machine — 300ms is plenty of
        // breathing room. The window is per-button so the two never block each other.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs.get() < 300L) return
        lastClickMs.set(now)
        action()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Draws three horizontal bars whose height follows the live amplitude. Falls back to a
     * gentle pulse while amplitude stays at zero (e.g., right at startup) so the UI never
     * looks frozen.
     */
    private class AmplitudeMeterView(context: Context) : View(context) {
        var meterColor: Int = Color.LTGRAY
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level: Float = 0f // 0..1
        private var animator: ValueAnimator? = null
        private var pulsePhase: Float = 0f

        fun startAnimation() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulsePhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        fun setAmplitude(meanAbs: Double) {
            // Map 0..~6000 to 0..1 with a gentle curve so quiet speech still moves the needle.
            val normalized = (meanAbs / 6000.0).coerceIn(0.0, 1.0)
            val curved = Math.sqrt(normalized).toFloat()
            // Smooth toward target to avoid jitter.
            level = level + (curved - level) * 0.35f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val barCount = 3
            val gap = width / 14f
            val barWidth = (width - gap * (barCount + 1)) / barCount
            val maxBarHeight = height.toFloat() * 0.85f
            val centerY = height / 2f
            paint.color = meterColor
            for (i in 0 until barCount) {
                val phase = (pulsePhase + i * 0.2f) % 1f
                val pulse = (kotlin.math.sin(phase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
                val mix = (level * 0.85f + pulse * 0.15f).coerceIn(0.15f, 1f)
                val h = maxBarHeight * mix
                val left = gap + i * (barWidth + gap)
                val top = centerY - h / 2f
                val bottom = centerY + h / 2f
                paint.alpha = (120 + 135 * mix).toInt().coerceAtMost(255)
                canvas.drawRoundRect(left, top, left + barWidth, bottom, barWidth / 2f, barWidth / 2f, paint)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopAnimation()
        }
    }
}
