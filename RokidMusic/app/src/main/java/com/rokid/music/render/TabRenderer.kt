package com.rokid.music.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import com.rokid.music.model.Duration
import com.rokid.music.model.Event
import com.rokid.music.model.Measure
import com.rokid.music.model.Note
import com.rokid.music.model.Spanner
import com.rokid.music.model.TabScore
import com.rokid.music.model.TimeSig
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Timing-first TAB renderer for the 480x640 Rokid green display.
 *
 * The glasses deliberately use one measure per row: at 480 px this is the
 * smallest layout that keeps 16th-note passages, technique marks and fret
 * numbers readable. Every visual anchor is indexed once from the same
 * tick-to-x function, then rhythm and techniques are drawn as overlays.
 */
class TabRenderer {

    companion object {
        const val VIEWPORT_W = 480f
        const val GHOST_TOP = 64f
        const val BEAT_WIDTH = 78f
        // TAB/time-signature occupy the compact left gutter; keep the first
        // timed note close to the barline instead of reserving a wide blank.
        const val CLEF_RESERVE = 36f
        const val TIME_SIG_RESERVE = 42f
        const val MEASURE_START_PAD = 14f
        const val MEASURE_END_PAD = 12f
        const val LEFT = 12f
        const val RIGHT = 12f
        const val TOP = 48f
        const val STRING_GAP = 11f
        const val BOTTOM = 28f
        const val SYSTEM_PAD = 8f

        const val GREEN = 0xFF00FF44.toInt()
        const val GREEN_DIM = 0xFF008F2C.toInt()

        val SYSTEM_H = TOP + STRING_GAP * 5 + BOTTOM
        val SYSTEM_TOTAL = SYSTEM_H + SYSTEM_PAD
    }

    data class NotePos(
        val note: Note,
        val event: Event,
        val x: Float,
        val y: Float,
        val measureId: String,
        val systemIndex: Int,
        val contentStart: Float,
        val contentEnd: Float
    )

    data class EventPos(
        val event: Event,
        val x: Float,
        val measureId: String,
        val systemIndex: Int,
        val contentStart: Float,
        val contentEnd: Float
    )

    data class MeasureLayout(
        val measureId: String,
        val systemIndex: Int,
        val x0: Float,
        val x1: Float,
        val width: Float,
        val firstInSystem: Boolean,
        val showTimeSig: Boolean,
        val contentStart: Float,
        val contentEnd: Float
    )

    data class SystemLayout(val y: Float, val measureIds: List<String>)

    data class LayoutResult(
        val systems: List<SystemLayout>,
        val measureLayouts: Map<String, MeasureLayout>,
        val notePositions: Map<String, NotePos>,
        val eventPositions: Map<String, EventPos>,
        val totalHeight: Float
    )

    private var ppq = 960
    private var defaultTimeSig = TimeSig(4, 4)
    private val measureBeatWidths = mutableMapOf<String, Float>()

