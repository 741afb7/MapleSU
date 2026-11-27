"""Compile the real path_mount declarations/thunks and preserve native errno."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

BRIDGE = Path(__file__).resolve().parents[1]
CC = os.environ.get("BRIDGE_FIXTURE_CC") or shutil.which("gcc") or "C:/msys64/ucrt64/bin/gcc.exe"


class PathMountImports(unittest.TestCase):
    def fixture(self, const, legacy, mismatch=False, missing=False):
        header = (BRIDGE / "native_imports.h").read_text()
        source = (BRIDGE / "native_imports.c").read_text()
        sdk = (BRIDGE.parents[2] / "maplesu-sdk/sdk/src/module/su_redirect_tu/kernel/su_profile_namespace.c").read_text()
        pattern = r"#ifndef MSU_PATH_MOUNT_PATH_QUALIFIER\n#error[^\n]*\n#endif\nextern int path_mount\([^;]+;"
        declaration = re.search(pattern, header)
        sdk_declaration = re.search(pattern, sdk)
        self.assertIsNotNone(declaration)
        self.assertIsNotNone(sdk_declaration)
        invocation = re.search(r"IMPORT_RETURN\(int, path_mount,.*?\(dev_name, path, type_page, flags, data_page\)\)", source, re.S)
        self.assertIsNotNone(invocation)
        macros = re.findall(r"^#define IMPORT_RETURN\([^\n]*\\\n(?:[^\n]*\\\n)*[^\n]*", source, re.M)
        qualifier = "const " if const else ""
        call = re.search(r"error = path_mount\(NULL, &root, NULL, MS_PRIVATE \| MS_REC, NULL\);", sdk)
        self.assertIsNotNone(call)
        thunk = invocation.group()
        if mismatch:
            thunk = thunk.replace("MSU_PATH_MOUNT_PATH_QUALIFIER", "" if const else "const")
        return "\n".join([
            "#define noinline __attribute__((noinline))", "#define __used __attribute__((used))",
            "#define NULL ((void *)0)", "#define MS_PRIVATE 1UL", "#define MS_REC 2UL",
            "struct path { int value; };",
            "" if missing else "#define MSU_PATH_MOUNT_PATH_QUALIFIER " + qualifier,
            declaration.group(), macros[0 if legacy else 1], thunk,
            "int fixture_target(const char *a, " + qualifier + "struct path *b, const char *c, unsigned long d, void *e) { return -43; }",
            ("int msu_direct_target_path_mount(const char *a, " + qualifier + "struct path *b, const char *c, unsigned long d, void *e) { return fixture_target(a,b,c,d,e); }") if legacy else "",
            "#define path_mount (*msu_native_path_mount)", sdk_declaration.group(),
            "int main(void) { struct path root = {0}; int error;",
            "" if legacy else "msu_native_path_mount = fixture_target;",
            "if (ref_path_mount(NULL, &root, NULL, MS_PRIVATE | MS_REC, NULL) != -43) return 2;",
            call.group(), "return error == -43 ? 0 : 1; }",
        ])

    def compile_fixture(self, text, root):
        path = root / "path_mount.c"
        output = root / "path_mount.exe"
        path.write_text(text)
        env = dict(os.environ, PATH=str(Path(CC).resolve().parent) + os.pathsep + os.environ.get("PATH", ""))
        result = subprocess.run([CC, "-std=gnu11", "-O2", "-Wall", "-Werror", str(path), "-o", str(output)],
                                capture_output=True, text=True, timeout=25, env=env)
        return result, output

    def test_const_and_mutable_legacy_and_modern_thunks_keep_errno(self):
        for const in (0, 1):
            for legacy in (False, True):
                with self.subTest(const=const, legacy=legacy), tempfile.TemporaryDirectory(prefix="bridge-path-mount-") as temp:
                    result, output = self.compile_fixture(self.fixture(const, legacy), Path(temp))
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    run = subprocess.run([str(output)], capture_output=True, timeout=5)
                    self.assertEqual(run.returncode, 0)

    def test_type_mismatch_and_missing_feature_fail_before_build(self):
        for const in (0, 1):
            for legacy in (False, True):
                for mismatch, missing in ((True, False), (False, True)):
                    with self.subTest(const=const, legacy=legacy, mismatch=mismatch, missing=missing), tempfile.TemporaryDirectory(prefix="bridge-path-mount-red-") as temp:
                        result, _ = self.compile_fixture(self.fixture(const, legacy, mismatch, missing), Path(temp))
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn("static assertion failed" if mismatch else "target type selection required", result.stderr)


if __name__ == "__main__":
    unittest.main()
