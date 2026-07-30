package com.rokid.music

import android.content.Context
import android.media.AudioManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import com.rokid.music.audio.AudioEngine
import com.rokid.music.model.Event
import com.rokid.music.model.Note
import com.rokid.music.model.TabScore
import com.rokid.music.render.TabRenderer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Glasses tablature viewer + playback controller.
 *
 * Interaction model (touch-pad only, Back as fallback):
 *
 *   Mode          | ◀▶ Swipe   | Click (ENTER)  | Long-press (TV)         | Double ENTER
 *   ──────────────┼────────────┼────────────────┼─────────────────────────┼─────────────
 *   BROWSING      | scroll     | —              | countdown → PLAYING      | exit
 *   COUNTDOWN      | —          | —              | —                       | exit (cancel)
 *   PLAYING       | volume     | pause           | countdown → restart     | exit
 *   PAUSED        | scroll     | resume          | countdown → restart     | exit
 *
 * Once PLAYING is entered, the playhead stays visible until exit.
 * Volume defaults to 70%; the TP adjusts it while playing.
 */
class PlayerView(
    context: Context,
    private val score: TabScore,
    private val onExit: () -> Unit
) : View(context) {

    // ── Modes ───────────────────────────────────────────────────────────────

    private enum class Mode { BROWSING, COUNTDOWN, PLAYING, PAUSED }
    private var mode = Mode.BROWSING

    // ── Rendering ───────────────────────────────────────────────────────────

    private val renderer = TabRenderer()
    private val audio = AudioEngine()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val initialSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    private var systemVolumeRestored = false
    private var layout: TabRenderer.LayoutResult? = null
    private val measureMap = score.measures.associateBy { it.id }

    // ── State ───────────────────────────────────────────────────────────────

    private var scrollY = 0f
    private var scrollTargetY = 0f
    private var playheadTick: Int? = null        // null = no playhead yet
    private var playStartTick = 0                // score tick when playback started
    private var playStartPlayTick = 0f           // flattened timeline tick when started
    private var playStartMs = 0L                 // monotonic clock when playback started
    private var hasPlayhead = false              // true once countdown completes
    private var volume = 0.0f                    // system STREAM_MUSIC ratio
    private var volumeShowUntil = 0L             // timestamp to hide volume bar

    private data class PlaybackSegment(
        val scoreStart: Int,
        val scoreEnd: Int,
        val playStart: Int,
        val playEnd: Int
    )

    private val playbackTimeline = buildPlaybackTimeline()

    // Double-click detection
    private var lastClickTime = 0L
    private var pendingClickAction: Runnable? = null
    private var lastDirectionTime = 0L

    // Countdown
    private var countdownStartMs = 0L

    // Scroll
    private var maxScroll = 0f

    // Handler
    private val handler = Handler(Looper.getMainLooper())
    private var playbackLoop: Runnable? = null
    private var countdownLoop: Runnable? = null
    private val playedEventIds = mutableSetOf<String>()

    // ── Paints ──────────────────────────────────────────────────────────────

    private val greenBright = 0xFF00FF44.toInt()
    private val greenDim = 0xFF008F2C.toInt()

    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; textSize = 120f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("monospace", Typeface.BOLD)
    }
    private val volumeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.FILL; alpha = 200
    }
    private val volumeFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenDim; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val volumeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; style = Paint.Style.FILL
    }
    private val volumeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; textSize = 13f; typeface = Typeface.MONOSPACE
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
    }
    private val headerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenDim; style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val headerHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; textSize = 10f; textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT
    }

    // ── Init ────────────────────────────────────────────────────────────────

    init {
        isFocusableInTouchMode = true
        isFocusable = true
        // Focus is still required for TP/DPAD events; only the framework's
        // default focus outline is unwanted on the glasses display.
        defaultFocusHighlightEnabled = false
        volume = systemVolumeRatio()
        // System STREAM_MUSIC is the authoritative volume on Rokid. Keep the
        // AudioTrack at unity gain so the OS mixer setting is not attenuated a
        // second time inside the app.
        audio.setVolume(1f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // Layout when size is known
        if (layout == null && w > 0) {
            layout = renderer.layout(score)
            maxScroll = maxOf(0f, (layout?.totalHeight ?: 0f) - h)
            Log.d("PlayerView", "Layout: ${layout?.systems?.size} systems, totalH=${layout?.totalHeight}, maxScroll=$maxScroll")
        }
    }

    // ── Draw ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val lay = layout ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // The title bar is outside the translated score canvas and therefore
        // remains fixed while the player scrolls or the playhead moves.
        drawHeader(canvas, w)

        // The title bar is a fixed HUD. Clip the translated score canvas so
        // scrolling can never paint over the title or its separator line.
        canvas.save()
        canvas.clipRect(0f, HEADER_BOTTOM, w, h)
        renderer.drawContent(canvas, lay, scrollY, playheadTick, score, measureMap)
        canvas.restore()

        // Volume bar (shown during volume adjustment)
        if (System.currentTimeMillis() < volumeShowUntil) {
            drawVolumeBar(canvas, w)
        }

        // Countdown HUD
        if (mode == Mode.COUNTDOWN) {
            drawCountdown(canvas, w, h)
        }
    }

    private fun drawHeader(canvas: Canvas, w: Float) {
        val title = score.metadata.title.trim()
        val artist = score.metadata.artist.trim()
        val label = when {
            title.isEmpty() -> artist
            artist.isEmpty() -> title
            else -> "$title — $artist"
        }
        val hint = when (mode) {
            Mode.BROWSING -> "◉ long-press to play  ◀▶ swipe"
            Mode.COUNTDOWN -> "◉ starting playback"
            Mode.PLAYING -> "● short-click pause  ◀▶ volume"
            Mode.PAUSED -> "◉ long-press play from top  ● short-click continue  ◀▶ swipe"
        }
        headerHintPaint.textSize = 10f
        headerPaint.textSize = 12f
        // Reserve the hint's real width so the longer paused-state guidance
        // cannot overlap the song title on the compact glasses display.
        val hintWidth = headerHintPaint.measureText(hint)
        val maxWidth = (w - 36f - hintWidth).coerceAtLeast(1f)
        var shown = label
        while (shown.length > 1 && headerPaint.measureText(shown) > maxWidth) {
            shown = shown.dropLast(1)
        }
        if (shown != label) shown = shown.dropLast(1) + "…"
        canvas.drawText(shown, 12f, 19f, headerPaint)
        canvas.drawText(hint, w - 12f, 19f, headerHintPaint)
        canvas.drawLine(12f, HEADER_BOTTOM, w - 12f, HEADER_BOTTOM, headerLinePaint)
    }

    private companion object {
        const val HEADER_BOTTOM = 34f
    }

    private fun drawVolumeBar(canvas: Canvas, w: Float) {
        val barW = 120f; val barH = 14f
        val barX = w - barW - 18f; val barY = 40f

        canvas.drawRect(barX - 2f, barY - 2f, barX + barW + 2f, barY + barH + 2f, volumeBgPaint)
        canvas.drawRect(barX - 2f, barY - 2f, barX + barW + 2f, barY + barH + 2f, volumeFramePaint)
        canvas.drawRect(barX, barY, barX + barW * volume, barY + barH, volumeFillPaint)

        volumeTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${(volume * 100).toInt()}%", barX + barW / 2f, barY + barH + 16f, volumeTextPaint)
    }

    private fun drawCountdown(canvas: Canvas, w: Float, h: Float) {
        val elapsed = System.currentTimeMillis() - countdownStartMs
        val second = (elapsed / 1000f).toInt() + 1  // 1, 2, 3
        val num = when {
            second <= 1 -> "3"
            second == 2 -> "2"
            else -> "1"
        }

        val alpha = ((elapsed % 1000f) / 1000f * 255).toInt().coerceIn(80, 255)
        countdownPaint.alpha = alpha
        canvas.drawText(num, w / 2f, h / 2f + 30f, countdownPaint)
        countdownPaint.alpha = 255
    }

    // ── Input ───────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Firmware TP-contact precursor. It accompanies ordinary taps and
        // swipes, so consuming it prevents false playback starts.
        if (keyCode == 83) return true

        if (keyCode == KeyEvent.KEYCODE_TV) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onTpLongPress()
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        Log.d("PlayerView", "key: $keyCode mode=$mode")

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                handleClick()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDuplicateDirection()) return true
                handleSwipeLeft()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDuplicateDirection()) return true
                handleSwipeRight()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDuplicateDirection()) return true
                handleSwipeUp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDuplicateDirection()) return true
                handleSwipeDown()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                exit()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun onTpLongPress() {
        handleLongPress()
    }

    /** Fast TP swipes arrive as RIGHT+DOWN or LEFT+UP on this firmware. */
    private fun isDuplicateDirection(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastDirectionTime < 280L) return true
        lastDirectionTime = now
        return false
    }

    private fun handleClick() {
        val now = System.currentTimeMillis()

        when (mode) {
            Mode.BROWSING -> {
                // Click ignored in browsing mode (only long-press starts playback)
                // But check for double-click exit
                if (now - lastClickTime < 400) {
                    pendingClickAction?.let { handler.removeCallbacks(it) }
                    exit()
                    return
                }
                lastClickTime = now
                // No single-click action in browsing mode
            }
            Mode.COUNTDOWN -> {
                // Cancel countdown
                cancelCountdown()
            }
            Mode.PLAYING -> {
                // Check double-click first
                if (now - lastClickTime < 400) {
                    pendingClickAction?.let { handler.removeCallbacks(it) }
                    exit()
                    return
                }
                lastClickTime = now

                // Schedule pause with delay to distinguish from double-click
                pendingClickAction?.let { handler.removeCallbacks(it) }
                pendingClickAction = Runnable {
                    if (mode == Mode.PLAYING && System.currentTimeMillis() - lastClickTime >= 400) {
                        pause()
                    }
                }
                handler.postDelayed(pendingClickAction!!, 420)
            }
            Mode.PAUSED -> {
                // Check double-click first
                if (now - lastClickTime < 400) {
                    pendingClickAction?.let { handler.removeCallbacks(it) }
                    exit()
                    return
                }
                lastClickTime = now

                // Schedule resume with delay to distinguish from double-click
                pendingClickAction?.let { handler.removeCallbacks(it) }
                pendingClickAction = Runnable {
                    if (mode == Mode.PAUSED && System.currentTimeMillis() - lastClickTime >= 400) {
                        resume()
                    }
                }
                handler.postDelayed(pendingClickAction!!, 420)
            }
        }
    }

    private fun handleLongPress() {
        // Reset last click time to prevent accidental double-click detection
        lastClickTime = 0L

        when (mode) {
            Mode.BROWSING -> startCountdown()
            Mode.COUNTDOWN -> { /* ignore during countdown */ }
            Mode.PLAYING -> {
                stopPlayback()
                startCountdown()
            }
            Mode.PAUSED -> {
                startCountdown()
            }
        }
    }

    private fun handleSwipeLeft() {
        when (mode) {
            Mode.BROWSING, Mode.PAUSED -> scrollBy(1)
            Mode.PLAYING -> adjustVolume(-0.05f)
            Mode.COUNTDOWN -> { /* ignore */ }
        }
    }

    private fun handleSwipeRight() {
        when (mode) {
            Mode.BROWSING, Mode.PAUSED -> scrollBy(-1)
            Mode.PLAYING -> adjustVolume(+0.05f)
            Mode.COUNTDOWN -> { /* ignore */ }
        }
    }

    private fun handleSwipeUp() {
        when (mode) {
            Mode.BROWSING, Mode.PAUSED -> scrollBy(2)  // faster scroll
            Mode.PLAYING -> adjustVolume(+0.05f)
            Mode.COUNTDOWN -> { /* ignore */ }
        }
    }

    private fun handleSwipeDown() {
        when (mode) {
            Mode.BROWSING, Mode.PAUSED -> scrollBy(-2)
            Mode.PLAYING -> adjustVolume(-0.05f)
            Mode.COUNTDOWN -> { /* ignore */ }
        }
    }

    // ── Scroll ──────────────────────────────────────────────────────────────

    private fun scrollBy(steps: Int) {
        val stepSize = height * 0.33f
        scrollTargetY = (scrollTargetY - steps * stepSize).coerceIn(0f, maxScroll)
        scrollY = scrollTargetY
        invalidate()
    }

    // ── Volume ──────────────────────────────────────────────────────────────

    private fun adjustVolume(delta: Float) {
        val direction = if (delta >= 0f) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        volume = systemVolumeRatio()
        audio.setVolume(1f)
        volumeShowUntil = System.currentTimeMillis() + 2000
        Log.d("PlayerView", "volume: ${(volume * 100).toInt()}%")
        invalidate()
    }

    private fun systemVolumeRatio(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    // ── Countdown ───────────────────────────────────────────────────────────

    private fun startCountdown() {
        mode = Mode.COUNTDOWN
        countdownStartMs = System.currentTimeMillis()
        Log.d("PlayerView", "countdown started")

        // Countdown loop
        countdownLoop?.let { handler.removeCallbacks(it) }
        val startTime = countdownStartMs
        countdownLoop = object : Runnable {
            override fun run() {
                if (mode != Mode.COUNTDOWN) return
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= 3000) {
                    // Countdown complete → start playback
                    beginPlayback()
                } else {
                    invalidate()
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.postDelayed(countdownLoop!!, 50)
    }

    private fun cancelCountdown() {
        countdownLoop?.let { handler.removeCallbacks(it) }
        countdownLoop = null
        mode = if (hasPlayhead) Mode.PAUSED else Mode.BROWSING
        Log.d("PlayerView", "countdown cancelled")
        invalidate()
    }

    // ── Playback Control ────────────────────────────────────────────────────

    private fun beginPlayback() {
        mode = Mode.PLAYING
        hasPlayhead = true
        playStartMs = SystemClock.uptimeMillis()

        // Start from the topmost visible measure
        val topMeasure = findTopVisibleMeasure()
        playStartTick = topMeasure?.startTick ?: 0
        playheadTick = playStartTick
        playStartPlayTick = scoreTickToPlayTick(playStartTick)
        resetPlayedEvents(playStartTick)
        audio.start()

        Log.d("PlayerView", "playback started at tick=$playStartTick, measure=${topMeasure?.number}")

        startPlaybackLoop()
        invalidate()
    }

    private fun pause() {
        mode = Mode.PAUSED
        stopPlaybackLoop()
        audio.pause()
        Log.d("PlayerView", "paused at tick=$playheadTick")
        invalidate()
    }

    private fun resume() {
        mode = Mode.PLAYING
        playStartMs = SystemClock.uptimeMillis()
        playStartTick = playheadTick ?: 0
        playStartPlayTick = scoreTickToPlayTick(playStartTick)
        audio.start()
        Log.d("PlayerView", "resumed at tick=$playStartTick")
        startPlaybackLoop()
        invalidate()
    }

    private fun stopPlayback() {
        stopPlaybackLoop()
        audio.clearVoices()
        // keep playhead visible, don't clear hasPlayhead
        Log.d("PlayerView", "playback stopped at tick=$playheadTick")
    }

    // ── Playback Loop ───────────────────────────────────────────────────────

    private fun startPlaybackLoop() {
        stopPlaybackLoop()
        playbackLoop = object : Runnable {
            override fun run() {
                if (mode != Mode.PLAYING) return

                val elapsedMs = SystemClock.uptimeMillis() - playStartMs
                val bpm = score.defaults.tempo.bpm
                val ppq = score.defaults.ppq
                // ticks per millisecond = bpm * ppq / 60000
                val tickRate = bpm.toFloat() * ppq / 60000f
                val currentPlayTick = playStartPlayTick + elapsedMs * tickRate
                playheadTick = playTickToScoreTick(currentPlayTick)
                triggerDueNotes(playheadTick ?: 0)

                // Check end of score
                val playEnd = playbackTimeline.lastOrNull()?.playEnd?.toFloat() ?: 0f
                if (currentPlayTick >= playEnd) {
                    playheadTick = playbackTimeline.lastOrNull()?.scoreEnd ?: playheadTick
                    pause()
                    return
                }

                // Auto-scroll: keep playhead in upper-mid viewport
                autoScroll()

                invalidate()
                handler.postDelayed(this, 16)
            }
        }
        handler.postDelayed(playbackLoop!!, 16)
    }

    private fun stopPlaybackLoop() {
        playbackLoop?.let { handler.removeCallbacks(it) }
        playbackLoop = null
    }

    private fun buildPlaybackTimeline(): List<PlaybackSegment> {
        var playCursor = 0
        return buildList {
            score.measures.forEach { measure ->
                val duration = measure.durationTicks.coerceAtLeast(0)
                if (measure.events.isEmpty()) {
                    add(PlaybackSegment(
                        measure.startTick,
                        measure.startTick + duration,
                        playCursor,
                        playCursor + duration
                    ))
                    playCursor += duration
                    return@forEach
                }

                val firstTick = measure.events.minOf { it.tick }
                val lastEnd = measure.events.fold(firstTick) { end, event ->
                    if (event.notes.isNotEmpty() && event.notes.all { it.status == "mute" }) end
                    else if (event.notes.any { it.status == "ring" }) maxOf(end, duration)
                    else maxOf(end, event.tick + eventDurationTicks(event.duration))
                }.coerceAtMost(duration)
                if (lastEnd <= firstTick) return@forEach

                val segmentDuration = lastEnd - firstTick
                add(PlaybackSegment(
                    measure.startTick + firstTick,
                    measure.startTick + lastEnd,
                    playCursor,
                    playCursor + segmentDuration
                ))
                playCursor += segmentDuration
            }
        }
    }

    private fun eventDurationTicks(duration: com.rokid.music.model.Duration): Int {
        val ppq = score.defaults.ppq.coerceAtLeast(1)
        var ticks = ppq * 4.0 / duration.base.coerceAtLeast(1)
        repeat(duration.dots) { dot -> ticks += ppq * 4.0 / duration.base / (2 shl dot) }
        duration.tuplet?.let { ticks = ticks * it.normal / it.actual.coerceAtLeast(1) }
        return ticks.roundToInt()
    }

    private fun resetPlayedEvents(startTick: Int) {
        playedEventIds.clear()
        score.measures.forEach { measure ->
            measure.events.forEach { event ->
                if (measure.startTick + event.tick < startTick) {
                    playedEventIds += eventKey(measure.id, event)
                }
            }
        }
    }

    private fun triggerDueNotes(currentTick: Int) {
        val bpm = score.defaults.tempo.bpm.coerceAtLeast(1)
        val ppq = score.defaults.ppq.coerceAtLeast(1)
        val allNotes = score.measures.flatMap { measure ->
            measure.events.flatMap { it.notes }
        }.associateBy { it.id }
        score.measures.forEach { measure ->
            measure.events.forEach eventLoop@{ event ->
                if (event.type != "note") return@eventLoop
                val globalTick = measure.startTick + event.tick
                if (globalTick > currentTick || !playedEventIds.add(eventKey(measure.id, event))) return@eventLoop
                event.notes.forEach noteLoop@{ note ->
                    if (note.status == "ghost" || note.status == "tied") return@noteLoop
                    val bend = measure.spanners.firstOrNull {
                        (it.type == "bend" || it.type == "bend-vibrato") && it.from == note.id
                    }
                    val slide = measure.spanners.firstOrNull {
                        it.type == "slide" && (it.from == note.id || it.to == note.id)
                    }
                    val vibrato = measure.spanners.firstOrNull {
                        (it.type == "vibrato" || it.type == "bend-vibrato") && it.from == note.id
                    }
                    val ticks = if (note.status == "ring") {
                        (measure.durationTicks - event.tick).coerceAtLeast(eventDurationTicks(event.duration))
                    } else eventDurationTicks(event.duration)
                    val targetFrequency = slide?.to?.let { allNotes[it] }?.let(::noteFrequency)
                    val slideInFrequency = if (slide?.to == note.id && slide.fromFret != null) {
                        noteFrequency(note, slide.fromFret)
                    } else null
                    audio.play(
                        AudioEngine.PlayNote(
                            frequency = noteFrequency(note),
                            durationSeconds = ticks * 60.0 / (bpm * ppq),
                            harmonic = note.effects.any { it.type == "harmonic" },
                            bend = bend?.curve?.map { AudioEngine.BendPoint(it.at, it.alter) } ?: emptyList(),
                            slideTargetFrequency = targetFrequency,
                            slideInFrequency = slideInFrequency,
                            vibratoWidth = vibrato?.width,
                            muted = note.status == "mute" || event.notes.all { it.status == "mute" }
                        )
                    )
                }
            }
        }
    }

    private fun eventKey(measureId: String, event: Event): String = "$measureId:${event.id}"

    private fun noteFrequency(note: Note, fret: Int = note.fret): Double {
        val tuning = score.defaults.tuning.strings.firstOrNull { it.number == note.string }?.pitch
            ?: listOf("E4", "B3", "G3", "D3", "A2", "E2").getOrElse(note.string - 1) { "E4" }
        val midi = pitchToMidi(tuning) + fret + score.defaults.tuning.capo
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    private fun pitchToMidi(pitch: String): Int {
        val match = Regex("^([A-G])([#b]?)(-?\\d+)$").find(pitch) ?: return 64
        val semitones = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        val accidental = when (match.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
        return (match.groupValues[3].toInt() + 1) * 12 +
            (semitones[match.groupValues[1][0]] ?: 0) + accidental
    }

    private fun scoreTickToPlayTick(scoreTick: Int): Float {
        playbackTimeline.forEach { segment ->
            if (scoreTick in segment.scoreStart..segment.scoreEnd) {
                return segment.playStart + (scoreTick - segment.scoreStart).toFloat()
            }
        }
        val next = playbackTimeline.firstOrNull { it.scoreStart > scoreTick }
        return (next?.playStart ?: playbackTimeline.lastOrNull()?.playEnd ?: 0).toFloat()
    }

    private fun playTickToScoreTick(playTick: Float): Int {
        if (playbackTimeline.isEmpty()) return playTick.roundToInt()
        playbackTimeline.forEach { segment ->
            if (playTick <= segment.playEnd) {
                return segment.scoreStart + maxOf(0f, playTick - segment.playStart).roundToInt()
            }
        }
        return playbackTimeline.last().scoreEnd
    }

    // ── Auto-scroll ─────────────────────────────────────────────────────────

    private fun autoScroll() {
        val lay = layout ?: return
        val tick = playheadTick ?: return
        val phY = renderer.tickToSystemY(tick, lay, score)

        val viewTop = scrollY
        val viewBot = scrollY + height
        if (phY > viewBot - height * 0.3f || phY < viewTop + height * 0.15f) {
            scrollTargetY = (phY - height * 0.35f).coerceIn(0f, maxScroll)
        }
        // Follow the target with easing instead of jumping at each system.
        val delta = scrollTargetY - scrollY
        if (kotlin.math.abs(delta) > 0.1f) {
            scrollY += delta * 0.14f
        }
    }

    private fun findTopVisibleMeasure(): com.rokid.music.model.Measure? {
        val lay = layout ?: return score.measures.firstOrNull()
        val viewTop = scrollY

        for (measure in score.measures) {
            val ml = lay.measureLayouts[measure.id] ?: continue
            val sysY = renderer.stringY(1, ml.systemIndex) - TabRenderer.TOP
            if (sysY >= viewTop) return measure
        }
        return score.measures.firstOrNull()
    }

    // ── Exit ────────────────────────────────────────────────────────────────

    private fun exit() {
        stopPlaybackLoop()
        countdownLoop?.let { handler.removeCallbacks(it) }
        pendingClickAction?.let { handler.removeCallbacks(it) }
        Log.d("PlayerView", "exit")
        onExit()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun release() {
        stopPlaybackLoop()
        countdownLoop?.let { handler.removeCallbacks(it) }
        pendingClickAction?.let { handler.removeCallbacks(it) }
        audio.release()
        restoreSystemVolume()
    }

    private fun restoreSystemVolume() {
        if (systemVolumeRestored) return
        systemVolumeRestored = true
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            initialSystemVolume.coerceIn(0, max),
            0
        )
    }
}
