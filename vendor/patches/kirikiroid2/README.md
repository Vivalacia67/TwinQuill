# Kirikiroid2 build patches

TwinQuill keeps `vendor/kirikiroid2` byte-identical to its pinned upstream
snapshot. CMake applies the patches in this directory only to generated files
inside the ignored native build directory.

`0001-fix-tjs-vector-pop-back.patch` adapts two TJS2 free-list operations to
the standard `std::vector::pop_back()` API used by NDK 28 libc++. It preserves
the original behavior by reading `back()` before removing that element.
