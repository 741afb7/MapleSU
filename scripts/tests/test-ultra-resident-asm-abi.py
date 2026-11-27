"""Compile the current resident assembly import boundary against an explicit host dependency jar."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/specgen")]
import native_toolchain
from specgen.product import cached_jar


def identity(path):
    return {"path": str(path), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    production = ROOT / "manager/src/main/java/dev/maplesu/manager/ultrakernel"
    paths = [production / name for name in ("UltraKernelBtf.kt", "UltraKernelResidentAbi.kt",
             "UltraKernelManagerClaimPayload.kt", "UltraKernelManagerClaimCarrier.kt")]
    paths += [ROOT / "manager/src/test/java/dev/maplesu/manager/ultrakernel/UltraKernelResidentAsmAbiTest.kt"]
    sources = []
    inputs = [identity(path) for path in paths]
    for index, path in enumerate(paths):
        copy = out / "sources" / str(index) / path.name
        copy.parent.mkdir(parents=True, exist_ok=True)
        copy.write_bytes(path.read_bytes())
        sources.append(copy)
    deps = [cached_jar(*entry) for entry in [
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.11.0"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0"),
        ("org.jetbrains", "annotations", "23.0.0"),
        ("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3")]]
    compiler = cached_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20")
    classpath = os.pathsep.join(map(str, [args.base.resolve(), *deps]))
    jar = out / "tests.jar"
    java = str(native_toolchain.jdk_tool("java"))
    commands = [
        [java, "-Xmx768m", "-cp", str(compiler) + os.pathsep + classpath,
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
         "-Xfriend-paths=" + str(args.base.resolve()), "-classpath", classpath, "-d", str(jar), *map(str, sources)],
        [java, "-Xmx768m", "-Dmaplesu.asm.assets=" + str(args.assets.resolve()), "-cp", str(jar) + os.pathsep + classpath,
         "org.junit.runner.JUnitCore", "dev.maplesu.manager.ultrakernel.UltraKernelResidentAsmAbiTest"]]
    results = []
    for index, command in enumerate(commands):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=180)
        (out / f"step-{index}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        print(result.stdout + result.stderr, flush=True)
        results.append({"argv": command, "exitCode": result.returncode})
        if result.returncode:
            break
    unchanged = inputs == [identity(path) for path in paths]
    (out / "RESULT.json").write_text(json.dumps({"status": "PASS" if result.returncode == 0 and unchanged else "FAIL",
        "inputs": inputs, "dependencyJar": identity(args.base.resolve()), "sourceUnchanged": unchanged,
        "deviceExecuted": False, "partitionWritten": False, "commands": results}, indent=2) + "\n", encoding="utf-8")
    return result.returncode if unchanged else 1


if __name__ == "__main__":
    raise SystemExit(main())
