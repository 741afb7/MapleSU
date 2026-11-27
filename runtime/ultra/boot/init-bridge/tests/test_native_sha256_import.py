"""SHA256 thunks retain the actual kernel prototype and full length value."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


BRIDGE = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get("BRIDGE_SHA256_SOURCE", BRIDGE / "native_imports.c"))
CC = os.environ.get("BRIDGE_FIXTURE_CC", "C:/msys64/ucrt64/bin/gcc.exe")


class Sha256ImportChecks(unittest.TestCase):
    def fixture(self, native_length, legacy):
        source = SOURCE.read_text()
        macros = re.findall(r"^#define IMPORT_VOID\([^\n]*\\\n(?:[^\n]*\\\n)*[^\n]*", source, re.M)
        self.assertEqual(len(macros), 2)
        start = source.index("IMPORT_VOID(sha256,")
        typedef = re.search(r"typedef typeof\(__builtin_choose_expr\([\s\S]+?msu_native_sha256_length_t;", source)
        end = source.index("#if LINUX_VERSION_CODE", start)
        declarations = source[start:end]
        return "\n".join([
            "#include <stddef.h>", "typedef unsigned char u8;",
            "#define noinline __attribute__((noinline))", "#define __used __attribute__((used))",
            "extern void sha256(const u8 *, " + native_length + ", u8 *);",
            macros[0 if legacy else 1], typedef.group() if typedef else "", declarations,
            "static size_t seen;",
            "static void actual_native(const u8 *bytes, " + native_length + " length, u8 *digest) {",
            "    seen = length; *digest = *bytes;", "}",
            "void msu_direct_target_sha256(const u8 *bytes, " + native_length + " length, u8 *digest) {",
            "    actual_native(bytes, length, digest);", "}",
            "int main(void) {",
            "    const u8 bytes = 47; u8 digest = 0;",
            "    " + native_length + " length = (" + native_length + ")0x100000007ULL;",
            "    " + ("" if legacy else "msu_native_sha256 = actual_native;"),
            "    sha256(&bytes, length, &digest);",
            "    if (seen != length || digest != bytes) return 1;",
            "    seen = 0; digest = 0; ref_sha256(&bytes, length, &digest);",
            "    return seen != length || digest != bytes;", "}",
        ])

    def compile_fixture(self, native_length, legacy, directory):
        path = directory / "sha256.c"
        path.write_text(self.fixture(native_length, legacy))
        binary = directory / "sha256.exe"
        result = subprocess.run([CC, "-std=gnu11", "-O2", "-Wall", "-Wextra", "-Werror",
                                 str(path), "-o", str(binary)], capture_output=True, text=True, timeout=25)
        return result, binary

    def test_actual_supported_native_prototypes_and_forwarding(self):
        for native_length in ("unsigned int", "size_t"):
            for legacy in (False, True):
                with self.subTest(native_length=native_length, legacy=legacy), tempfile.TemporaryDirectory(prefix="bridge-sha256-") as temp:
                    result, binary = self.compile_fixture(native_length, legacy, Path(temp))
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    subprocess.run([str(binary)], check=True, timeout=5)

    def test_other_native_prototype_still_fails_the_exact_type_gate(self):
        for legacy in (False, True):
            with self.subTest(legacy=legacy), tempfile.TemporaryDirectory(prefix="bridge-sha256-abi-") as temp:
                result, _ = self.compile_fixture("unsigned short", legacy, Path(temp))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("native host prototype: sha256", result.stderr)


if __name__ == "__main__":
    unittest.main()
