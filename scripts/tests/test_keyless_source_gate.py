"""Current delivery gate contract tests using ordinary fixture bytes and mocked ELF/ar readers."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import source_sdk_delivery as D


class CurrentSourceGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="maplesu-keyless-gate-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.build_root = self.root / "build"
        self.tools = self.root / "runtime/maplesu-sdk/sdk/tools"
        for name in ("keyless_gate.py", "keyless_public_abi.cpp"):
            self.write(self.tools / name, (ROOT / "runtime/maplesu-sdk/sdk/tools" / name).read_bytes())
        header = self.root / "runtime/maplesu-sdk/include/maplesu/compat/native_module_abi.h"
        self.write(header, (ROOT / "runtime/maplesu-sdk/include/maplesu/compat/native_module_abi.h").read_bytes())
        self.symbols = ["abi_fixture_" + str(index) + ("_keyless_v1" if index < 4 else "") for index in range(7)]
        # Read the exact macro vocabulary; the binary parsers remain fixture boundaries.
        namespace = {}
        exec((self.tools / "keyless_gate.py").read_text(encoding="utf-8"), namespace)
        self.symbols = namespace["module_entry_symbols"](header.read_text(encoding="utf-8"))
        archive = self.file("build/archive.a")
        toolchain = [self.file("ndk/" + name) for name in ("clang++", "ld.lld", "llvm-nm", "llvm-cxxfilt")]
        members = [dict(name=f"member{index}.o", idx=index, status="PASS", layout=dict(ref="elf", new="elf"), problems=[]) for index in range(260)]
        raw = dict(scope="full", expected_members=260, members=members, problems=[], deferred_idx=[], status="PASS", summary={}, inputs=dict(new=dict(sha256=archive["sha256"], bytes=archive["size"])))
        raw_file = self.file("build/report/member-gate.json", json.dumps(raw).encode())
        provider = self.file("ndk/libc.so")
        trace = "\n".join([str(self.root / archive["path"]) + "(" + member["name"] + ")" for member in members] + [str(self.root / provider["path"])])
        output = self.file("build/archive.so")
        link = dict(exit_code=0, argv=[str(self.root / toolchain[0]["path"]), "-shared", "-Wl,--no-undefined", "-Wl,--no-gc-sections", "-Wl,--trace", "-Wl,--whole-archive", str(self.root / archive["path"]), "-Wl,--no-whole-archive", "-o", str(self.root / output["path"])], compiler=toolchain[0], linker=toolchain[1], tools_before=toolchain[:3], output=output, map=self.file("build/archive.map"), trace=self.file("build/archive.trace", trace.encode()), providers=[provider])
        public_object, public_output = self.file("build/public.o"), self.file("build/public.so")
        public = dict(schema="maplesu-sdk-keyless-public-abi/1", status="PASS", problems=[], runtime_acceptance=False, archive=archive, source=D.record(self.tools / "keyless_public_abi.cpp", self.root), object=public_object, output=public_output, tools_before=toolchain, dependencies=dict(dependency_complete=True, precompile_complete=True), module_symbols=self.symbols, required_symbols={}, legacy_credential_symbols=[])
        public["compile_argv"] = [str(self.root / toolchain[0]["path"]), "-c", str(self.tools / "keyless_public_abi.cpp"), "-o", str(self.root / public_object["path"])]
        public["link_argv"] = [str(self.root / toolchain[0]["path"]), "-shared", "-Wl,--no-undefined", "-Wl,--no-gc-sections", str(self.root / public_object["path"]), str(self.root / archive["path"]), "-o", str(self.root / public_output["path"])]
        self.file("build/report/keyless-public-abi.json", json.dumps(public).encode())
        validation = dict(archive=archive, raw_gate=raw_file, reference_equivalence=False, runtime_acceptance=False, link=link, symbol_tool=toolchain[2], symbols=self.file("build/symbols.json", json.dumps(dict(defined=[], undefined=[], weak_defined=[], weak_undefined=[])).encode()))
        gate = dict(schema="maplesu-sdk-keyless-delivery-gate/1", status="PASS", exit_code=0, problems=[], reference_equivalence=False, runtime_acceptance=False, complete_archive_link=link, historical_reference=dict(status="PASS", summary={}), public_abi="build/report/keyless-public-abi.json")
        self.build = dict(compile=dict(failures=[]), source_selection=dict(fixture=True), gate=gate, final_archive_stability=dict(passed=True), stages_run=["pack", "gate"], source_validation=validation, pack=dict(archive=archive["path"], bytes=archive["size"], sha256=archive["sha256"]), provenance=dict(toolchain=dict(ndk_root="ndk")), keyless_public_abi=public)
        self.addCleanup(patch.stopall)
        patch.object(D, "parse_ar", return_value=dict(members=members)).start()
        patch.object(D, "elf_symbol_sets", return_value=(set(self.symbols), set())).start()

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def file(self, name, data=b"ordinary fixture bytes"):
        path = self.root / name
        self.write(path, data)
        return D.record(path, self.root)

    def verify(self, build):
        return D.verify_source_build_status(build, self.build_root / "report/build-report.json", self.root, self.build_root)

    def test_current_gate_passes_without_legacy_review(self):
        self.update_public(required_symbols={}, legacy_credential_symbols=[])
        self.assertIsNone(self.verify(self.build))

    def update_public(self, **fields):
        self.build["keyless_public_abi"].update(fields)
        self.file("build/report/keyless-public-abi.json", json.dumps(self.build["keyless_public_abi"]).encode())

    def test_legacy_tls_symbols_do_not_retire_current_keyless_api(self):
        legacy = ["__set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW",
                  "__get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW"]
        self.build["source_validation"]["symbols"] = self.file("build/symbols.json", json.dumps(
            dict(defined=legacy, undefined=[], weak_defined=[], weak_undefined=[])).encode())
        self.update_public(legacy_credential_symbols=sorted(legacy), required_symbols={})
        self.assertIsNone(self.verify(self.build))

    def test_keyless_probe_requiring_legacy_credential_fails(self):
        symbol = "__get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW"
        self.update_public(required_symbols={symbol: symbol}, legacy_credential_symbols=[])
        with self.assertRaises(AssertionError):
            self.verify(self.build)

    def test_legacy_symbol_receipt_mismatch_fails(self):
        self.update_public(required_symbols={}, legacy_credential_symbols=["extra_fixture_symbol"])
        with self.assertRaises(AssertionError):
            self.verify(self.build)

    def test_mutations_fail_closed(self):
        mutations = [
            lambda b: b.update(failure=dict(exit_code=5)),
            lambda b: b["compile"]["failures"].append("fixture"),
            lambda b: b.update(source_selection=None),
            lambda b: b["gate"].update(status="FAIL"),
            lambda b: b["gate"].update(runtime_acceptance=True),
            lambda b: b["source_validation"].update(reference_equivalence=True),
            lambda b: b["source_validation"]["link"]["argv"].append("-Wl,--gc-sections"),
            lambda b: b["source_validation"]["link"]["output"].update(sha256="0" * 64),
            lambda b: b["final_archive_stability"].update(passed=False),
            lambda b: b["stages_run"].append("link_sample"),
            lambda b: b["keyless_public_abi"].update(status="FAIL"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                changed = copy.deepcopy(self.build)
                mutate(changed)
                with self.assertRaises((AssertionError, KeyError)):
                    self.verify(changed)


if __name__ == "__main__":
    unittest.main()
