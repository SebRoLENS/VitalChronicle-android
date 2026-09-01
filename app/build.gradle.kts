plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val releaseKeystorePath = providers.environmentVariable("VC_ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("VC_ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("VC_ANDROID_KEY_ALIAS").orNull

android {
    namespace = "io.github.sebrolens.vitalchronicle.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sebrolens.vitalchronicle.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.2.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (
            !releaseKeystorePath.isNullOrBlank() &&
            !releaseKeystorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                // VitalChronicle intentionally uses the same password for the
                // keystore and key entry, reducing the number of CI secrets.
                keyPassword = releaseKeystorePassword
            }
        }
    }

    val stableReleaseSigning = signingConfigs.findByName("release")
    buildTypes {
        getByName("release") {
            stableReleaseSigning?.let { signingConfig = it }
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("python3.13")
    }
}

dependencies {
    // Compose 1.10 is the newest stable generation which remains compatible
    // with compileSdk 36 / AGP 8.x. Compose 1.12 requires SDK 37 and AGP 9.
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // genai-prompt beta4 calls the coroutines 1.11 JVM-default ABI from its
    // download Flow. Resolving 1.10.x crashes at runtime with NoSuchMethodError.
    val coroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:21.5.0")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
}
