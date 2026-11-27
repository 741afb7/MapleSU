"""Bind TaskProfile source facts to the actual Kbuild output directory."""
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
from unittest.mock import patch
import unittest

spec = importlib.util.spec_from_file_location("task_source_proof", Path(__file__).parents[1] / "task_profile_abi.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class SourceProofChecks(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="maplesu-task-source-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        module = self.root / "maplesu_ultra_bridge.ko"
        module.write_bytes(b"explicit non-ELF identity fixture")
        self.config = self.root / ".config"
        self.config.write_bytes(b"CONFIG_SMP=y\n")
        self.selection = self.root / "SOURCE-SELECTION.json"
        self.build = self.root / "BUILD.json"
        self.successor = dict(schema="maplesu-task-profile-source-successor/1",
            productionWritten=False, sourceOwnerFrozen=True, files=[])
        selected, built = [], []
        for logical in sorted(api.SOURCES):
            relative = "host/" + Path(logical).name
            path = self.root / relative
            path.parent.mkdir(exist_ok=True)
            path.write_bytes((logical + "\n").encode())
            identity = api.package.file_identity(path)
            selected.append(dict(repositoryPath=logical, staged=relative, selected=identity))
            self.successor["files"].append(dict(logicalPath=logical, after=identity))
            built.append(dict(identity, path="/module/" + relative))
        self.selection.write_text(json.dumps(dict(inputs=selected)))
        self.receipt = dict(exitCode=0, inputsUnchanged=True, nativeComponentsOnly=False,
            command=["make", "-C", "/kernel-output", "modules"],
            artifacts=[dict(api.package.file_identity(module), path="/module/maplesu_ultra_bridge.ko")],
            sources=[*built, dict(api.package.file_identity(self.selection), path="/module/SOURCE-SELECTION.json"),
                dict(api.package.file_identity(self.config), path="/kernel-output/.config")])

    def proof(self):
        self.build.write_text(json.dumps(self.receipt))
        return api.source_proof(self.selection, self.build, self.root, self.successor, self.config)

    def test_direct_build_directory(self):
        self.assertEqual(set(self.proof()), api.SOURCES)

    def test_separate_output_directory(self):
        self.receipt["command"] = ["make", "-C", "/kernel-source", "O=/kernel-output", "modules"]
        self.assertEqual(set(self.proof()), api.SOURCES)

    def test_separate_output_requires_retained_config(self):
        self.receipt["command"] = ["make", "-C", "/kernel-source", "O=/different-output", "modules"]
        with self.assertRaisesRegex(ValueError, "build-config"):
            self.proof()

    def test_duplicate_output_is_ambiguous(self):
        self.receipt["command"] = ["make", "-C", "/kernel-output", "O=/kernel-output", "O=/kernel-output", "modules"]
        with self.assertRaisesRegex(ValueError, "ACTUAL_BUILD_DIRECTORY"):
            self.proof()

    def test_relative_output_is_rejected(self):
        self.receipt["command"] = ["make", "-C", "/kernel-output", "O=relative", "modules"]
        with self.assertRaisesRegex(ValueError, "ACTUAL_BUILD_DIRECTORY"):
            self.proof()

    def test_output_path_traversal_is_rejected(self):
        self.receipt["command"] = ["make", "-C", "/kernel-output", "O=/build/../kernel-output", "modules"]
        with self.assertRaisesRegex(ValueError, "ACTUAL_BUILD_DIRECTORY"):
            self.proof()

    def test_output_config_hash_is_required(self):
        self.receipt["command"] = ["make", "-C", "/kernel-source", "O=/kernel-output", "modules"]
        self.receipt["sources"][-1]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "BUILD_CONFIG"):
            self.proof()


    def test_stage_probe_preserves_flags_and_records_source(self):
        (self.root / "maplesu_ultra_bridge.ko").unlink()
        runtime=self.root / "selected-runtime.mk"
        runtime.write_bytes(b"# exact generated task flags\n")
        with patch.object(sys, "argv", ["task_profile_abi.py", "stage-probe", "--module-source", str(self.root)]):
            api.stage_probe_main()
        probe=self.root / "host/manager_task_state_native_facts.c"
        self.assertEqual(probe.read_text(), api.NATIVE_PROBE_SOURCE)
        self.assertIn(b"CFLAGS_host/manager_task_state_native_facts.o = $(CFLAGS_host/manager_task_state.o)", runtime.read_bytes())
        selected=json.loads(self.selection.read_text())
        self.assertFalse(selected["generatedTaskProfileNativeProbe"]["buildExecuted"])
        self.assertEqual(selected["generatedTaskProfileNativeProbe"]["source"], api.package.file_identity(probe))
        with patch.object(sys, "argv", ["task_profile_abi.py", "stage-probe", "--module-source", str(self.root)]):
            with self.assertRaisesRegex(ValueError, "FRESH_NATIVE_PROBE_STAGE_REQUIRED"):
                api.stage_probe_main()

    def test_stage_probe_rejects_compiled_stage(self):
        (self.root / "selected-runtime.mk").write_bytes(b"# flags\n")
        (self.root / "stale.o").write_bytes(b"stale")
        with patch.object(sys, "argv", ["task_profile_abi.py", "stage-probe", "--module-source", str(self.root)]):
            with self.assertRaisesRegex(ValueError, "FRESH_NATIVE_PROBE_STAGE_REQUIRED"):
                api.stage_probe_main()
        self.assertFalse((self.root / "host/manager_task_state_native_facts.c").exists())


if __name__ == "__main__":
    unittest.main()
