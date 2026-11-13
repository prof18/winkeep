# Platform testing

## Automated native tests

```sh
./gradlew -q --console=plain nativeTest --rerun
```

`nativeTest` opens real decorated windows on the current display with JetBrains Runtime 21 and fails, rather than skips, without display access. It covers:

- `ClassicWindowContractNativeTest`: a test-local probe of the recording policy on a real classic `Window`. Maximize and fullscreen keep the normal bounds, minimize/restore and hide/show do not replace them, and a resize immediately followed by close is captured before disposal.
- `WinKeepEffectNativeTest`: the real effect across three cycles with two independent windows, including that every WinKeep listener is removed when a window leaves composition.
- `WindowRestartNativeTest`: bounds written in one JVM are restored in a fresh JVM through the default Preferences store.

These tests run on one machine and do not cover moving physical monitors, other operating systems, other JDK vendors or Wayland.

On Linux, `nativeTest` sets `awt.robot.screenshotMethod=x11`. On a Wayland session, `Robot` otherwise captures the screen through the desktop portal, which waits for a permission dialog and blocks the run. Under XWayland those captures come back black, so the tests check fullscreen by bounds there instead of by pixels.

On Windows, Compose enters fullscreen through `GraphicsDevice.setFullScreenWindow`, which sizes the decorated frame to the screen and keeps its title bar and borders. The screen edges then show what is behind the window, so the tests check fullscreen by bounds there too.

They were run on Ubuntu 26.04 with GNOME 50, where windows run through XWayland, on JetBrains Runtime 25 and OpenJDK 21:

- The WinKeep effect and restart tests pass on JetBrains Runtime 25. On OpenJDK 21 the window manager sometimes keeps a window at the fullscreen size after it leaves fullscreen, past WinKeep's hold.
- GNOME can return a window from minimized shifted or resized by its title bar on both runtimes. The classic probe skips that check on Linux, and the README lists it as a limitation.

On Windows 11 Pro over a Remote Desktop session, `check` and all three native tests pass in three runs out of three on both JetBrains Runtime 21 and Eclipse Temurin 21. On macOS they also pass on Azul Zulu 21.

## Coordinate space

In Compose Multiplatform 1.12.1, the classic `WindowState` copies AWT `x`, `y`, `width` and `height` directly into `WindowPosition` and `DpSize`. These are the decorated outer bounds in AWT user-space units, the same space as `GraphicsConfiguration.getBounds()` and screen insets. WinKeep stores and restores those numbers without applying any display scale. Content density is separate.

## Manual checklist

Use the sample (`./gradlew -q --console=plain :sample:run`). For each run, note the commit, OS and version, JDK, desktop session (for example X11 or Wayland), monitor layout and scales, the observed outer bounds, and pass or fail.

| Check | macOS | Windows | Linux X11 |
| --- | --- | --- | --- |
| Move and resize, close, reopen: same bounds | [ ] | [ ] | [ ] |
| Native title-bar maximize and restore keep the normal bounds | [ ] | [ ] | [ ] |
| Close while fullscreen, reopen: fullscreen, and leaving it returns to the normal bounds | [ ] | [ ] | [ ] |
| Close while minimized, reopen: visible, not minimized | [ ] | [ ] | [ ] |
| Resize and close immediately: final size kept | [ ] | [ ] | [ ] |
| The two sample windows keep separate geometry | [ ] | [ ] | [ ] |
| "Save now" returns `Saved` and the geometry survives a force quit right after | [ ] | [ ] | [ ] |
| Monitor left of the primary: negative x kept | [ ] | [ ] | [ ] |
| Monitor above the primary: negative y kept | [ ] | [ ] | [ ] |
| Unplugged monitor: window reopens fully inside a remaining screen | [ ] | [ ] | [ ] |
| Rearranged monitor: offset from its work area kept, title bar reachable | [ ] | [ ] | [ ] |
| Mixed display scales: no double scaling | [ ] | [ ] | [ ] |
| Window fits inside taskbar, dock and menu bar work areas | [ ] | [ ] | [ ] |

### Linux decoration insets

On X11, a window can receive corrected decoration insets after it is shown, which grows it by the inset delta. WinKeep sets the restored size back once when the first post-show resize is exactly that delta. The decision logic is unit-tested, but the behaviour needs checking on a real Linux X11 session:

- [ ] Reopen the sample several times without touching it: the size does not grow on each launch.
- [ ] Resize by hand right after the window appears: the new size is kept.
- [ ] Reopen a window saved as maximized: no correction is applied.

Native Wayland does not support window positions; record what happens there, but it is not a supported configuration.
