package com.rokid.game.flappy

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class GameView(context: Context) : View(context), SensorEventListener {

    private val stroke = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val fill = Paint().apply { color = Color.GREEN; style = Paint.Style.FILL; isAntiAlias = true }
    private val scoreP = Paint().apply { color = Color.GREEN; textSize = 48f; isAntiAlias = true }
    private val titleP = Paint().apply { color = Color.GREEN; textSize = 56f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val msgP = Paint().apply { color = Color.GREEN; textSize = 44f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val menuP = Paint().apply { color = Color.GREEN; textSize = 36f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val smallP = Paint().apply { color = Color.GREEN; textSize = 26f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

    private val handler = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            tick()
            invalidate()
            handler.postDelayed(this, 16)
        }
    }

    // === Sensor ===
    private var sm: SensorManager? = null
    private var pitch = 0f
    private var pitch0 = 0f
    private var ready = false
    private var stype = 0

    // === Screens ===
    private enum class Screen { TITLE, LEADERBOARD, COUNTDOWN, PLAYING, DEAD }
    private var screen = Screen.TITLE
    private var menuSel = 0
    private var countFrame = 0
    private var animFrame = 0

    // === Bird ===
    private var birdY = 0f
    private var birdV = 0f
    private val birdX = 160f
    private val birdR = 20f
    private var wingFrame = 0

    // === Pipes ===
    private data class Pipe(var x: Float, val gapY: Float, var scored: Boolean = false)
    private val pipes = mutableListOf<Pipe>()
    private val pipeW = 52f
    private val pipeGap = 300f
    private val pipeSpeed = 5.4f
    private val pipeEvery = 65
    private var pipeTick = pipeEvery - 30

    // === Score ===
    private var score = 0

    // === Screen ===
    private var sw = 0
    private var sh = 0
    private val ground = 4f

    // === Flap detection ===
    private var dPrev = 0f
    private var trendUp = false
    private var trough = 0f
    private var armed = true
    private val flapRise = 3f
    private val grav = 0.4f
    private val flapV = -12f
    private val maxV = 10f

    // === City ===
    private val buildings = mutableListOf<Float>()
    private var lastClickTime = 0L

    // === Prefs ===
    private val prefs: SharedPreferences = context.getSharedPreferences("flappy_prefs", Context.MODE_PRIVATE)

    init { isFocusable = true; isFocusableInTouchMode = true }

    fun startSensors() {
        sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var s = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) stype = Sensor.TYPE_ROTATION_VECTOR
        else { s = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR); if (s != null) stype = Sensor.TYPE_GAME_ROTATION_VECTOR }
        sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        handler.post(loop)
    }

    fun stopSensors() { sm?.unregisterListener(this); handler.removeCallbacks(loop) }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == stype && stype > 0) {
            val m = FloatArray(9); SensorManager.getRotationMatrixFromVector(m, e.values)
            val o = FloatArray(3); SensorManager.getOrientation(m, o)
            pitch = o[1]; ready = true
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onKeyDown(kc: Int, ev: KeyEvent): Boolean {
        when (kc) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (screen) {
                    Screen.TITLE -> {
                        if (menuSel == 0) startGame() else screen = Screen.LEADERBOARD
                    }
                    Screen.LEADERBOARD -> { screen = Screen.TITLE; menuSel = 0 }
                    Screen.DEAD -> {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < 400) { screen = Screen.TITLE; menuSel = 0 }
                        lastClickTime = now
                        handler.postDelayed({
                            if (System.currentTimeMillis() - lastClickTime >= 400 && screen == Screen.DEAD) startGame()
                        }, 420)
                    }
                    else -> {}
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                when (screen) {
                    Screen.LEADERBOARD, Screen.DEAD -> { screen = Screen.TITLE; menuSel = 0 }
                    Screen.TITLE -> (context as? android.app.Activity)?.finish()
                    else -> {}
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (screen == Screen.TITLE) menuSel = maxOf(menuSel - 1, 0); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (screen == Screen.TITLE) menuSel = minOf(menuSel + 1, 1); return true
            }
        }
        return super.onKeyDown(kc, ev)
    }

    private fun startGame() {
        birdY = sh / 2f; birdV = 0f
        pipes.clear(); pipeTick = pipeEvery - 30
        score = 0; wingFrame = 0
        countFrame = 0; animFrame = 0
        pitch0 = pitch
        dPrev = 0f; trendUp = false; trough = 0f; armed = true
        screen = Screen.COUNTDOWN
    }

    private fun tick() {
        animFrame++
        when (screen) {
            Screen.COUNTDOWN -> { countFrame++; if (countFrame > 160) screen = Screen.PLAYING }
            Screen.PLAYING -> updateGame()
            else -> {}
        }
    }

    private fun updateGame() {
        wingFrame = (wingFrame + 1) % 12
        val diff = Math.toDegrees((pitch - pitch0).toDouble()).toFloat()

        if (diff > dPrev + 0.3f) {
            if (!trendUp) { armed = true; trough = dPrev }
            trendUp = true
            if (armed && diff > trough + flapRise) { birdV = flapV; armed = false }
        } else if (diff < dPrev - 0.3f) {
            trendUp = false
        }
        dPrev = diff

        birdV += grav
        birdV = birdV.coerceIn(-maxV, maxV)
        birdY += birdV

        pipeTick++
        if (pipeTick >= pipeEvery) {
            pipeTick = 0
            val margin = pipeGap / 2 + 40f
            val gapY = margin + (Math.random() * (sh - 2 * margin)).toFloat()
            pipes.add(Pipe(sw.toFloat(), gapY))
        }
        val it = pipes.iterator()
        while (it.hasNext()) {
            val p = it.next(); p.x -= pipeSpeed
            if (!p.scored && p.x + pipeW < birdX) { p.scored = true; score++ }
            if (p.x + pipeW < 0) it.remove()
        }

        val half = birdR * 0.7f
        if (birdY - half <= 0 || birdY + half >= sh - ground) { die(); return }
        for (p in pipes) {
            if (birdX + half > p.x && birdX - half < p.x + pipeW) {
                val top = p.gapY - pipeGap / 2
                val bot = p.gapY + pipeGap / 2
                if (birdY - half < top || birdY + half > bot) { die(); return }
            }
        }
    }

    private fun die() {
        screen = Screen.DEAD
        if (score > 0) {
            val json = prefs.getString("scores", "[]") ?: "[]"
            val arr = JSONArray(json)
            arr.put(JSONObject().apply {
                put("score", score); put("time", System.currentTimeMillis())
            })
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                .sortedByDescending { it.getInt("score") }.take(5)
            val na = JSONArray(); list.forEach { na.put(it) }
            prefs.edit().putString("scores", na.toString()).apply()
        }
    }

    private fun getTopScores() = try {
        val arr = JSONArray(prefs.getString("scores", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); sw = w; sh = h; birdY = sh / 2f
        buildings.clear(); var x = 0f
        while (x < sw + 80) {
            buildings.add(40f + Math.random().toFloat() * (sh * 0.35f)); x += 50f + Math.random().toFloat() * 40f
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        when (screen) {
            Screen.TITLE -> drawTitle(c)
            Screen.LEADERBOARD -> drawLeaderboard(c)
            Screen.COUNTDOWN -> { drawGame(c); drawCountdown(c) }
            Screen.PLAYING -> drawGame(c)
            Screen.DEAD -> { drawGame(c); drawDeath(c) }
        }
    }

    private fun drawTitle(c: Canvas) {
        var x = 0f
        for (h in buildings) { c.drawRect(x, sh - h, x + 30f, sh - ground, fill); x += 80f }
        c.drawText("FLAPPY BIRD", sw / 2f, sh * 0.25f, titleP)
        val bob = kotlin.math.sin(animFrame * 0.08).toFloat() * 12f
        drawBird(c, sw / 2f, sh * 0.25f + 90f + bob)
        val items = listOf("▶  START", "♛  RANKING")
        val my = sh * 0.65f
        for ((i, item) in items.withIndex()) {
            val y = my + i * 60f
            c.drawText(if (i == menuSel) "> $item <" else item, sw / 2f, y, menuP)
        }
        c.drawText("Swipe to choose · Click to select · Back to exit", sw / 2f, sh - 50f, smallP)
    }

    private fun drawLeaderboard(c: Canvas) {
        c.drawText("RANKING", sw / 2f, 60f, titleP)
        val scores = getTopScores()
        if (scores.isEmpty()) c.drawText("No scores yet", sw / 2f, sh / 2f - 30f, msgP)
        else {
            val p = Paint().apply { color = Color.GREEN; textSize = 32f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            for ((i, s) in scores.withIndex()) {
                val sc = s.getInt("score")
                val tm = s.getLong("time")
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(tm))
                c.drawText("#${i + 1}    $sc    $date", sw / 2f, 140f + i * 65f, p)
            }
        }
        c.drawText("Click or Back to return", sw / 2f, sh - 50f, smallP)
    }

    private fun drawCountdown(c: Canvas) {
        val num = when { countFrame < 40 -> "3"; countFrame < 80 -> "2"; countFrame < 120 -> "1"; else -> "GO!" }
        val alpha = ((countFrame % 40) / 40f * 255).roundToInt().coerceIn(0, 255)
        val p = Paint().apply { color = Color.argb(alpha, 0, 255, 0); textSize = 100f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        c.drawText(num, sw / 2f, sh / 2f + 20f, p)
    }

    private fun drawGame(c: Canvas) {
        c.drawRect(0f, sh - ground, sw.toFloat(), sh.toFloat(), fill)
        for (p in pipes) {
            val top = p.gapY - pipeGap / 2; val bot = p.gapY + pipeGap / 2
            c.drawRect(p.x, 0f, p.x + pipeW, top, stroke)
            c.drawRect(p.x, bot, p.x + pipeW, sh - ground, stroke)
        }
        drawBird(c, birdX, birdY)
        c.drawText("$score", 20f, 52f, scoreP)
    }

    private fun drawDeath(c: Canvas) {
        c.drawText("Score: $score", sw / 2f, sh / 2f - 45f, msgP)
        c.drawText("Click to retry", sw / 2f, sh / 2f + 15f, msgP)
        c.drawText("Double-click or Back for menu", sw / 2f, sh / 2f + 55f, smallP)
    }

    private fun drawBird(c: Canvas, cx: Float, cy: Float) {
        val wu = wingFrame < 6; val flap = if (wu) -birdR * 0.6f else birdR * 0.3f
        c.drawCircle(cx, cy, birdR, fill)
        val ex = cx + birdR * 0.3f; val ey = cy - birdR * 0.35f
        c.drawCircle(ex, ey, birdR * 0.25f, Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = true })
        c.drawCircle(ex, ey, birdR * 0.1f, fill)
        val beak = Path().apply { moveTo(cx + birdR * 0.7f, cy); lineTo(cx + birdR * 1.5f, cy - birdR * 0.1f); lineTo(cx + birdR * 0.7f, cy + birdR * 0.15f); close() }
        c.drawPath(beak, fill)
        val wing = Path().apply { moveTo(cx - birdR * 0.1f, cy - birdR * 0.2f); lineTo(cx - birdR * 0.4f, cy - birdR * 1.1f + flap); lineTo(cx + birdR * 0.3f, cy + birdR * 0.3f); close() }
        c.drawPath(wing, fill)
    }
}
