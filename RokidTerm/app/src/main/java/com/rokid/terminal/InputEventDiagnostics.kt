package com.rokid.terminal

import android.util.Log
import android.view.KeyEvent

/**
 * Development-only raw key metadata for hardware profiling.
 *
 * Deliberately excludes unicodeChar, characters, draft text, and terminal text so
 * typing or remote content cannot leak into logcat.
 */
object InputEventDiagnostics {
    private const val TAG = "RokidTermInput"

    fun log(event: KeyEvent, interactionState: String) {
        val device = event.device
        val phase = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> event.action.toString()
        }
        val candidate = when (event.keyCode) {
            KeyEvent.KEYCODE_CAMERA -> "SHUTTER_DELETE"
            KeyEvent.KEYCODE_FOCUS -> "SHUTTER_FOCUS_UNMAPPED"
            KeyEvent.KEYCODE_BACK -> "ANDROID_BACK"
            KeyEvent.KEYCODE_DEL -> "KEYBOARD_BACKSPACE"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "PRIMARY"
            KeyEvent.KEYCODE_NOTIFICATION -> "UNASSIGNED_VENDOR_EVENT"
            KeyEvent.KEYCODE_TV -> "LEGACY_LONG_PRESS"
            else -> "UNMAPPED_OR_PRINTABLE"
        }

        Log.i(
            TAG,
            buildString {
                append("phase=").append(phase)
                append(" state=").append(interactionState)
                append(" keyCode=").append(event.keyCode)
                append('(').append(KeyEvent.keyCodeToString(event.keyCode)).append(')')
                append(" scanCode=").append(event.scanCode)
                append(" repeat=").append(event.repeatCount)
                append(" deviceId=").append(event.deviceId)
                append(" source=0x").append(event.source.toString(16))
                append(" meta=0x").append(event.metaState.toString(16))
                append(" flags=0x").append(event.flags.toString(16))
                append(" candidate=").append(candidate)
                if (device != null) {
                    append(" device=").append(device.name)
                    append(" vendor=").append(device.vendorId)
                    append(" product=").append(device.productId)
                }
            },
        )
    }
}
