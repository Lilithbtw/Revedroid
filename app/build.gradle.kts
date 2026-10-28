import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

// Release signing comes from keystore.properties locally or REVEDROID_* env vars
// in CI. Without either, release builds are produced unsigned.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use(::load)
    }

fun signingValue(
    key: String,
    env: String,
): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotEmpty() }

// The version comes from the release tag: CI passes -PrevedroidVersion=1.2.3 for tag
// v1.2.3, and local builds fall back to the newest v* tag in the checkout, skipping
// the v1.2.3-fdroid tags of the F-Droid releases.
val appVersion: String =
    providers.gradleProperty("revedroidVersion").orNull
        ?: providers
            .exec {
                commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "--exclude", "*-fdroid")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
            .removePrefix("v")
            .ifEmpty { "0.0.1" }

// 1.2.3 becomes 1002003, so each release tag installs as an upgrade over the last.
// Releases before this scheme all used versionCode 1.
val appVersionCode: Int =
    appVersion
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
        .plus(listOf(0, 0, 0))
        .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }
        .coerceAtLeast(2)

// Where the in-app updater looks for new releases; overridable for testing.
val updateFeed: String =
    providers.gradleProperty("revedroidUpdateFeed").orNull
        ?: "https://api.github.com/repos/Lilithbtw/Revedroid/releases/latest"

android {
    namespace = "app.revedroid"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.revedroid"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_FEED", "\"$updateFeed\"")
    }

    // "github" builds update themselves from GitHub releases. "fdroid" builds leave the
    // updater out (no install permission, no release checks), as F-Droid requires.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    // The dependency metadata block is encrypted for Google Play, which doesn't ship this
    // app, and F-Droid rejects APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "REVEDROID_KEYSTORE_PATH")?.let { storeFile = rootProject.file(it) }
            storePassword = signingValue("storePassword", "REVEDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "REVEDROID_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "REVEDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        // A separate package id lets a debug build sit next to the release app
        // instead of clashing with its signature.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // CI uploads the SARIF report to GitHub code scanning.
        sarifReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ktlint {
    version.set(libs.versions.ktlintEngine)
    android.set(true)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
