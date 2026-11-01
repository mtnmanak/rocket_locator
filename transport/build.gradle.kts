plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.mtnmanak.rocketlocator26.transport"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    lint { abortOnError = true }

    // AGP 9 creates unit-test tasks late; configure JUnit 5 through the DSL so it also holds
    // under the configuration cache (a top-level tasks.withType<Test> block was missed there).
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.failOnNoDiscoveredTests.set(true) // a silently empty test run must fail the build
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}


kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
