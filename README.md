# WinKeep

Compose Desktop window persistence for caller-owned classic windows.

The implementation targets Kotlin 2.4.10, Compose 1.12.1 and Java 21.
Native lifecycle feasibility is being verified before persistent contracts are implemented.

## Development

```sh
./gradlew -q --console=plain ktlintCheck check compileKotlin :sample:compileKotlin
./gradlew -q --console=plain nativeTest --rerun
```

The ordinary gate uses headless tests. Native tests require a live desktop display.

Licensed under [Apache-2.0](LICENSE).
