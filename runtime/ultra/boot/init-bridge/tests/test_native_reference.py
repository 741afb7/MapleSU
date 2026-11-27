"""Exercise current-source native-reference command/identity boundaries without compiling."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("current_native_reference", Path(__file__).parents[1] / "native_reference.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class ReferenceChecks(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="maplesu-reference-")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.module = self.base / "module"
        self.kernel = self.base / "kernel"
        self.tool = self.base / "llvm"
        self.out = self.base / "evidence"
        self.module.mkdir(); self.kernel.mkdir(); self.tool.mkdir()
        (self.kernel / "include/generated").mkdir(parents=True)
        for path in [self.module / "Kbuild", self.module / "native_imports.c", self.kernel / ".config",
                     self.kernel / "include/generated/utsrelease.h", *(self.tool / n for n in ("clang", "ld.lld", "llvm-ar"))]:
            path.write_bytes(path.name.encode())
        self.selected = self.module / "SOURCE-SELECTION.json"
        self.selection = dict(formalRuntimeSelections=dict(currentSourceSelection=dict(
            repositoryPath=".tmp/fixture-current.json", sha256="a" * 64)),
            inputs=[dict(staged="native_imports.c", selected=api.identity(self.module / "native_imports.c"))])
        self.write_selection()
        self.validator = Mock(return_value={})
        self.argv = [str(Path(api.__file__)), "--module-source", str(self.module), "--kernel-build", str(self.kernel),
            "--llvm-bin", str(self.tool), "--evidence", str(self.out)]

    def write_selection(self):
        self.selected.write_text(json.dumps(self.selection))

    def invoke(self, run):
        with patch.object(sys, "argv", self.argv), patch.object(api.subprocess, "run", run), \
                patch.dict(sys.modules, {"claim_current_sources": SimpleNamespace(read_current_source_selection=self.validator)}):
            api.main()

    def test_current_input_and_all_five_commands(self):
        calls=[]
        def run(argv, **kwargs):
            calls.append(argv)
            if len(calls) == 5:
                (self.module / "reference.lto.o").write_bytes(b"explicit reference output fixture")
            return SimpleNamespace(returncode=0)
        self.invoke(run)
        receipt=json.loads((self.out / "REFERENCE.json").read_text())
        self.assertTrue(receipt["inputsUnchanged"] and receipt["toolsUnchanged"])
        self.assertEqual(len(calls), 5)
        self.assertEqual([a[-1] for a in calls[:3]], ["native_imports.o", "native_sdk_imports.o", "probe.o"])
        self.assertIn("obj-m=probe.o", calls[2])
        self.assertEqual(calls[4][-2:], ["--whole-archive", str(self.module / "reference.o")])
        self.validator.assert_called_once()

    def test_changed_stage_stops_before_compile(self):
        (self.module / "native_imports.c").write_bytes(b"changed")
        run=Mock()
        with self.assertRaisesRegex(ValueError, "STAGED_SOURCE_CHANGED"):
            self.invoke(run)
        run.assert_not_called()
        self.assertFalse(self.out.exists())

    def test_no_current_receipt_has_no_fallback(self):
        self.selection["formalRuntimeSelections"]={}; self.write_selection()
        run=Mock()
        with self.assertRaisesRegex(ValueError, "CURRENT_SOURCE_SELECTION_REQUIRED"):
            self.invoke(run)
        run.assert_not_called(); self.validator.assert_not_called()

    def test_existing_objects_are_rejected(self):
        (self.module / "stale.o").write_bytes(b"stale")
        run=Mock()
        with self.assertRaisesRegex(ValueError, "FRESH_CURRENT_STAGE_REQUIRED"):
            self.invoke(run)
        run.assert_not_called()

    def test_first_build_error_is_preserved(self):
        run=Mock(return_value=SimpleNamespace(returncode=23))
        with self.assertRaises(SystemExit) as raised:
            self.invoke(run)
        self.assertEqual(raised.exception.code, 23)
        self.assertEqual(run.call_count, 1)
        receipt=json.loads((self.out / "REFERENCE.json").read_text())
        self.assertEqual(receipt["commands"][0]["exitCode"], 23)
        self.assertNotIn("reference", receipt)


if __name__ == "__main__":
    unittest.main()
