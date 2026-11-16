# AGENTS.md

WinKeep is a Compose Desktop library that saves and restores the geometry of caller-owned classic windows. It is published as `com.prof18:winkeep` on Maven Central.

## Docs

| Page | Open it when |
| --- | --- |
| [README](README.md) | You need the user-facing contract: installation, the four steps, saving, errors, limitations. |
| [docs/architecture.md](docs/architecture.md) | You change any source file: the file map and the launch-to-close flow. |
| [docs/api.md](docs/api.md) | You change or rely on a public type, its validation, threading or error reporting. |
| [docs/storage.md](docs/storage.md) | You touch the default store, the record format, the schema, debounce or flush. |
| [docs/platform-testing.md](docs/platform-testing.md) | You change native behaviour or the native tests, or run them on another OS or JDK. |
| [docs/releasing.md](docs/releasing.md) | You bump the version or publish to Maven Central. |

## Gates

Run every Gradle command with `-q --console=plain`.

```sh
./gradlew -q --console=plain ktlintCheck check compileKotlin :sample:compileKotlin
./gradlew -q --console=plain nativeTest --rerun
```

The first is the headless gate CI runs; it includes the ABI check against `api/winkeep.api`. Run `./gradlew -q --console=plain updateKotlinAbi` only when you change the public API on purpose. `nativeTest` opens real windows: it needs a live, unlocked desktop and a JetBrains Runtime 21 toolchain. A single red `nativeTest` run on the studio Mac can be timing noise; rerun it before treating it as a regression.

## Rules

- Public model classes stay plain classes with hand-written `equals`, `hashCode` and `toString`. A data class would put `copy` and `componentN` into the ABI. Update all three methods when you add a field.
- Write KDoc and comments only where they add something the signature does not say: contracts, threading, failure modes, the reason for a choice.
- Native tests wait for the OS to finish a transition (`NativeTransitions`) and drive only `WindowState`. Never set `window.placement` and `WindowState.placement` together, and never assert right after a request.
- Keep this repository free of FeedFlow or any other consumer's integration code, docs and local machine paths. Consumers use the published artifact.
- Move user-facing changes into `CHANGELOG.md` under `Unreleased`.

## Plans

A plan lives under `plan/` (never committed) only while it is being built. Its last task updates the `docs/` pages its feature changed and records new decisions; then the plan is deleted.
