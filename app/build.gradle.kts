import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Optional release signing. Create keystore.properties (git-ignored) with
// storeFile / storePassword / keyAlias / keyPassword to sign locally. Without
// it the release build is produced unsigned, which is what CI wants.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val enableAbiSplits = providers.gradleProperty("forge.abiSplits").orNull?.toBoolean() ?: true

android {
    namespace = "com.myfactory.forge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.myfactory.forge"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Every shipped ABI. armeabi-v7a is the whole point of this fork: a
        // 32-bit-only phone must get a working build, not "app not compatible".
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        resourceConfigurations += listOf("en", "fr", "sw", "ar")
    }

    if (enableAbiSplits) {
        splits {
            abi {
                isEnable = true
                reset()
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                // Keep the universal APK: sideloaders and F-Droid style
                // distribution need one file that works everywhere.
                isUniversalApk = true
            }
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time / java.nio.file are API 26+. Desugaring is not enabled;
        // :core is written against the API 24 surface instead so that the
        // 32-bit low-end path carries no extra dex weight.
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Compose tests resolve strings, themes and drawables, so the unit
        // test JVM needs the real merged resources rather than stubs.
        unitTests.isIncludeAndroidResources = true
    }
}

// Stamp each split APK with a distinct versionCode so a store can serve the
// right one. Universal keeps the base code.
if (enableAbiSplits) {
    val abiCodes = mapOf("armeabi-v7a" to 1, "x86" to 2, "arm64-v8a" to 3, "x86_64" to 4)
    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val abi = output.filters.find {
                    it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
                }?.identifier
                val offset = abiCodes[abi] ?: 0
                val base = output.versionCode.orNull ?: 1
                output.versionCode.set(base * 10 + offset)
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":runtime-linux"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Robolectric lets the Compose tests press real buttons on the JVM. Without
    // it these would be instrumented tests, which need hardware and so would
    // not run in CI on every push - which is exactly how a screen full of
    // dead controls goes unnoticed.
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.graphics)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
