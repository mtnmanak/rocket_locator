// Pure-Kotlin domain module: NMEA parsing, geodesy, flight state, simulation.
// Must never depend on Android APIs — keeps it unit-testable and KMP-ready.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
