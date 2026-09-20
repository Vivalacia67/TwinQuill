"""Static B1 native-host boundary checks.

These checks deliberately inspect only first-party runtime sources and the
small CMake seam. They do not claim device execution; that evidence belongs to
the Android instrumentation stage.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "engine-krkr" / "src" / "main" / "cpp"
STATE_HEADER = CPP / "krkr_runtime_state.h"
STATE_SOURCE = CPP / "krkr_runtime_state.cpp"
COCOS_HEADER = CPP / "krkr_cocos_runtime.h"
COCOS_SOURCE = CPP / "krkr_cocos_runtime.cpp"
JNI_SOURCE = CPP / "krkr_runtime_jni.cpp"
CORE_CMAKE = CPP / "krkr_cocos_core.cmake"
LINK_CHECKER = ROOT / "scripts" / "check_krkr_cocos_link_boundary.py"


class KrkrRuntimeNativeTests(unittest.TestCase):
    def test_owned_native_sources_exist(self) -> None:
        for path in (
            STATE_HEADER,
            STATE_SOURCE,
            COCOS_HEADER,
            COCOS_SOURCE,
            JNI_SOURCE,
        ):
            self.assertTrue(path.is_file(), path)

    def test_jni_exports_match_private_java_contract(self) -> None:
        text = JNI_SOURCE.read_text(encoding="utf-8")
        methods = (
            "nativeCreate",
            "nativeSurfaceCreated",
            "nativeSurfaceChanged",
            "nativeDrawFrame",
            "nativePause",
            "nativeResume",
            "nativeLowMemory",
            "nativeSurfaceLost",
            "nativeDestroy",
            "nativeTouch",
            "nativeKey",
            "nativeCounters",
        )
        for method in methods:
            self.assertIn(
                f"Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_{method}",
                text,
            )
        self.assertEqual(len(re.findall(r"Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_", text)), len(methods))
        self.assertIn("nativeCounters", text)
        self.assertIn("NewLongArray", text)

    def test_state_registry_and_queue_are_bounded(self) -> None:
        header = STATE_HEADER.read_text(encoding="utf-8")
        source = STATE_SOURCE.read_text(encoding="utf-8")
        self.assertIn("kInputQueueCapacity = 64U", header)
        self.assertIn("std::array<InputEvent, kInputQueueCapacity>", source)
        self.assertIn("std::mutex g_registry_mutex", source)
        self.assertIn("std::unordered_map<RuntimeHandle, std::shared_ptr<RuntimeState>>", source)
        self.assertIn("g_next_handle++", source)
        self.assertIn("return kRuntimeStaleHandle", source)
        self.assertIn("queue_size_ >= kInputQueueCapacity", source)
        self.assertIn("++counters_.dropped_input_count", source)
        self.assertIn("++counters_.input_generation", source)

    def test_counter_order_is_frozen(self) -> None:
        header = STATE_HEADER.read_text(encoding="utf-8")
        source = STATE_SOURCE.read_text(encoding="utf-8")
        fields = (
            "frame_count",
            "input_generation",
            "pause_count",
            "resume_count",
            "low_memory_count",
            "surface_generation",
            "surface_loss_count",
            "dropped_input_count",
            "queued_input_count",
        )
        positions = [header.index(f"std::uint64_t {field}") for field in fields]
        self.assertEqual(positions, sorted(positions))
        for index, field in enumerate(fields):
            output_index = index if index < 7 else index + 1
            self.assertIn(f"output[{output_index}] = {field};", source)
        self.assertIn("output[7] = global_destroy_count;", source)
        self.assertIn("output[10] = rejected_operation_count;", source)
        self.assertIn("constexpr std::size_t kCounterCount = 11U", header)
        self.assertIn("jlongArray>(environment, nullptr", (JNI_SOURCE).read_text(encoding="utf-8"))

    def test_gl_seam_uses_only_admitted_shaders(self) -> None:
        source = COCOS_SOURCE.read_text(encoding="utf-8")
        self.assertIn('#include "cocos/renderer/ccShader_PositionColor.vert"', source)
        self.assertIn('#include "cocos/renderer/ccShader_PositionColor.frag"', source)
        self.assertIn('"uniform mat4 CC_MVPMatrix;\\n"', source)
        self.assertIn("kIdentityMvp", source)
        self.assertIn("GL_TRIANGLE_STRIP", source)
        self.assertIn("a_position", source)
        self.assertIn("a_color", source)
        self.assertIn(
            "glDisableVertexAttribArray(static_cast<GLuint>(color_attribute_))",
            source,
        )
        self.assertIn(
            "glVertexAttrib4fv(static_cast<GLuint>(color_attribute_), color)",
            source,
        )
        self.assertNotIn(
            "glVertexAttribPointer(static_cast<GLuint>(color_attribute_)",
            source,
        )
        self.assertIn("0.18F", source)
        self.assertIn("0.95F", source)
        for blocked in ("CCRenderer", "Director", "GLViewImpl", "ccShaders.cpp", "EGL"):
            self.assertNotIn(blocked, source)
        self.assertIn("GLES2/gl2.h", source)

    def test_context_rebuild_and_context_free_cleanup_are_explicit(self) -> None:
        header = COCOS_HEADER.read_text(encoding="utf-8")
        source = COCOS_SOURCE.read_text(encoding="utf-8")
        state = STATE_SOURCE.read_text(encoding="utf-8")
        self.assertIn("int abandon_context() noexcept;", header)
        self.assertIn("(void)abandon_context();", source)
        self.assertIn("int CocosRuntime::abandon_context() noexcept", source)
        abandon_body = source.split("int CocosRuntime::abandon_context() noexcept", 1)[1]
        abandon_body = abandon_body.split("int CocosRuntime::destroy()", 1)[0]
        self.assertNotRegex(abandon_body, r"\bgl[A-Z]")
        destroy_body = state.split("int destroy(RuntimeHandle handle)", 1)[1]
        destroy_body = destroy_body.split("int touch(", 1)[0]
        self.assertIn("retired_state = found->second;", destroy_body)
        self.assertIn("g_registry.erase(found);", destroy_body)
        self.assertIn("++g_global_destroy_count;", destroy_body)
        self.assertNotIn("state->destroy()", destroy_body)
        self.assertNotIn("renderer_.abandon_context()", destroy_body)
        self.assertNotIn("std::lock_guard<std::mutex> lock(mutex_)", destroy_body)
        self.assertNotIn("destroyed_", state)
        self.assertNotIn("int destroy()", state)
        self.assertIn("surface_active_ = false", state)
        self.assertNotIn("renderer_.destroy()", state)

    def test_global_counter_and_stale_operation_contract_is_frozen(self) -> None:
        state = STATE_SOURCE.read_text(encoding="utf-8")
        header = STATE_HEADER.read_text(encoding="utf-8")
        jni = JNI_SOURCE.read_text(encoding="utf-8")
        self.assertIn("g_global_destroy_count", state)
        self.assertIn("g_rejected_operation_count", state)
        self.assertIn("if (handle == 0U)", state)
        self.assertIn("output[7] = global.destroy_count", state)
        self.assertIn("output[10] = global.rejected_operation_count", state)
        self.assertIn("record_rejected_operation();", state)
        self.assertIn("void record_rejected_operation() {", state)
        self.assertIn("GlobalCounterSnapshot global_counter_snapshot() {", state)
        self.assertIn(
            "bool snapshot(std::uint64_t* output, std::size_t count) const {",
            state,
        )
        self.assertIn("std::shared_ptr<RuntimeState> find_state(RuntimeHandle handle) {", state)
        self.assertIn("int call_state(RuntimeHandle handle, Call&& call) {", state)
        self.assertIn("std::size_t count) {", state)
        self.assertNotIn("void record_rejected_operation() noexcept", state)
        self.assertNotIn("GlobalCounterSnapshot global_counter_snapshot() noexcept", state)
        self.assertNotIn(
            "bool snapshot(std::uint64_t* output, std::size_t count) const noexcept",
            state,
        )
        self.assertNotIn("std::shared_ptr<RuntimeState> find_state(RuntimeHandle handle) noexcept", state)
        self.assertNotIn("int call_state(RuntimeHandle handle, Call&& call) noexcept", state)
        self.assertNotIn("std::size_t count) noexcept", state)
        self.assertIn("std::size_t count);", header)
        self.assertIn("return jni_boundary<jint>(environment, kJniExceptionResult", jni)
        self.assertIn("class UtfChars", jni)
        self.assertIn("ReleaseStringUTFChars", jni)
        self.assertIn("ExceptionCheck", jni)
        self.assertIn("SetLongArrayRegion", jni)

    def test_state_has_no_java_or_external_runtime_dependency(self) -> None:
        for path in (STATE_HEADER, STATE_SOURCE, COCOS_HEADER, COCOS_SOURCE):
            text = path.read_text(encoding="utf-8")
            for blocked in ("JNIEnv", "Activity", "TJS", "KAG", "VFS", "Director", "CCRenderer", "GLViewImpl", "EGL"):
                self.assertNotIn(blocked, text, f"{blocked} in {path}")

    def test_cmake_adds_only_runtime_sources_and_glesv2(self) -> None:
        cmake = CORE_CMAKE.read_text(encoding="utf-8")
        for name in (
            "krkr_runtime_state.cpp",
            "krkr_cocos_runtime.cpp",
            "krkr_runtime_jni.cpp",
        ):
            self.assertEqual(cmake.count(name), 1, name)
        self.assertIn("target_sources(twinquill_engine_krkr PRIVATE", cmake)
        self.assertIn("target_link_libraries(twinquill_engine_krkr PRIVATE GLESv2)", cmake)
        self.assertNotIn("target_link_libraries(twinquill_engine_krkr PRIVATE EGL)", cmake)
        self.assertNotIn("CCRenderer.cpp", cmake)
        self.assertNotIn("GLViewImpl", cmake)

    def test_link_checker_keeps_glesv2_inside_system_allowlist(self) -> None:
        checker = LINK_CHECKER.read_text(encoding="utf-8")
        match = re.search(r"ALLOWED_SYSTEM_LIBS\s*=\s*\{([^}]+)\}", checker)
        self.assertIsNotNone(match)
        self.assertIn('"GLESv2"', match.group(1))
        self.assertNotIn('"EGL"', match.group(1))
        self.assertNotIn('"vulkan"', match.group(1))


if __name__ == "__main__":
    unittest.main()
