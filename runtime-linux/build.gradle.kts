// :runtime-linux is the only module that touches native code.
//
// It contains two separable things:
//   1. libforgepty.so - a small JNI shim around forkpty(3). Built here from
//      source for every shipped ABI. This is what makes the terminal a real
//      terminal rather than a line-buffered command runner.
//   2. The PRoot bootstrap - download, verify and unpack a Linux rootfs, then
//      launch commands inside it. PRoot and BusyBox themselves are *not* built
//      here; see docs/NATIVE_BINARIES.md. When they are absent the module
//      reports reduced availability and the app degrades instead of failing.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val buildNative = providers.gradleProperty("forge.buildNative").orNull?.toBoolean() ?: true

android {
    namespace = "com.myfactory.forge.runtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        if (buildNative) {
            externalNativeBuild {
                cmake {
                    // c++_static keeps the .so self-contained; the shim is tiny
                    // so there is no size argument for the shared STL.
                    arguments += listOf("-DANDROID_STL=c++_static")
                    cppFlags += listOf("-fvisibility=hidden", "-Oz", "-fno-exceptions", "-fno-rtti")
                }
            }
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
    }

    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    packaging {
        jniLibs {
            // PRoot and BusyBox ship as "lib*.so" inside jniLibs so that the
            // installer places them in nativeLibraryDir, the one directory an
            // app may still exec from on API 29+. They must not be compressed
            // or stripped.
            useLegacyPackaging = false
            keepDebugSymbols += listOf("**/libproot.so", "**/libbusybox.so", "**/libloader.so")
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
}
