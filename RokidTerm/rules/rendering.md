# RokidTerm rules: Terminal rendering

Loaded on demand from `CLAUDE.md`.

## Terminal data pipeline

Keep remote-output processing and Canvas rendering as separate layers:

```text
SSH PTY bytes
-> continuous UTF-8 decoding in SshTerminalSession
-> TerminalOutputProcessor
-> TerminalScreen VT/ANSI state machine
-> immutable TerminalFrame
-> TerminalView Canvas rendering
```

- `SshTerminalSession` owns transport and decoding only; it must not parse terminal semantics or touch Android Views.
- `TerminalOutputProcessor` owns the mutable terminal emulator and publishes immutable frame snapshots. It has no Android dependency and should be covered by JVM tests.
- `TerminalView` is a renderer only. It must not own or mutate `TerminalScreen`.
- Keep PTY/grid/display geometry in `TerminalSpec`/`TerminalViewport` so transport and rendering cannot silently diverge. The character grid is derived from the actual Android View size, not a fixed Canvas scale.
- The processor-to-view boundary uses latest-frame coalescing: remote reads may generate frames quickly, but the UI queue must contain at most one pending drain runnable and retain the highest revision. Further dirty-row optimization belongs at this same boundary.

## Rendering constraints

- Target is 480x640 portrait, green monochrome on black.
- Keep all drawing inside the 480x640 Canvas. Keep `defaultFocusHighlightEnabled=false`, while retaining focus for hardware keys.
- SSH PTY and local screen must always use the same `TerminalViewport`. At 480x640 the result is 54 columns x 36 rows (verified on device 2026-08-06), but other View sizes produce a different grid automatically.
- Use continuous UTF-8 decoding across network reads. Never decode each SSH byte packet independently; packets may split a Chinese character.
- Chinese and other wide characters consume two terminal cells.
- View resize order is: Android View size -> `TerminalViewport` -> local `TerminalOutputProcessor.resize` -> `ChannelShell.setPtySize` -> remote SIGWINCH -> tmux/Claude redraw. Do not implement client-side reflow for a full-screen TUI; preserve the rectangular grid until the remote application redraws.
- Unicode rendering must account for supplementary code points, combining marks, ZWJ sequences, and wide-cell continuation cleanup after edits or clipping.
- `TerminalScreen` implements the subset of VT/ANSI used by tmux and Claude: cursor movement, save/restore, erase display/line/characters, insert/delete characters and lines, scrolling, and delayed autowrap.
- Treat recurring stray or missing characters as missing terminal semantics, not as font corruption. Add a focused regression test before extending the parser.
- Ordinary printable characters such as `<`, `>`, and `B` must never be filtered as a display workaround. Preserve the cross-network-chunk `ESC(B` regression behavior in the VT parser.
- `TerminalView` must render ordinary printable characters such as `<` and `>` unchanged on every row, including the tmux status row. Fix malformed status text at the tmux configuration or VT parsing layer rather than filtering glyphs in the renderer.
- The monochrome renderer ignores RGB color selection but preserves meaningful text attributes: bold, dim, underline, and inverse.
- The idle footer is one compact line. At live bottom it advertises history/input actions; in history it shows the row offset and adds `NEW OUTPUT` when remote output arrives. The local composer temporarily overlays the terminal and replaces the footer hint while it is open.
- The composer overlay is render-only: opening it must never pause SSH reads, continuous UTF-8 decoding, terminal parsing, or frame publishing. Closing the overlay reveals the latest frame, not the frame that existed when it opened. (`NEW OUTPUT`/history behavior is unaffected while the composer is open.)

## Claude Code input-line integration (verified 2026-08-05)

- Claude Code's input prompt is `❯` (U+276F), NOT `>`, and it is NOT the
  last grid row: the layout is input line, then the `⏵⏵ bypass permissions`
  banner, then the tmux status line on the last row. **Claude also prefixes
  CONVERSATION user messages with `❯`**, so `findInputRow` must not take
  "the last ❯ row": it prefers the bottom five rows (input-line area),
  falls back to any ❯ row, then the row above the status line. While
  browsing history the input line sits at `cursor.row + offset` and
  `findInputRow` returns null once it scrolls out of view (every remaining
  ❯ row is then a conversation message). Rendering split (user decision
  2026-08-06): the INPUT LINE keeps its original look — raw `❯` glyph,
  blinking `_` cursor, NO dark fill; CONVERSATION user messages (live AND
  history) render as a small green box plus the dark block fill (inferred
  for imported rows that lost their SGR background).
- The remote cursor on the input line is static through tmux (the VT frame
  cursor sits at row/column via `ESC[27;3H`). The app draws its own
  blinking `_` (500 ms toggle) over that position and skips the frame's
  static underline cursor there (`drawBlinkingCursor`, idle state only), so
  exactly one cursor shows. A small gap (`0.35 × cellWidth`) after `❯`
  prevents the cursor from hugging the prompt glyph.
- Input-history preview (COIDEA keys 4/6, terminal mode) renders INTO the
  `❯` row: cover the row with black, draw `❯ {draft}`, never draw the `_`
  cursor, and truncate by measured pixel width (character counts are wrong
  for CJK) with `…`. The draft loads into the composer only on TP
  click / left-knob press.
- Two-finger and single-finger swipes both emit `KEYCODE_NOTIFICATION` (83)
  with DPAD pairs (`LEFT+UP`/`RIGHT+DOWN`) — see `input.md`; the renderer
  itself does not depend on 83.
- Local scrollback is bounded to 5000 rows and is fed only by Claude Code
  redraw shifts plus explicit imports (per-endpoint persistence); real
  scrolls are deliberately NOT captured (2026-08-06): primary-screen
  scrolls are login/launcher noise, and alternate-screen scrolls are
  attach-redraw artifacts that duplicate the live screen — both produced
  the "only the last round is browsable" bug. Claude Code (inside tmux)
  re-renders its TUI by overwriting cells at absolute
  positions — no scroll escapes — so the processor detects shifts by
  comparing the live screen against a baseline snapshot of the last
  settled alternate screen (`TerminalOutputProcessor.findScrollCapture`):
  the smallest k where ≥60% of rows (contiguous span ≥60%) match the
  baseline k rows below, first matched row within the top 3 rows; the
  baseline is refreshed only after ~500 ms of quiet — never mid-burst
  (the attach redraw must settle first, or partial frames fabricate
  duplicate history rows; on-device 2026-08-06) — and after each capture;
  captured rows are the baseline rows that vanished from the shifted
  region. `snapshotRows()` must return copied
  row arrays — the live arrays are mutated in place by later writes
  (2026-08-06 regression: the "before" snapshot aliased the live buffer,
  so detection compared the new screen against itself and never fired).
  Reset, resize, and CSI `3J` clear history; the baseline is cleared
  alongside.
- Historical frames hide the cursor. New output must not snap a historical viewport live; preserve the viewed position as full-screen rows arrive, including across bounded-history eviction. Returning to live clears the `NEW OUTPUT` indicator.
- Scrollback persistence is keyed per CONVERSATION (2026-08-08): files are
  `scrollback_<endpointId>_<folderKey>_<sessionId>.txt` (folderKey = the
  server's encoded project dir; sessionId = the Claude session uuid — the
  app supplies it for new conversations via `--session-id`). Bounded at
  1000 rows/file and 30 files per endpoint (LRU by mtime). Binding follows
  the conversation picker's choice; a 30 s sync watcher re-binds when the
  server's active session changes out-of-band (manual `/resume`, `/cd`).
