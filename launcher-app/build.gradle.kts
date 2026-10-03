plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val krkrRuntimeInstrumentation =
    providers.gradleProperty("twinquillKrkrRuntimeInstrumentation")
        .orNull
        ?.toBoolean() == true
val androidTestTargetProcess =
    if (krkrRuntimeInstrumentation) "io.github.twinquill:krkr" else "io.github.twinquill"
val krkrRuntimeTestClass = "io.github.twinquill.engine.krkr.KrkrRuntimeHostInstrumentedTest"
val krkrBrokerLifecycleTestClass = "io.github.twinquill.launcher.KrkrBrokerLifecycleInstrumentedTest"
val krkrRuntimeTestClasses = listOf(
    krkrRuntimeTestClass,
    krkrBrokerLifecycleTestClass
).joinToString(",")

abstract class KagTestAssets : Copy() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val assetsDirectory: org.gradle.api.file.DirectoryProperty
}
val prepareKagTestAssets by tasks.registering(KagTestAssets::class) {
    from(rootProject.file("vendor/kag3-1f3ab309"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/kag-test-assets"))
    into(layout.buildDirectory.dir("generated/kag-test-assets/kag3"))
}
abstract class M4TestAssets : Exec() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val assetsDirectory: org.gradle.api.file.DirectoryProperty
}
val prepareM4TestAssets by tasks.registering(M4TestAssets::class) {
    val destination = layout.buildDirectory.dir("generated/m4-test-assets")
    assetsDirectory.set(destination)
    inputs.files(rootProject.file("scripts/create_krkr_m4_fixtures.py"),
        rootProject.file("scripts/create_krkr_m3_fixtures.py"))
    inputs.dir(rootProject.file("vendor/kag3-1f3ab309"))
    inputs.dir(rootProject.file("tests/fixtures/krkr-m4"))
    val windows = System.getProperty("os.name").startsWith("Windows")
    val environmentPython = rootProject.file(if (windows) ".venv/Scripts/python.exe" else ".venv/bin/python")
    val python = providers.gradleProperty("twinquillPython").orElse(
        if (environmentPython.isFile) environmentPython.absolutePath else if (windows) "python" else "python3")
    workingDir(rootProject.projectDir)
    commandLine(python.get(), rootProject.file("scripts/create_krkr_m4_fixtures.py").absolutePath,
        "--output", destination.get().asFile.absolutePath)
}
androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareKagTestAssets) { it.assetsDirectory }
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareM4TestAssets) { it.assetsDirectory }
}

android {
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("tests/fixtures/krkr-m3"))
    namespace = "io.github.twinquill.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.twinquill"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["twinquillTargetProcesses"] = androidTestTargetProcess
        if (krkrRuntimeInstrumentation) {
            testInstrumentationRunnerArguments["class"] = krkrRuntimeTestClasses
        } else {
            testInstrumentationRunnerArguments["notClass"] = krkrRuntimeTestClasses
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        javaCompileOptions {
            annotationProcessorOptions {
                argument("room.schemaLocation", "$projectDir/schemas")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":native-vfs"))
    implementation(project(":engine-ons"))
    implementation(project(":engine-krkr"))

    implementation("androidx.lifecycle:lifecycle-livedata:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addStaticSourceDirectory(
        rootProject.file("tests/fixtures/krkr-m2").absolutePath
    )
}
