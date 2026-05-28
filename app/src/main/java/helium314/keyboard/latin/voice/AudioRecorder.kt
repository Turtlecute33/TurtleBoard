// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records audio from the microphone directly to a WAV file on disk.
 *
 * Bytes are appended to the output file chunk-by-chunk while recording, so peak
 * heap usage stays bounded regardless of the recording length. A placeholder
 * WAV header is written up-front and rewritten in [stop] once the sample count
 * is known.
 *
 * Additionally exposes live telemetry during recording:
 *  - [currentAmplitude]: rolling mean absolute sample amplitude (0..32767)
 *  - [currentDurationMs]: elapsed duration since start()
 * and, post-stop: [lastDurationMs], [lastMeanAmplitude].
 */
class AudioRecorder(
    private val outputFile: File,
    private val maxDurationMs: Long = 90_000L,
    /** If >0, stop after this many contiguous ms of silence once the user has spoken at least once. */
    private val autoStopSilenceMs: Long = 0L,
) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Amplitude threshold (0..32767) separating silence from speech for auto-stop heuristic.
        private const val SPEECH_AMPLITUDE_THRESHOLD = 300.0
    }

    private var audioRecord: AudioRecord? = null
    private val recordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var pcmOutputFile: RandomAccessFile? = null
    @Volatile private var pcmBytesWritten: Long = 0L
    @Volatile private var amplitudeSum: Long = 0L
    @Volatile private var amplitudeCount: Long = 0L
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    @Volatile private var isRecording = false
    @Volatile private var cancelRequested = false
    @Volatile private var recordingStartMs: Long = 0L
    private var completion: CompletableDeferred<File?>? = null

    /** Duration of the last completed recording, in milliseconds. Updated by [stop]. */
    @Volatile var lastDurationMs: Long = 0L
        private set

    /** Mean absolute sample amplitude of the last completed recording (0..32767). Updated by [stop]. */
    @Volatile var lastMeanAmplitude: Double = 0.0
        private set

    /** Rolling mean amplitude of the most recent audio chunk, read-safe from any thread. */
    @Volatile var currentAmplitude: Double = 0.0
        private set

    /** Live elapsed time since start(), 0 when idle. */
    val currentDurationMs: Long
        get() = if (isRecording && recordingStartMs > 0) SystemClock.elapsedRealtime() - recordingStartMs else 0L

    var onMaxDurationReached: (() -> Unit)? = null
    var onAutoStopSilence: (() -> Unit)? = null

    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            attachAudioEffects(audioRecord!!.audioSessionId)

            outputFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(outputFile, "rw")
            raf.setLength(0L)
            // Write a 44-byte placeholder; finalizeOutputFile rewrites with accurate sizes.
            raf.write(ByteArray(WAV_HEADER_SIZE))
            pcmOutputFile = raf
            pcmBytesWritten = 0L
            amplitudeSum = 0L
            amplitudeCount = 0L
            currentAmplitude = 0.0
            cancelRequested = false
            isRecording = true
            recordingStartMs = SystemClock.elapsedRealtime()
            audioRecord?.startRecording()

            val deferred = CompletableDeferred<File?>()
            completion = deferred
            recordingJob = recordingScope.launch(CoroutineName("AudioRecorder")) {
                val file = try {
                    try {
                        runRecordingLoop(bufferSize)
                    } finally {
                        // Resource release and file finalization happen on the recording thread,
                        // so callers (including the IME main thread) never have to block waiting
                        // for the loop to drain.
                        cleanupAudioRecord()
                    }
                    finalizeOutputFile()
                } catch (_: CancellationException) {
                    cleanupRecordingFailure()
                    null
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording failed", t)
                    cleanupRecordingFailure()
                    null
                }
                deferred.complete(file)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            cleanupStartFailure()
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord construction failed", e)
            cleanupStartFailure()
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord failed to start", e)
            cleanupStartFailure()
            false
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to open recording output file", e)
            cleanupStartFailure()
            false
        }
    }

    private suspend fun runRecordingLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var hasSpoken = false
        var silenceRunStartMs = 0L
        while (isRecording && currentCoroutineContext().isActive) {
            if (SystemClock.elapsedRealtime() - recordingStartMs > maxDurationMs) {
                isRecording = false
                onMaxDurationReached?.invoke()
                break
            }
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: AudioRecord.ERROR_INVALID_OPERATION
            when {
                read > 0 -> {
                    try {
                        pcmOutputFile?.write(buffer, 0, read)
                        pcmBytesWritten += read
                    } catch (e: java.io.IOException) {
                        Log.e(TAG, "Failed to write PCM chunk", e)
                        isRecording = false
                        break
                    }
                    val amp = chunkMeanAmplitude(buffer, read)
                    currentAmplitude = amp
                    // Running mean for post-stop gate — avoids re-reading the whole file.
                    val samples = read / 2
                    amplitudeSum += (amp * samples).toLong()
                    amplitudeCount += samples
                    if (autoStopSilenceMs > 0L) {
                        val now = SystemClock.elapsedRealtime()
                        if (amp >= SPEECH_AMPLITUDE_THRESHOLD) {
                            hasSpoken = true
                            silenceRunStartMs = 0L
                        } else if (hasSpoken) {
                            if (silenceRunStartMs == 0L) silenceRunStartMs = now
                            else if (now - silenceRunStartMs >= autoStopSilenceMs) {
                                isRecording = false
                                onAutoStopSilence?.invoke()
                                break
                            }
                        }
                    }
                }
                read == 0 -> Unit
                read == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "AudioRecord dead object, recording aborted")
                    isRecording = false
                    break
                }
                else -> {
                    Log.e(TAG, "AudioRecord read error: $read")
                    isRecording = false
                }
            }
        }
    }

    /**
     * Requests a graceful stop and returns a [Deferred] that completes with the finalized WAV
     * file (or null if no usable audio was captured). Returns immediately — callers should
     * `await` the deferred from a background coroutine, never block the main thread on it.
     * Callers own the returned file and must delete it.
     */
    fun stop(): Deferred<File?> {
        isRecording = false
        // AudioRecord.stop() is documented as safe from any thread, but on some OEMs it can
        // block 150–500ms while the audio HAL drains. Always dispatch it off-thread so callers
        // (including the IME main thread) never risk an ANR. The recording loop's read() will
        // also exit on its own once isRecording=false on the next ~80ms iteration.
        dispatchAudioRecordStopAsync()
        return completion ?: CompletableDeferred<File?>().apply { complete(null) }
    }

    /**
     * Aborts the recording and discards the output file. Non-blocking; if the recording loop
     * is in flight, its `finally` block deletes the partial file once it observes the cancel.
     */
    fun cancel() {
        cancelRequested = true
        isRecording = false
        // Same rationale as stop(): never call AudioRecord.stop() on the caller's thread.
        dispatchAudioRecordStopAsync()
        if (recordingJob == null) {
            // Either start() failed before launching, or stop() already ran. Make sure any
            // partial file is gone and per-recording state is reset.
            closeOutputSafely()
            if (outputFile.exists()) outputFile.delete()
            resetCounters()
        }
    }

    private fun dispatchAudioRecordStopAsync() {
        val ar = audioRecord ?: return
        recordingScope.launch(CoroutineName("AudioRecorderStop")) {
            try { ar.stop() } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop() failed", e)
            }
        }
    }

    private fun cleanupAudioRecord() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        releaseAudioEffects()
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        recordingStartMs = 0L
    }

    private fun finalizeOutputFile(): File? {
        val pcmBytes = pcmBytesWritten
        lastDurationMs = if (pcmBytes >= 2) (pcmBytes * 1000L) / (SAMPLE_RATE.toLong() * 2L) else 0L
        lastMeanAmplitude = if (amplitudeCount > 0) amplitudeSum.toDouble() / amplitudeCount else 0.0
        currentAmplitude = 0.0
        val raf = pcmOutputFile
        pcmOutputFile = null
        recordingJob = null

        if (cancelRequested || raf == null || pcmBytes < 2) {
            try { raf?.close() } catch (_: Throwable) {}
            if (outputFile.exists()) outputFile.delete()
            if (cancelRequested) resetCounters()
            return null
        }
        return try {
            writeWavHeader(raf, pcmBytes.toInt())
            raf.close()
            outputFile
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to finalize WAV header", e)
            try { raf.close() } catch (_: Throwable) {}
            if (outputFile.exists()) outputFile.delete()
            null
        }
    }

    private fun resetCounters() {
        pcmBytesWritten = 0L
        amplitudeSum = 0L
        amplitudeCount = 0L
        lastDurationMs = 0L
        lastMeanAmplitude = 0.0
        currentAmplitude = 0.0
    }

    private fun cleanupStartFailure() {
        isRecording = false
        recordingStartMs = 0L
        closeOutputSafely()
        releaseAudioEffects()
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun cleanupRecordingFailure() {
        isRecording = false
        recordingStartMs = 0L
        recordingJob = null
        closeOutputSafely()
        releaseAudioEffects()
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        if (outputFile.exists()) outputFile.delete()
        resetCounters()
    }

    private fun closeOutputSafely() {
        try { pcmOutputFile?.close() } catch (_: Throwable) {}
        pcmOutputFile = null
    }

    private fun attachAudioEffects(sessionId: Int) {
        // Trade-off: VOICE_RECOGNITION on most Android devices already applies NS/AGC internally,
        // so attaching these effects is often redundant. We keep the attach code because some OEMs
        // do NOT apply NS/AGC on VOICE_RECOGNITION, and on those devices we still want the boost.
        // Free perceptual quality boost when the device supports it. Silent no-op otherwise.
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "NoiseSuppressor unavailable", e)
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "AGC unavailable", e)
        }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        noiseSuppressor = null
        agc = null
    }

    private fun chunkMeanAmplitude(buf: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sum = 0L
        var count = 0
        var i = 0
        val end = length - 1
        while (i < end) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val signed = ((hi shl 8) or lo).toShort().toInt()
            sum += if (signed < 0) -signed else signed
            count++
            i += 2
        }
        return if (count > 0) sum.toDouble() / count else 0.0
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmSize: Int) {
        val totalDataLen = pcmSize + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcmSize)
        }
        raf.seek(0L)
        raf.write(header.array())
    }
}

private const val WAV_HEADER_SIZE = 44
