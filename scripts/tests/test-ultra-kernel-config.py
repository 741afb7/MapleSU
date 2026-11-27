#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-ultra-kernel-config.py"
MODULE_BTF = "CONFIG_MODULES=y\nCONFIG_DEBUG_INFO_BTF=y\nCONFIG_DEBUG_INFO_BTF_MODULES=y\n"


class UltraKernelConfigTest(unittest.TestCase):
    def check(self, config, *options):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".config"
            path.write_text(config, encoding="utf-8")
            return subprocess.run([sys.executable, "-B", "-X", "utf8", str(SCRIPT), str(path), *options],
                                  capture_output=True, text=True)

    def test_no_btf_and_no_modversions_are_not_global_requirements(self):
        result = self.check("# CONFIG_DEBUG_INFO_BTF is not set\n# CONFIG_MODVERSIONS is not set\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("ULTRA_KERNEL_CONFIG_OK", result.stdout)
        self.assertIn("Scope:", result.stdout)

    def test_module_btf_without_tolerance_does_not_prove_mismatch(self):
        result = self.check(MODULE_BTF + "CONFIG_MODVERSIONS=y\n# CONFIG_MODULE_ALLOW_BTF_MISMATCH is not set\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("compatibility: unknown", result.stdout)

    def test_selected_btf_route_requires_btf(self):
        result = self.check("# CONFIG_DEBUG_INFO_BTF is not set\n", "--require-btf")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("CONFIG_DEBUG_INFO_BTF=y", result.stdout)

    def test_selected_versioned_module_route_requires_modversions(self):
        result = self.check("# CONFIG_MODVERSIONS is not set\n", "--require-modversions")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("CONFIG_MODVERSIONS=y", result.stdout)

    def test_selected_requirements_pass_without_mismatch_tolerance(self):
        result = self.check(MODULE_BTF + "CONFIG_MODVERSIONS=y\n", "--require-btf", "--require-modversions")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_confirmed_mismatch_is_rejected_without_tolerance(self):
        result = self.check(MODULE_BTF, "--module-btf-mismatch")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("confirmed module BTF mismatch", result.stdout)

    def test_confirmed_mismatch_with_tolerance_does_not_claim_module_btf(self):
        result = self.check(MODULE_BTF + "CONFIG_MODULE_ALLOW_BTF_MISMATCH=y\n", "--module-btf-mismatch")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("load without their BTF", result.stdout)

    def test_disabled_module_btf_does_not_require_tolerance(self):
        result = self.check("CONFIG_DEBUG_INFO_BTF=y\n# CONFIG_DEBUG_INFO_BTF_MODULES is not set\n",
                            "--module-btf-mismatch")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_no_btf_route_does_not_require_tolerance(self):
        result = self.check("CONFIG_MODULES=y\n# CONFIG_DEBUG_INFO_BTF is not set\n", "--module-btf-mismatch")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_empty_config_does_not_claim_bootability(self):
        result = self.check("")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("device boot remain separate", result.stdout)

    def test_missing_config_is_a_usage_error(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, "-B", "-X", "utf8", str(SCRIPT), str(Path(directory) / "missing")],
                                    capture_output=True, text=True)
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)

    def test_kconfig_parser_preserves_disabled_and_quoted_values(self):
        spec = importlib.util.spec_from_file_location("ultra_config", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".config"
            path.write_text('# comment\nCONFIG_LOCALVERSION="-fixture"\r\n# CONFIG_DEBUG_INFO_BTF is not set\n',
                            encoding="utf-8")
            values = module.parse(str(path))
        self.assertEqual("-fixture", values["CONFIG_LOCALVERSION"])
        self.assertEqual("n", values["CONFIG_DEBUG_INFO_BTF"])


if __name__ == "__main__":
    unittest.main()
