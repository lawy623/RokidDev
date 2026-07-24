package com.rokid.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class ScoreEntry(
    val title: String,
    val artist: String,
    val fileName: String
)

class StartScreenView(
    context: Context,
    private val onScoreSelected: (ScoreEntry) -> Unit
) : View(context) {

    private val greenBright = 0xFF00FF44.toInt()
    private val greenDim    = 0xFF008F2C.toInt()
    private val greenPanel  = 0xFF001B08.toInt()

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright
        typeface = Typeface.create("monospace", Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; alpha = 200; typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; alpha = 210; typeface = Typeface.MONOSPACE
    }
    private val scoreNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; typeface = Typeface.create("monospace", Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; alpha = 180; typeface = Typeface.MONOSPACE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenDim; style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val selectBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenBright; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val selectFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenPanel; style = Paint.Style.FILL
    }

    private val scores = mutableListOf<ScoreEntry>()
    private var selectedIndex = 0
    private var statusText = "Loading score list..."
    private var serverInfo: String? = null
    private var guitarBitmap: Bitmap? = null
    private var lastW = 0
    private var listExpanded = false
    private var lastNavigationAt = 0L

    companion object {
        // This Rokid firmware emits multiple directional key-down events for a
        // single TP swipe (and a fast swipe may emit RIGHT+DOWN or LEFT+UP).
        private const val SWIPE_DEBOUNCE_MS = 280L

        // Observed TP contact precursor on this device. It is emitted for taps
        // and swipes too, so it must never be interpreted as a long press.
        private const val KEYCODE_TP_CONTACT = 83
    }

    init {
        isFocusableInTouchMode = true
        isFocusable = true
        // Keep keyboard/TP focus for Rokid input, but suppress Android's
        // default full-view focus rectangle around this Canvas surface.
        defaultFocusHighlightEnabled = false
        loadScoreList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // MainActivity reuses this view after leaving PlayerView. Reclaiming
        // focus here keeps the next TP click/swipe from being consumed by the
        // window focus transition.
        post { requestFocus() }
    }

    fun setServerInfo(info: String) {
        serverInfo = info
        invalidate()
    }

    fun reloadScores() {
        loadScoreList()
        invalidate()
    }

    private fun loadScoreList() {
        scores.clear()
        // Uploaded scores are the sole source of truth for the player UI.
        try {
            val uploadDir = context.getExternalFilesDir("scores") ?: File(context.filesDir, "scores")
            uploadDir.listFiles()?.filter { it.name.endsWith(".tab.json") }?.forEach { f ->
                try {
                    val json = org.json.JSONObject(f.readText())
                    val metadata = json.optJSONObject("metadata")
                    val title = metadata?.optString("title", "")?.trim().orEmpty()
                    val artist = metadata?.optString("artist", "")?.trim().orEmpty()
                    scores.add(ScoreEntry(
                        title = if (title.isEmpty()) "Untitled" else title,
                        artist = artist,
                        fileName = f.name
                    ))
                } catch (e: Exception) {
                    Log.w("StartScreenView", "Skipping invalid uploaded score: ${f.name}", e)
                }
            }
        } catch (_: Exception) {}
        if (scores.isNotEmpty()) { selectedIndex = 0; statusText = "${scores.size} scores" }
        else statusText = "No scores found."
    }

    private fun loadGuitarBitmap(targetW: Int) {
        if (guitarBitmap != null) return
        val opts = BitmapFactory.Options().apply { inScaled = true }
        val raw = BitmapFactory.decodeResource(context.resources, R.drawable.guitar_silhouette, opts)
        if (raw != null) {
            val scale = targetW.toFloat() / raw.width
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            guitarBitmap = Bitmap.createScaledBitmap(raw, targetW, h, true)
            if (targetW != raw.width || h != raw.height) raw.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (width != lastW) {
            lastW = width
            val base = w / 320f
            titlePaint.textSize = (28f * base).coerceIn(18f, 36f)
            subtitlePaint.textSize = (11f * base).coerceIn(8f, 14f)
            labelPaint.textSize = (10f * base).coerceIn(8f, 13f)
            scoreNamePaint.textSize = (14f * base).coerceIn(11f, 18f)
            hintPaint.textSize = (9f * base).coerceIn(7f, 12f)
            guitarBitmap?.recycle(); guitarBitmap = null
        }

        canvas.drawColor(Color.BLACK)

        val pad = w * 0.06f
        val contentL = pad; val contentR = w - pad; val contentW = contentR - contentL
        val gap = titlePaint.textSize * 0.55f
        var curY = h * 0.04f + 64f

        canvas.drawText("Guitar Player", contentL, curY + titlePaint.textSize, titlePaint)
        curY += titlePaint.textSize + gap

        val guitarMaxH = h * 0.28f
        val guitarW = (contentW * 0.48f).toInt()
        loadGuitarBitmap(guitarW)
        guitarBitmap?.let { bmp ->
            val displayH = minOf(bmp.height.toFloat(), guitarMaxH)
            val bmpL = contentL + (contentW - bmp.width) * 0.7f
            canvas.drawBitmap(bmp, bmpL, curY, null)
            curY += displayH + gap * 3f
        }

        canvas.drawLine(contentL, curY, contentR, curY, borderPaint)
        curY += gap * 1.2f

        canvas.drawText("Select a TAB score for Rokid green display.", contentL, curY + subtitlePaint.textSize, subtitlePaint)
        curY += subtitlePaint.textSize + gap

        canvas.drawText("SCORE", contentL, curY + labelPaint.textSize, labelPaint)
        // Score count right-aligned on same row
        val countW = labelPaint.measureText(statusText)
        canvas.drawText(statusText, contentR - countW, curY + labelPaint.textSize, labelPaint)
        curY += labelPaint.textSize + gap * 0.5f

        val boxH = h * 0.055f
        val boxRect = RectF(contentL, curY, contentR, curY + boxH)
        canvas.drawRect(boxRect, selectFillPaint)
        canvas.drawRect(boxRect, selectBoxPaint)

        val currentScore = if (scores.isNotEmpty()) {
            val s = scores[selectedIndex]
            if (s.artist.isNotEmpty()) "${s.title}  —  ${s.artist}" else s.title
        } else "(none)"
        canvas.drawText(currentScore, contentL + 8f, curY + boxH * 0.65f, scoreNamePaint)

        if (scores.size > 1) {
            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = greenBright; alpha = 140
                textSize = scoreNamePaint.textSize * 0.85f; typeface = Typeface.MONOSPACE
            }
            val ax = contentR - 14f
            canvas.drawText("▲", ax, curY + boxH * 0.35f, arrowPaint)
            canvas.drawText("▼", ax, curY + boxH * 0.85f, arrowPaint)
        }
        curY += boxH + gap * 0.6f

        // Interaction hints
        val hint = if (listExpanded) "▲▼ swipe to select  ● click to confirm"
                   else "● click to expand  ▲▼ swipe to switch  ◉ long-press to enter"
        canvas.drawText(hint, contentL, curY + hintPaint.textSize, hintPaint)
        curY += hintPaint.textSize + gap

        // Line 2: score manager URL (or error)
        val serverLine = serverInfo ?: "Score Manager Error"
        val serverPaint = if (serverInfo?.startsWith("Score Manager Error") == true) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5555.toInt(); textSize = hintPaint.textSize; typeface = Typeface.MONOSPACE; alpha = 200 }
        } else {
            hintPaint
        }
        canvas.drawText(serverLine, contentL, curY + hintPaint.textSize, serverPaint)

        // --- Expanded list overlay ---
        if (listExpanded) drawExpandedList(canvas)
    }

    private fun drawExpandedList(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val boxL = w * 0.06f; val boxR = w - boxL
        val top = h * 0.35f; val bot = h * 0.78f
        val itemH = (bot - top) / minOf(scores.size, 6).coerceAtLeast(1)

        // Background
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE000000.toInt(); style = Paint.Style.FILL }
        canvas.drawRect(boxL, top, boxR, bot, bg)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = greenBright; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        canvas.drawRect(boxL, top, boxR, bot, frame)

        // Viewport scrolling: keep selectedIndex in visible window
        val visibleCount = ((bot - top) / itemH).toInt().coerceAtLeast(1)
        var offset = 0
        if (selectedIndex >= visibleCount) {
            offset = selectedIndex - visibleCount + 1
        }
        if (offset < 0) offset = 0
        val maxOffset = (scores.size - visibleCount).coerceAtLeast(0)
        if (offset > maxOffset) offset = maxOffset

        // Items
        val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = greenBright; textSize = scoreNamePaint.textSize; typeface = Typeface.MONOSPACE
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = greenPanel; style = Paint.Style.FILL
        }
        val highlightText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = greenBright; textSize = scoreNamePaint.textSize; typeface = Typeface.create("monospace", Typeface.BOLD)
        }

        for (i in offset until minOf(scores.size, offset + visibleCount)) {
            val relIdx = i - offset
            val y = top + relIdx * itemH
            val s = scores[i]
            val label = if (s.artist.isNotEmpty()) "${s.title} - ${s.artist}" else s.title
            if (i == selectedIndex) {
                canvas.drawRect(boxL + 1, y, boxR - 1, y + itemH, highlightPaint)
                canvas.drawText("  $label", boxL + 10f, y + itemH * 0.65f, highlightText)
            } else {
                canvas.drawText("  $label", boxL + 10f, y + itemH * 0.65f, itemPaint)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Key 83 is a TP contact/prelude event on the current glasses firmware,
        // not the documented long-press key. Consume both DOWN and UP.
        if (keyCode == KEYCODE_TP_CONTACT) {
            Log.d("RokidMusic", "TP contact: action=${event.action}")
            return true
        }

        // Official Rokid TP long press: KEYCODE_TV (170). Handle once and
        // consume the whole key sequence so the system cannot reuse it.
        if (keyCode == KeyEvent.KEYCODE_TV) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.d("RokidMusic", "TP long press: expanded=$listExpanded")
                onTpLongPress()
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        Log.d("RokidMusic", "key: $keyCode repeat=${event.repeatCount} expanded=$listExpanded")
        if (scores.isEmpty()) return super.dispatchKeyEvent(event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!listExpanded) return true
                val now = SystemClock.uptimeMillis()
                if (now - lastNavigationAt < SWIPE_DEBOUNCE_MS) return true
                lastNavigationAt = now
                selectedIndex = if (selectedIndex > 0) selectedIndex - 1 else scores.size - 1
                invalidate(); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!listExpanded) return true
                val now = SystemClock.uptimeMillis()
                if (now - lastNavigationAt < SWIPE_DEBOUNCE_MS) return true
                lastNavigationAt = now
                selectedIndex = if (selectedIndex < scores.size - 1) selectedIndex + 1 else 0
                invalidate(); return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount != 0) return true
                if (listExpanded) {
                    listExpanded = false; invalidate()
                } else {
                    lastNavigationAt = 0L
                    listExpanded = true; invalidate()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (listExpanded) {
                    listExpanded = false; invalidate()
                } else {
                    (context as? android.app.Activity)?.finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun onTpLongPress() {
        if (!listExpanded && scores.isNotEmpty()) enterScore()
    }

    private fun enterScore() {
        if (scores.isEmpty()) return
        val score = scores[selectedIndex]
        statusText = "Selected: ${score.title}"
        invalidate()
        onScoreSelected(score)
    }
}
