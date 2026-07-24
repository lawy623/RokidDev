package com.rokid.music.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Small mono PCM guitar-like synthesizer for the glasses.
 *
 * The web player uses several oscillators and a WebAudio effect chain.  On the
 * glasses we keep the same musical semantics, but mix voices in one dedicated
 * AudioTrack thread so overlapping tab notes cannot race on the audio device.
 */
class AudioEngine {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_FRAMES = 128
        private const val TWO_PI = PI * 2.0
    }

    data class BendPoint(val at: Double, val alter: Double)

    data class PlayNote(
        val frequency: Double,
        val durationSeconds: Double,
        val harmonic: Boolean = false,
        val bend: List<BendPoint> = emptyList(),
        val slideTargetFrequency: Double? = null,
        val slideInFrequency: Double? = null,
        val vibratoWidth: String? = null,
        val muted: Boolean = false
    )

    private val commands = ConcurrentLinkedQueue<PlayNote>()
    private val lock = Any()
    private val voices = mutableListOf<Voice>()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var outputVolume = 0.70f

    fun setVolume(value: Float) {
        outputVolume = value.coerceIn(0f, 1f)
        track?.setVolume(outputVolume)
    }

    @Synchronized
    fun start() {
        if (running) {
            paused = false
            track?.play()
            return
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_FRAMES * 2 * 4)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(outputVolume)
                it.play()
            }
        running = true
        paused = false
        worker = Thread(::mixLoop, "RokidMusic-Audio").apply {
            isDaemon = true
            start()
        }
    }

    fun pause() {
        clearVoices()
        paused = true
        track?.pause()
        try { track?.flush() } catch (_: Exception) {}
    }

    fun play(note: PlayNote) {
        if (!running) start()
        commands.offer(note)
    }

    fun clearVoices() {
        commands.clear()
        synchronized(lock) { voices.clear() }
    }

    @Synchronized
    fun release() {
        running = false
        paused = true
        commands.clear()
        synchronized(lock) { voices.clear() }
        worker?.interrupt()
        worker = null
        track?.let {
            try { it.pause() } catch (_: Exception) {}
            try { it.flush() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    private fun mixLoop() {
        val buffer = ShortArray(CHUNK_FRAMES)
        while (running && !Thread.currentThread().isInterrupted) {
            if (paused) {
                try { Thread.sleep(4) } catch (_: InterruptedException) { break }
                continue
            }
            while (true) {
                val note = commands.poll() ?: break
                synchronized(lock) { voices += Voice(note) }
            }
            // Lock once per short block instead of once per sample. The old
            // pattern caused mixer jitter and AudioTrack underruns (clicks).
            synchronized(lock) {
                repeat(CHUNK_FRAMES) { index ->
                    var sample = 0.0
                    val iterator = voices.iterator()
                    while (iterator.hasNext()) {
                        val voice = iterator.next()
                        sample += voice.sample()
                        if (voice.finished) iterator.remove()
                    }
                    // The glasses speaker path has generous headroom but the
                    // synthesized guitar voice is intentionally conservative.
                    buffer[index] = ((sample * 1.55).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                }
            }
            try {
                track?.write(buffer, 0, buffer.size)
            } catch (_: Exception) {
                if (running) Thread.yield()
            }
        }
    }

    private class Voice(private val note: PlayNote) {
        private var frame = 0L
        private var phase = 0.0
        private var noiseState = 0x1234567
        val finished: Boolean get() = frame >= totalFrames
        private val totalFrames = max(1, (note.durationSeconds.coerceIn(.06, 3.2) * SAMPLE_RATE).toLong())

        fun sample(): Double {
            if (finished) return 0.0
            val progress = (frame.toDouble() / totalFrames).coerceIn(0.0, 1.0)
            val frequency = frequencyAt(progress)
            phase += TWO_PI * frequency / SAMPLE_RATE
            if (phase > TWO_PI) phase -= TWO_PI

            // Two harmonics plus a short pick transient gives a useful guitar
            // cue without the CPU cost of a full convolution/effect graph.
            val body = sin(phase) * .72 + sin(phase * 2.0) * .20 + sin(phase * 3.0) * .08
            val pick = if (frame < SAMPLE_RATE * .035) nextNoise() * (1.0 - frame / (SAMPLE_RATE * .035)) * .14 else 0.0
            val attack = min(1.0, frame.toDouble() / (SAMPLE_RATE * .008))
            val decay = exp(-3.0 * progress)
            val muteEnvelope = if (note.muted) exp(-30.0 * progress) else 1.0
            frame++
            return (body * decay * attack + pick) * .24 * muteEnvelope
        }

        private fun frequencyAt(progress: Double): Double {
            var base = note.frequency * if (note.harmonic) 2.0 else 1.0
            note.slideInFrequency?.let { start ->
                base = start + (base - start) * smooth(progress / .3)
            }
            note.slideTargetFrequency?.let { target ->
                base += (target - base) * smooth(progress / .65)
            }
            if (note.bend.isNotEmpty()) {
                val points = note.bend.sortedBy { it.at }
                val alter = interpolate(points, progress)
                base *= 2.0.pow(alter / 12.0)
            }
            val depth = when (note.vibratoWidth) {
                "wide" -> .010
                "narrow" -> .003
                else -> if (note.vibratoWidth != null) .007 else 0.0
            }
            if (depth > 0.0 && progress > .25) {
                base *= 1.0 + sin(progress * 34.0 * PI) * depth
            }
            return base.coerceIn(20.0, 12_000.0)
        }

        private fun nextNoise(): Double {
            noiseState = noiseState * 1664525 + 1013904223
            return ((noiseState ushr 8) and 0xFFFF) / 32768.0 - 1.0
        }

        private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

        private fun interpolate(points: List<BendPoint>, at: Double): Double {
            if (at <= points.first().at) return points.first().alter
            for (index in 1 until points.size) {
                val left = points[index - 1]
                val right = points[index]
                if (at <= right.at) {
                    val span = (right.at - left.at).coerceAtLeast(.0001)
                    return left.alter + (right.alter - left.alter) * ((at - left.at) / span)
                }
            }
            return points.last().alter
        }
    }
}

private fun Double.pow(power: Double): Double = kotlin.math.exp(kotlin.math.ln(this) * power)
