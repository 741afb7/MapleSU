"""Exercise the actual native-start body with host prefix/lifecycle seams."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "jailbreak-providers/dirtyfrag/tests"))
from test_l3_arm import definition, host_compiler


class BootSdkCfiPrefixTest(unittest.TestCase):
    def test_compiler_prefix_and_start_guards(self):
        source = (HERE.parent / "module/maplesu_boot_sdk.c").read_text(encoding="utf-8")
        body = definition(source, "msu_boot_sdk_native_start")
        self.assertIsNotNone(body)
        compiler = host_compiler()
        self.assertIsNotNone(compiler)
        with tempfile.TemporaryDirectory(prefix="maplesu-sdk-cfi-prefix-") as directory:
            temporary = Path(directory)
            (temporary / "boot_sdk_native_start.inc").write_text(body, encoding="utf-8")
            binary = temporary / "prefix.exe"
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror",
                "-I" + str(temporary), HERE / "boot_sdk_cfi_prefix_test.c",
                "-o", binary], check=True)
            result = subprocess.run([binary], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
