package com.rokid.terminal

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Records short utterances as 16 kHz mono signed-16-bit PCM WAV — the exact
 * format the ASR server accepts.
 *
 * The Rokid firmware's `MIC` source was verified in RokidLocalAsr to deliver
 * non-zero PCM; `VOICE_RECOGNITION` may be muted by the system AudioPolicy, so
 * `MIC` is the primary source. Keep it a manual start/stop so the caller can
 * bound the recording window.
 *
 * A dedicated capture thread reads AudioRecord continuously while recording:
 * AudioRecord's ring buffer is only a few hundred ms, so without a reader the
 * oldest samples are overwritten and stop() would capture just a fragment
 * (measured 80 ms of a multi-second utterance). Reads also keep the HAL
 * buffer from ever filling, so stop() never blocks on a full drain.
 */
class AudioRecorder {
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var capturing = false
    private var pcm = ByteArrayOutputStream()

    val isRecording: Boolean
        get() = recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /**
     * Start capturing. Must be paired with [stop] (or [cancel]); not calling
     * stop leaks the AudioRecord.
     */
    fun start(): Boolean {
        if (isRecording) return true
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        record.startRecording()
        recorder = record
        pcm = ByteArrayOutputStream()
        capturing = true
        captureThread = Thread {
            val buffer = ByteArray(4096)
            while (capturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcm) { pcm.write(buffer, 0, read) }
                }
            }
        }.apply {
            isDaemon = true
            name = "asr-capture"
            start()
        }
        return true
    }

    /**
     * Stop recording and return the full WAV bytes (RIFF header + PCM data).
     * Returns an empty array if nothing was captured.
     */
    fun stop(): ByteArray {
        val record = recorder ?: return ByteArray(0)
        recorder = null
        // Stop the reader first: the capture loop exits on the flag, and the
        // final read() returns whatever the HAL still had buffered. Bounded
        // join so a wedged HAL cannot block the caller indefinitely.
        capturing = false
        runCatching { record.stop() }
        captureThread?.join(CAPTURE_JOIN_MS)
        captureThread = null
        runCatching { record.release() }
        synchronized(pcm) {
            val total = pcm.size()
            if (total == 0) return ByteArray(0)
            return wrapWav(pcm.toByteArray())
        }
    }

    /** Discard the current capture without producing WAV bytes. */
    fun cancel() {
        val record = recorder
        recorder = null
        capturing = false
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        captureThread?.join(CAPTURE_JOIN_MS)
        captureThread = null
        pcm = ByteArrayOutputStream()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CAPTURE_JOIN_MS = 2_000L
    }
}

/** Write a 16-bit value little-endian (DataOutputStream.writeShort is big-endian). */
private fun DataOutputStream.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}

/** Wrap raw PCM into a 16 kHz mono signed-16-bit WAV (RIFF header + data). */
internal fun wrapWav(pcm: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    val data = DataOutputStream(out)
    val dataSize = pcm.size
    val byteRate = AudioRecorder.SAMPLE_RATE * 2
    data.writeBytes("RIFF")
    data.writeInt(Integer.reverseBytes(36 + dataSize)) // file size - 8
    data.writeBytes("WAVE")
    data.writeBytes("fmt ")
    data.writeInt(Integer.reverseBytes(16))
    data.writeShortLe(1) // PCM
    data.writeShortLe(1) // mono
    data.writeInt(Integer.reverseBytes(AudioRecorder.SAMPLE_RATE))
    data.writeInt(Integer.reverseBytes(byteRate))
    data.writeShortLe(2) // block align
    data.writeShortLe(16) // bits per sample
    data.writeBytes("data")
    data.writeInt(Integer.reverseBytes(dataSize))
    data.write(pcm)
    data.flush()
    return out.toByteArray()
}

/**
 * Generate a short silent WAV in the server-accepted format — used to warm the
 * server ASR model so the first real transcription is fast.
 */
internal fun silentWav(durationSeconds: Double = 0.5): ByteArray {
    val pcm = ByteArray((AudioRecorder.SAMPLE_RATE * durationSeconds).toInt() * 2)
    return wrapWav(pcm)
}
