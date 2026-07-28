# Cocos2d-x 3.6 math build patch

TwinQuill keeps `vendor/deps/krkr/cocos2d-x-3.6` byte-identical to the
selected files from the pinned upstream tree. CMake applies the patch in this
directory only to a generated copy inside the ignored native build directory.

`0001-modern-ndk-armv7-math.patch` removes the obsolete Android
`cpu-features.h` dependency and keeps ARMv7 on the portable scalar math path.
The arm64 NEON implementation remains enabled. This lets the pinned math code
build with the modern Android NDK without changing the audited snapshot.
