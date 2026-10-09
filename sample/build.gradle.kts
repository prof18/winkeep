plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.prof18.winkeep.sample.MainKt"
    }
}
