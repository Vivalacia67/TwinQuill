plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.twinquill.engine.krkr"
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
                arguments += "-DANDROID_STL=c++_shared"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

androidComponents.onVariants { variant ->
    // Only the source-policy audited fallback font and its license are bundled.
    variant.sources.assets?.addStaticSourceDirectory(rootProject.file("third_party/fonts").absolutePath)
}

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":native-vfs"))
    testImplementation("junit:junit:4.13.2")
}
