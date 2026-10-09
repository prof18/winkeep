# WinKeep

Window geometry and presentation persistence for caller-owned classic Compose Desktop windows.

WinKeep restores reachable decorated bounds before the Window is shown, keeps normal bounds while maximized/fullscreen, and provides an awaited close hook. It targets Kotlin 2.4.10, Compose 1.12.1 and Java 21; v1 uses AWT user-space coordinates directly, without display-transform scaling.

## Installation

The planned alpha coordinates are `com.prof18:winkeep:0.0.1-alpha01`. Public release is still a human gate. After release, add this dependency to a Compose Desktop JVM project:

```kotlin
implementation("com.prof18:winkeep:0.0.1-alpha01")
```

The library exports its Compose runtime/UI and coroutines API transitives. The application supplies its platform graphics runtime, for example `compose.desktop.currentOs`. Release steps are in [release instructions](docs/releasing.md).

## Usage

Compose a Window only after `isReady`, attach `WinKeepEffect` inside it, and await the terminal `saveOnClose` before hiding/removing it or exiting. The complete compiled sample helper is reproduced below; [the sample source](sample/src/main/kotlin/com/prof18/winkeep/sample/Main.kt) also includes its imports and a two-window application entry point.

```kotlin
@Composable
private fun persistentWindow(
    windowId: String,
    title: String,
    onClosed: () -> Unit,
    openSettings: (() -> Unit)? = null,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val state =
        rememberWinKeepState(
            appId = "com.prof18.winkeep.sample",
            windowId = windowId,
            defaultSize = DpSize(800.dp, 600.dp),
            onError = { error = "${it.operation}: ${it.cause.message}" },
        )
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                state.saveOnClose()
                // Even when persistence fails, the caller may finish closing.
                onClosed()
            }
        }
    }
    if (state.isReady) {
        Window(onCloseRequest = close, state = state.windowState, title = title) {
            WinKeepEffect(state)
            MenuBar {
                Menu("Window") {
                    WindowPlacement.entries.forEach { placement ->
                        RadioButtonItem(
                            text = placement.name,
                            selected = state.windowState.placement == placement,
                            enabled = !closing,
                            onClick = { state.windowState.placement = placement },
                        )
                    }
                    if (openSettings != null) Item("Open settings", enabled = !closing, onClick = openSettings)
                    Item("Close", enabled = !closing, onClick = close)
                }
            }
            Column(Modifier.padding(24.dp)) {
                BasicText("$title — independent window ID: $windowId")
                BasicText("Move or resize this window, then close and reopen the sample.")
                BasicText("The Window menu changes placement and opens the second window.")
                error?.let { BasicText(it) }
            }
        }
    }
}
```

Use distinct window IDs for independent windows. The same IDs retain their initial store/defaultSize throughout composition; onError updates live. Remember custom stores. A backend change requires orderly close/disposal and fresh composition. Close is terminal, repeated calls join the same result, and false permits the caller to finish closing after reporting failure.

The default lazy Preferences backend owns one hashed identity leaf and uses a compact single record. Future schemas are preserved in read-only recovery. See [API](docs/api.md), [storage and durability](docs/storage.md) and [platform checklist](docs/platform-testing.md). Physical multi-monitor and other OS acceptance remain human checks; forced termination can lose pending work.

## Development

```sh
./gradlew -q --console=plain ktlintCheck check compileKotlin :sample:compileKotlin
./gradlew -q --console=plain nativeTest --rerun
./gradlew -q --console=plain :sample:run
```

Ordinary tests are headless. Native gates require a live desktop and run freshly.

Licensed under [Apache-2.0](LICENSE).
