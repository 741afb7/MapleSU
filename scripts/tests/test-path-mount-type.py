"""path_mount qualifier selection must use real type evidence, preserving const."""
import ast
import hashlib
import importlib.util
from pathlib import Path
import struct
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from policy_path_mount import path_mount_path_const, path_mount_path_const_btf, path_mount_qualifier_define


def fixture_btf(const):
    strings = b"\0int\0path\0path_mount\0"
    types = [struct.pack("<IIII", 1, 1 << 24, 4, 0x1000020),
             struct.pack("<III", 5, 4 << 24, 16),
             struct.pack("<III", 0, (10 if const else 8) << 24, 2),
             struct.pack("<III", 0, 2 << 24, 3),
             struct.pack("<III", 0, 13 << 24 | 5, 1) + b"".join(struct.pack("<II", 0, t) for t in (4,4,4,1,4)),
             struct.pack("<III", 10, 12 << 24, 5)]
    blob = b"".join(types)
    return struct.pack("<HBBIIIII", 0xeb9f, 1, 0, 24, 0, len(blob), len(blob), len(strings)) + blob + strings


class PathMountTypes(unittest.TestCase):
    def test_both_header_signatures_and_btf_const_chain(self):
        for const in (0, 1):
            header = ("int path_mount(const char *dev_name, %sstruct path *path,\n"
                      " const char *type_page, unsigned long flags, void *data_page);" % ("const " if const else "")).encode()
            self.assertEqual(path_mount_path_const(header), const)
            self.assertEqual(path_mount_path_const_btf(fixture_btf(const)), const)
            self.assertEqual(path_mount_qualifier_define(const),
                             "#define MSU_PATH_MOUNT_PATH_QUALIFIER " + ("const" if const else "") + "\n")

    def test_missing_ambiguous_or_changed_declaration_has_no_default(self):
        prototype = b"int path_mount(const char *, struct path *, const char *, unsigned long, void *);"
        for header in (b"", prototype * 2, prototype.replace(b"struct path", b"struct file"),
                       prototype.replace(b"unsigned long", b"unsigned int"),
                       prototype.replace(b"unsigned long", b"unsigned longflags"),
                       prototype.replace(b"struct path *", b"volatile struct path *")):
            with self.subTest(header=header), self.assertRaises(ValueError):
                path_mount_path_const(header)
        with self.assertRaises(ValueError):
            path_mount_qualifier_define(2)

    def test_reviewed_runtime_source_contract_binds_actual_candidate_bytes(self):
        tree = ast.parse((SCRIPTS / "build-ultra-manager-claim.py").read_text(encoding="utf-8-sig"))
        bindings = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                        and any(isinstance(target, ast.Name) and target.id == "RUNTIME_SU_CURRENT_SHA256" for target in node.targets))
        source = SCRIPTS.parent / "runtime/maplesu-sdk/sdk/src/module/su_redirect_tu/kernel/su_profile_namespace.c"
        self.assertEqual(bindings["su_profile_namespace.c"], hashlib.sha256(source.read_bytes()).hexdigest())

    def test_feature_is_required_in_native_kmi_receipt(self):
        spec = importlib.util.spec_from_file_location("path_mount_kmi", SCRIPTS.parent / "runtime/ultra/boot/init-bridge/target_kmi.py")
        target = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(target)
        with self.assertRaisesRegex(ValueError, "PATH_MOUNT_TYPE_REQUIRED"):
            target.native_import_features(b"", b"")
        for value in (0, 1):
            header = ("#define MSU_PATH_MOUNT_PATH_CONST %d\n" % value).encode()
            self.assertEqual(target.native_import_features(header, b"")["MSU_PATH_MOUNT_PATH_CONST"], value)
        with self.assertRaisesRegex(ValueError, "AMBIGUOUS"):
            target.native_import_features(b"#define MSU_PATH_MOUNT_PATH_CONST 1\n", b"-DMSU_PATH_MOUNT_PATH_CONST=0")


if __name__ == "__main__":
    unittest.main()
