import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties locally (gitignored), env vars on CI
// (decoded from GitHub secrets). Absent both, release builds stay unsigned —
// still compilable, just not installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "io.github.mtnmanak.rocketlocator26"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mtnmanak.rocketlocator26"
        minSdk = 26
        targetSdk = 36
        // Beta versioning: versionName 0.NN, versionCode = NN (see CHANGELOG.md)
        versionCode = 3
        versionName = "0.03"
        resValue("string", "footer_version", "v${versionName} beta")
    }

    signingConfigs {
        val storePath = signingValue("storeFile", "SIGNING_STORE_FILE")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

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

    buildFeatures {
        compose = true
        resValues = true // footer_version is generated from versionName (AGP 9 defaults this off)
    }

    lint {
        abortOnError = true
    }
}


dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
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
