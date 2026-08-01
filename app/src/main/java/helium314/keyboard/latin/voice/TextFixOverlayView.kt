// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import kotlin.reflect.KMutableProperty0

/**
 * Overlay shown in the suggestion strip during a text-fix round-trip.
 *
 * States:
 *  - working: shows "Fixing…" status while the request is in flight.
 *  - result: shows the proposed text plus Replace/Discard buttons.
 */
class TextFixOverlayView(context: Context) : LinearLayout(context) {

    private val statusText: TextView
    private val resultText: TextView
    private val replaceButton: TextView
    private val discardButton: TextView

    var onReplaceClick: (() -> Unit)? = null
    var onDiscardClick: (() -> Unit)? = null
    private var lastReplaceClickMs = 0L
    private var lastDiscardClickMs = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), dp(4), dp(12), dp(4))

        statusText = TextView(context).apply {
            textSize = 13f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
            visibility = View.GONE
        }
        resultText = TextView(context).apply {
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        discardButton = makePillButton(R.string.text_fix_discard, isPrimary = false) {
            debounceClick(::lastDiscardClickMs) { onDiscardClick?.invoke() }
        }
        replaceButton = makePillButton(R.string.text_fix_replace, isPrimary = true) {
            debounceClick(::lastReplaceClickMs) { onReplaceClick?.invoke() }
        }

        addView(statusText)
        addView(resultText)
        addView(discardButton)
        addView(replaceButton)
    }

    private fun makePillButton(labelRes: Int, isPrimary: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isAllCaps = false
            minHeight = dp(36)
            minWidth = dp(64)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
            }
            background = bg
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                // Space between siblings; the left-most button gets gap from the text via its own
                // space, the primary button gets a small trailing margin from the strip edge.
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    fun setColors(textColor: Int) {
        statusText.setTextColor(textColor)
        resultText.setTextColor(textColor)
        // Primary (Replace): strong filled background with full-opacity text.
        replaceButton.setTextColor(textColor)
        (replaceButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x55000000)
        // Secondary (Discard): muted text, subtle fill to keep it clearly recessive.
        discardButton.setTextColor((textColor and 0x00FFFFFF) or 0xB0000000.toInt())
        (discardButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x18000000)
    }

    fun showWorking() {
        statusText.text = context.getString(R.string.text_fix_working)
        statusText.visibility = View.VISIBLE
        resultText.visibility = View.GONE
        replaceButton.visibility = View.GONE
        discardButton.visibility = View.VISIBLE
        announceForAccessibility(statusText.text)
    }

    fun showResult(proposed: String) {
        statusText.visibility = View.GONE
        resultText.text = proposed
        resultText.visibility = View.VISIBLE
        replaceButton.visibility = View.VISIBLE
        discardButton.visibility = View.VISIBLE
        announceForAccessibility(context.getString(R.string.text_fix_result_a11y, proposed))
    }

    fun showError(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        resultText.visibility = View.GONE
        replaceButton.visibility = View.GONE
        discardButton.visibility = View.VISIBLE
        announceForAccessibility(message)
    }

    private fun debounceClick(lastClickMs: KMutableProperty0<Long>, action: () -> Unit) {
        // Replace and Discard both mutate persistent state (cancelling an in-flight request or
        // committing a text replacement). A double-tap should never fire the callback twice.
        // The window is per-button: a shared one let a tap on Replace swallow a deliberate tap on
        // Discard moments later, which is a different action, not a double-tap.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs.get() < 300L) return
        lastClickMs.set(now)
        action()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
