// :core is a plain Kotlin/JVM library on purpose.
//
// It holds every piece of logic that does not need an Android device: the
// provider adapters, the diff engine, the agent loop, capability scoring, the
// workspace file layer and the checkpoint store. Keeping it off the Android
// plugin means the whole of it runs under `./gradlew :core:test` on any
// machine, which is where the bulk of this project's test coverage lives.
//
// Constraint: nothing here may use an API that is missing on Android 7 (API
// 24). In practice that rules out java.nio.file and java.time. Use java.io.File
// and epoch millis instead. `ApiLevelCompatibilityTest` enforces this by
// scanning the compiled classes.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
    sourceSets.all {
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

sourceSets {
    named("main") { kotlin.srcDirs("src/main/kotlin") }
    named("test") { kotlin.srcDirs("src/test/kotlin") }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
