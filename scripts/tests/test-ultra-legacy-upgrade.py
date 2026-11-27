"""Compile the entire production upgrade entrypoint against Android/SDK boundary fixtures."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/specgen"))
from specgen.product import cached_jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    base = ROOT / "manager/src/main/java/dev/maplesu/manager/runtime/ultra"
    fixture = ROOT / "manager/src/test/resources/ultra-upgrade-jvm"
    sources = [base / name for name in ("UltraEnvironmentUpgrade.kt", "UltraEnvironmentUpgradePolicy.kt", "UltraLegacyCredential.kt")]
    sources += sorted(fixture.glob("*.kt"))
    kernel_entry = ROOT / "manager/src/main/java/com/maplesu/core/ui/util/KsuCli.kt"
    entry_source = kernel_entry.read_text(encoding="utf-8")
    entry_source = entry_source[entry_source.index("internal fun installUltraKernelUpdate("):entry_source.index("/** A builder can stop before any write")]
    generated_entry = out / "KernelUpgradeEntry.kt"
    generated_entry.write_text("package com.maplesu.core.ui.util\n" +
        "import dev.maplesu.manager.workmode.ModeRuntime\n" +
        "import dev.maplesu.manager.installer.InstallerServiceClient\n" +
        "import dev.maplesu.manager.installer.InstallTargetMode\n" +
        "import java.io.File\n" +
        "import android.util.Log\nprivate const val TAG = \"fixture\"\n" + entry_source, encoding="utf-8")
    inputs = sources + [Path(__file__), kernel_entry]
    sources += [generated_entry]
    initial = {str(path.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    dependencies = [cached_jar(*coordinate) for coordinate in [
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"),
        ("org.jetbrains", "annotations", "13.0"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"),
        ("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3"),
    ]]
    compiler = cached_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20")
    java = shutil.which("java")
    if java is None:
        raise RuntimeError("JAVA_REQUIRED")
    classes = out / "classes"
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, [classes, *dependencies]))
    commands = [
        ("compile", [java, "-Xmx256m", "-cp", os.pathsep.join(map(str, [compiler, *dependencies])),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn", "-jvm-target", "21",
            "-classpath", cp, "-d", str(classes), *map(str, sources)]),
        ("test", [java, "-Xmx128m", "-cp", cp, "org.junit.runner.JUnitCore", "dev.maplesu.manager.runtime.ultra.LegacyUpgradeRegression"]),
    ]
    steps = []
    output = ""
    for label, argv in commands:
        result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=55)
        output = result.stdout + result.stderr
        (out / (label + ".log")).write_text(output, encoding="utf-8")
        steps.append(dict(label=label, argv=argv, exitCode=result.returncode))
        print(label, result.returncode, output, flush=True)
        if result.returncode:
            break
    stable = all(hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == sha for name, sha in initial.items())
    passed = len(steps) == 2 and all(step["exitCode"] == 0 for step in steps) and stable
    count = re.search(r"OK \((\d+) tests?\)", output)
    receipt = dict(passed=passed, utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), sourceSha256=initial,
        inputsUnchanged=stable, tests=int(count[1]) if count else None, commands=steps,
        scope="Complete current production UltraEnvironmentUpgrade, upgrade policy and LegacyCredential. Android, SDK receipts, carrier installer and identity readback are external boundary fixtures; no device acceptance.")
    (out / "RESULT.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
