package com.rokid.music

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import com.rokid.music.model.TabScore
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : Activity() {

    private lateinit var startScreen: StartScreenView
    private var server: ScoreServer? = null
    private var playerView: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var infoUpdater: Runnable? = null
    private var aiStartReceiverRegistered = false

    /**
     * Current Rokid firmware converts a TP long press into an ordered
     * ACTION_AI_START broadcast before an app receives a key event. Register a
     * foreground-only, high-priority receiver so the long press belongs to
     * RokidMusic while this activity is visible, without changing system-wide
     * assistant behaviour.
     */
    private val aiStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_AI_START) return
            Log.d("RokidMusic", "Intercepted TP long-press AI broadcast")
            if (playerView != null) {
                playerView?.onTpLongPress()
            } else {
                startScreen.onTpLongPress()
            }
            if (isOrderedBroadcast) abortBroadcast()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startScreen = StartScreenView(this) { scoreEntry ->
            openPlayer(scoreEntry)
        }
        setContentView(startScreen)
        startScreen.post { startScreen.requestFocus() }

        server = ScoreServer(this) {
            runOnUiThread { startScreen.reloadScores() }
        }.also { it.start() }

        infoUpdater = object : Runnable {
            override fun run() {
                startScreen.setServerInfo(server?.infoLine() ?: "Score Manager Error")
                handler.postDelayed(this, 2000)
            }
        }
        startScreen.post { infoUpdater?.run() }
    }

    override fun onStart() {
        super.onStart()
        if (!aiStartReceiverRegistered) {
            val filter = IntentFilter(ACTION_AI_START).apply { priority = 1000 }
            registerReceiver(aiStartReceiver, filter)
            aiStartReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (aiStartReceiverRegistered) {
            try { unregisterReceiver(aiStartReceiver) } catch (_: Exception) {}
            aiStartReceiverRegistered = false
        }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercept the official TP long-press key before any system handler.
        // Key 83 is only a TP contact precursor on this firmware and is also
        // routed to the active view so it can be consumed without side effects.
        if (event.keyCode == KeyEvent.KEYCODE_TV || event.keyCode == 83) {
            if (playerView != null) {
                playerView!!.dispatchKeyEvent(event)
            } else {
                startScreen.dispatchKeyEvent(event)
            }
            return true
        }
        // Keep player click/back events inside PlayerView. On some firmware
        // the second ENTER of a TP double-click is otherwise handled by the
        // system Activity dispatcher and closes the app instead of returning
        // to the score picker.
        if (playerView != null && event.keyCode in setOf(
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BACK
            )) {
            playerView!!.dispatchKeyEvent(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Player ──────────────────────────────────────────────────────────────

    fun openPlayer(scoreEntry: ScoreEntry) {
        try {
            val json = loadScoreJson(scoreEntry.fileName)
            val score = TabScore.parse(json)
            Log.d("RokidMusic", "Opening: ${score.metadata.title} (${score.measures.size} measures)")

            playerView?.release()
            playerView = PlayerView(this, score) { closePlayer() }
            setContentView(playerView)
        } catch (e: Exception) {
            Log.e("RokidMusic", "Failed to open score: ${scoreEntry.fileName}", e)
        }
    }

    fun closePlayer() {
        playerView?.release()
        playerView = null
        setContentView(startScreen)
        startScreen.post { startScreen.requestFocus() }
        startScreen.reloadScores()
    }

    private fun loadScoreJson(fileName: String): String {
        val uploadDir = getExternalFilesDir("scores") ?: File(filesDir, "scores")
        val file = File(uploadDir, fileName)
        if (!file.exists()) throw java.io.FileNotFoundException(file.absolutePath)
        return file.readText()
    }

    override fun onDestroy() {
        super.onDestroy()
        infoUpdater?.let { handler.removeCallbacks(it) }
        server?.stop()
        playerView?.release()
    }

    companion object {
        private const val ACTION_AI_START = "com.android.action.ACTION_AI_START"
    }
}
