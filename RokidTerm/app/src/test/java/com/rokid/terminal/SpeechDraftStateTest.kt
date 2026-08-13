package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDraftStateTest {
    @Test
    fun `partial hypotheses replace one anchored span`() {
        val composer = InputComposerState().apply { insertText("请检查") }
        val speech = SpeechDraftState(composer)

        assertTrue(speech.updatePartial("服务器"))
        assertTrue(speech.updatePartial("服务器状态"))

        assertEquals("请检查服务器状态", composer.text)
        assertEquals(composer.text.length, composer.cursor)
        assertTrue(speech.hasActiveHypothesis)
    }

    @Test
    fun `speech inserts at current cursor and final commits it`() {
        val composer = InputComposerState().apply {
            insertText("前后")
            moveLeft()
        }
        val speech = SpeechDraftState(composer)

        speech.updatePartial("中")
        speech.finalize("中间")

        assertEquals("前中间后", composer.text)
        assertEquals("前中间".length, composer.cursor)
        assertFalse(speech.hasActiveHypothesis)
    }

    @Test
    fun `discard removes only active hypothesis`() {
        val composer = InputComposerState().apply { insertText("保留") }
        val speech = SpeechDraftState(composer)

        speech.updatePartial("临时文字")
        speech.discardActive()

        assertEquals("保留", composer.text)
        assertEquals("保留".length, composer.cursor)
    }

    @Test
    fun `committed partial remains editable`() {
        val composer = InputComposerState()
        val speech = SpeechDraftState(composer)

        speech.updatePartial("你好")
        speech.commitActive()
        composer.deletePrevious()

        assertEquals("你", composer.text)
        assertFalse(speech.hasActiveHypothesis)
    }

    @Test
    fun `finalize strips a trailing emoji run`() {
        val composer = InputComposerState()
        val speech = SpeechDraftState(composer)

        speech.finalize("你好呀😊")   // 你好呀😊
        assertEquals("你好呀", composer.text)
    }

    @Test
    fun `finalize keeps text without emoji`() {
        val composer = InputComposerState()
        val speech = SpeechDraftState(composer)

        speech.finalize("好的，谢谢。")
        assertEquals("好的，谢谢。", composer.text)
    }

    @Test
    fun `finalize keeps chinese punctuation`() {
        val composer = InputComposerState()
        val speech = SpeechDraftState(composer)

        speech.finalize("完成了✅。")   // emoji in the MIDDLE is untouched
        assertEquals("完成了✅。", composer.text)
    }

    @Test
    fun `finalize of emoji-only text leaves empty draft`() {
        val composer = InputComposerState()
        val speech = SpeechDraftState(composer)

        speech.finalize("👍")   // 👍
        assertEquals("", composer.text)
        assertFalse(speech.hasActiveHypothesis)
    }
}

class StripTrailingEmojiTest {

    @Test
    fun stripsMultipleEmoji() {
        assertEquals("好的", stripTrailingEmoji("好的😊👍"))  // 好的😊👍
    }

    @Test
    fun keepsPlainText() {
        assertEquals("hello world", stripTrailingEmoji("hello world"))
        assertEquals("中文结尾。", stripTrailingEmoji("中文结尾。"))
    }

    @Test
    fun stripsZwjSequence() {
        // 👨‍👩‍👧 = 1F468 200D 1F469 200D 1F467
        assertEquals("好的", stripTrailingEmoji("好的👨‍👩‍👧"))
    }

    @Test
    fun stripsSkinToneAndVariationSelector() {
        // 👍🏽 = 1F44D 1F3FD
        assertEquals("棒", stripTrailingEmoji("棒👍🏽"))
        // ❤️ = 2764 FE0F
        assertEquals("爱", stripTrailingEmoji("爱❤️"))
    }

    @Test
    fun stripsKeycapIncludingDigit() {
        // 3️⃣ = 33 FE0F 20E3
        assertEquals("", stripTrailingEmoji("3️⃣"))
        assertEquals("答案", stripTrailingEmoji("答案3️⃣"))
    }

    @Test
    fun emptyAndEmojiOnly() {
        assertEquals("", stripTrailingEmoji(""))
        assertEquals("", stripTrailingEmoji("🎉"))   // 🎉
    }

    @Test
    fun stripsTextPresentationEmojiWithVariationSelector() {
        // ©️ = 00A9 FE0F — the © is emoji only because of the FE0F.
        assertEquals("版权", stripTrailingEmoji("版权©️"))
        // ™️ = 2122 FE0F
        assertEquals("", stripTrailingEmoji("™️"))
        // Bare © without FE0F is NOT emoji — kept.
        assertEquals("版权©", stripTrailingEmoji("版权©"))
    }

    @Test
    fun stripsEmojiThenTextPresentationPair() {
        // "😊©️" — pictograph + text-emoji-with-VS: both go.
        assertEquals("好的", stripTrailingEmoji("好的😊©️"))
    }
}
