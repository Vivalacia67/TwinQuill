#pragma once

// NDK r28 marks ALooper_pollAll unavailable. Include the declarations before
// defining the compatibility alias so the macro does not rewrite the legacy
// declaration itself.
#include <android/looper.h>

#define ALooper_pollAll ALooper_pollOnce
