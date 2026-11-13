import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    `java-library`
}

// Coordinates, version and POM metadata live in gradle.properties (GROUP, VERSION_NAME, POM_*).

kotlin {
    // Build and test on JDK 21, but publish Java 17 bytecode against the JDK 17 API.
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

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
    // On a Wayland session Robot otherwise captures through the screencast portal, which blocks on a permission dialog.
    if (System.getProperty("os.name").startsWith("Linux")) {
        systemProperty("awt.robot.screenshotMethod", "x11")
    }
    timeout.set(Duration.ofMinutes(20))
    maxParallelForks = 1
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.JETBRAINS)
        },
    )
    testLogging.showStandardStreams = true
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
}

signing {
    setRequired { gradle.taskGraph.allTasks.any { it.name.contains("MavenCentral") } }
}
