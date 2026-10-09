# Release gates

Publish only after the [physical acceptance](platform-testing.md) checklist has been run.

## Local artifacts

The publication contains a JVM jar, POM, Gradle module metadata, sources jar and Dokka HTML javadoc jar. OS-native graphics binaries are supplied only by the application runtime.

## Public release

Verify the intended owner/repository slug, Central namespace, signing/account access and approved version. Keep credentials out of source/docs.

After physical acceptance, publish the approved version, then verify that the public jar, metadata, sources and docs are available.
