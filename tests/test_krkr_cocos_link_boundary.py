"""Fixtures for the generated Krkr Cocos link-boundary checker."""

from __future__ import annotations

import sys
import subprocess
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from check_krkr_cocos_link_boundary import (  # noqa: E402
    LinkBoundaryError,
    parse_link_items,
    verify_build,
    verify_needed_names,
)


GOOD_LINK = (
    "libtwinquill_krkr_tjs2.a D:/Project/TwinQuill/native-vfs/build/obj/arm64-v8a/"
    "libtwinquill_native_vfs.so -landroid -llog "
    "-Wl,--whole-archive libtwinquill_krkr_cocos_core.a "
    "-Wl,--no-whole-archive libonig.a libtwinquill_krkr_libpng.a "
    "libtwinquill_krkr_zlib.a generated/freetype/libtwinquill_krkr_freetype.a "
    "generated/libjpeg-turbo/libtwinquill_krkr_libjpeg.a -lm"
)


@contextmanager
def build_fixture(link: str):
    with tempfile.TemporaryDirectory(prefix="twinquill-link-boundary-") as temporary:
        directory = Path(temporary)
        (directory / "build.ninja").write_text(
            "build out/libtwinquill_engine_krkr.so: CXX_SHARED_LIBRARY_LINKER__twinquill_engine_krkr_Debug\n"
            f"  LINK_LIBRARIES = {link}\n",
            encoding="utf-8",
        )
        yield directory


