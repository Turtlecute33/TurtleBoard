/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.RequiresApi;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.SettingsValues;

/**
 * This class gathers audio feedback and haptic feedback functions.
 * <p>
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private boolean mDoNotDisturb;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void performHapticAndAudioFeedback(
        final int code,
        final View viewToPerformHapticFeedbackOn,
        final HapticEvent hapticEvent
    ) {
        performHapticFeedback(viewToPerformHapticFeedbackOn, hapticEvent);
        performAudioFeedback(code, hapticEvent);
    }

    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    /**
     * A very light single-pulse tick — used for subtle "I crossed something" feedback
     * (e.g. dragging across popup buttons). Bypasses the app's vibrate-on-keypress toggle
     * because callers gate this on their own pref. Picks the most delicate primitive the
     * platform offers so it doesn't feel like a buzz.
     */
    public void vibrateTick() {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // EFFECT_TICK is the OS-defined "scroll-tick"-style pulse: shorter and
                // weaker than EFFECT_CLICK, single pulse, no buzz on stock Android.
                mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Pre-Q: best we can do is a very short low-amplitude one-shot.
                mVibrator.vibrate(VibrationEffect.createOneShot(8L, 40));
                return;
            }
        } catch (final Exception ignore) {
            // Some OEM vibrators reject specific effects — fall through to legacy.
        }
        mVibrator.vibrate(8L);
    }

    public void vibrate(final long milliseconds) {
        if (mVibrator == null || milliseconds <= 0) {
            return;
        }
        mVibrator.vibrate(milliseconds);
    }

    /**
     * Plays a multi-pulse vibration pattern (off, on, off, on, …). Used for distinct voice cues
     * such as a "buzz-buzz" failure or a light confirming double-tap on success.
     */
    public void vibratePattern(final long[] timings) {
        if (mVibrator == null || timings == null || timings.length == 0) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mVibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
                return;
            }
        } catch (final Exception ignore) {
            // fall through to legacy pattern vibration
        }
        mVibrator.vibrate(timings, -1);
    }

    /**
     * Plays a one-off sample of the keypress haptic at the given intensity (1..100, or -1 for the
     * device default amplitude). Used by the settings slider so users can feel the strength as they
     * drag it. Bypasses the vibrate-on-keypress toggle, like the duration slider preview.
     */
    public void vibratePreview(final int intensityPercent) {
        vibrateForEvent(HapticEvent.KEY_PRESS, -1, intensityPercent);
    }

    /**
     * Builds the most refined vibration the device can produce for a given event, scaled by the
     * user's intensity preference. Preference of fidelity:
     *   1. Composed haptic primitives (Android 12+/R) — crisp, low-latency, OS-tuned. Used when no
     *      explicit duration is requested so we get the "premium click" feel.
     *   2. Amplitude-controlled one-shot (Android 8+) — honours an explicit duration and intensity.
     *   3. Legacy timed buzz — last resort on old/limited vibrators.
     */
    private void vibrateForEvent(final HapticEvent hapticEvent, final int durationPref, final int intensityPref) {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;
        if (intensityPref == 0) return;
        if (durationPref < 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && vibrateComposed(hapticEvent, intensityPref)) {
            return;
        }
        final long duration = durationPref >= 0 ? durationPref : defaultDurationMs(hapticEvent);
        if (duration <= 0) return;
        vibrateOneShot(duration, intensityPref);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private boolean vibrateComposed(final HapticEvent hapticEvent, final int intensityPref) {
        final int primitive = hapticEvent == HapticEvent.KEY_LONG_PRESS
                ? VibrationEffect.Composition.PRIMITIVE_CLICK
                : VibrationEffect.Composition.PRIMITIVE_TICK;
        try {
            if (!mVibrator.areAllPrimitivesSupported(primitive)) return false;
            final float scale = intensityPref >= 0
                    ? Math.min(intensityPref / 100f, 1f)
                    : 1f;
            mVibrator.vibrate(VibrationEffect.startComposition()
                    .addPrimitive(primitive, scale)
                    .compose());
            return true;
        } catch (final Exception ignore) {
            // Some OEM vibrators advertise primitives but reject composition — fall back.
            return false;
        }
    }

    private void vibrateOneShot(final long duration, final int intensityPref) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                final int amplitude = (intensityPref >= 0 && mVibrator.hasAmplitudeControl())
                        ? Math.min(Math.max(intensityPref * 255 / 100, 1), 255)
                        : VibrationEffect.DEFAULT_AMPLITUDE;
                mVibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                return;
            } catch (final Exception ignore) {
                // fall through to legacy timed vibration
            }
        }
        mVibrator.vibrate(duration);
    }

    private static long defaultDurationMs(final HapticEvent hapticEvent) {
        return hapticEvent == HapticEvent.KEY_LONG_PRESS ? 20L : 10L;
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null || mDoNotDisturb) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code, final HapticEvent hapticEvent) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        if (mAudioManager == null) {
            return;
        }
        if (!mSoundOn) {
            return;
        }
        if (hapticEvent != HapticEvent.KEY_PRESS) {
            return;
        }
        final int sound = switch (code) {
            case KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE;
            case Constants.CODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN;
            case Constants.CODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR;
            default -> AudioManager.FX_KEYPRESS_STANDARD;
        };
        mAudioManager.playSoundEffect(sound, mSettingsValues.mKeypressSoundVolume);
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn, final HapticEvent hapticEvent) {
        if (!mSettingsValues.mVibrateOn || (mDoNotDisturb && !mSettingsValues.mVibrateInDndMode)) {
            return;
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            // Avoid surprises with the handling of HapticFeedbackConstants.NO_HAPTICS
            return;
        }
        final int duration = mSettingsValues.mKeypressVibrationDuration;
        final int intensity = mSettingsValues.mKeypressVibrationIntensity;
        if (hapticEvent.allowCustomDuration && (duration >= 0 || intensity >= 0)) {
            // The user dialled in a duration and/or intensity: build a precise effect so the feel is
            // consistent across devices instead of deferring to each OEM's haptic constant.
            vibrateForEvent(hapticEvent, duration, intensity);
            return;
        }
        // Go ahead with the system default
        if (viewToPerformHapticFeedbackOn != null) {
            viewToPerformHapticFeedbackOn.performHapticFeedback(
                    hapticEvent.feedbackConstant,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged(boolean doNotDisturb) {
        mDoNotDisturb = doNotDisturb;
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
