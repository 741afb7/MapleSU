"""Replay admission, native selection and packaging without running a provider."""
import hashlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


derive = load("compat_derive", ROOT / "dirtyfrag/derive.py")
package = load("compat_package", ROOT / "package.py")
with patch.object(sys, "path", [str(ROOT), *sys.path]):
    builder = load("compat_builder", ROOT / "build.py")
KOS = ROOT / "dirtyfrag/upstream/stock-artifacts/ko"


def body(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class DirtyfragCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def overlay(self):
        output = self.directory / "overlay"
        return output, derive.derive(output)

    def test_every_embedded_family_dispatches_maplesu_bootstrap(self):
        output, files = self.overlay()
        for source in sorted(KOS.glob("*.ko")):
            with self.subTest(kmi=source.stem):
                key = "artifacts/" + source.stem + ".minimal.ko"
                self.assertIn(key, files)
                raw = (output / key).read_bytes()
                self.assertEqual(len(source.read_bytes()), len(raw))
                self.assertIn(derive.KO_BOOTSTRAP_REPLACEMENT + b"\0", raw)
                self.assertNotIn(derive.KO_BOOTSTRAP_ORIGINAL, raw)
                for symbol in derive.KO_SYMBOLS:
                    self.assertNotIn(symbol + b"\0", raw)

    def test_derived_native_selector_retains_reference_family_fallback(self):
        compiler = PROJECT / ".research/toolchains/llvm-mingw-20261020-ucrt-x86_64/bin/x86_64-w64-mingw32-clang.exe"
        if not compiler.is_file():
            compiler = shutil.which("cc") or shutil.which("clang")
        self.assertTrue(compiler, "a host C compiler is required")
        source = derive.derive_exp((ROOT / "dirtyfrag/upstream" / derive.EXP).read_text())
        selector = body(source, "static const struct KoImage *select_ko_image(")
        declarations = "\n".join(
            "char dfroot_ko_" + family + "_start[1], dfroot_ko_" + family + "_end[1];"
            for family in ("12_5_10", "13_5_10", "13_5_15", "14_5_15", "14_6_1", "15_6_6", "16_6_12", "17_6_18")
        )
        cases = [(12, 5, 10, 12), (13, 5, 10, 13), (13, 5, 15, 13),
                 (14, 5, 15, 14), (14, 6, 1, 14), (15, 6, 6, 15),
                 (16, 6, 12, 16), (17, 6, 18, 17), (16, 6, 6, 15),
                 (0, 5, 15, 13), (15, 6, 1, 14), (15, 6, 99, 0)]
        checks = []
        for android, major, minor, expected in cases:
            checks.append("p=select_ko_image(%d,%d,%d); if ((p ? p->android_release : 0) != %d) return 1;" %
                          (android, major, minor, expected))
        unit = "#include <stddef.h>\n" + declarations + "\nstruct KoImage { int android_release, kver_major, kver_minor; const char *start, *end; };\n" + selector
        unit += "\nint main(void) { const struct KoImage *p; " + "\n".join(checks) + " return 0; }\n"
        path = self.directory / "selector.c"
        path.write_text(unit)
        executable = self.directory / "selector.exe"
        subprocess.run([str(compiler), str(path), "-o", str(executable)], check=True, capture_output=True)
        result = subprocess.run([str(executable)], capture_output=True)
        self.assertEqual(0, result.returncode, "derived selector rejected a reference family fallback")

    def test_real_admission_does_not_veto_kernel_or_device_metadata(self):
        source = (ROOT / "android/src/main/java/dev/maplesu/manager/jailbreak/DirtyfragAttempt.java").read_text()
        methods = body(source, "static String exactKmi(") + "\n" + body(source, "public static Admission admit(")
        methods = methods.replace("android.os.Process.myUid()", "0")
        fixture = r'''
package dev.maplesu.manager.jailbreak;
import java.io.*;
import java.util.regex.*;
public class AdmissionFixture {
  static final String TAG="fixture";
  static final Pattern MAJOR_MINOR=Pattern.compile("^(\\d+)\\.(\\d+)");
  static final Pattern ANDROID=Pattern.compile("android(\\d+)");
  static class Context {}
  static class Os { static String release; static Uname uname(){return new Uname(release);} }
  record Uname(String release) {}
  static class Build {
    static String[] SUPPORTED_ABIS={"arm64-v8a"};
    static String FINGERPRINT="Xiaomi/fixture", SOC_MODEL="SM8750";
    static class VERSION { static String RELEASE="16", SECURITY_PATCH="2026-05-01"; }
  }
  static class Log { static void w(String tag,String message){} }
  static class JailbreakArtifacts {
    record Entry(String targetKmi, String koVariant, String koSha256, String sha256) {
      String koSha256(String kmi) { return koSha256; }
    }
    static Entry require(Context context,String name){return new Entry("android15-6.6","minimal","digest","digest");}
  }
  static String readBootId(){return "fixture-boot";}
  static String readKernelWindow(Context context){return null;}
  static int readEnforcing(){return -1;}
'''
        fixture += body(source, "public record Admission(") + "\n" + methods
        fixture += r'''
  public static void main(String[] args) throws Exception {
    for(String release:args){
      Os.release=release;
      Admission value=admit(new Context());
      if(!release.equals(value.kernelRelease())) throw new AssertionError("release lost");
      if(release.startsWith("6.1.") && !value.kmi().equals("android14-6.1")) throw new AssertionError("KMI observation lost");
    }
    Build.FINGERPRINT=""; Build.SOC_MODEL=""; Build.VERSION.SECURITY_PATCH="";
    admit(new Context());
  }
}
'''
        path = self.directory / "AdmissionFixture.java"
        path.write_text(fixture)
        subprocess.run([shutil.which("javac"), "-d", str(self.directory), str(path),
                        str(ROOT / "android/src/main/java/dev/maplesu/manager/jailbreak/JailbreakTargets.java")],
                       check=True, capture_output=True)
        releases = ["5.10.236-android12-9", "5.10.236-android13-9", "5.15.180-android13-9",
                    "5.15.180-android14-9", "6.1.166-android14-12", "6.6.118-android15-8",
                    "6.12.76-android16-8", "6.18.4-android17-8", "6.6.118", "6.6.118-android16-8", "6.99.1"]
        result = subprocess.run([shutil.which("java"), "-cp", str(self.directory),
                                 "dev.maplesu.manager.jailbreak.AdmissionFixture", *releases],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def report(self):
        output, files = self.overlay()
        modules = {}
        for source in sorted(KOS.glob("*.ko")):
            kmi = source.stem.removeprefix("dfroot-")
            path = output / ("artifacts/" + source.stem + ".minimal.ko")
            modules[kmi] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
        products = []
        for name in package.REQUIRED:
            if name == "libmaplesu_ghostlock_provider.so":
                continue
            path = self.directory / name
            path.write_bytes(b"fixture-" + name.encode())
            products.append({"name": name, "path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size})
        return {"koVariant": "minimal", "artifacts": products, "dirtyfragModules": modules,
                "dirtyfragOverlay": {"content": {"files": files}}}

    def package(self, report):
        with patch.object(package, "stage_profiles", return_value=([], {})), patch.object(package, "stage_ir", return_value={}):
            return package.build(report, self.directory, self.directory / "jni", self.directory / "assets/manifest.json")

    def test_packaging_records_all_actual_embedded_module_identities(self):
        report = self.report()
        document = self.package(report)
        first = next(row for row in document["artifacts"] if row["name"] == "libdfroot.so")
        self.assertEqual("", first["targetKmi"])
        self.assertEqual(set(report["dirtyfragModules"]), set(first["koImages"]))
        for kmi, record in report["dirtyfragModules"].items():
            self.assertEqual(record["sha256"], first["koImages"][kmi]["sha256"])

    def test_packaging_source_epoch_is_stable_across_repeated_runs(self):
        report = self.report()
        with patch.dict(package.os.environ, {"SOURCE_DATE_EPOCH": "1700000000"}):
            first = self.package(report)
            second = self.package(report)
        self.assertEqual("2023-11-14T22:13:20+00:00", first["generatedAtUtc"])
        self.assertEqual(first, second)

    def test_packaging_rejects_module_drift(self):
        report = self.report()
        path = Path(report["dirtyfragModules"]["android14-6.1"]["path"])
        path.write_bytes(path.read_bytes() + b"drift")
        with self.assertRaises(SystemExit):
            self.package(report)

    def test_packaging_rejects_missing_module_inventory(self):
        report = self.report()
        del report["dirtyfragModules"]
        with self.assertRaises(SystemExit):
            self.package(report)

    def test_packaging_rejects_a_missing_family(self):
        report = self.report()
        del report["dirtyfragModules"]["android14-6.1"]
        with self.assertRaises(SystemExit):
            self.package(report)

    def test_build_inventory_is_bound_to_the_native_incbin_bytes(self):
        stage = self.directory / "build/dirtyfrag/product-stage/ko"
        stage.mkdir(parents=True)
        binary = bytearray(b"native-fixture")
        for source in sorted(KOS.glob("*.ko")):
            shutil.copyfile(source, stage / source.name)
            binary.extend(source.read_bytes())
        native = self.directory / "build/jailbreak-product/libdfroot.so"
        native.parent.mkdir()
        native.write_bytes(binary)
        inventory = builder.dirtyfrag_modules(self.directory)
        self.assertEqual(8, len(inventory))
        target = stage / "dfroot-android14-6.1.ko"
        target.write_bytes(target.read_bytes() + b"drift")
        with self.assertRaisesRegex(ValueError, "does not embed android14-6.1"):
            builder.dirtyfrag_modules(self.directory)


if __name__ == "__main__":
    unittest.main(verbosity=2)
