"""Bind the feature header actually consumed by the native-import Kbuild."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("bridge_target_feature", Path(__file__).parents[1] / "target_kmi.py")
kmi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kmi)
NAME = "msu_resolver_build_config.h"


class NativeFeaturePathChecks(unittest.TestCase):
    def stage(self, directory, selected):
        root = Path(directory)
        (root / "host").mkdir()
        for relative, value in ((NAME, 0), ("host/" + NAME, 1)):
            (root / relative).write_text("#define MSU_PATH_MOUNT_PATH_CONST " + str(value) + "\n")
        (root / ".native_imports.o.cmd").write_text(
            "cmd_/module/native_imports.o := clang -I/module/host -c /module/native_imports.c\n"
            "source_/module/native_imports.o := /module/native_imports.c\n"
            "deps_/module/native_imports.o := \\\n" +
            "".join("  /module/" + name + " \\\n" for name in selected) + "\n")
        return root / "reference.lto.o"

    def test_current_and_historical_layout_follow_the_compiled_dependency(self):
        for relative in (NAME, "host/" + NAME):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory(prefix="kmi-feature-") as directory:
                reference = self.stage(directory, [relative])
                path = kmi.native_import_feature_path(reference)
                self.assertEqual(path, reference.parent / relative)
                values = kmi.native_import_features(path.read_bytes(), (reference.parent / ".native_imports.o.cmd").read_bytes())
                self.assertEqual(values["MSU_PATH_MOUNT_PATH_CONST"], int(relative.startswith("host/")))

    def test_missing_compiled_header_does_not_select_the_unused_copy(self):
        with tempfile.TemporaryDirectory(prefix="kmi-feature-missing-") as directory:
            reference = self.stage(directory, ["host/" + NAME])
            (reference.parent / "host" / NAME).unlink()
            with self.assertRaises(FileNotFoundError):
                kmi.native_import_feature_path(reference).read_bytes()

    def test_missing_or_ambiguous_dependency_is_rejected(self):
        for selected in ([], [NAME, "host/" + NAME]):
            with self.subTest(selected=selected), tempfile.TemporaryDirectory(prefix="kmi-feature-dep-") as directory:
                reference = self.stage(directory, selected)
                with self.assertRaisesRegex(ValueError, "NATIVE_FEATURE_DEPENDENCY"):
                    kmi.native_import_feature_path(reference)

    def test_external_same_named_header_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="kmi-feature-external-") as directory:
            reference = self.stage(directory, ["host/" + NAME])
            command = reference.parent / ".native_imports.o.cmd"
            command.write_text(command.read_text().replace("  /module/host/", "  /other/host/"))
            with self.assertRaisesRegex(ValueError, "NATIVE_FEATURE_DEPENDENCY"):
                kmi.native_import_feature_path(reference)

    def test_relative_external_module_kbuild_dependencies_are_supported(self):
        with tempfile.TemporaryDirectory(prefix="kmi-feature-relative-") as directory:
            reference = self.stage(directory, ["host/" + NAME])
            command = reference.parent / ".native_imports.o.cmd"
            command.write_text(command.read_text().replace("/module/", ""))
            self.assertEqual(kmi.native_import_feature_path(reference), reference.parent / "host" / NAME)

    def test_feature_hash_and_command_disagreement_still_fail(self):
        with tempfile.TemporaryDirectory(prefix="kmi-feature-identity-") as directory:
            reference = self.stage(directory, ["host/" + NAME])
            path = kmi.native_import_feature_path(reference)
            original = kmi.identity(path)
            path.write_text("#define MSU_PATH_MOUNT_PATH_CONST 0\n")
            with self.assertRaisesRegex(ValueError, "KMI_INPUT_CHANGED"):
                kmi.checked(original)
            with self.assertRaisesRegex(ValueError, "NATIVE_FEATURE_AMBIGUOUS"):
                kmi.native_import_features(path.read_bytes(), b" -DMSU_PATH_MOUNT_PATH_CONST=1 ")

    def test_explicit_reference_receipt_binds_compiled_object_header_and_command(self):
        with tempfile.TemporaryDirectory(prefix="kmi-feature-receipt-") as directory:
            reference = self.stage(directory, ["host/" + NAME])
            blobs = {"nativeImportsReference": b"fixture reference object", "nativeImportsC": b"fixture imports source",
                     "nativeImportsH": b"fixture imports header", "nativeImportsFeature": (reference.parent / "host" / NAME).read_bytes(),
                     "nativeImportsCommand": (reference.parent / ".native_imports.o.cmd").read_bytes()}
            def row(path, data):
                return {"path":path,"bytes":len(data),"sha256":kmi.digest(data)}
            receipt = {"schema":"maplesu-current-native-import-reference/1", "inputsUnchanged":True, "toolsUnchanged":True,
                       "reference":row("/module/reference.lto.o",blobs["nativeImportsReference"]),
                       "commands":[{"argv":["make","M=/module","native_imports.o"],"exitCode":0}],
                       "inputs":[row("/module/"+relative,blobs[key]) for key,relative in (
                           ("nativeImportsC","native_imports.c"),("nativeImportsH","native_imports.h"),
                           ("nativeImportsFeature","host/"+NAME))]}
            blobs["nativeImportsReferenceReceipt"] = json.dumps(receipt).encode()
            kmi.validate_native_import_reference_receipt(blobs)
            for mutation in (
                    lambda value:value["reference"].__setitem__("sha256","0"*64),
                    lambda value:value["inputs"][2].__setitem__("sha256","0"*64),
                    lambda value:value["commands"][0]["argv"].__setitem__(1,"M=/other"),
                    lambda value:value["commands"][0].__setitem__("exitCode",1)):
                changed = json.loads(json.dumps(receipt))
                mutation(changed)
                blobs["nativeImportsReferenceReceipt"] = json.dumps(changed).encode()
                with self.assertRaisesRegex(ValueError,"NATIVE_REFERENCE_RECEIPT"):
                    kmi.validate_native_import_reference_receipt(blobs)


if __name__ == "__main__":
    unittest.main()
