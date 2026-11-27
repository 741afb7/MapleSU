"""Stage current production bridge sources; kernel headers/config are fixtures."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from claim_current_sources import freeze_current_sources, read_current_source_selection


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class CurrentBridgeSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(dir=ROOT / ".tmp", prefix="current-bridge-")
        cls.root = Path(cls.temporary.name)
        cls.bridge = ROOT / "runtime/ultra/boot/init-bridge"
        cls.builder = load("current_bridge_claim_test", ROOT / "scripts/build-ultra-manager-claim.py")
        cls.gate = load("current_bridge_build_test", cls.bridge / "build_target.py")
        cls.selection = cls.root / "current.json"
        cls.reference = freeze_current_sources(cls.builder, cls.selection)
        cls.kernel, cls.kdir, cls.stage = cls.root / "kernel", cls.root / "obj", cls.root / "stage"
        cls.kdir.mkdir()
        (cls.kdir / ".config").write_text("CONFIG_ARM64_4K_PAGES=y\n", encoding="utf-8")
        for name, text in {
            "include/linux/fs_struct.h": "struct fs_struct { spinlock_t lock; seqcount_t seq; };\n",
            "include/linux/cred.h": "struct cred { int uid; };\n",
            "fs/mount.h": "/* fixture private mount header; no native build */\n",
            "fs/internal.h": "int path_mount(const char *, const struct path *, const char *, unsigned long, void *);\n",
        }.items():
            path = cls.kernel / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        cls.command = [sys.executable, "-B", str(cls.bridge / "stage.py"), "--repo-root", str(ROOT),
            "--kernel-source", str(cls.kernel), "--kernel-build", str(cls.kdir),
            "--out", str(cls.stage), "--current-source-selection", str(cls.selection),
            "--current-source-selection-sha256", cls.reference["sha256"], "--runtime-facts"]
        result = subprocess.run(cls.command, capture_output=True, text=True, encoding="utf-8")
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        cls.receipt = json.loads((cls.stage / "SOURCE-SELECTION.json").read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_actual_current_sources_reach_stage_and_build_input_gate(self):
        receipt = self.receipt
        self.assertEqual("runtime-kmi-component", receipt["targetBinding"]["kind"])
        self.assertTrue(receipt["runtimeFactsSelected"])
        self.assertFalse(receipt["compiled"])
        selected = self.gate.staged_target_inputs(self.stage, self.kdir, ROOT, self.kernel)
        self.assertIn(self.selection, selected)
        self.assertIn(ROOT / "scripts/build-ultra-manager-claim.py", selected)
        for row in receipt["inputs"]:
            if row["repositoryPath"] != "fs/mount.h":
                self.assertEqual("current-source-selection", row["selection"])
                self.assertEqual((ROOT / row["repositoryPath"]).read_bytes(),
                                 (self.stage / row["staged"]).read_bytes())
        current = read_current_source_selection(ROOT, self.selection, self.reference["sha256"])
        self.assertTrue(any(row["role"] == "bridge" for row in current["files"]))

    def test_mixed_overlay_and_current_selection_fail_before_emission(self):
        command = list(self.command)
        command[command.index("--out") + 1] = str(self.root / "mixed")
        result = subprocess.run([*command, "--core-overlay", str(ROOT)], capture_output=True,
                                text=True, encoding="utf-8")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CURRENT_SELECTION_MODE_CONFLICT", result.stderr)
        self.assertFalse((self.root / "mixed").exists())

    def test_staged_fact_mutation_is_rejected(self):
        path = self.stage / "host/manager_runtime_accessor.c"
        before = path.read_bytes()
        try:
            path.write_bytes(before + b"\n/* changed staged source */\n")
            with self.assertRaisesRegex(ValueError, "RUNTIME_FACTS_SOURCE_IDENTITY"):
                self.gate.staged_target_inputs(self.stage, self.kdir, ROOT, self.kernel)
        finally:
            path.write_bytes(before)

    def test_kernel_configuration_mutation_is_rejected(self):
        path = self.kdir / ".config"
        before = path.read_bytes()
        try:
            path.write_bytes(before + b"CONFIG_OTHER=y\n")
            with self.assertRaisesRegex(ValueError, "RUNTIME_FACTS_BUILD_CONFIG"):
                self.gate.staged_target_inputs(self.stage, self.kdir, ROOT, self.kernel)
        finally:
            path.write_bytes(before)


if __name__ == "__main__":
    unittest.main()
