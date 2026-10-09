# Platform acceptance

Automated native checks ran on the local macOS arm64 desktop with JetBrains Runtime 21. They do not certify physical monitor movement, different OS sessions, JDK vendors or native Wayland.

Run each native gate freshly on a live display:

```sh
./gradlew -q --console=plain nativeTest --rerun
```

Tests fail without display access and do not silently skip.

## Human checklist

Record commit IDs, OS/JDK, toolkit/session, display arrangement/scales, actual decorated bounds and pass/fail screenshots/logs separately for macOS, Windows and Linux. Use the sample app.

- [ ] Native title-bar maximize/restore retains normal geometry.
- [ ] Fullscreen close/reopen restores presentation with retained normal bounds.
- [ ] Minimized exit/reopen opens visibly, without storing minimization.
- [ ] Normal resize followed immediately by close preserves final size/position.
- [ ] Two independent IDs do not share geometry or lifecycle.
- [ ] Monitor left of primary preserves legitimate negative x.
- [ ] Monitor above primary preserves legitimate negative y.
- [ ] Unplugged monitor restores fully inside a reachable usable area.
- [ ] Moved monitor preserves relative offset and reachable title bar.
- [ ] Mixed display scale avoids physical-pixel conversion or double scaling.
- [ ] Decorated bounds fit taskbar/dock/menu work areas.

v1 pairs normal bounds with their monitor; maximized/fullscreen presentation has no separately saved monitor. Record this distinction during multi-monitor tests. Native Wayland remains unsupported until independently demonstrated for that session.
