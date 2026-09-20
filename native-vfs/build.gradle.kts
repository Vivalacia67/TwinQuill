plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.twinquill.nativevfs"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Export the existing native VFS library and public headers through
        // Android Prefab for in-project native consumers.
        prefabPublishing = true
    }

    prefab {
        create("twinquill_native_vfs") {
            libraryName = "twinquill_native_vfs"
            headers = "src/main/cpp/include"
        }
    }
}

afterEvaluate {
    val nativeBuildDebug = tasks.named("externalNativeBuildDebug")
    tasks.named("prefabDebugConfigurePackage").configure {
        val debugNativeOutputTree = layout.buildDirectory
            .dir("intermediates/cxx/Debug")
            .map { cxxDirectory ->
                cxxDirectory.asFileTree.matching {
                    include("**/obj/**/libtwinquill_native_vfs.so")
                }
            }
        dependsOn(nativeBuildDebug)
        inputs.files(nativeBuildDebug)
        inputs.files(debugNativeOutputTree)
        outputs.upToDateWhen { false }
    }
    val nativeBuildRelease = tasks.named("externalNativeBuildRelease")
    tasks.named("prefabReleaseConfigurePackage").configure {
        val releaseNativeOutputTree = layout.buildDirectory
            .dir("intermediates/cxx/RelWithDebInfo")
            .map { cxxDirectory ->
                cxxDirectory.asFileTree.matching {
                    include("**/obj/**/libtwinquill_native_vfs.so")
                }
            }
        dependsOn(nativeBuildRelease)
        inputs.files(nativeBuildRelease)
        inputs.files(releaseNativeOutputTree)
        outputs.upToDateWhen { false }
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
