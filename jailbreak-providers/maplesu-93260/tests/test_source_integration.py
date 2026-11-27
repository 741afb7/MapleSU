"""Retained acquisition parity and the actual packaging transform, without a device."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import tempfile
import unittest

PROVIDER = Path(__file__).resolve().parents[1]
ROOT = PROVIDER.parent
spec = importlib.util.spec_from_file_location("provider_package", ROOT / "package.py")
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


def profiles_document():
    return json.loads((PROVIDER / "profiles.json").read_text(encoding="utf-8"))


class SourceIntegrationTest(unittest.TestCase):
    def test_acquisition_functions_match_supplied_source(self):
        rel = "me/yuki/fina/adbroot/flow/RootFlow.kt"
        upstream = (PROVIDER / "upstream/app/src/main/java" / rel).read_text(encoding="utf-8")
        derived = (PROVIDER / "src/main/kotlin" / rel).read_text(encoding="utf-8")
        for name in ("checkKernel", "checkManager", "waitBoot", "pushFiles", "ensureTmpWritable",
                     "startFake", "stepTracepoint", "stepKprobe", "stepNotifier", "kallsyms"):
            def function(text):
                start = text.index("    private suspend fun " + name + "(")
                following = re.search(r"\n    private (?:suspend )?fun ", text[start + 1:])
                return text[start:start + 1 + following.start()] if following else text[start:]
            with self.subTest(function=name):
                self.assertEqual(function(upstream), function(derived))
        self.assertLess(derived.index("if (!kallsyms(payloadDir, slide))"), derived.index("handover?.let"))
        self.assertLess(derived.index("handover?.let"), derived.index("if (!lateLoad())"))

    def test_packaged_payloads_and_decoded_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory)
            inventory = package.stage_maplesu93260(assets)
            self.assertEqual(4, len(inventory["files"]))
            for item in inventory["files"]:
                raw = (assets / item["asset"]).read_bytes()
                decoded = gzip.decompress(raw) if item["gzip"] else raw
                self.assertEqual(item["sha256"], hashlib.sha256(raw).hexdigest())
                self.assertEqual(item["decodedSha256"], hashlib.sha256(decoded).hexdigest())
                self.assertEqual(item["decodedBytes"], len(decoded))

    def test_changed_retained_bytes_fail_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            manifest = json.loads((PROVIDER / "upstream-manifest.json").read_text(encoding="utf-8"))
            copy = fixture / "maplesu-93260"
            for item in manifest["files"]:
                dest = copy / item["path"]
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(PROVIDER / item["path"], dest)
            (copy / "upstream-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (copy / manifest["files"][0]["path"]).write_bytes(b"changed")
            original = package.HERE
            try:
                package.HERE = fixture
                with self.assertRaises(SystemExit):
                    package.stage_maplesu93260(fixture / "assets")
            finally:
                package.HERE = original

    def test_packaged_catalog_keeps_the_default_and_packages_our_plants(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory)
            inventory = package.stage_maplesu93260(assets)
            manifest = json.loads((PROVIDER / "upstream-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["kernelRelease"], inventory["kernelRelease"])
            default = inventory["profiles"][0]
            self.assertEqual(inventory["defaultProfile"], default["id"])
            self.assertEqual("retained", default["engine"])
            self.assertEqual([manifest["kernelRelease"]], default["releases"])
            self.assertEqual(4, len(default["files"]))
            for field in package.MAPLESU93260_ADDRESS_FIELDS:
                self.assertRegex(default["addresses"][field], r"^[0-9a-f]{1,16}$")
            plants = [item for item in inventory["profiles"] if item["engine"] == "plant"]
            self.assertEqual(13, len(plants))
            self.assertEqual([], inventory["declared"])
            for profile in plants:
                self.assertRegex(profile["runner"], r"^plant-\d{5}$")
                self.assertRegex(profile["runnerFile"]["asset"],
                                 r"^maplesu-93260/plant/plant-\d{5}$")
                self.assertNotIn("addresses", profile)
                self.assertNotIn("files", profile)
                self.assertTrue(profile["variant"])
                asset = assets / profile["runnerFile"]["asset"]
                self.assertTrue(asset.is_file())
                self.assertEqual(profile["runnerFile"]["sha256"],
                                 hashlib.sha256(asset.read_bytes()).hexdigest())
            releases = [item["releases"][0] for item in inventory["profiles"]]
            self.assertEqual(len(releases), len(set(releases)))
            self.assertTrue(inventory["catalog"]["url"].startswith("https://"))

    def test_catalog_addresses_match_the_compiled_default(self):
        """profiles.json and KernelProfiles.kt must not drift apart."""
        default = profiles_document()["profiles"][0]
        kotlin = (PROVIDER / "src/main/kotlin/me/yuki/fina/adbroot/core/KernelProfiles.kt") \
            .read_text(encoding="utf-8")
        for field in package.MAPLESU93260_ADDRESS_FIELDS:
            value = default["addresses"][field]
            self.assertIn('u64("%s")' % value, kotlin, field)
        self.assertIn('DEFAULT_RELEASE = "%s"' % default["releases"][0], kotlin)
        self.assertIn('DEFAULT_ID = "%s"' % default["id"], kotlin)

    def test_plants_are_built_from_the_retained_source(self):
        document = json.loads((package.SHARK_MANIFEST).read_text(encoding="utf-8"))
        self.assertEqual("GPL-3.0", document["license"])
        self.assertEqual("2af72cb8959fae5f1c7f929a649db5e546334cef", document["commit"])
        records = document["plants"]
        self.assertEqual(7, len(records))
        for item in records:
            built = package.SHARK_PLANTS / item["name"]
            self.assertRegex(item["name"], r"^plant-[0-9]{5}$")
            self.assertEqual(item["sha256"], hashlib.sha256(built.read_bytes()).hexdigest())
            self.assertEqual(item["sourceSha256"],
                             hashlib.sha256((package.SHARK_DIR / item["source"]).read_bytes()).hexdigest())
        self.assertIn("compiler", document["build"])
        self.assertEqual("aarch64-linux-android31", document["build"]["target"])
        # The upstream release binaries are not part of this distribution.
        self.assertFalse((package.SHARK_DIR / "runners").exists())
        self.assertTrue((package.SHARK_DIR / "upstream/exploit/src/plant_core.inc").is_file())

    def test_plant_profile_must_name_a_built_plant(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            provider = fixture / "maplesu-93260"
            shutil.copytree(PROVIDER, provider)
            document = profiles_document()
            document["profiles"][1]["runner"] = "plant-99999"
            (provider / "profiles.json").write_text(json.dumps(document), encoding="utf-8")
            original = package.HERE
            try:
                package.HERE = fixture
                with self.assertRaises(SystemExit):
                    package.stage_maplesu93260(fixture / "assets")
            finally:
                package.HERE = original

    def test_profile_without_an_address_is_rejected_at_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            provider = fixture / "maplesu-93260"
            shutil.copytree(PROVIDER, provider)
            document = profiles_document()
            document["profiles"][0]["addresses"].pop("traceSysExit")
            (provider / "profiles.json").write_text(json.dumps(document), encoding="utf-8")
            original = package.HERE
            try:
                package.HERE = fixture
                with self.assertRaises(SystemExit):
                    package.stage_maplesu93260(fixture / "assets")
            finally:
                package.HERE = original

    def test_profile_payload_names_are_the_retained_device_names(self):
        for profile in profiles_document()["profiles"]:
            if profile.get("engine", "retained") != "retained":
                continue
            self.assertEqual(package.MAPLESU93260_DEVICE_NAMES,
                             set(profile["payload"]["files"].values()))


if __name__ == "__main__":
    unittest.main()
