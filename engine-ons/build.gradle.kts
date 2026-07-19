plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.twinquill.engine.ons"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
    }

    lint {
        // Freeze findings inherited from the pinned SDL2 2.26.3 Android glue.
        // New findings outside these exact source locations still fail lint.
        baseline = file("lint-baseline.xml")
    }

    sourceSets {
        getByName("main") {
            // Compile SDL's Android glue from the pinned source snapshot. This
            // is source input, not a prebuilt Android or native dependency.
            java.directories.add(
                "../vendor/deps/ons/SDL2-2.26.3/android-project/app/src/main/java"
            )
        }
    }
}

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":native-vfs"))
}
