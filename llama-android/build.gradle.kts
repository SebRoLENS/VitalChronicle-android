plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val spirvHeadersDir = providers.environmentVariable("SPIRV_HEADERS_DIR").orNull
val glslcPath = providers.environmentVariable("GLSLC_PATH").orNull

android {
    namespace = "com.arm.aichat"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    // Vendor-neutral Android GPU fallback. ggml builds this as a
                    // dynamically discoverable backend beside the CPU variants.
                    "-DGGML_VULKAN=ON",
                    "-DGGML_VULKAN_RUN_TESTS=OFF",
                )
                // Cross-compiling Vulkan needs host shader tools. CI resolves
                // these paths from installed packages; local builds can either
                // set the same variables or let CMake discover an installed SDK.
                spirvHeadersDir?.takeIf { it.isNotBlank() }?.let {
                    arguments += "-DSPIRV-Headers_DIR=$it"
                }
                glslcPath?.takeIf { it.isNotBlank() }?.let {
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$it"
                }
            }
        }
    }

    sourceSets.named("main") {
        java.srcDir("../third_party/llama.cpp/examples/llama.android/lib/src/main/java")
        manifest.srcFile("../third_party/llama.cpp/examples/llama.android/lib/src/main/AndroidManifest.xml")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
