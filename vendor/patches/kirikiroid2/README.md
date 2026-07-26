# Kirikiroid2 build patches

TwinQuill keeps `vendor/kirikiroid2` byte-identical to its pinned upstream
snapshot. CMake applies the patches in this directory only to generated files
inside the ignored native build directory.

`0001-fix-tjs-vector-pop-back.patch` adapts two TJS2 free-list operations to
the standard `std::vector::pop_back()` API used by NDK 28 libc++. It preserves
the original behavior by reading `back()` before removing that element.
`0002-narrow-kag-parser-platform-includes.patch` narrows the generated
KAGParser copy to TwinQuill's M3 Android shim: it uses the upstream storage
interface, replaces the message/event surface with first-party declarations,
and removes parser-unneeded plugin, charset, and transition includes. The full
parser implementation still comes from the pinned upstream source.
