import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key lives in the (private) repo so every build, local or CI, is signed
// with the same key and updates install over older versions. CJ_KEYSTORE* environment
// variables override it if you ever move the key into CI secrets.
val signingProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystorePath: String = System.getenv("CJ_KEYSTORE") ?: rootProject.file("signing/clipjoiner-release.jks").path

android {
    namespace = "io.github.kaustubhowmick.clipjoiner"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kaustubhowmick.clipjoiner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("CJ_KEYSTORE_PASSWORD") ?: signingProps.getProperty("storePassword")
            keyAlias = System.getenv("CJ_KEY_ALIAS") ?: signingProps.getProperty("keyAlias")
            keyPassword = System.getenv("CJ_KEY_PASSWORD") ?: signingProps.getProperty("keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same key as release, so the debug APK used by the emulator tests is
            // interchangeable with the release one on a device.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.kotlin_module", "META-INF/*.version", "META-INF/**/LICENSE*", "META-INF/**/NOTICE*", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
