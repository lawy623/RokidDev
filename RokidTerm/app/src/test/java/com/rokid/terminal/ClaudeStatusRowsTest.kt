package com.rokid.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Render-suppression vs history-filter separation (2026-08-14).
 *
 * History must never contain status rows (capture/purge use isStatusRow);
 * but the RENDER path must not freeze rows that carry information — the
 * ticking elapsed timer. Pure animation (spinner/thinking rows with no
 * timer) may freeze. User report 2026-08-14: after the "· Ns" content rule
 * started classifying the tool timer as status, hasRenderableChange
 * suppressed its repaints and the elapsed time froze until the next
 * non-status change — the user could not tell whether the tool had
 * finished.
 */
class ClaudeStatusRowsTest {

    @Test
    fun spinnerOnlyRowsAreRenderSuppressible() {
        assertTrue(ClaudeStatusRows.isRenderSuppressible("✻ Combobulating…"))
        assertTrue(ClaudeStatusRows.isRenderSuppressible("✻ Combobulating… (thinking…)"))
        assertTrue(ClaudeStatusRows.isRenderSuppressible("● Running 1 shell command…"))
    }

    @Test
    fun timerRowsAreNotRenderSuppressible() {
        assertFalse(ClaudeStatusRows.isRenderSuppressible("● Generating LOD1 at 50% decimation · 1m 40s"))
        assertFalse(ClaudeStatusRows.isRenderSuppressible("✻ Combobulating… (1m 10s · thought for 4s)"))
        assertFalse(ClaudeStatusRows.isRenderSuppressible("✻ Brewed for 3m 59s"))
        assertFalse(ClaudeStatusRows.isRenderSuppressible("    && ls -la hkustgz_low.* (2m 5s)"))
    }

    @Test
    fun contentRowsAreNotRenderSuppressible() {
        assertFalse(ClaudeStatusRows.isRenderSuppressible("提交成功 3s"))
        assertFalse(ClaudeStatusRows.isRenderSuppressible("❯ 请等待 3m 59s 后重试"))
    }
}
