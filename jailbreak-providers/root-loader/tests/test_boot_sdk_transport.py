"""Compile the real stage functions against host memory/credential fixtures."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "jailbreak-providers/dirtyfrag/tests"))
from test_l3_arm import definition, host_compiler


class BootSdkTransportTest(unittest.TestCase):
    def protocol(self, case):
        compiler = host_compiler()
        self.assertIsNotNone(compiler)
        with tempfile.TemporaryDirectory(prefix="maplesu-sdk-protocol-") as directory:
            executable = Path(directory) / "protocol.exe"
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
                "-I" + str(HERE.parent / "module"), HERE / "boot_sdk_protocol_test.c", "-o", executable], check=True)
            result = subprocess.run([executable, case], capture_output=True, text=True, timeout=10)
            if result.returncode == 77:
                self.skipTest("LP64 address arithmetic requires an LP64 host")
            self.assertEqual(0, result.returncode, result.stderr)

    def test_legacy_packet_and_metadata_key_encoding(self):
        self.protocol("packet")

    def test_unsigned_metadata_window_and_arena_guard_page(self):
        self.protocol("range")

    def test_page_rounded_execution_rw_state_and_sdk_free_lifecycle(self):
        source = (HERE.parent / "module/maplesu_boot_sdk.c").read_text(encoding="utf-8")
        ledger = re.search(r"struct stage \{[^}]+\};", source)
        stages = re.search(r"static struct stage stages\[64\];", source)
        self.assertIsNotNone(ledger)
        self.assertIsNotNone(stages)
        names = ("zero", "owned", "discard", "sdk_module_free", "legacy_scope_begin", "legacy_scope_end",
                 "same_name", "sdk_lookup", "sdk_current_task", "sdk_current_cred", "execute", "dispatch")
        functions = [definition(source, name) for name in names]
        self.assertTrue(all(functions), "The harness must use every production definition")
        compiler = host_compiler()
        self.assertIsNotNone(compiler)
        with tempfile.TemporaryDirectory(prefix="maplesu-boot-sdk-") as directory:
            temporary = Path(directory)
            (temporary / "boot_sdk_stage.inc").write_text(
                ledger.group() + "\n" + stages.group() + "\nstruct sdk_legacy_scope { const struct maplesu_legacy_hook_provider *provider; void *lease; u64 scope; };\n"
                "extern unsigned char msu_boot_sdk_legacy_scope_start[], msu_boot_sdk_legacy_scope_end[];\n"
                "static int (*legacy_provider_get)(const struct maplesu_legacy_hook_provider **, void **);\n"
                "static void (*legacy_provider_put)(void *);\n" + "\n".join(functions), encoding="utf-8")
            executable = temporary / "transport.exe"
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
                "-I" + str(temporary), "-I" + str(HERE.parent / "module"),
                "-I" + str(ROOT / "runtime/ultra/authorization"),
                HERE / "boot_sdk_transport_test.c", "-o", executable], check=True)
            for case in ("rounded", "credentials", "rewrite", "rw-state", "free-reuse",
                         "quarantine", "ro-error", "x-error", "invalid", "scope-error", "scope-discard-error"):
                with self.subTest(case=case):
                    subprocess.run([executable, case], check=True, capture_output=True, text=True, timeout=10)


if __name__ == "__main__":
    unittest.main()
