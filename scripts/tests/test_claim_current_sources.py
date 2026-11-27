from pathlib import Path
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))


def builder():
    name = "current_claim_test"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/build-ultra-manager-claim.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class CurrentClaimSourceTests(unittest.TestCase):
    def test_dependency_capture_uses_the_selected_su_objects_and_requires_commands(self):
        module = builder()
        base = ("manager_owner_watch", "manager_management", "manager_management_facts",
                "manager_callback_types", "manager_resident_host", "manager_uid_gateway",
                "manager_uid_snapshot", "manager_uid_facts")
        components = (*module.SU_COMPONENT_OBJECTS, *module.SU_COMPONENT_PROBES)
        legacy = ("su_install_registry", "manager_su_facts", "manager_su_callback_types")
        host = {"schema": module.current_sources.SCHEMA,
                "runtimeChannel": "current-authenticated-installation",
                "runtimeObjects": list(module.HOST_RUNTIME_OBJECTS)}
        host_objects = (*module.HOST_RUNTIME_OBJECTS, "manager_host_runtime_types")
        for schema, selected, missing, host_selection in (
            (module.current_sources.SCHEMA, components, None, None),
            ("maplesu-p2-su-component-source-selection/1", components, None, None),
            ("maplesu-p2-su-candidate-source-selection/1", legacy, None, None),
            (module.current_sources.SCHEMA, components, "manager_su_component_facts", None),
            (module.current_sources.SCHEMA, components, None, host),
            (module.current_sources.SCHEMA, components, "manager_su_runtime_target", host),
        ):
            with self.subTest(schema=schema, missing=missing), tempfile.TemporaryDirectory(
                    dir=ROOT / ".tmp", prefix="claim-dependencies-") as temporary:
                directory = Path(temporary)
                target, kernel = directory / "target", directory / "kernel"
                target.mkdir()
                kernel.mkdir()
                (kernel / ".config").write_text("CONFIG_ARM64=y\n", encoding="utf-8")
                (target / "kbuild-working-directory.txt").write_text(str(kernel), encoding="utf-8")
                (target / "build-inputs.json").write_text(json.dumps({
                    "suSourceSelection": {"schema": schema},
                    "hostDeploymentSourceSelection": host_selection,
                }), encoding="utf-8")
                capture = target / "capture-dependencies.py"
                capture.write_text(module.DEPENDENCY_EXTRACTOR, encoding="utf-8")
                for name in dict.fromkeys((*base, *components, *legacy, *host_objects)):
                    source = target / (name + ".c")
                    source.write_text("int " + name + ";\n", encoding="utf-8")
                    (target / (name + ".o")).write_bytes(name.encode("ascii"))
                    if name != missing:
                        (target / ("." + name + ".o.cmd")).write_text(
                            "source_fixture := " + source.as_posix() + "\n" +
                            "deps_fixture := " + source.as_posix() + "\n", encoding="utf-8")
                result = subprocess.run([sys.executable, "-B", str(capture), str(target), str(kernel)],
                                        capture_output=True, text=True, encoding="utf-8")
                if missing:
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("." + missing + ".o.cmd", result.stderr)
                    continue
                self.assertEqual(0, result.returncode, result.stderr)
                receipts = target / "build-dependencies"
                expected = (*base, *selected, *(host_objects if host_selection else ()))
                self.assertEqual({name + ".json" for name in expected},
                                 {path.name for path in receipts.glob("*.json")})
                for name in selected:
                    receipt = json.loads((receipts / (name + ".json")).read_text(encoding="utf-8"))
                    self.assertTrue(receipt["complete"])
                    self.assertEqual(module.sha(target / (name + ".o")),
                                     receipt["objectIdentity"]["sha256"])
                    self.assertEqual(2, len(receipt["dependencies"]))

    def test_layout_refresh_removes_only_the_previous_layout_members(self):
        module = builder()
        self.assertEqual({obj: list(names) for obj, names in module.SU_COMPONENT_FUNCTIONS.items()},
                         module.su_component_required_entrypoints())
        facts = dict(serviceBytes="old", serviceAlign="old", serviceOldOffset="old",
                     serviceOldBytes="old", serviceDescriptorBytes="descriptor",
                     serviceDescriptorHostOffset="descriptor-host", profileDomainBytes="scalar")
        module.current_sources._layout_facts(module, "service", "msu_runtime_service",
                                             ("root",), facts, ("old",))
        self.assertNotIn("serviceOldOffset", facts)
        self.assertNotIn("serviceOldBytes", facts)
        self.assertEqual("descriptor", facts["serviceDescriptorBytes"])
        self.assertEqual("descriptor-host", facts["serviceDescriptorHostOffset"])
        self.assertEqual("scalar", facts["profileDomainBytes"])
        self.assertEqual("offsetof(struct msu_runtime_service, root)", facts["serviceRootOffset"])

    def test_current_selection_emits_every_existing_native_probe(self):
        module = builder()
        with tempfile.TemporaryDirectory(dir=ROOT / ".tmp", prefix="claim-source-test-") as temporary:
            path = Path(temporary) / "selection.json"
            receipt = module.current_sources.freeze_current_sources(module, path)
            module.current_sources.select_current_sources(module, path, receipt["sha256"])
            host = module.host_deployment_public_selection()
            self.assertEqual(module.HOST_DEPLOYMENT_SELECTION["runtimeChannel"], host["runtimeChannel"])
            self.assertEqual(list(module.HOST_RUNTIME_OBJECTS), host["runtimeObjects"])
            self.assertTrue(all(path.is_relative_to(ROOT / "runtime") for path in module.SOURCES))
            self.assertEqual(10, len(module.su_source_paths()))
            self.assertIn("root", module.CORE_LAYOUT_TYPES["session"][2])
            self.assertIn("root", module.CORE_LAYOUT_TYPES["publication"][2])
            self.assertIn("root", module.CORE_LAYOUT_TYPES["sharedBool"][2])
            for prefix, (_, probe, members) in module.CORE_LAYOUT_TYPES.items():
                facts = module.CORE_UID_FACTS if probe == "manager_core_uid_facts" else module.CORE_FACTS
                self.assertIn(prefix + "Bytes", facts)
                self.assertIn(prefix + "Align", facts)
                for member in members:
                    key = prefix + "".join(word.capitalize() for word in member.split("_"))
                    self.assertIn(key + "Offset", facts)
                    self.assertIn(key + "Bytes", facts)
            for name in module.CORE_PROBES[:2]:
                self.assertIn("msu_core_build_facts", module.core_probe_source(name))
            self.assertTrue(module.core_callback_type_source())
            required = module.su_component_required_entrypoints()
            self.assertIn("msu_su_registry_revoke", required["su_install_registry"])
            self.assertIn("msu_su_apply_held_detailed", required["su_profile_execution"])
            for name in module.current_sources.LOCAL_ONLY_SU_ENTRYPOINTS:
                self.assertNotIn(name, [entry for entries in required.values() for entry in entries])
                self.assertIn(name, [entry for entries in module.SU_COMPONENT_FUNCTIONS.values() for entry in entries])
            for name in module.SU_COMPONENT_PROBES:
                self.assertTrue(module.su_component_probe(name))
            self.assertTrue(module.host_runtime_probe_source())
            self.assertTrue(module.task_profile_type_source())
            self.assertFalse(module.upstream_source_freeze()["targetQualified"])

    def test_stale_byte_identity_and_historical_replacement_fail_closed(self):
        for field in ("hash", "path", "tool"):
            module = builder()
            with tempfile.TemporaryDirectory(dir=ROOT / ".tmp", prefix="claim-source-test-") as temporary:
                path = Path(temporary) / "selection.json"
                module.current_sources.freeze_current_sources(module, path)
                record = json.loads(path.read_text(encoding="utf-8"))
                if field == "hash":
                    record["files"][0]["after"]["sha256"] = "0" * 64
                elif field == "path":
                    record["files"][0]["after"]["path"] = ".tmp/historical/manager_claim_entry.c"
                else:
                    record["tools"][0]["sha256"] = "0" * 64
                path.write_text(json.dumps(record), encoding="utf-8")
                with self.assertRaises(ValueError):
                    module.current_sources.select_current_sources(module, path, module.sha(path))


if __name__ == "__main__":
    unittest.main()