class KrkrCocosLinkBoundaryTests(unittest.TestCase):
    def test_allowed_transitive_closure(self) -> None:
        with build_fixture(GOOD_LINK) as fixture:
            verify_build(fixture)

    def test_atomic_is_rejected_even_if_archive_closure_is_complete(self) -> None:
        with self.assertRaises(LinkBoundaryError):
            with build_fixture(GOOD_LINK + " -latomic") as fixture:
                verify_build(fixture)

    def test_unknown_system_library_is_rejected(self) -> None:
        for library in ("-lvulkan", "-lEGL"):
            with self.subTest(library=library):
                with self.assertRaises(LinkBoundaryError):
                    with build_fixture(GOOD_LINK + " " + library) as fixture:
                        verify_build(fixture)

    def test_missing_approved_dependency_is_rejected(self) -> None:
        with self.assertRaises(LinkBoundaryError):
            with build_fixture(GOOD_LINK.replace("libtwinquill_krkr_libpng.a ", "")) as fixture:
                verify_build(fixture)

    def test_legacy_archive_names_are_rejected_when_substituted_for_direct_targets(self) -> None:
        direct_to_legacy = (
            (
                "generated/libjpeg-turbo/libtwinquill_krkr_libjpeg.a",
                "generated/libjpeg-turbo/libjpeg.a",
            ),
            (
                "generated/freetype/libtwinquill_krkr_freetype.a",
                "generated/freetype/libfreetype.a",
            ),
            (
                "generated/freetype/libtwinquill_krkr_freetype.a",
                "generated/freetype/libfreetyped.a",
            ),
        )
        for direct_archive, legacy_archive in direct_to_legacy:
            with self.subTest(legacy_archive=legacy_archive):
                substituted = GOOD_LINK.replace(direct_archive, legacy_archive)
                with self.assertRaises(LinkBoundaryError) as context:
                    with build_fixture(substituted) as fixture:
                        verify_build(fixture)
                self.assertIn(legacy_archive, str(context.exception))

    def test_core_must_be_whole_archived(self) -> None:
        malformed = GOOD_LINK.replace(
            "-Wl,--whole-archive libtwinquill_krkr_cocos_core.a -Wl,--no-whole-archive",
            "libtwinquill_krkr_cocos_core.a",
        )
        with self.assertRaises(LinkBoundaryError):
            with build_fixture(malformed) as fixture:
                verify_build(fixture)

    def test_needed_ceiling(self) -> None:
        verify_needed_names({"libc++_shared.so", "libtwinquill_native_vfs.so", "libGLESv2.so", "liblog.so"})
        with self.assertRaises(LinkBoundaryError):
            verify_needed_names({"libc++_shared.so", "libOpenSLES.so"})
        with self.assertRaises(LinkBoundaryError):
            verify_needed_names({"libc++_shared.so", "libEGL.so"})

    def test_ninja_escapes_and_windows_backslashes_are_preserved(self) -> None:
        escaped = (
            r"libtwinquill_krkr_tjs2.a C:\\Program$ Files\\native-vfs\\"
            r"libtwinquill_native_vfs.so -landroid -llog "
            r"-Wl,--whole-archive C:\\obj$:\\cocos\\libtwinquill_krkr_cocos_core.a "
            r"-Wl,--no-whole-archive libonig.a libtwinquill_krkr_libpng.a "
            r"libtwinquill_krkr_zlib.a generated/freetype/libtwinquill_krkr_freetype.a "
            r"generated/libjpeg-turbo/libtwinquill_krkr_libjpeg.a -lm $$literal"
        )
        with build_fixture(escaped) as fixture:
            with self.assertRaises(LinkBoundaryError) as context:
                verify_build(fixture)
            self.assertIn("bare token", str(context.exception))

        allowed_escaped = (
            r"libtwinquill_krkr_tjs2.a C:\\Program$ Files\\native-vfs\\"
            r"libtwinquill_native_vfs.so -landroid -llog "
            r"-Wl,--whole-archive C:\\obj$:\\cocos\\libtwinquill_krkr_cocos_core.a "
            r"-Wl,--no-whole-archive libonig.a libtwinquill_krkr_libpng.a "
            r"libtwinquill_krkr_zlib.a generated/freetype/libtwinquill_krkr_freetype.a "
            r"generated/libjpeg-turbo/libtwinquill_krkr_libjpeg.a -lm"
        )
        with build_fixture(allowed_escaped) as fixture:
            items = parse_link_items(fixture)
            self.assertIn(r"C:\\Program Files\\native-vfs\\libtwinquill_native_vfs.so", items)
            self.assertIn(r"C:\\obj:\\cocos\\libtwinquill_krkr_cocos_core.a", items)
            verify_build(fixture)

    def test_unknown_direct_so_object_rsp_and_flag_are_rejected(self) -> None:
        for suffix in (" unknown.so", " unknown.o", " unknown.obj", " @objects.rsp", " -fuse-ld=lld"):
            with self.subTest(suffix=suffix):
                with self.assertRaises(LinkBoundaryError):
                    with build_fixture(GOOD_LINK + suffix) as fixture:
                        verify_build(fixture)

    def test_duplicate_archive_is_rejected(self) -> None:
        with self.assertRaises(LinkBoundaryError):
            with build_fixture(GOOD_LINK + " libtwinquill_krkr_libpng.a") as fixture:
                verify_build(fixture)

    def test_unique_link_variable_without_target_rule_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="twinquill-link-boundary-") as temporary:
            fixture = Path(temporary)
            (fixture / "build.ninja").write_text(
                f"  LINK_LIBRARIES = {GOOD_LINK}\n",
                encoding="utf-8",
            )
            with self.assertRaises(LinkBoundaryError):
                verify_build(fixture)

    def test_target_followed_by_other_edge_before_variable_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="twinquill-link-boundary-") as temporary:
            fixture = Path(temporary)
            (fixture / "build.ninja").write_text(
                "build out/libtwinquill_engine_krkr.so: CXX_SHARED_LIBRARY_LINKER__twinquill_engine_krkr_Debug\n"
                "build out/other.so: CXX_SHARED_LIBRARY_LINKER__other_Debug\n"
                f"  LINK_LIBRARIES = {GOOD_LINK}\n",
                encoding="utf-8",
            )
            with self.assertRaises(LinkBoundaryError):
                verify_build(fixture)

    def test_duplicate_target_rules_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="twinquill-link-boundary-") as temporary:
            fixture = Path(temporary)
            rule = "build out/libtwinquill_engine_krkr.so: CXX_SHARED_LIBRARY_LINKER__twinquill_engine_krkr_Debug\n"
            (fixture / "build.ninja").write_text(
                rule + f"  LINK_LIBRARIES = {GOOD_LINK}\n" + rule,
                encoding="utf-8",
            )
            with self.assertRaises(LinkBoundaryError):
                verify_build(fixture)

    def test_duplicate_link_variables_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="twinquill-link-boundary-") as temporary:
            fixture = Path(temporary)
            (fixture / "build.ninja").write_text(
                "build out/libtwinquill_engine_krkr.so: CXX_SHARED_LIBRARY_LINKER__twinquill_engine_krkr_Debug\n"
                f"  LINK_LIBRARIES = {GOOD_LINK}\n"
                f"  LINK_LIBRARIES = {GOOD_LINK}\n",
                encoding="utf-8",
            )
            with self.assertRaises(LinkBoundaryError):
                verify_build(fixture)

    def test_cli_requires_final_so_and_llvm_readelf(self) -> None:
        script = ROOT / "scripts" / "check_krkr_cocos_link_boundary.py"
        result = subprocess.run(
            [sys.executable, str(script), "--help"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--final-so", result.stdout)
        self.assertIn("--llvm-readelf", result.stdout)


if __name__ == "__main__":
    unittest.main()
