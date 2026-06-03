// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.settings.Settings;

/**
 * Draws a brief expanding, fading ring at each key-press location on the overlay placer view.
 * It lives entirely on top of the buffered key rendering, so it never touches the hot key-draw
 * path: ripples are spawned on press and self-animate by re-invalidating the overlay until they
 * fade out. A small ring buffer lets quick successive taps overlap naturally.
 */
public final class KeyPressRippleDrawingPreview extends AbstractDrawingPreview {
    private static final long RIPPLE_DURATION_MS = 220L;
    private static final int MAX_RIPPLES = 6;
    private static final float START_RADIUS_DP = 8f;
    private static final float END_RADIUS_DP = 26f;
    private static final int START_ALPHA = 90; // out of 255; kept subtle on purpose

    private static final class Ripple {
        float cx, cy;
        long startTime; // 0 means the slot is free
    }

    private final Ripple[] mRipples = new Ripple[MAX_RIPPLES];
    private int mNextSlot = 0;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mStartRadius;
    private final float mEndRadius;

    public KeyPressRippleDrawingPreview(final float density) {
        for (int i = 0; i < MAX_RIPPLES; i++) {
            mRipples[i] = new Ripple();
        }
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2.0f * density);
        mStartRadius = START_RADIUS_DP * density;
        mEndRadius = END_RADIUS_DP * density;
    }

    /** Spawn a ripple centred at (x, y) in placer-view coordinates. */
    public void addRipple(final float x, final float y) {
        if (!isPreviewEnabled()) {
            return;
        }
        final Ripple r = mRipples[mNextSlot];
        r.cx = x;
        r.cy = y;
        r.startTime = SystemClock.uptimeMillis();
        mNextSlot = (mNextSlot + 1) % MAX_RIPPLES;
        invalidateDrawingView();
    }

    @Override
    public void drawPreview(@NonNull final Canvas canvas) {
        if (!isPreviewEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final int color = Settings.getValues().mColors.get(ColorType.GESTURE_TRAIL);
        boolean anyAlive = false;
        for (final Ripple r : mRipples) {
            if (r.startTime == 0) {
                continue;
            }
            final float progress = (now - r.startTime) / (float) RIPPLE_DURATION_MS;
            if (progress >= 1f) {
                r.startTime = 0;
                continue;
            }
            anyAlive = true;
            final float eased = 1f - (1f - progress) * (1f - progress); // ease-out quad
            final float radius = mStartRadius + (mEndRadius - mStartRadius) * eased;
            mPaint.setColor(color);
            mPaint.setAlpha((int) (START_ALPHA * (1f - progress)));
            canvas.drawCircle(r.cx, r.cy, radius, mPaint);
        }
        // Keep the overlay refreshing only while a ripple is still animating.
        if (anyAlive) {
            invalidateDrawingView();
        }
    }

    @Override
    public void setPreviewPosition(@NonNull final PointerTracker tracker) {
        // Ripples are spawned explicitly on key press, not driven by pointer movement.
    }

    @Override
    public void onDeallocateMemory() {
        for (final Ripple r : mRipples) {
            r.startTime = 0;
        }
    }
}
