package com.rokid.terminal

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Glue between the UI (composer), the microphone, and the server ASR client.
 *
 * Flow: connect asr-fwd channel (once, when the terminal connects) ->
 * record a short utterance -> upload WAV -> final text into the speech draft.
 * The controller owns a single-thread executor so record/transcribe never
 * interleave, and exposes callbacks that land on the caller-provided (UI)
 * thread.
 */
class AsrController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "asr-controller").apply { isDaemon = true }
    }
    private val recorder = AudioRecorder()
    @Volatile
    private var client: ServerAsrClient? = null

    val isListening: Boolean
        get() = recorder.isRecording

    /** Connect to the ASR service for the given endpoint. Call once per session. */
    fun connect(endpoint: EndpointProfile) {
        val profile = AsrProfile.fromEndpoint(endpoint)
        Log.i(TAG, "ASR connect: entering for ${profile.id} @ ${profile.host}:${profile.port}")
        executor.execute {
            Log.i(TAG, "ASR connect: task running")
            try {
                val identity = DeviceKeyStore(context, profile.id).getOrCreate()
                Log.i(TAG, "ASR connect: identity ok, public=${identity.publicKey.take(40)}...")
                val newClient = ServerAsrClient(profile, identity)
                client = newClient
                newClient.connect(object : ServerAsrClient.Listener {
                    override fun onStatus(status: String) {
                        // NOTE: must qualify with this@AsrController — inside an
                        // anonymous object, an unqualified `onStatus` resolves to
                        // the object's own method (infinite message loop).
                        Log.i(TAG, "ASR listener onStatus: $status")
                        if (status == "ASR READY") {
                            // The forward is up. Warm the server model now so the
                            // first real transcription is fast: CONNECTING ->
                            // MODEL LOADING -> READY.
                            post { this@AsrController.onStatus("ASR MODEL LOADING") }
                            executor.execute {
                                val ok = warmUpModel(newClient)
                                post {
                                    if (ok) {
                                        this@AsrController.onStatus("ASR READY")
                                    } else {
                                        this@AsrController.onError("ASR MODEL LOAD FAIL")
                                    }
                                }
                            }
                            return
                        }
                        post { this@AsrController.onStatus(status) }
                    }

                    override fun onResult(text: String) {
                        post { this@AsrController.onResult(text) }
                    }

                    override fun onError(message: String) {
                        post { this@AsrController.onError(message) }
                    }
                })
            } catch (error: Exception) {
                post { onError("ASR KEY ERROR: ${error.message}") }
            }
        }
    }

    /** Start a recording round. Call when the composer speech path is used. */
    fun startRecording(): Boolean {
        return if (recorder.isRecording) {
            true
        } else {
            recorder.start()
        }
    }

    /** Stop recording and transcribe. Callbacks arrive on the UI thread. */
    fun stopAndTranscribe() {
        executor.execute {
            val wav = recorder.stop()
            if (wav.isEmpty()) {
                post { onError("NO AUDIO / MIC MUTED") }
                return@execute
            }
            val current = client
            if (current == null) {
                post { onError("ASR NOT READY") }
                return@execute
            }
            if (!current.isConnected) {
                post { onError("ASR NOT CONNECTED") }
                return@execute
            }
            post { onStatus("TRANSCRIBING") }
            try {
                val text = current.transcribe(wav)
                if (text.isBlank()) {
                    post { onError("NO SPEECH RECOGNIZED") }
                } else {
                    post { onResult(text) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "ASR transcribe failed", error)
                post { onError("ASR FAILED: ${error.message ?: "unknown"}") }
            }
        }
    }

    /** Cancel recording without transcribing. */
    fun cancelRecording() {
        executor.execute { recorder.cancel() }
    }

    /**
     * Post a silent WAV until the server model answers. Retries cover the
     * race where uvicorn was just restarted by the login hook and is not yet
     * listening on 127.0.0.1:8765: the app module import (funasr/torch) can
     * take 15-25 s before the listener is up, so the backoff window must be
     * several times longer than the boot.
     */
    private fun warmUpModel(client: ServerAsrClient): Boolean {
        for (attempt in 0 until WARMUP_ATTEMPTS) {
            if (client.warmup()) return true
            val delay = WARMUP_BACKOFF_MS[minOf(attempt, WARMUP_BACKOFF_MS.size - 1)]
            Thread.sleep(delay)
        }
        return false
    }

    fun disconnect() {
        executor.execute {
            client?.destroy()
            client = null
        }
    }

    fun destroy() {
        disconnect()
        executor.shutdown()
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    companion object {
        private const val TAG = "RokidTerminal"
        private const val WARMUP_ATTEMPTS = 10
        private val WARMUP_BACKOFF_MS = longArrayOf(
            1_000, 1_000, 2_000, 2_000, 3_000, 3_000, 5_000, 5_000, 10_000, 10_000,
        )
    }
}
