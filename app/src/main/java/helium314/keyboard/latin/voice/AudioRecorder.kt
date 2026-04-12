// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import helium314.keyboard.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio from the microphone into an in-memory WAV byte array.
 * Uses AudioRecord for low-level control without writing to disk.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_DURATION_MS = 90_000L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmOutput = ByteArrayOutputStream()
    @Volatile private var isRecording = false

    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
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

            pcmOutput.reset()
            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread("AudioRecording") {
                val buffer = ByteArray(bufferSize)
                val startTime = System.currentTimeMillis()
                while (isRecording) {
                    if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                        isRecording = false
                        onMaxDurationReached?.invoke()
                        break
                    }
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        pcmOutput.write(buffer, 0, read)
                    }
                }
            }
            recordingThread?.start()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            false
        }
    }

    fun stop(): ByteArray {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = pcmOutput.toByteArray()
        pcmOutput.reset()
        return createWav(pcmData)
    }

    fun cancel() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        pcmOutput.reset()
    }

    var onMaxDurationReached: (() -> Unit)? = null

    private fun createWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
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
            putInt(pcmData.size)
        }

        return header.array() + pcmData
    }
}
