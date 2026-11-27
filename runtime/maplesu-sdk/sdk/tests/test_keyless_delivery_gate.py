from pathlib import Path
import copy
import importlib.util
import json
import sys
import unittest

SDK = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK / "tools"))
import keyless_gate as G

spec = importlib.util.spec_from_file_location("keyless_sdk_build", SDK / "build.py")
B = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = B
spec.loader.exec_module(B)


class KeylessDeliveryGateTests(unittest.TestCase):
    def migration(self):
        return dict(scope="full", expected_members=1, deferred_idx=[],
            members=[dict(idx=0, name="api.o", status="FAIL", layout=dict(ref="elf", new="elf"),
                          problems=["T missing 1", "T extra 1"])],
            problems=["api.o: T missing 1", "api.o: T extra 1"])

    def test_deliberate_tu_migration_keeps_other_errors_fatal(self):
        gate = self.migration()
        self.assertEqual([], G.structural_problems(gate))
        for problem in ("ordinal sequence differs", "api.o: llvm-nm failed: fixture", "member extra: fixture"):
            changed = copy.deepcopy(gate)
            changed["problems"].append(problem)
            self.assertIn(problem, G.structural_problems(changed))
        changed = copy.deepcopy(gate)
        changed["members"][0]["layout"]["new"] = "bitcode"
        self.assertTrue(G.structural_problems(changed))

    def test_exact_retired_apis_fail_without_rejecting_business_keys(self):
        required = ["maplesu::sdk::get_root()", "maplesu::sdk::run_root_cmd(char const*, std::string&)",
                    "maplesu::sdk::get_all_modules_with_cb(void (*)(module_record const*))"]
        self.assertEqual([], G.retired_api_problems(["business_key"], required, required))
        problems = G.retired_api_problems(list(G.RETIRED_SYMBOLS),
            ["skroot_env::get_root(char const*)", "maplesu::sdk::run_root_cmd(char const*, char const*, std::string&)"], required)
        self.assertEqual(10, len(problems))

    def test_explicit_legacy_tls_keeps_keyless_consumer_independent(self):
        legacy = ["__set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW",
                  "__get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW"]
        current = ["maplesu::sdk::get_root()"]
        self.assertEqual([], G.retired_api_problems(legacy, current, current))
        self.assertEqual([], G.legacy_credential_problems([]))
        self.assertEqual(2, len(G.legacy_credential_problems(legacy)))

    def test_module_macros_select_actual_keyless_symbols(self):
        header = (SDK.parent / "include/maplesu/compat/native_module_abi.h").read_text(encoding="utf-8")
        symbols = G.module_entry_symbols(header)
        self.assertEqual(7, len(set(symbols)))
        self.assertEqual(4, sum(name.endswith("_keyless_v1") for name in symbols))

    def entries(self):
        entries = []
        for path in sorted((SDK / "manifest").glob("*.json")):
            if not path.name[:4].isdigit():
                continue
            row = json.loads(path.read_text(encoding="utf-8-sig"))
            entries.append(B.ManifestEntry(idx=row["idx"], name=row["name"],
                sources=[B.resolve_source(item) for item in row.get("sources", [])],
                source_raw=row.get("sources", []), flags_profile=row["flagsProfile"],
                status=row["status"], layout=row["layout"]))
        return entries

    def test_active_api_members_all_select_current_source(self):
        result = B.validate_keyless_sources(self.entries())
        self.assertTrue(result)

    def test_complete_source_build_selects_current_links(self):
        source = B.parse_args(["--all", "--source-selection", "fixture.json"])
        selected = B._select_stages(source)
        self.assertTrue(selected["pack"] and selected["gate"])
        self.assertFalse(selected["link_sample"] or selected["verify"])
        reference = B._select_stages(B.parse_args(["--all", "--reference-payloads"]))
        self.assertTrue(reference["link_sample"] and reference["verify"])

    def test_vendor_missing_and_ir_fallback_are_rejected(self):
        for kind in ("vendor", "missing", "ir"):
            entries = self.entries()
            entry = next(item for item in entries if item.idx == 23)
            if kind == "vendor":
                entry.status = B.S.STATUS_VENDOR_COPY
            elif kind == "missing":
                entry.sources = []
            else:
                entry.sources = [SDK / "legacy.bc"]
            with self.assertRaises(B.StageError):
                B.validate_keyless_sources(entries)


if __name__ == "__main__":
    unittest.main()
