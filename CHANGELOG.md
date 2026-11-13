# Changelog

## Unreleased

## 0.0.1-alpha01 (2026-11-16)

- First release. Restores and saves the size, position and placement of caller-owned classic Compose Desktop windows through `rememberWinKeepState` and `WinKeepEffect`.
- `saveOnClose()` is the terminal close hook and `save()` writes the current geometry without closing; both return a `WindowSaveResult`.
- `minimumSize` raises restored and default sizes before they are clamped to the screen.
- The default store keeps one record per window in Java Preferences; `WindowStateStore` allows custom backends and `clear()` resets a window.
- On Linux X11, a restored floating window no longer grows by the decoration insets on every launch.
- Published as Java 17 bytecode.
