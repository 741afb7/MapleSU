"""Identity and canonical-entry checks for compact bridge source facts."""
import copy
import importlib.util
import struct
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("reference_facts_test", Path(__file__).parents[1] / "reference_facts.py")
M = importlib.util.module_from_spec(spec)
spec.loader.exec_module(M)


class ReferenceFactsTest(unittest.TestCase):
    def fixture(self):
        pairs = {"referenceBootSha256": "targetBootSha256", "referenceKernelSha256": "kernelSha256",
                 "bridgeSha256": "bridgeSha256", "contractSha256": "targetContractSha256",
                 "sourceGenerationSha256": "sourceGenerationSha256", "sourceRelease": "kernelRelease",
                 "sourceVermagic": "targetVermagic", "sourceConfigSha256": "configSha256"}
        manifest = {key: str(index) * 64 for index, key in enumerate(pairs.values(), 1)}
        facts = {key: manifest[value] for key, value in pairs.items()}
        facts.update(schema=M.SCHEMA, legacyCfi=True, sourcePageSize=4096,
                     sourceProofs={"sessionAbiSha256": None, "taskProfileAbiSha256": None})
        return facts, manifest, {"cfiMode": "legacy-lto-cfi"}

    def test_every_source_identity_is_bound(self):
        facts, manifest, contract = self.fixture()
        M.verify(facts, manifest, contract, {})
        for key in ("referenceBootSha256", "referenceKernelSha256", "bridgeSha256", "contractSha256",
                    "sourceGenerationSha256", "sourceRelease", "sourceVermagic", "sourceConfigSha256"):
            with self.subTest(key=key):
                changed = copy.deepcopy(facts)
                changed[key] = "changed"
                with self.assertRaisesRegex(ValueError, "IDENTITY:"):
                    M.verify(changed, manifest, contract, {})

    def test_both_retained_proofs_are_bound(self):
        facts, manifest, contract = self.fixture()
        components = {"sessionAbi": b"session", "taskProfileAbi": b"task"}
        facts["sourceProofs"] = {key + "Sha256": M.digest(value) for key, value in components.items()}
        M.verify(facts, manifest, contract, components)
        for key in components:
            changed = dict(components, **{key: b"tampered"})
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "PROOF:"):
                M.verify(facts, manifest, contract, changed)

    def test_schema_cfi_and_page_are_not_inferred(self):
        facts, manifest, contract = self.fixture()
        for key, value in (("schema", "other"), ("legacyCfi", False), ("sourcePageSize", 8192)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                M.verify(dict(facts, **{key: value}), manifest, contract, {})

    def test_canonical_entry_checks_actual_branch_destination(self):
        class Symbols:
            def all_offsets(self, name):
                return {"f": [8], "f.cfi_jt": [0]}[name]
        image = struct.pack("<IIII", 0x14000002, 0, 0xd503201f, 0)
        M.canonical(image, Symbols(), "f")
        with self.assertRaisesRegex(ValueError, "CANONICAL_TARGET"):
            M.canonical(struct.pack("<I", 0x14000003) + image[4:], Symbols(), "f")
        M.canonical(struct.pack("<IIII", 0xd503245f, 0x14000001, 0xd503201f, 0), Symbols(), "f")

    def test_json_encoding_is_canonical_utf8(self):
        self.assertEqual('{"a":1,"z":"\u4e2d"}\n'.encode("utf-8"), M.encoded({"z": "\u4e2d", "a": 1}))


if __name__ == "__main__":
    unittest.main()
