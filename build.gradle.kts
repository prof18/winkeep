
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
}

group = "com.prof18"
version = "0.0.1-alpha01"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(compose.runtime)
    api(compose.ui)
    api(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.settings)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.settings.test)
    testImplementation(libs.coroutines.test)
    testImplementation(compose.desktop.currentOs)
}

tasks.test {
    exclude("**/*NativeTest*")
    systemProperty("java.awt.headless", "true")
    // M1 has native fixtures only; require discovery once ordinary tests exist.
    failOnNoDiscoveredTests =
        fileTree("src/test") {
            include("**/*Test.kt")
            exclude("**/*NativeTest.kt")
        }.files.isNotEmpty()
}

tasks.register<Test>("nativeTest") {
    group = "verification"
    description = "Runs real desktop window contracts against the current display."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*NativeTest*")
    systemProperty("java.awt.headless", "false")
    // NativeTransitions waits for macOS fullscreen animations through this JDK-internal listener.
    if (System.getProperty("os.name").startsWith("Mac")) {
        jvmArgs("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED", "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
    }
    maxParallelForks = 1
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.JETBRAINS)
        },
    )
    testLogging.showStandardStreams = true
}