    private val staffPaint = stroke(GREEN_DIM, 1f)
    private val linePaint = stroke(GREEN, 1.35f)
    private val thinPaint = stroke(GREEN, 1f)
    private val beamPaint = stroke(GREEN, 3f)
    private val fillPaint = fill(GREEN)
    private val blackPaint = fill(Color.BLACK)
    private val playheadPaint = stroke(GREEN, 2f)
    private val numberPaint = text(9.5f, true)
    private val smallPaint = text(7.5f, true)
    private val labelPaint = text(7.5f, true)
    private val italicPaint = text(7.5f, false, italic = true)
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN; textSize = 22f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val timeSigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN; textSize = 20f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    }

    fun layout(score: TabScore): LayoutResult {
        ppq = score.defaults.ppq.coerceAtLeast(1)
        defaultTimeSig = score.defaults.timeSignature ?: TimeSig(4, 4)
        measureBeatWidths.clear()

        for (measure in score.measures) {
            measureBeatWidths[measure.id] = if (measure.wide) BEAT_WIDTH * 1.35f else BEAT_WIDTH
        }

        // One measure per row is intentional for the 480 px optical display.
        val systems = score.measures.mapIndexed { index, measure ->
            SystemLayout(GHOST_TOP + index * SYSTEM_TOTAL, listOf(measure.id))
        }
        val measureLayouts = linkedMapOf<String, MeasureLayout>()
        val notes = linkedMapOf<String, NotePos>()
        val events = linkedMapOf<String, EventPos>()

        score.measures.forEachIndexed { systemIndex, measure ->
            val x0 = LEFT
            val baseX1 = VIEWPORT_W - RIGHT
            measure.showTimeSig = true
            val contentStart = x0 + measureContentInset(true, true)
            val baseSlot = baseX1 - x0 - measureContentInset(true, true) - MEASURE_END_PAD
            val standardTicks = (ppq * 4).coerceAtLeast(1)
            val durationRatio = (measure.durationTicks.toFloat() / standardTicks).coerceIn(.5f, 1f)
            val x1 = x0 + measureContentInset(true, true) + baseSlot * durationRatio + MEASURE_END_PAD
            val contentEnd = x1 - MEASURE_END_PAD
            val ml = MeasureLayout(
                measure.id, systemIndex, x0, x1, x1 - x0,
                firstInSystem = true, showTimeSig = true,
                contentStart = contentStart, contentEnd = contentEnd
            )
            measureLayouts[measure.id] = ml

            measure.events.forEach { event ->
                val x = tickToMeasureX(measure, x0, x1, true, event.tick)
                events[event.id] = EventPos(event, x, measure.id, systemIndex, contentStart, contentEnd)
                if (event.type == "note") {
                    event.notes.forEach { note ->
                        notes[note.id] = NotePos(
                            note, event, x, stringY(note.string, systemIndex), measure.id,
                            systemIndex, contentStart, contentEnd
                        )
                    }
                }
            }
        }

        val totalHeight = GHOST_TOP + systems.size * SYSTEM_TOTAL + 12f
        return LayoutResult(systems, measureLayouts, notes, events, totalHeight)
    }

    fun tickToMeasureX(
        measure: Measure,
        x0: Float,
        x1: Float,
        firstInSystem: Boolean,
        tick: Int
    ): Float {
        val inset = measureContentInset(firstInSystem, measure.showTimeSig)
        val slotWidth = max(1f, x1 - x0 - inset - MEASURE_END_PAD)
        val fullTicks = measure.durationTicks.coerceAtLeast(1)
        val tickWidth = (measureBeatWidths[measure.id] ?: BEAT_WIDTH) / ppq
        val timedWidth = fullTicks * tickWidth
        val start = x0 + inset + max(0f, slotWidth - timedWidth) / 2f

        val range = activeTickRange(measure)
        val unusedTicks = max(0, fullTicks - (range.second - range.first))
        val balancedEmpty = unusedTicks * tickWidth / 2f
        val local = tick.coerceIn(0, fullTicks)
        return start + balancedEmpty + (local - range.first) * tickWidth
    }

    private fun activeTickRange(measure: Measure): Pair<Int, Int> {
        if (measure.events.isEmpty()) return 0 to measure.durationTicks
        val start = measure.events.minOf { it.tick }
        val end = measure.events.maxOf { event ->
            if (event.notes.any { it.status == "ring" }) measure.durationTicks
            else event.tick + durationTicks(event.duration)
        }
        return start to end.coerceAtMost(measure.durationTicks)
    }

    private fun measureContentInset(first: Boolean, showTimeSig: Boolean): Float {
        return (if (first) CLEF_RESERVE else 0f) +
            (if (!first && showTimeSig) TIME_SIG_RESERVE else 0f) + MEASURE_START_PAD
    }

    private fun durationTicks(duration: Duration): Int {
        var ticks = ppq * 4.0 / duration.base.coerceAtLeast(1)
        repeat(duration.dots) { dot -> ticks += ppq * 4.0 / duration.base / (2 shl dot) }
        duration.tuplet?.let { ticks = ticks * it.normal / it.actual.coerceAtLeast(1) }
        return ticks.roundToInt()
    }

    fun drawContent(
        canvas: Canvas,
        layout: LayoutResult,
        scrollY: Float,
        playheadTick: Int?,
        score: TabScore,
        measureMap: Map<String, Measure>? = null
    ) {
        val measures = measureMap ?: score.measures.associateBy { it.id }
        canvas.save()
        canvas.translate(0f, -scrollY)

        layout.systems.forEachIndexed { index, system ->
            val y = GHOST_TOP + index * SYSTEM_TOTAL
            if (y + SYSTEM_TOTAL >= scrollY && y <= scrollY + 650f) {
                drawSystem(canvas, index, system, layout, measures, score)
            }
        }
        if (playheadTick != null && playheadTick >= 0) drawPlayhead(canvas, layout, playheadTick, score)
        canvas.restore()
    }

    private fun drawSystem(
        canvas: Canvas,
        sysIdx: Int,
        system: SystemLayout,
        layout: LayoutResult,
        measures: Map<String, Measure>,
        score: TabScore
    ) {
        system.measureIds.forEach { id ->
            val measure = measures[id] ?: return@forEach
            val ml = layout.measureLayouts[id] ?: return@forEach
            drawMeasureBase(canvas, measure, ml, sysIdx)
        }
        system.measureIds.forEach { id ->
            val measure = measures[id] ?: return@forEach
            drawMeasureEvents(canvas, measure, layout, sysIdx)
        }
        system.measureIds.forEach { id ->
            val measure = measures[id] ?: return@forEach
            drawMeasureSpanners(canvas, measure, layout, sysIdx)
        }
        drawIncomingSpanners(canvas, score, layout, sysIdx)
    }

    private fun drawMeasureBase(canvas: Canvas, measure: Measure, ml: MeasureLayout, sysIdx: Int) {
        for (string in 1..6) {
            canvas.drawLine(ml.x0, stringY(string, sysIdx), ml.x1, stringY(string, sysIdx), staffPaint)
        }
        drawBarline(canvas, ml.x0, measure.barline?.left ?: "single", true, sysIdx)
        drawBarline(canvas, ml.x1, measure.barline?.right ?: "single", false, sysIdx)

        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(measure.number.toString(), ml.x0 + 2f, stringY(1, sysIdx) - 10f, smallPaint)
        drawDurationWarning(canvas, measure, ml.x1, sysIdx)
        drawTabClef(canvas, ml.x0 + 5f, sysIdx)
        drawTimeSignature(canvas, measure.timeSignature ?: defaultTimeSig, ml.x0 + 28f, sysIdx)
    }

    private fun drawBarline(canvas: Canvas, x: Float, style: String, left: Boolean, sysIdx: Int) {
        val top = stringY(1, sysIdx) - 6f
        val bottom = stringY(6, sysIdx) + 6f
        val thick = style == "final"
        val paint = if (thick) stroke(GREEN, 3f) else linePaint
        if (style == "double" || style == "final") {
            canvas.drawLine(x + if (left) 4f else -4f, top, x + if (left) 4f else -4f, bottom, paint)
        }
        if (style == "repeat-start" || style == "repeat-both") {
            canvas.drawCircle(x + 8f, stringY(3, sysIdx), 2.5f, fillPaint)
            canvas.drawCircle(x + 8f, stringY(4, sysIdx), 2.5f, fillPaint)
        }
        if (style == "repeat-end" || style == "repeat-both") {
            canvas.drawCircle(x - 8f, stringY(3, sysIdx), 2.5f, fillPaint)
            canvas.drawCircle(x - 8f, stringY(4, sysIdx), 2.5f, fillPaint)
        }
        canvas.drawLine(x, top, x, bottom, paint)
    }

    private fun drawTabClef(canvas: Canvas, x: Float, sysIdx: Int) {
        tabPaint.textAlign = Paint.Align.LEFT
        val topEdge = stringY(1, sysIdx) + STRING_GAP * .5f - 3f
        val bottomEdge = stringY(6, sysIdx) - STRING_GAP * .5f + 3f
        val topBounds = Rect()
        val bottomBounds = Rect()
        tabPaint.getTextBounds("T", 0, 1, topBounds)
        tabPaint.getTextBounds("B", 0, 1, bottomBounds)
        val topBaseline = topEdge - topBounds.top
        val bottomBaseline = bottomEdge - bottomBounds.bottom
        val middleBaseline = (topBaseline + bottomBaseline) * .5f
        drawMaskedText(canvas, "T", x, topBaseline, tabPaint)
        drawMaskedText(canvas, "A", x, middleBaseline, tabPaint)
        drawMaskedText(canvas, "B", x, bottomBaseline, tabPaint)
    }

    private fun drawTimeSignature(canvas: Canvas, time: TimeSig, x: Float, sysIdx: Int) {
        timeSigPaint.textAlign = Paint.Align.CENTER
        val topEdge = stringY(2, sysIdx) + STRING_GAP * .5f - 5f
        val bottomEdge = stringY(5, sysIdx) - STRING_GAP * .5f + 5f
        val topBounds = Rect()
        val bottomBounds = Rect()
        val topValue = time.beats.toString()
        val bottomValue = time.beatType.toString()
        timeSigPaint.getTextBounds(topValue, 0, topValue.length, topBounds)
        timeSigPaint.getTextBounds(bottomValue, 0, bottomValue.length, bottomBounds)
        drawMaskedText(canvas, topValue, x, topEdge - topBounds.top, timeSigPaint)
        drawMaskedText(canvas, bottomValue, x, bottomEdge - bottomBounds.bottom, timeSigPaint)
    }

    private fun drawMaskedText(canvas: Canvas, value: String, x: Float, y: Float, paint: Paint) {
        val oldStyle = paint.style
        val oldColor = paint.color
        val oldWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.strokeWidth = 3f
        canvas.drawText(value, x, y, paint)
        paint.style = oldStyle; paint.color = oldColor; paint.strokeWidth = oldWidth
        canvas.drawText(value, x, y, paint)
    }

    private fun drawDurationWarning(canvas: Canvas, measure: Measure, x1: Float, sysIdx: Int) {
        if (measure.events.isEmpty()) return
        val total = measure.events
            .filterNot { isMuteEvent(it) }
            .maxOfOrNull { event ->
                if (event.notes.any { it.status == "ring" }) measure.durationTicks
                else event.tick + durationTicks(event.duration)
            } ?: 0
        if (total == measure.durationTicks) return
        val x = x1 - 16f
        val y = stringY(1, sysIdx) - 35f
        canvas.drawCircle(x, y, 7f, thinPaint)
        smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("?", x, y + 4f, smallPaint)
    }

    private fun drawMeasureEvents(canvas: Canvas, measure: Measure, layout: LayoutResult, sysIdx: Int) {
        val groups = beamGroups(measure)
        measure.events.forEach { event ->
            val eventPos = layout.eventPositions[event.id] ?: return@forEach
            if (event.type == "rest") {
                drawRest(canvas, eventPos.x, stringY(3, sysIdx), event.duration, sysIdx)
                return@forEach
            }
            if (event.type != "note") return@forEach
            val positions = event.notes.mapNotNull { layout.notePositions[it.id] }
            positions.forEach { drawNote(canvas, it) }
            drawArticulations(canvas, event, eventPos.x, sysIdx)
            if (positions.isNotEmpty()) {
                val group = event.beamGroup?.let { groups[it] }
                val effectiveGroup = if ((group?.size ?: 0) >= 2) group else null
                drawRhythm(canvas, eventPos.x, event.duration, effectiveGroup != null, isMuteEvent(event), sysIdx)
            }
        }
        drawBeams(canvas, measure, layout, sysIdx)
    }

    private fun drawNote(canvas: Canvas, pos: NotePos) {
        val display = noteDisplay(pos.note)
        numberPaint.textSize = if (display.length > 2) 7.5f else 9.5f
        numberPaint.textAlign = Paint.Align.CENTER
        val box = noteBox(pos.note, pos.x, pos.y)
        canvas.drawRect(box, blackPaint)
        val baseline = pos.y - (numberPaint.ascent() + numberPaint.descent()) / 2f
        canvas.drawText(display, pos.x, baseline, numberPaint)
        if (pos.note.status == "ring") {
            canvas.drawOval(
                RectF(pos.x - box.width() * .6f, pos.y - box.height() * .6f,
                    pos.x + box.width() * .6f, pos.y + box.height() * .6f), linePaint
            )
        }
        pos.note.effects.forEach { effect ->
            when (effect.type) {
                "tap" -> drawSmallLabel(canvas, pos.x, pos.y - 18f, effect.label ?: "T")
            }
        }
    }

    private fun noteDisplay(note: Note): String = when (note.status) {
        "dead", "mute" -> "X"
        "tied", "ghost" -> "(${stripOuterParens(note.display.ifEmpty { note.fret.toString() })})"
        else -> {
            val raw = note.display.ifEmpty { note.fret.toString() }
            if (note.effects.any { it.type == "ghost" }) "(${stripOuterParens(raw)})" else stripOuterParens(raw)
        }
    }

    private fun stripOuterParens(value: String): String {
        var result = value.trim()
        while (result.length >= 2 && result.first() == '(' && result.last() == ')') {
            result = result.substring(1, result.length - 1).trim()
        }
        return result
    }

    private fun noteBox(note: Note, x: Float, y: Float): RectF {
        val display = noteDisplay(note)
        numberPaint.textSize = if (display.length > 2) 7.5f else 9.5f
        val width = max(14f, numberPaint.measureText(display) + 7f)
        val height = numberPaint.fontMetrics.run { descent - ascent + 4f }
        return RectF(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
    }

    private fun glyphHalfWidth(note: Note): Float {
        val len = noteDisplay(note).length
        return max(4f, len * if (len > 2) 3.3f else 3.9f)
    }

    private fun digitHalfWidth(note: Note): Float {
        val raw = stripOuterParens(note.display.ifEmpty { note.fret.toString() })
        numberPaint.textSize = if (raw.length > 2) 7.5f else 9.5f
        return numberPaint.measureText(raw) * .5f
    }

    private fun smallTextHalfWidth(value: String): Float {
        smallPaint.textSize = 7.5f
        return smallPaint.measureText(value) * .5f
    }

    private fun drawArticulations(canvas: Canvas, event: Event, x: Float, sysIdx: Int) {
        if (event.articulations.contains("staccato")) {
            canvas.drawCircle(x, techniqueRailY(sysIdx), 2.2f, fillPaint)
        }
    }

    private fun drawRhythm(
        canvas: Canvas,
        x: Float,
        duration: Duration,
        beamed: Boolean,
        mute: Boolean,
        sysIdx: Int
    ) {
        val base = duration.base
        val stemTop = stringY(6, sysIdx) + STRING_GAP
        val stemBottom = rhythmY(sysIdx)

        if (base <= 4) {
            val oval = RectF(x - 11f, stemBottom - 2f, x + 1f, stemBottom + 6f)
            if (base == 4) canvas.drawOval(oval, fillPaint) else canvas.drawOval(oval, linePaint)
        }
        if (base >= 2 && !mute) canvas.drawLine(x + 1f, stemTop, x + 1f, stemBottom, linePaint)
        if (base >= 8 && !beamed && !mute) drawFlags(canvas, x + 1f, stemBottom, beamLevels(base))

        if (duration.dots > 0) {
            val dotY = if (beamed) stemBottom - 6f else stemBottom + 5f
            val dotX = x + if (beamed) 7f else 11f
            repeat(duration.dots) { canvas.drawCircle(dotX + it * 7f, dotY, 2f, fillPaint) }
        }
    }

    private fun drawFlags(canvas: Canvas, x: Float, y: Float, count: Int) {
        repeat(count) { index ->
            val yy = y - index * 6f
            val path = Path().apply {
                moveTo(x, yy)
                cubicTo(x + 8f, yy - 2f, x + 9f, yy - 9f, x + 3f, yy - 13f)
            }
            canvas.drawPath(path, linePaint)
        }
    }

    private fun beamGroups(measure: Measure): Map<String, List<Event>> = measure.events
        .filter { it.type == "note" && !it.beamGroup.isNullOrEmpty() && it.duration.base >= 8 }
        .groupBy { it.beamGroup!! }
        .mapValues { (_, value) -> value.sortedBy { it.tick } }

    private fun drawBeams(canvas: Canvas, measure: Measure, layout: LayoutResult, sysIdx: Int) {
        beamGroups(measure).values.forEach { events ->
            if (events.size < 2) return@forEach
            val items = events.mapNotNull { event -> layout.eventPositions[event.id]?.let { event to it.x } }
            if (items.size < 2) return@forEach
            val maxLevel = items.maxOf { beamLevels(it.first.duration.base) }
            for (level in 1..maxLevel) {
                drawBeamLevel(canvas, items, rhythmY(sysIdx) - (level - 1) * 6f, level)
            }
            items.first().first.duration.tuplet?.let { drawTuplet(canvas, items, sysIdx, it.actual) }
        }
    }

    private fun drawBeamLevel(canvas: Canvas, items: List<Pair<Event, Float>>, y: Float, level: Int) {
        var index = 0
        while (index < items.size) {
            if (beamLevels(items[index].first.duration.base) < level) { index++; continue }
            val start = index
            while (index + 1 < items.size && beamLevels(items[index + 1].first.duration.base) >= level) index++
            val end = index
            if (start == end) {
                val hook = min(14f, BEAT_WIDTH / 6f)
                val x = items[start].second + 1f
                if (start == items.lastIndex && start > 0) canvas.drawLine(x - hook, y, x, y, beamPaint)
                else canvas.drawLine(x, y, x + hook, y, beamPaint)
            } else {
                canvas.drawLine(items[start].second + 1f, y, items[end].second + 1f, y, beamPaint)
            }
            index++
        }
    }

    private fun drawTuplet(canvas: Canvas, items: List<Pair<Event, Float>>, sysIdx: Int, actual: Int) {
        val x1 = items.first().second - 4f
        val x2 = items.last().second + 4f
        val y = rhythmY(sysIdx) + 12f
        canvas.drawLine(x1, y, x2, y, thinPaint)
        canvas.drawLine(x1, y, x1, y - 5f, thinPaint)
        canvas.drawLine(x2, y, x2, y - 5f, thinPaint)
        smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(actual.toString(), (x1 + x2) / 2f, y + 12f, smallPaint)
    }

    private fun beamLevels(base: Int): Int = if (base < 8) 0 else max(1, (log2(base.toDouble()) - 2).roundToInt())

    private fun drawRest(canvas: Canvas, x: Float, y: Float, duration: Duration, sysIdx: Int) {
        when (duration.base) {
            1 -> canvas.drawRect(x - 6f, y, x + 6f, y + 5f, fillPaint)
            2 -> canvas.drawRect(x - 6f, y - 5f, x + 6f, y, fillPaint)
            4 -> drawQuarterRest(canvas, x, y)
            else -> drawShortRest(canvas, x, y, beamLevels(duration.base))
        }
        val dotY = when (duration.base) {
            1 -> stringY(3, sysIdx) + 1f
            2 -> stringY(3, sysIdx) - 5f
            4 -> stringY(3, sysIdx) + 1f
            else -> stringY(3, sysIdx) - 2f
        }
        repeat(duration.dots) { canvas.drawCircle(x + 11f + it * 7f, dotY, 2f, fillPaint) }
    }

    private fun drawQuarterRest(canvas: Canvas, x: Float, y: Float) {
        val top = y - 14f
        val path = Path().apply {
            moveTo(x - 3f, top); lineTo(x + 1f, top + 4f)
            lineTo(x - 3f, top + 7f); lineTo(x + 1f, top + 13f)
            quadTo(x - 9f, top + 16f, x - 3f, top + 20f)
        }
        canvas.drawPath(path, stroke(GREEN, 2f))
    }

    private fun drawShortRest(canvas: Canvas, x: Float, y: Float, heads: Int) {
        val count = heads.coerceAtLeast(1)
        val stemTopX = x + 1f
        val stemTopY = y - 7f
        val stemBottomX = x - 4f
        val stemBottomY = stemTopY + 6f + count * 5f
        canvas.drawLine(stemTopX, stemTopY, stemBottomX, stemBottomY, linePaint)
        repeat(count) { index ->
            val headY = y - 9f + index * if (count > 2) 6f else 5f
            val t = (headY - stemTopY) / (stemBottomY - stemTopY)
            val stemX = stemTopX + (stemBottomX - stemTopX) * t
            val headX = stemX - 6f
            canvas.drawCircle(headX, headY, 2.4f, fillPaint)
            val path = Path().apply {
                moveTo(headX + 2f, headY + 1f)
                quadTo(stemTopX - 1f, headY + 4f, stemX, headY)
            }
            canvas.drawPath(path, linePaint)
        }
    }

    private fun drawMeasureSpanners(canvas: Canvas, measure: Measure, layout: LayoutResult, sysIdx: Int) {
        val chains = collectHammerPullChains(measure.spanners)
        val chained = chains.flatten().map { it.id }.toSet()
        chains.forEach { drawHammerPullChain(canvas, it, layout, sysIdx) }
        val slideLabels = mutableSetOf<String>()

        measure.spanners.forEach { spanner ->
            if (spanner.id in chained) return@forEach
            when (spanner.type) {
                "bend" -> drawBend(canvas, spanner, layout, sysIdx, vibrato = false)
                "bend-vibrato" -> drawBend(canvas, spanner, layout, sysIdx, vibrato = true)
                "slide" -> {
                    val from = spanner.from?.let { layout.notePositions[it] }
                    val to = spanner.to?.let { layout.notePositions[it] }
                    val key = if (from != null && to != null) "${from.event.id}|${to.event.id}" else spanner.id
                    drawSlide(canvas, spanner, layout, sysIdx, drawLabel = slideLabels.add(key))
                }
                "hammer-on", "pull-off", "tie", "slur", "trill" -> drawSlur(canvas, spanner, layout, sysIdx)
                "vibrato" -> drawVibrato(canvas, spanner, layout, sysIdx)
                "let-ring", "palm-mute" -> drawRangeLine(canvas, spanner, layout, sysIdx)
            }
        }
        drawHarmonicRanges(canvas, measure, layout, sysIdx)
    }

    private fun collectHammerPullChains(spanners: List<Spanner>): List<List<Spanner>> {
        val candidates = spanners.filter { it.type in setOf("hammer-on", "pull-off") && it.from != null && it.to != null }
        val used = mutableSetOf<String>()
        val result = mutableListOf<List<Spanner>>()
        candidates.forEach { first ->
            if (!used.add(first.id)) return@forEach
            val chain = mutableListOf(first)
            var last = first
            while (true) {
                val next = candidates.firstOrNull { it.id !in used && it.from == last.to } ?: break
                used.add(next.id); chain.add(next); last = next
            }
            if (chain.size > 1) result.add(chain)
        }
        return result
    }

    private fun drawHammerPullChain(canvas: Canvas, chain: List<Spanner>, layout: LayoutResult, sysIdx: Int) {
        val first = chain.first().from?.let { layout.notePositions[it] } ?: return
        val last = chain.last().to?.let { layout.notePositions[it] } ?: return
        if (first.systemIndex != sysIdx || last.systemIndex != sysIdx) return
        val path = Path().apply {
            moveTo(first.x + 4f, first.y - 10f)
            quadTo((first.x + last.x) / 2f, min(first.y, last.y) - 22f, last.x - 4f, last.y - 10f)
        }
        canvas.drawPath(path, linePaint)
        chain.forEach { sp ->
            val from = sp.from?.let { layout.notePositions[it] }
            val to = sp.to?.let { layout.notePositions[it] }
            if (from != null && to != null) {
                drawTechniqueLabel(canvas, (from.x + to.x) / 2f, sp.label ?: if (sp.type == "hammer-on") "H" else "P", sysIdx)
            }
        }
    }

    private fun drawSlur(canvas: Canvas, sp: Spanner, layout: LayoutResult, sysIdx: Int) {
        val from = sp.from?.let { layout.notePositions[it] }
        val to = sp.to?.let { layout.notePositions[it] }
        if (from == null && to == null) return
        val technique = sp.type == "hammer-on" || sp.type == "pull-off"
        val anchor = from ?: to ?: return
        if (anchor.systemIndex != sysIdx) return

        // H-in/P-out records intentionally omit one endpoint.  The omitted
        // fret is an inline grace value, not a timed Event, so draw it next to
        // the anchor without changing tick spacing.
        if (technique && (from == null || to == null)) {
            val incoming = from == null
            val fret = if (incoming) sp.fromFret else sp.toFret
            if (fret == null) return
            val sourceHalf = smallTextHalfWidth(fret.toString())
            val targetHalf = digitHalfWidth(anchor.note)
            val sourceX: Float
            val targetX: Float
            val x1: Float
            val x2: Float
            if (incoming) {
                targetX = anchor.x
                sourceX = targetX - targetHalf - sourceHalf - 5f
                x1 = sourceX
                x2 = targetX
            } else {
                sourceX = anchor.x
                targetX = sourceX + targetHalf + sourceHalf + 5f
                x1 = sourceX
                x2 = targetX
            }
            if (incoming) drawMaskedSmallText(canvas, fret.toString(), sourceX, anchor.y)
            else drawMaskedSmallText(canvas, fret.toString(), targetX, anchor.y)
            val arcY = anchor.y - 9f
            if (x2 > x1) {
                val path = Path().apply {
                    moveTo(x1, arcY)
                    quadTo((x1 + x2) / 2f, arcY - 8f, x2, arcY)
                }
                canvas.drawPath(path, linePaint)
                drawTechniqueLabel(canvas, (x1 + x2) / 2f, sp.label ?: if (sp.type == "hammer-on") "H" else "P", sysIdx)
            }
            return
        }

        val x1 = when {
            from == null -> anchor.contentStart + 5f
            else -> from.x + 4f
        }
        val x2 = when {
            to == null || to.systemIndex != sysIdx -> anchor.contentEnd - 5f
            else -> to.x - 4f
        }
        if (x2 <= x1) return
        val below = !technique
        val baseY = anchor.y + if (below) 11f else -10f
        val controlY = baseY + if (below) 11f else -12f
        val path = Path().apply { moveTo(x1, baseY); quadTo((x1 + x2) / 2f, controlY, x2, baseY) }
        canvas.drawPath(path, if (sp.type == "tie") thinPaint else linePaint)
        val label = sp.label ?: if (technique) if (sp.type == "hammer-on") "H" else "P" else null
        if (label != null) {
            if (technique) drawTechniqueLabel(canvas, (x1 + x2) / 2f, label, sysIdx)
            else drawSmallLabel(canvas, (x1 + x2) / 2f, controlY + if (below) 10f else -5f, label)
        }
    }

    private fun drawSlide(
        canvas: Canvas,
        sp: Spanner,
        layout: LayoutResult,
        sysIdx: Int,
        drawLabel: Boolean
    ) {
        val from = sp.from?.let { layout.notePositions[it] }
        val to = sp.to?.let { layout.notePositions[it] }
        val anchor = from ?: to ?: return
        if (anchor.systemIndex != sysIdx) return
        if (from == null || to == null || to.systemIndex != sysIdx) {
            drawSingleSlide(canvas, sp, anchor, slideIn = from == null)
            return
        }
        val sameString = from.note.string == to.note.string
        val x1 = from.x + glyphHalfWidth(from.note) + 2f
        val x2 = to.x - glyphHalfWidth(to.note) - 2f
        if (x2 <= x1 + 1f) return
        if (sameString) canvas.drawLine(x1 - 2f, from.y, x2 + 2f, from.y, stroke(Color.BLACK, 3f))
        val rising = from.note.fret < to.note.fret
        val delta = if (sameString) 1f else 5f
        canvas.drawLine(x1, from.y + if (rising) delta else -delta, x2, from.y + if (rising) -delta else delta, linePaint)
        if (drawLabel && sp.label != null) drawTechniqueLabel(canvas, (x1 + x2) / 2f, sp.label.ifEmpty { "sl." }, sysIdx)
    }

    private fun drawSingleSlide(canvas: Canvas, sp: Spanner, pos: NotePos, slideIn: Boolean) {
        val half = glyphHalfWidth(pos.note)
        val length = 8f
        val x1 = if (slideIn) pos.x - half - 2f - length else pos.x + half + 2f
        val x2 = if (slideIn) pos.x - half - 2f else pos.x + half + 2f + length
        val rises = sp.direction != "down"
        val y1 = pos.y + if (rises) 3f else -3f
        val y2 = pos.y + if (rises) -3f else 3f
        canvas.drawLine(x1 - 1f, pos.y, x2 + 1f, pos.y, stroke(Color.BLACK, 3f))
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        val fret = if (slideIn) sp.fromFret else sp.toFret
        if (fret != null) {
            val fx = if (slideIn) x1 - 7f else x2 + 7f
            drawMaskedSmallText(canvas, fret.toString(), fx, pos.y)
        }
    }

    private fun drawBend(canvas: Canvas, sp: Spanner, layout: LayoutResult, sysIdx: Int, vibrato: Boolean) {
        val from = sp.from?.let { layout.notePositions[it] } ?: return
        if (from.systemIndex != sysIdx) return
        val to = sp.to?.let { layout.notePositions[it] }?.takeIf { it.systemIndex == sysIdx }
        val next = to ?: nextNote(from, layout)
        val startX = from.x + glyphHalfWidth(from.note) + 2f
        val startY = from.y - 9f
        val anchorX = next?.x ?: from.contentEnd
        val endX = max(startX + 14f, (from.x + anchorX) / 2f)
        val highY = stringY(1, sysIdx) - STRING_GAP * 2f
        val returns = sp.curve.size >= 3 && sp.curve.last().alter <= 0.0 && sp.curve.any { it.alter > 0.0 }

        val path = Path()
        if (returns && next != null) {
            val apex = (from.x + next.x) / 2f
            path.moveTo(startX, startY)
            path.cubicTo(startX + (apex - startX) * .45f, startY, apex - 1f, highY, apex, highY)
            path.cubicTo(apex + 10f, highY, next.x - 10f, highY, next.x, next.y - 10f)
            canvas.drawPath(path, linePaint)
            drawArrow(canvas, apex, highY, up = true)
            drawArrow(canvas, next.x, next.y - 10f, up = false)
            drawBendLabel(canvas, apex, highY - 6f, sp)
        } else {
            val flat = startX + (endX - startX) * .74f
            path.moveTo(startX, startY)
            path.cubicTo(startX + (flat - startX) * .35f, startY + 2f, flat - 8f, startY + 2f, flat, startY - 1f)
            path.cubicTo(endX - 2f, startY - 1f, endX, highY, endX, highY)
            canvas.drawPath(path, linePaint)
            drawArrow(canvas, endX, highY, up = true)
            if (vibrato) drawWave(canvas, endX + 2f, highY, min(34f, max(18f, anchorX - endX - 2f)), waveAmplitude(sp.width))
            drawBendLabel(canvas, if (vibrato) endX + 12f else endX, highY - 6f, sp)
        }
    }

    private fun drawBendLabel(canvas: Canvas, x: Float, y: Float, sp: Spanner) {
        val fallback = when {
            sp.curve.maxOfOrNull { it.alter } == 1.0 -> "1/2"
            (sp.curve.maxOfOrNull { it.alter } ?: 2.0) < 1.0 -> "1/4"
            else -> "full"
        }
        drawSmallLabel(canvas, x, y, sp.label ?: fallback)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, up: Boolean) {
        val direction = if (up) 1f else -1f
        canvas.drawLine(x, y, x - 4f, y + 6f * direction, linePaint)
        canvas.drawLine(x, y, x + 4f, y + 6f * direction, linePaint)
    }

    private fun drawVibrato(canvas: Canvas, sp: Spanner, layout: LayoutResult, sysIdx: Int) {
        val from = sp.from?.let { layout.notePositions[it] } ?: return
        if (from.systemIndex != sysIdx) return
        val targetNote = sp.to?.let { layout.notePositions[it] }?.takeIf { it.systemIndex == sysIdx }
        val targetEvent = sp.toEvent?.let { layout.eventPositions[it] }?.takeIf { it.systemIndex == sysIdx }
        val hasExternalTarget = sp.to != null || sp.toEvent != null
        val end = targetNote?.x ?: targetEvent?.x ?: if (hasExternalTarget) from.contentEnd else nextEvent(from, layout)?.x ?: from.contentEnd
        if (end > from.x + 12f) drawWave(canvas, from.x, techniqueRailY(sysIdx), end - from.x, waveAmplitude(sp.width))
    }

    private fun waveAmplitude(width: String?): Float = when (width) {
        "wide" -> 2.8f
        "narrow" -> 1.6f
        else -> 2.2f
    }

    private fun drawWave(canvas: Canvas, x: Float, y: Float, width: Float, amplitude: Float) {
        if (width <= 0f) return
        val path = Path().apply { moveTo(x, y) }
        val steps = max(8, ceil(width / 2f).toInt())
        for (i in 1..steps) {
            val px = x + width * i / steps
            val py = y + sin(i / steps.toDouble() * width / 7f * Math.PI * 2).toFloat() * amplitude
            path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawRangeLine(canvas: Canvas, sp: Spanner, layout: LayoutResult, sysIdx: Int) {
        val from = sp.fromEvent?.let { layout.eventPositions[it] } ?: return
        if (from.systemIndex != sysIdx) return
        val to = sp.toEvent?.let { layout.eventPositions[it] }?.takeIf { it.systemIndex == sysIdx }
        val next = nextEvent(from, layout)
        val x1 = if (sp.type == "let-ring" && next != null) (from.x + next.x) / 2f else from.x - 6f
        val x2 = to?.x?.plus(6f) ?: next?.x?.plus(6f) ?: from.contentEnd - 4f
        val label = sp.label ?: if (sp.type == "palm-mute") "P.M." else "let ring"
        val y = techniqueRailY(sysIdx) + 1f
        italicPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x1, y + 4f, italicPaint)
        val lineStart = x1 + italicPaint.measureText(label) + 4f
        if (x2 > lineStart + 8f) {
            val dashed = stroke(GREEN, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) }
            canvas.drawLine(lineStart, y, x2, y, dashed)
            canvas.drawLine(x2, y - 4f, x2, y + 3f, thinPaint)
        }
    }

    private fun drawHarmonicRanges(canvas: Canvas, measure: Measure, layout: LayoutResult, sysIdx: Int) {
        measure.events.forEach { event ->
            event.notes.forEach noteLoop@{ note ->
                val effect = note.effects.firstOrNull { it.type == "harmonic" } ?: return@noteLoop
                val pos = layout.notePositions[note.id] ?: return@noteLoop
                if (pos.systemIndex != sysIdx) return@noteLoop
                val targetNote = effect.to?.let { layout.notePositions[it] }?.takeIf { it.systemIndex == sysIdx }
                val targetEvent = effect.toEvent?.let { layout.eventPositions[it] }?.takeIf { it.systemIndex == sysIdx }
                val hasExternalTarget = effect.to != null || effect.toEvent != null
                val next = nextEvent(pos, layout)
                val endX = targetNote?.x ?: targetEvent?.x?.plus(6f)
                    ?: if (hasExternalTarget) pos.contentEnd - 4f else next?.x?.plus(6f) ?: pos.contentEnd - 4f
                drawLabeledRange(
                    canvas,
                    pos.x - 4f,
                    endX,
                    harmonicLabel(effect),
                    techniqueRailY(sysIdx) + 1f,
                    dashed = true
                )
            }
        }
    }

    private fun harmonicLabel(effect: com.rokid.music.model.Effect): String = effect.label ?: when (effect.kind) {
        "artificial" -> "A.H."
        "pinch" -> "P.H."
        "tapped" -> "T.H."
        else -> "<>"
    }

    private fun drawIncomingSpanners(canvas: Canvas, score: TabScore, layout: LayoutResult, sysIdx: Int) {
        score.measures.flatMap { it.spanners }.forEach { sp ->
            val from = sp.from?.let { layout.notePositions[it] }
            val to = sp.to?.let { layout.notePositions[it] }
            val fromEvent = sp.fromEvent?.let { layout.eventPositions[it] }
            val toEvent = sp.toEvent?.let { layout.eventPositions[it] }

            if (sp.type == "let-ring" || sp.type == "palm-mute") {
                if (toEvent == null || toEvent.systemIndex != sysIdx || fromEvent == null || fromEvent.systemIndex == sysIdx) return@forEach
                drawLabeledRange(
                    canvas,
                    toEvent.contentStart,
                    toEvent.x + 6f,
                    sp.label ?: if (sp.type == "palm-mute") "P.M." else "let ring",
                    techniqueRailY(sysIdx) + 1f,
                    dashed = true
                )
                return@forEach
            }

            if (to == null || to.systemIndex != sysIdx || from == null || from.systemIndex == sysIdx) return@forEach
            when (sp.type) {
                "tie", "slur", "hammer-on", "pull-off", "trill" -> {
                    val technique = sp.type == "hammer-on" || sp.type == "pull-off"
                    val half = digitHalfWidth(to.note)
                    // An incoming tie is rendered beneath the carried note;
                    // its horizontal span follows the real `(xx)` glyph,
                    // rather than stretching from the measure's left edge.
                    val x1 = if (sp.type == "tie") to.x - half else to.contentStart + 5f
                    val x2 = if (sp.type == "tie") to.x + half else to.x - 4f
                    val baseY = to.y + if (technique) -10f else 8f
                    // Cross-measure ties should be a shallow continuation,
                    // not a deep U-shaped bowl under the first note.
                    val controlY = baseY + if (technique) -10f else 9f
                    val path = Path().apply { moveTo(x1, baseY); quadTo((x1 + x2) / 2f, controlY, x2, baseY) }
                    val arcPaint = if (sp.type == "tie") stroke(GREEN, 1.6f) else linePaint
                    canvas.drawPath(path, arcPaint)
                }
                "slide" -> {
                    val x1 = to.contentStart + 5f
                    val x2 = to.x - glyphHalfWidth(to.note) - 2f
                    val rising = sp.direction != "down"
                    canvas.drawLine(x1, to.y + if (rising) 3f else -3f, x2, to.y + if (rising) -1f else 1f, linePaint)
                }
                "bend" -> {
                    val x1 = to.contentStart + 5f
                    val x2 = to.x - 3f
                    val highY = stringY(1, sysIdx) - STRING_GAP * 2f
                    if (x2 > x1 + 8f) {
                        val path = Path().apply {
                            moveTo(x1, highY)
                            cubicTo(x1 + (x2 - x1) * .55f, highY, x2 - 8f, to.y - 10f, x2, to.y - 10f)
                        }
                        canvas.drawPath(path, linePaint)
                        drawArrow(canvas, x2, to.y - 10f, up = false)
                    }
                }
                "vibrato", "bend-vibrato" -> {
                    val x1 = to.contentStart + 5f
                    if (to.x > x1 + 12f) drawWave(canvas, x1, techniqueRailY(sysIdx), to.x - x1, waveAmplitude(sp.width))
                }
            }
        }

        drawIncomingHarmonics(canvas, score, layout, sysIdx)
    }

    private fun drawIncomingHarmonics(canvas: Canvas, score: TabScore, layout: LayoutResult, sysIdx: Int) {
        score.measures.asSequence().flatMap { it.events.asSequence() }.flatMap { it.notes.asSequence() }.forEach { note ->
            val effect = note.effects.firstOrNull { it.type == "harmonic" } ?: return@forEach
            val source = layout.notePositions[note.id] ?: return@forEach
            val targetNote = effect.to?.let { layout.notePositions[it] }
            val targetEvent = effect.toEvent?.let { layout.eventPositions[it] }
            val targetSystem = targetNote?.systemIndex ?: targetEvent?.systemIndex ?: return@forEach
            if (targetSystem != sysIdx || source.systemIndex == sysIdx) return@forEach
            val endX = targetNote?.x ?: targetEvent!!.x + 6f
            val contentStart = targetNote?.contentStart ?: targetEvent!!.contentStart
            drawLabeledRange(
                canvas,
                contentStart,
                endX,
                harmonicLabel(effect),
                techniqueRailY(sysIdx) + 1f,
                dashed = true
            )
        }
    }

    private fun drawLabeledRange(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        label: String,
        y: Float,
        dashed: Boolean
    ) {
        if (endX <= startX) return
        italicPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, startX, y + 4f, italicPaint)
        val lineStart = startX + italicPaint.measureText(label) + 4f
        if (endX <= lineStart + 8f) return
        val paint = if (dashed) stroke(GREEN, 1f).apply {
            pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        } else thinPaint
        canvas.drawLine(lineStart, y, endX, y, paint)
        canvas.drawLine(endX, y - 4f, endX, y + 3f, thinPaint)
    }

    private fun nextNote(from: NotePos, layout: LayoutResult): NotePos? = layout.notePositions.values
        .filter { it.measureId == from.measureId && it.event.tick > from.event.tick && it.note.status !in setOf("tied", "ghost") }
        .minByOrNull { it.event.tick }

    private fun nextEvent(from: NotePos, layout: LayoutResult): EventPos? = layout.eventPositions.values
        .filter { it.measureId == from.measureId && it.event.tick > from.event.tick }
        .minByOrNull { it.event.tick }

    private fun nextEvent(from: EventPos, layout: LayoutResult): EventPos? = layout.eventPositions.values
        .filter { it.measureId == from.measureId && it.event.tick > from.event.tick }
        .minByOrNull { it.event.tick }

    private fun drawTechniqueLabel(canvas: Canvas, x: Float, value: String, sysIdx: Int) {
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, x, techniqueRailY(sysIdx), labelPaint)
    }

    private fun drawSmallLabel(canvas: Canvas, x: Float, y: Float, value: String) {
        smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, x, y, smallPaint)
    }

    private fun drawMaskedSmallText(canvas: Canvas, value: String, x: Float, y: Float) {
        smallPaint.textAlign = Paint.Align.CENTER
        val width = smallPaint.measureText(value) + 6f
        canvas.drawRect(x - width / 2f, y - 7f, x + width / 2f, y + 7f, blackPaint)
        canvas.drawText(value, x, y + 4f, smallPaint)
    }

    private fun isMuteEvent(event: Event): Boolean =
        event.type == "note" && event.notes.size == 1 && event.notes[0].status == "mute"

    fun stringY(string: Int, systemIndex: Int): Float =
        GHOST_TOP + systemIndex * SYSTEM_TOTAL + TOP + (string.coerceIn(1, 6) - 1) * STRING_GAP

    private fun techniqueRailY(systemIndex: Int): Float = stringY(1, systemIndex) - STRING_GAP
    // Keep stems visibly connected to the rhythm marks after the compact
    // system layout; this gives quarter/eighth notes a little more height.
    private fun rhythmY(systemIndex: Int): Float = stringY(6, systemIndex) + 31f

    private fun drawPlayhead(canvas: Canvas, layout: LayoutResult, tick: Int, score: TabScore) {
        score.measures.forEachIndexed { index, measure ->
            val end = measure.startTick + measure.durationTicks
            val matches = tick >= measure.startTick && (tick < end || (tick == end && index == score.measures.lastIndex))
            if (!matches) return@forEachIndexed
            val ml = layout.measureLayouts[measure.id] ?: return
            val x = tickToMeasureX(measure, ml.x0, ml.x1, ml.firstInSystem, tick - measure.startTick)
            canvas.drawLine(x, stringY(1, ml.systemIndex) - 12f, x, rhythmY(ml.systemIndex) + 18f, playheadPaint)
            return
        }
    }

    fun tickToSystemY(tick: Int, layout: LayoutResult, score: TabScore): Float {
        score.measures.forEachIndexed { index, measure ->
            val end = measure.startTick + measure.durationTicks
            if (tick >= measure.startTick && (tick < end || index == score.measures.lastIndex && tick == end)) {
                return GHOST_TOP + (layout.measureLayouts[measure.id]?.systemIndex ?: index) * SYSTEM_TOTAL
            }
        }
        return 0f
    }

    private fun stroke(colorValue: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue; strokeWidth = width; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun fill(colorValue: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue; style = Paint.Style.FILL
    }

    private fun text(size: Float, bold: Boolean, italic: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN; textSize = size
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        typeface = Typeface.create("monospace", style)
    }
}
