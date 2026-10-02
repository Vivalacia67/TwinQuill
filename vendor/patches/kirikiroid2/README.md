# Kirikiroid2 build patches

TwinQuill keeps `vendor/kirikiroid2` byte-identical to its pinned upstream
snapshot. CMake applies the patches in this directory only to generated files
inside the ignored native build directory.

`0001-fix-tjs-vector-pop-back.patch` adapts two TJS2 free-list operations to
the standard `std::vector::pop_back()` API used by NDK 28 libc++. It preserves
the original behavior by reading `back()` before removing that element.

`0002-fix-tjs-free-null.patch` makes the TJS allocator's free path accept null,
including variant-stack teardown after startup errors.

`0003-tjs-execution-budget.patch` adds host-owned checkpoints to the VM opcode
loop and lexer token entry. The Android host bounds startup and callback
execution and uses the upstream silent exception to bypass script catch blocks
on cancellation. The snapshot remains unchanged.

`0004-tvp-timer-interval-bounds.patch` checks non-negative, finite intervals
up to one day before the native integer cast. The admitted upstream Timer
binding uses TwinQuill's worker-based Android timer backend.

`0005-tjs-shutdown-finalizer-cleanup.patch` retains native invalidation and
member release when a script finalizer throws or times out during host-owned
shutdown. Explicit invalidation during script execution still propagates the
original error. Timer owners are invalidated before global clearing to break
self-retaining action cycles.

`0006-android-basic-visual-bindings.patch` retains the upstream Window, Layer
and Font TJS bindings behind `TWINQUILL_ANDROID_BASIC_TVP`. The selected members
use TwinQuill's Android bitmap/font/composition backend; unsupported members
throw named errors. Desktop implementations, full RenderManager and FontSystem
are excluded in this mode. Rectangle arguments are checked before integer
addition. The patch body preserves the original CP932 bytes.

`0007-tvp-async-worker-admission.patch` admits the upstream AsyncTrigger portion
of EventIntf, registers owners for shutdown, and bounds modes and object count.
Its cached, cancel and priority behavior uses the same worker event queue as
Timer; the desktop event loop is excluded.

Validation: `tests/test_krkr_tjs2_source_admission.py` verifies patch application
and manifest digests; Android instrumentation exercises startup errors,
uncatchable loops, cancellation/relaunch, timers, pause/resume and limits.
Pixel and lifecycle tests additionally cover images, Unicode text, alpha,
standard input/close events, AsyncTrigger and surface/Activity recreation.
