package com.rokid.terminal

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Thin adapter around Android's exported RecognitionService contract.
 *
 * Transcript text is deliberately never logged. The callback split between
 * partial and final text lets the composer replace one active hypothesis span
 * instead of appending every partial result.
 */
class SpeechInput(
    private val context: Context,
    private val onState: (String) -> Unit,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
) : RecognitionListener {
    data class Capability(
        val permissionGranted: Boolean,
        val androidReportsAvailable: Boolean,
        val serviceCount: Int,
        val provider: ComponentName?,
    ) {
        val isAvailable: Boolean
            get() = androidReportsAvailable || provider != null

        fun safeSummary(): String = buildString {
            append("permission=")
            append(permissionGranted)
            append(" androidAvailable=")
            append(androidReportsAvailable)
            append(" services=")
            append(serviceCount)
            append(" provider=")
            append(provider?.flattenToShortString() ?: "none")
        }
    }

    private val capability = inspectCapability(context)
    private val recognizer: SpeechRecognizer? = if (capability.isAvailable) {
        runCatching {
            val instance = capability.provider?.let {
                SpeechRecognizer.createSpeechRecognizer(context, it)
            } ?: SpeechRecognizer.createSpeechRecognizer(context)
            instance.apply { setRecognitionListener(this@SpeechInput) }
        }.onFailure {
            Log.w(TAG, "ASR recognizer creation failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    } else {
        null
    }
    private var listening = false
    private var acceptingResults = false

    init {
        Log.i(TAG, "ASR capability: ${capability.safeSummary()} created=${recognizer != null}")
    }

    val isAvailable: Boolean
        get() = recognizer != null

    val isListening: Boolean
        get() = listening

    fun unavailableStatus(): String = when {
        capability.serviceCount == 0 -> "VOICE UNAVAILABLE / NO ASR SERVICE"
        recognizer == null -> "VOICE UNAVAILABLE / ASR INIT FAILED"
        else -> "VOICE UNAVAILABLE / USE KEYBOARD"
    }

    fun start() {
        val activeRecognizer = recognizer
        if (activeRecognizer == null) {
            onState(unavailableStatus())
            return
        }
        if (listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        acceptingResults = true
        listening = true
        onState("LISTENING / SPEAK NOW")
        runCatching { activeRecognizer.startListening(intent) }
            .onFailure {
                listening = false
                acceptingResults = false
                Log.w(TAG, "ASR start failed: ${it.javaClass.simpleName}")
                onState("ASR START FAILED / CLICK RETRY")
            }
    }

    fun toggle() {
        if (listening) {
            listening = false
            onState("TRANSCRIBING")
            recognizer?.stopListening()
        } else {
            start()
        }
    }

    fun cancel() {
        if (!listening && !acceptingResults) return
        listening = false
        acceptingResults = false
        recognizer?.cancel()
    }

    fun destroy() {
        listening = false
        acceptingResults = false
        recognizer?.destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (acceptingResults) onState("LISTENING / SPEAK NOW")
    }

    override fun onBeginningOfSpeech() {
        if (acceptingResults) onState("LISTENING / HEARING")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (!acceptingResults) return
        listening = false
        onState("TRANSCRIBING")
    }

    override fun onError(error: Int) {
        if (!acceptingResults) return
        listening = false
        acceptingResults = false
        onState("ASR ${errorName(error)} / CLICK RETRY")
    }

    override fun onResults(results: Bundle?) {
        if (!acceptingResults) return
        listening = false
        acceptingResults = false
        val text = bestTranscript(results)
        if (text.isNotBlank()) {
            onFinalText(text)
        } else {
            onState("NO SPEECH / CLICK TO RETRY")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!acceptingResults) return
        val text = bestTranscript(partialResults)
        if (text.isNotBlank()) onPartialText(text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun bestTranscript(results: Bundle?): String = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()

    companion object {
        private const val TAG = "RokidTerminal"
        private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

        fun inspectCapability(context: Context): Capability {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val services = context.packageManager.queryIntentServices(intent, 0)
                .filter { result ->
                    result.serviceInfo?.let { it.enabled && it.exported } == true
                }
            val configured = Settings.Secure.getString(
                context.contentResolver,
                VOICE_RECOGNITION_SERVICE,
            )?.let(ComponentName::unflattenFromString)
            val discovered = services.firstOrNull { result ->
                val info = result.serviceInfo
                configured != null && info.packageName == configured.packageName && info.name == configured.className
            }?.serviceInfo ?: services.firstOrNull()?.serviceInfo
            val provider = discovered?.let { ComponentName(it.packageName, it.name) }
            return Capability(
                permissionGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                androidReportsAvailable = SpeechRecognizer.isRecognitionAvailable(context),
                serviceCount = services.size,
                provider = provider,
            )
        }

        private fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO ERROR"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT ERROR"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC DENIED"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK ERROR"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
            SpeechRecognizer.ERROR_SERVER -> "SERVER ERROR"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH TIMEOUT"
            else -> "ERROR $error"
        }
    }
}
