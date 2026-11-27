#!/usr/bin/env python3
"""Send the existing corpus to the current production file builder; never flash a device."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from specgen import product


ROOT = Path(__file__).resolve().parents[2]
DRIVER = Path(__file__).with_name("VerifyImageMain.kt")


def identity(path):
    path = Path(path).resolve()
    return {"path": str(path), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def corpus_inputs(corpus):
    manifest = json.loads((corpus / "MANIFEST.json").read_text(encoding="utf-8"))
    expected_files = {row["path"]: row for row in manifest["files"]}
    rows, seen = [], set()
    for expected in manifest["files"]:
        if not expected["path"].endswith("/init_boot.img"):
            continue
        path = (corpus / expected["path"]).resolve()
        if not path.is_relative_to(corpus.resolve()):
            raise ValueError("CORPUS_PATH_OUTSIDE_ROOT")
        actual = identity(path)
        if any(actual[key] != expected[key] for key in ("bytes", "sha256")):
            raise ValueError("CORPUS_INPUT_CHANGED:" + str(path))
        if actual["sha256"] in seen:
            continue
        seen.add(actual["sha256"])
        metadata = path.with_name("metadata.json")
        metadata_identity = identity(metadata)
        metadata_expected = expected_files.get(metadata.relative_to(corpus.resolve()).as_posix())
        if metadata_expected is None or any(metadata_identity[key] != metadata_expected[key] for key in ("bytes", "sha256")):
            raise ValueError("CORPUS_METADATA_CHANGED:" + str(metadata))
        facts = json.loads(metadata.read_text(encoding="utf-8"))
        if facts["image"]["sha256"] != actual["sha256"]:
            raise ValueError("CORPUS_METADATA_IMAGE_MISMATCH:" + str(metadata))
        rows.append({"id": path.parent.name, "input": actual, "metadata": metadata_identity,
                     "kernelFacts": facts.get("kernel"), "declaredPairedBoot": facts.get("pairedBoot")})
    if len(rows) != manifest["uniqueInitBoots"]:
        raise ValueError("CORPUS_UNIQUE_COUNT_MISMATCH")
    return rows


def compile_driver(output, commands, inputs):
    # A fresh per-run cache compiles every selected product source, rather than reusing old classes.
    product.HOST_CACHE = output / "host-runtime"
    original = product._checked_build

    def checked(command, code):
        entry = {"argv": command, "failureCode": code}
        commands.append(entry)
        for index, value in enumerate(command):
            candidates = value.split(os.pathsep) if index and command[index - 1] in ("-cp", "-classpath") else [value]
            for item in candidates:
                path = Path(item)
                if path.is_file() and not path.resolve().is_relative_to(output):
                    inputs.setdefault(str(path.resolve()), identity(path))
        try:
            result = original(command, code)
            entry["exitCode"] = 0
            return result
        except product._HostBuildFailure as error:
            entry["exitCode"] = error.returncode
            raise

    product._checked_build = checked
    java, host, classpath = product._host_runtime()
    compiler_cp = os.pathsep.join(str(product.cached_jar(*item)) for item in (
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"),
        ("org.jetbrains", "annotations", "13.0"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0")))
    jar = output / "verify-images.jar"
    command = [java, "-Xmx768m", "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               *product.JVM_FLAGS, "-Xfriend-paths=" + str(host), "-classpath",
               str(host) + os.pathsep + classpath, "-d", str(jar), str(DRIVER)]
    checked(command, "IMAGE_VERIFY_DRIVER_COMPILE_FAILED")
    return java, os.pathsep.join((str(jar), str(host), classpath))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / "Boot_Research/init_boot-corpus")
    parser.add_argument("--assets", type=Path, help="Full same-generation Manager assets with both bridge catalogs and claim")
    parser.add_argument("--sdk-release", type=Path, help="Same-generation SDK-RELEASE.json, recorded as input; SDK is not executed")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true", help="Compile current production host closure and plan corpus; no payload processing")
    args = parser.parse_args()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    rows = corpus_inputs(args.corpus)
    source_paths = sorted((ROOT / "image-core/src/main/java").rglob("*.java"))
    source_paths += sorted((ROOT / "manager/src/main/java/dev/maplesu/manager/ultrakernel").glob("*.kt"))
    source_paths += [ROOT / ("manager/src/main/java/" + name) for name in (
        "dev/maplesu/manager/workmode/Hashes.kt", "dev/maplesu/manager/workmode/WorkMode.kt",
        "dev/maplesu/manager/workmode/control/ControlPaths.kt", "dev/maplesu/manager/workmode/control/AtomicControlFile.kt",
        "dev/maplesu/manager/compat/UltraStateStorage.kt", "dev/maplesu/manager/compat/StoredInputs.kt")]
    source_paths += [Path(sys.executable), Path(__file__), Path(product.__file__), DRIVER, ROOT / "tools/specgen/HostMain.kt"]
    inputs = {str(path.resolve()): identity(path) for path in source_paths}
    inputs[str((args.corpus / "MANIFEST.json").resolve())] = identity(args.corpus / "MANIFEST.json")
    for row in rows:
        for key in ("input", "metadata"):
            inputs[row[key]["path"]] = row[key]
    assets = args.assets.resolve() if args.assets else None
    if not args.prepare_only:
        if assets is None or not assets.is_dir() or args.sdk_release is None:
            parser.error("--assets and --sdk-release are required for payload regression")
        for name in ("ultra-init-bridge/catalog.json", "ultra-init-bridge/catalog-kmi.json", "ultra-manager-claim/manifest.json"):
            if not (assets / name).is_file():
                raise ValueError("IMAGE_VERIFY_GENERATION_ASSET_MISSING:" + name)
        for path in sorted(assets.rglob("*")):
            if path.is_file():
                inputs[str(path.resolve())] = identity(path)
        inputs[str(args.sdk_release.resolve())] = identity(args.sdk_release)
    commands, results, runtime_artifacts = [], [], []
    try:
        java, classpath = compile_driver(output, commands, inputs)
        runtime_artifacts = [identity(path) for path in sorted(output.rglob("*.jar"))]
        if not args.prepare_only:
            for row in rows:
                facts = row["kernelFacts"] or {}
                kmi = facts.get("componentKmi")
                if not kmi:
                    results.append({**row, "status": "UNSUPPORTED", "stage": "TARGET_FACTS",
                                    "error": "IMAGE_VERIFY_TARGET_KMI_UNPROVEN", "productionInvoked": False})
                    continue
                work = output / row["id"]
                command = [java, "-Xmx768m", "-cp", classpath,
                           "dev.maplesu.manager.ultrakernel.VerifyImageMainKt", "--input", row["input"]["path"],
                           "--input-sha256", row["input"]["sha256"], "--assets", str(assets),
                           "--kmi", kmi, "--out", str(work)]
                process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
                work.mkdir(exist_ok=True)
                (work / "PROCESS.log").write_text(process.stdout + process.stderr, encoding="utf-8")
                result_path = work / "RESULT.json"
                result = json.loads(result_path.read_text()) if result_path.exists() else {
                    "status": "FAIL", "stage": "HOST_PROCESS", "error": "IMAGE_VERIFY_RESULT_MISSING"}
                if process.returncode != {"PASS": 0, "UNSUPPORTED": 2, "FAIL": 3}.get(result.get("status")):
                    result = {"status": "FAIL", "stage": "HOST_PROCESS", "error": "IMAGE_VERIFY_EXIT_RESULT_MISMATCH",
                              "reportedResult": result}
                results.append({**row, **result, "argv": command, "exitCode": process.returncode, "productionInvoked": True})
                print(row["id"], result["status"], result["stage"], flush=True)
    finally:
        changed = [before for path, before in inputs.items() if identity(path) != before]
        receipt = {"schema": "maplesu-production-corpus-regression/1", "scope": "HOST_PRODUCTION_FILE_ALGORITHMS",
                   "preparedOnly": args.prepare_only, "commands": commands, "plannedInputs": rows,
                   "currentVerificationInputs": list(inputs.values()), "changedInputs": changed, "results": results,
                   "compiledRuntimeArtifacts": runtime_artifacts,
                   "counts": {status: sum(row["status"] == status for row in results) for status in ("PASS", "FAIL", "UNSUPPORTED")},
                   "sdkExecuted": False, "deviceExecuted": False, "deviceBootVerified": False}
        (output / "RECEIPT.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if changed:
            raise RuntimeError("IMAGE_VERIFY_INPUT_CHANGED_DURING_RUN")
    return 0 if args.prepare_only or len(results) == len(rows) and all(row["status"] == "PASS" for row in results) else 1


if __name__ == "__main__":
    sys.exit(main())
