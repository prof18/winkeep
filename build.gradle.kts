
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

java { withSourcesJar() }
val documentationJar =
    tasks.register<Jar>("documentationJar") {
        dependsOn("dokkaGeneratePublicationHtml")
        archiveClassifier.set("javadoc")
        from(layout.buildDirectory.dir("dokka/html"))
    }
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(documentationJar)
            groupId = "com.prof18"
            artifactId = "winkeep"
            version = "0.0.1-alpha01"
            pom {
                name.set("WinKeep")
                description.set("Persistence for caller-owned classic Compose Desktop windows")
                url.set("https://github.com/prof18/winkeep")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("prof18")
                        name.set("Marco Gomiero")
                        url.set("https://github.com/prof18")
                    }
                }
                scm {
                    url.set("https://github.com/prof18/winkeep")
                    connection.set("scm:git:https://github.com/prof18/winkeep.git")
                    developerConnection.set("scm:git:ssh://git@github.com/prof18/winkeep.git")
                }
            }
        }
    }
}
