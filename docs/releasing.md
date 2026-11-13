# Releasing

The version lives in one place: `VERSION_NAME` in [gradle.properties](../gradle.properties). Group and POM metadata sit next to it. Publishing uses the `com.vanniktech.maven.publish` plugin, which produces the jar, sources jar, Dokka javadoc jar, POM and Gradle module metadata.

## 1. Verify locally

Run the gates on a machine with a live desktop:

```sh
./gradlew -q --console=plain ktlintCheck check compileKotlin :sample:compileKotlin
./gradlew -q --console=plain nativeTest --rerun
```

`nativeTest` includes an end-to-end journey: one JVM opens, moves and closes a real window, and a fresh JVM restores it from Preferences at the same bounds.

Run the [platform checklist](platform-testing.md) on the platforms you have.

## 2. Publish to Maven Central

1. Move the `Unreleased` entries in [CHANGELOG.md](../CHANGELOG.md) under the new version and date, and set `VERSION_NAME`.
2. Commit, tag the commit (for example `0.0.1-alpha01`) and push the tag.
3. Create a GitHub release for the tag. The [publish workflow](../.github/workflows/publish-library.yaml) runs on published releases and pre-releases, or manually with a tag input. It runs `publishAndReleaseToMavenCentral` with the `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `SIGNING_KEY` and `SIGNING_PASSWORD` repository secrets. Signing is required only for Maven Central tasks.
4. Once Central shows the version, check that the jar, POM, module, sources and javadoc are downloadable, and that a project without the local repository resolves it.
