#!/usr/bin/env python3
"""Build fresh native import type facts before an exact legacy target capture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def identity(path):
    data = path.read_bytes()
    return dict(path=str(path.resolve()), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("module-source", "kernel-build", "llvm-bin", "evidence"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    module, kernel, tool = args.module_source.resolve(), args.kernel_build.resolve(), args.llvm_bin.resolve()
    if (not (module / "SOURCE-SELECTION.json").is_file() or not (module / "Kbuild").is_file() or
            any(module.rglob("*.o")) or any(module.rglob("*.ko")) or (module / "probe.c").exists()):
        raise ValueError("ULTRA_REFERENCE_FRESH_CURRENT_STAGE_REQUIRED")
    selection = json.loads((module / "SOURCE-SELECTION.json").read_bytes())
    current = selection.get("formalRuntimeSelections", {}).get("currentSourceSelection")
    if not current or not current.get("repositoryPath"):
        raise ValueError("ULTRA_REFERENCE_CURRENT_SOURCE_SELECTION_REQUIRED")
    root = Path(__file__).resolve().parents[4]
    sys.path.insert(0, str(root / "scripts"))
    try:
        from claim_current_sources import read_current_source_selection
        read_current_source_selection(root, root / current["repositoryPath"], current["sha256"])
    finally:
        sys.path.pop(0)
    for row in selection.get("inputs", []):
        actual = identity(module / row["staged"])
        if any(actual[key] != row["selected"][key] for key in ("bytes", "sha256")):
            raise ValueError("ULTRA_REFERENCE_STAGED_SOURCE_CHANGED:" + row["staged"])
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    probe = Path(__file__).with_name("native_reference_probe.c")
    (module / "probe.c").write_bytes(probe.read_bytes())
    inputs = [*sorted(path for path in module.rglob("*") if path.is_file()),
              Path(__file__).resolve(), probe.resolve(), kernel / ".config", kernel / "include/generated/utsrelease.h"]
    before = [identity(path) for path in inputs]
    make = ["make", "-C", str(kernel), "M=" + str(module), "ARCH=arm64", "LLVM=1", "LLVM_IAS=1",
        "CC=clang", "LD=ld.lld", "AR=llvm-ar", "NM=llvm-nm", "STRIP=llvm-strip", "OBJCOPY=llvm-objcopy",
        "OBJDUMP=llvm-objdump", "READELF=llvm-readelf", "V=1", "-j1"]
    commands = [make + ["CFLAGS_probe.o=-DMSU_INIT_BRIDGE_HOST_IMPORTS=1"] +
        (["obj-m=probe.o"] if name == "probe.o" else []) + [name]
        for name in ("native_imports.o", "native_sdk_imports.o", "probe.o")]
    commands += [[str(tool / "llvm-ar"), "cDPrST", str(module / "reference.o")] +
        [str(module / name) for name in ("native_imports.o", "native_sdk_imports.o", "probe.o")],
        [str(tool / "ld.lld"), "-EL", "-maarch64elf", "-z", "norelro", "-mllvm", "-import-instr-limit=5",
         "-z", "noexecstack", "-r", "-o", str(module / "reference.lto.o"), "--whole-archive", str(module / "reference.o")]]
    record = dict(schema="maplesu-current-native-import-reference/1", inputs=before,
        tools=[identity(tool / name) for name in ("clang", "ld.lld", "llvm-ar")],
        commands=[], fullModule=False, deviceOperations=False)
    environment = dict(os.environ, PATH=str(tool) + os.pathsep + os.environ["PATH"], SOURCE_DATE_EPOCH="1700000000")
    code = 0
    try:
        for index, argv in enumerate(commands):
            with (evidence / (str(index) + ".log")).open("xb") as stream:
                code = subprocess.run(argv, env=environment, stdout=stream, stderr=subprocess.STDOUT).returncode
            record["commands"].append(dict(argv=argv, exitCode=code))
            if code:
                break
        record["inputsUnchanged"] = before == [identity(path) for path in inputs]
        record["toolsUnchanged"] = record["tools"] == [identity(Path(row["path"])) for row in record["tools"]]
        if not code and record["inputsUnchanged"] and record["toolsUnchanged"]:
            record["reference"] = identity(module / "reference.lto.o")
    finally:
        (evidence / "REFERENCE.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    if code:
        raise SystemExit(code)
    if "reference" not in record:
        raise ValueError("ULTRA_REFERENCE_INPUT_CHANGED")
    print(json.dumps(record["reference"]))


if __name__ == "__main__":
    main()
