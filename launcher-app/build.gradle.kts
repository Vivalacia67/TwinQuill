plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.twinquill.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.twinquill"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"

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
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
}
