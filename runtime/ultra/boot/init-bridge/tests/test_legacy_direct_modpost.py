"""Fixed-Kbuild input and argument regressions; no compiler or modpost runs."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("legacy_modpost_flags", HERE / "legacy_direct_modpost.py")
seam = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seam)


class DirectModpostSources(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="maplesu-modpost-sources-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.declared = self.write("declared-input", b"selected input")
        self.selection = self.write("SOURCE-SELECTION.json", json.dumps({"inputs": [
            {"staged": self.declared.name, "selected": seam.identity(self.declared)}]}).encode())

    def write(self, name, data=b"input"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def test_generated_compile_inputs_remain_frozen(self):
        names = ("Kbuild", "Makefile", "selected-runtime.mk", "selected-core.mk",
                 "boot_sdk_layout.h", "native_factory_bind.c", "native_factory_bind.h",
                 "native-private/seccomp-release-source.c", "native-private/seccomp-release-header.h",
                 "host/msu_resolver_build_config.h", "host/manager_task_state_native_facts.c",
                 "generated.S", "generated.s", "generated.inc")
        generated = [self.write(name) for name in names]
        sources = seam.staged_sources(self.root)
        self.assertEqual(set(sources), {self.selection, self.declared, *generated})
        for path in sources:
            before = seam.identity(path)
            path.write_bytes(path.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "DIRECT_INPUT_CHANGED"):
                seam.checked(before)
            path.write_bytes(path.read_bytes()[:-7])

    def test_cached_build_outputs_are_not_immutable_sources(self):
        names = ("host/.manager_owner.o.cmd", "host/manager_owner.o", "Module.symvers",
                 "maplesu_ultra_bridge.mod.c", "maplesu_ultra_bridge.mod.o",
                 "maplesu_ultra_bridge.mod", "maplesu_ultra_bridge.o", "maplesu_ultra_bridge.lto.o",
                 "maplesu_ultra_bridge.ko", "maplesu_ultra_bridge.lds", "modules.order",
                 "preinit/runner.elf")
        outputs = [self.write(name) for name in names]
        sources = seam.staged_sources(self.root)
        before = [seam.identity(path) for path in sources]
        for path in outputs:
            path.write_bytes(b"rebuilt output")
        self.assertEqual(sources, [self.selection, self.declared])
        self.assertEqual(before, [seam.identity(path) for path in seam.staged_sources(self.root)])

    def test_selected_inputs_still_require_exact_identity_and_path(self):
        self.declared.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "STAGED_IDENTITY"):
            seam.staged_sources(self.root)
        self.selection.write_text(json.dumps({"inputs": [{"staged": "../outside", "selected": {}}]}))
        with self.assertRaisesRegex(ValueError, "STAGED_MEMBER"):
            seam.staged_sources(self.root)


class DirectModpostArguments(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="maplesu-modpost-flags-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.source, self.kernel = self.root / "module", self.root / "kernel"
        self.source.mkdir()
        self.kernel.mkdir()
        self.symvers = self.kernel / "Module.symvers"
        self.symvers.write_text("0x00000001\tmodule_layout\tvmlinux\tEXPORT_SYMBOL\t\n", encoding="utf-8")
        self.obj = self.source / "maplesu_ultra_bridge.lto.o"
        self.obj.write_bytes(b"argument fixture; not an ELF or executed object")
        self.request = {"moduleSource": str(self.source), "kernelBuild": str(self.kernel)}
        self.arguments = ["-m", "-E", "-o", str(self.source / "Module.symvers"), "-e",
                          "-i", str(self.symvers), "-T", "-"]
        self.stdin = (str(self.obj) + "\n").encode()

    def facts(self, arguments):
        return seam.arguments_facts(arguments, self.stdin, self.request)

    def test_make_n_keeps_existing_argument_facts(self):
        expected = self.facts(self.arguments)
        for arguments in (["-n", *self.arguments], [*self.arguments, "-n"]):
            with self.subTest(arguments=arguments):
                self.assertEqual(self.facts(arguments), expected)
                self.assertEqual(arguments.count("-n"), 1)

    def test_duplicate_n_and_unsupported_flags_still_fail(self):
        for suffix in (["-n", "-n"], ["-w"], ["-k"], ["--unknown"]):
            with self.subTest(suffix=suffix):
                with self.assertRaisesRegex(ValueError, "MODPOST_ARGUMENTS"):
                    self.facts([*self.arguments, *suffix])

    def test_n_keeps_required_flags(self):
        for missing in ("-m", "-e"):
            with self.subTest(missing=missing):
                with self.assertRaisesRegex(ValueError, "MODPOST_ARGUMENTS"):
                    self.facts([arg for arg in self.arguments if arg != missing] + ["-n"])

    def test_n_keeps_exact_output_and_module_list(self):
        wrong_output = list(self.arguments)
        wrong_output[wrong_output.index("-o") + 1] = str(self.root / "Module.symvers")
        with self.assertRaisesRegex(ValueError, "MODPOST_OUTPUT"):
            self.facts([*wrong_output, "-n"])
        self.stdin = (str(self.root / "other.o") + "\n").encode()
        with self.assertRaisesRegex(ValueError, "MODPOST_OBJECT"):
            self.facts([*self.arguments, "-n"])

    def test_n_keeps_real_symvers_and_duplicate_checks(self):
        missing = self.arguments.index("-i")
        without_symvers = self.arguments[:missing] + self.arguments[missing + 2:]
        with self.assertRaisesRegex(ValueError, "REAL_SYMVERS_REQUIRED"):
            self.facts([*without_symvers, "-n"])
        with self.assertRaisesRegex(ValueError, "REAL_SYMVERS_REQUIRED"):
            self.facts([*self.arguments, "-i", str(self.symvers), "-n"])

    def test_n_does_not_skip_missing_or_invalid_symvers(self):
        self.symvers.unlink()
        with self.assertRaises(FileNotFoundError):
            self.facts([*self.arguments, "-n"])
        self.symvers.write_bytes(b"invalid symbol version dump\n")
        with self.assertRaisesRegex(ValueError, "SYMVERS_FORMAT"):
            self.facts([*self.arguments, "-n"])


if __name__ == "__main__":
    unittest.main()
