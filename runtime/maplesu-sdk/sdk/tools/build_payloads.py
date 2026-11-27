#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_payloads.py -- compile the embedded payload programs from ``sdk/src/payload/``.

What this tool replaces
-----------------------
Downstream of this stage ``sdk/assets/*.bin`` used to be copied verbatim from the
frozen archive: the four ``maplesu_*_install.o`` members embed them as byte arrays
(``maplesu::sdk::su_data`` &c.), and the installer code sizes every buffer from
``sizeof(array)``.  This tool builds those very programs from source trees under
``sdk/src/payload/<program>/`` instead, so the embedded array is a build product:

    compile  every program.json TU  ->  build/payload/<name>/obj/*.o
    link     PIE aarch64 executable ->  build/payload/<name>/<name>
    check    ELF identity, dynamic import/export surface vs the pinned reference,
             keyless installer contract
    publish  build/out/assets/<asset>.bin + build/out/assets/PAYLOADS.json

Program trees
-------------
Each program is described by a ``program.json`` next to its sources::

    {
      "name": "webui_loader",
      "asset": "webui_loader.bin",
      "member": "maplesu_unsafe_webui_loader_install.o",
      "symbol": "webui_loader_data",
      "origin": "runtime/maplesu-sdk/sdk/src/payload/webui_loader/README.md",
      "reference": {"bytes": 766320, "sha256": "9f65..."},
      "sources": ["src/main.cpp", "..."],
      "include_dirs": ["src", "third_party"],
      "defines": ["..."],
      "cflags": ["-std=c++17"],
      "ldflags": ["-static-libstdc++", "-Wl,--gc-sections"],
      "libs": ["dl", "m"],
    }

The boot helper is built by ``build_helper.py`` through the SDK's ``build.py``
entry point and supplied with its build receipt. Reference images are copied
only in the explicit ``--reference-only`` comparison mode.

Usage
-----
    py tools/build_payloads.py --source-helper HELPER --source-helper-report REPORT --sdk-archive SDK
    py tools/build_payloads.py --only su --sdk-archive SDK  # one program (repeatable)
    py tools/build_payloads.py --reference-only --out DIR  # copy all 4 pinned images
    py tools/build_payloads.py --check         # verify outputs, write nothing
    py tools/build_payloads.py --list          # show the program table

Exit codes: 0 ok, 1 build/validation failure, 2 usage/environment.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SDK = HERE.parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import sdk_common as S          # noqa: E402
import source_provenance as P   # noqa: E402

PAYLOAD_SRC_DIR = SDK / "src" / "payload"
REFERENCE_ASSETS_DIR = SDK / "assets"
BUILD_DIR = SDK / "build" / "payload"
DEFAULT_OUT = SDK / "build" / "out" / "assets"
MANIFEST_NAME = "PAYLOADS.json"

ANDROID_TARGET = "aarch64-linux-android21"
ANDROID_API = "21"
PROGRAM_SPEC = "program.json"

BASE_CFLAGS = [
    f"--target={ANDROID_TARGET}",
    "-fPIE",
    "-fPIC",
    "-O2",
    "-DNDEBUG",
    "-D_FORTIFY_SOURCE=2",
    "-fstack-protector-strong",
    "-ffunction-sections",
    "-fdata-sections",
    "-fno-omit-frame-pointer",
    "-Wall",
    "-Wextra",
    "-Wno-unused-parameter",
]

BASE_LDFLAGS = [
    f"--target={ANDROID_TARGET}",
    "-fPIE",
    "-pie",
    "-Wl,--gc-sections",
    "-Wl,-z,now",
    "-Wl,-z,relro",
    "-Wl,--build-id=sha1",
    "-Wl,-s",
    "-static-libstdc++",
]


# --------------------------------------------------------------------------- #
# small helpers
# --------------------------------------------------------------------------- #

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(Path(path).read_bytes())


def load_elf(path: Path):
    """Return an ElfFile (from tools/extract_payloads.py) for ``path``."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "extract_payloads", str(HERE / "extract_payloads.py"))
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module, module.ElfFile(Path(path).read_bytes())


def elf_identity(elf) -> dict:
    """Public identity facts of a payload ELF (no section symbol assumptions)."""
    interp = ""
    for ph in elf.phdrs:
        if ph["type"] == 3:  # PT_INTERP
            raw = elf.data[ph["offset"]:ph["offset"] + ph["filesz"]]
            interp = raw.split(b"\x00", 1)[0].decode("ascii", "replace")
    return {
        "class": elf.ei_class,
        "machine": elf.e_machine,
        "type": elf.e_type,
        "entry": elf.e_entry,
        "interp": interp,
        "load_segments": sum(1 for ph in elf.phdrs if ph["type"] == 1),
        "phnum": elf.e_phnum,
        "sections": len(elf.sections),
    }


def dynamic_symbols(elf) -> dict:
    """{exports: [...], imports: [...]} from .dynsym, sorted and de-duplicated."""
    exports: set[str] = set()
    imports: set[str] = set()
    for section in elf.sections:
        if section.type != 11 or not section.entsize:      # SHT_DYNSYM
            continue
        for name, _value, _size, info, shndx in elf.symbols(section):
            if not name:
                continue
            bind = info >> 4
            if shndx == 0:
                imports.add(name)
            elif bind in (1, 2):                            # GLOBAL / WEAK
                exports.add(name)
    return {"exports": sorted(exports), "imports": sorted(imports)}


def surface_report(built: dict, reference: dict | None) -> dict:
    """Compare the dynamic symbol surface with the pinned reference image."""
    report = {
        "exports": built["exports"],
        "imports": built["imports"],
        "reference_available": reference is not None,
    }
    if reference is None:
        return report
    ref_imports = set(reference["imports"])
    ref_exports = set(reference["exports"])
    out_imports = set(built["imports"])
    out_exports = set(built["exports"])
    report.update({
        "reference_imports": sorted(ref_imports),
        "reference_exports": sorted(ref_exports),
        "imports_matched": sorted(ref_imports & out_imports),
        "imports_missing": sorted(ref_imports - out_imports),
        "imports_extra": sorted(out_imports - ref_imports),
        "exports_missing": sorted(ref_exports - out_exports),
        "exports_extra": sorted(out_exports - ref_exports),
    })
    total = len(ref_imports) + len(ref_exports)
    covered = len(ref_imports & out_imports) + len(ref_exports & out_exports)
    report["reference_surface_coverage"] = round(covered / total, 4) if total else None
    return report


def spec_paths(spec: dict, program_dir: Path) -> list[Path]:
    sources = spec.get("sources") or []
    if not isinstance(sources, list) or not sources:
        raise ValueError("program.json: 'sources' must be a non-empty list")
    out = []
    for item in sources:
        path = (program_dir / str(item)).resolve()
        if not path.is_file():
            raise ValueError(f"program.json: source not found: {item}")
        out.append(path)
    return out


def load_specs(only: set[str] | None = None) -> list[dict]:
    programs: list[dict] = []
    if PAYLOAD_SRC_DIR.is_dir():
        for program_dir in sorted(p for p in PAYLOAD_SRC_DIR.iterdir() if p.is_dir()):
            spec_file = program_dir / PROGRAM_SPEC
            if not spec_file.is_file():
                continue
            spec = json.loads(spec_file.read_text(encoding="utf-8"))
            spec["_dir"] = program_dir
            spec.setdefault("name", program_dir.name)
            spec.setdefault("asset", f'{spec["name"]}.bin')
            if only and spec["name"] not in only:
                continue
            programs.append(spec)
    return programs


def reference_assets(only: set[str] | None = None) -> list[dict]:
    """Assets declared in sdk/assets/ASSETS.json (the pinned reference images)."""
    declared = []
    spec_file = REFERENCE_ASSETS_DIR / "ASSETS.json"
    if spec_file.is_file():
        doc = json.loads(spec_file.read_text(encoding="utf-8"))
        for entry in doc.get("assets") or []:
            asset = Path(str(entry.get("asset") or "")).name
            if not asset:
                continue
            if only and Path(asset).stem not in only:
                continue
            declared.append({
                "asset": asset,
                "member": entry.get("member"),
                "symbol": entry.get("symbol"),
                "reference": {"bytes": entry.get("size"), "sha256": entry.get("sha256")},
            })
    return declared


def run(argv: list[str], cwd: Path, log_path: Path) -> subprocess.CompletedProcess[str]:
    completed = S.run(argv, cwd=cwd)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("$ " + S.fmt_cmd(argv) + "\n\n")
        handle.write(f"[exit code] {completed.returncode}\n\n")
        handle.write("--- stdout ---\n" + (completed.stdout or "") + "\n")
        handle.write("--- stderr ---\n" + (completed.stderr or "") + "\n")
    return completed


# --------------------------------------------------------------------------- #
# build one program
# --------------------------------------------------------------------------- #

def build_program(spec: dict, tc: S.Toolchain, out_dir: Path, log_dir: Path,
                  build_dir: Path, sdk_archive: Path | None = None) -> dict:
    name = str(spec["name"])
    program_dir: Path = spec["_dir"]
    asset = str(spec["asset"])
    program_build = build_dir / name
    obj_dir = program_build / "obj"
    if obj_dir.is_dir():
        shutil.rmtree(obj_dir)
    obj_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    program_log = log_dir / name
    program_log.mkdir(parents=True, exist_ok=True)

    sources = spec_paths(spec, program_dir)
    clang = tc.tool("clang++")
    clang_c = tc.tool("clang") if tc.has("clang") else clang
    defines = list(spec.get("defines") or [])
    include_dirs = list(spec.get("include_dirs") or [])
    cflags = BASE_CFLAGS + [str(x) for x in (spec.get("cflags") or [])]
    includes = [f"-I{program_dir / item}" for item in include_dirs]
    sdk_inputs = []
    if spec.get("link_sdk"):
        if sdk_archive is None or not sdk_archive.is_file():
            raise ValueError(f"{name}: explicit source bootstrap SDK archive required")
        includes += [f"-I{SDK / 'include'}", f"-I{S.MAPLESU_SDK_ROOT / 'include'}"]
        sdk_inputs = [str(sdk_archive)]

    rows = []
    problems: list[str] = []
    objects: list[Path] = []
    for index, source in enumerate(sources):
        language = "assembly" if source.suffix.lower() == ".s" else "c" if source.suffix.lower() == ".c" else "c++"
        driver = clang if language == "c++" else clang_c
        obj = obj_dir / f"{index:02d}-{source.stem}.o"
        argv = [str(driver), *cflags, *includes]
        argv += [f"-D{d}" for d in defines]
        std = [x for x in cflags if x.startswith("-std=")]
        if language == "c++" and not std:
            argv.append("-std=c++17")
        if language != "c++":
            argv = [x for x in argv if not x.startswith("-std=")]
        argv += P.dependency_flags(source, obj)
        argv += ["-c", str(source), "-o", str(obj)]
        precompile = P.precompile_snapshot(argv, source, obj, program_dir, S.REPO_ROOT)
        obj.unlink(missing_ok=True)
        P.depfile_path(obj).unlink(missing_ok=True)
        completed = run(argv, program_dir, program_log / f"compile-{index:02d}.log")
        if completed.returncode != 0 or not obj.is_file() or obj.stat().st_size == 0:
            problems.append(f"compile failed: {source.name} (exit {completed.returncode})")
            continue
        objects.append(obj)
        rows.append({"source": S.rel(source), "build_source": S.rel(source),
                     "source_sha256": sha256_file(source), "object": S.rel(obj),
                     "bytes": obj.stat().st_size, "sha256": sha256_file(obj),
                     "language": language, "program": name, "stage": "payload",
                     **P.dependency_receipt(source, obj, program_dir, S.REPO_ROOT,
                                            precompile=precompile)})

    if problems:
        return {"name": name, "asset": asset, "status": "failed", "problems": problems,
                "sources": rows}

    binary = program_build / name
    libs = [f"-l{lib}" for lib in (spec.get("libs") or [])]
    argv = [str(clang), *BASE_LDFLAGS, *[str(x) for x in (spec.get("ldflags") or [])],
            *[str(o) for o in objects], *sdk_inputs, *libs, "-o", str(binary)]
    binary.unlink(missing_ok=True)
    completed = run(argv, program_dir, program_log / "link.log")
    if completed.returncode != 0 or not binary.is_file():
        return {"name": name, "asset": asset, "status": "failed",
                "problems": [f"link failed (exit {completed.returncode})"],
                "sources": rows}

    blob = binary.read_bytes()
    result: dict = {
        "name": name,
        "asset": asset,
        "member": spec.get("member"),
        "symbol": spec.get("symbol"),
        "origin": spec.get("origin"),
        "status": "built",
        "source_dir": S.rel(program_dir),
        "sources": rows,
        "built": {"bytes": len(blob), "sha256": sha256_bytes(blob),
                  "path": S.rel(binary)},
        "problems": [],
    }
    if sdk_inputs:
        result["sdk_archive"] = P.file_record(sdk_archive, S.REPO_ROOT)
    reference = sdk_reference_image(asset)
    result["reference"] = reference

    # ---- ELF identity + dynamic surface -----------------------------------
    try:
        ep, elf = load_elf(binary)
        identity = elf_identity(elf)
        built_symbols = dynamic_symbols(elf)
        reference_symbols = None
        reference_identity = None
        if reference.get("available"):
            ref_path = REFERENCE_ASSETS_DIR / asset
            _ep2, ref_elf = load_elf(ref_path)
            reference_symbols = dynamic_symbols(ref_elf)
            reference_identity = elf_identity(ref_elf)
        result["elf"] = identity
        result["reference_elf"] = reference_identity
        result["dynsym"] = surface_report(built_symbols, reference_symbols)
        problems += validate_identity(identity, reference_identity)
    except Exception as exc:                                # noqa: BLE001
        problems.append(f"ELF validation raised: {exc}")

    # Source programs use verified process identity and public deployment paths.
    if spec.get("feature_token"):
        problems.append("source payload must not declare an installer credential token")

    result["problems"] = problems
    if problems:
        result["status"] = "failed"
        return result

    published = out_dir / asset
    shutil.copyfile(binary, published)
    result["published"] = {"path": S.rel(published), "bytes": published.stat().st_size,
                           "sha256": sha256_file(published)}
    return result


def sdk_reference_image(asset: str) -> dict:
    """Pinned reference metadata for ``asset`` (ASSETS.json + on-disk image)."""
    path = REFERENCE_ASSETS_DIR / asset
    info = {"available": path.is_file()}
    spec_file = REFERENCE_ASSETS_DIR / "ASSETS.json"
    if spec_file.is_file():
        doc = json.loads(spec_file.read_text(encoding="utf-8"))
        for entry in doc.get("assets") or []:
            if Path(str(entry.get("asset") or "")).name == asset:
                info.update({
                    "member": entry.get("member"),
                    "symbol": entry.get("symbol"),
                    "declared_bytes": entry.get("size"),
                    "declared_sha256": entry.get("sha256"),
                })
    if path.is_file():
        info["bytes"] = path.stat().st_size
        info["sha256"] = sha256_file(path)
    return info


def validate_identity(identity: dict, reference: dict | None) -> list[str]:
    problems: list[str] = []
    if identity["class"] != 2:
        problems.append("built payload is not ELF64")
    if identity["machine"] != 183:
        problems.append("built payload is not AArch64")
    if identity["type"] != 3:
        problems.append("built payload is not ET_DYN (PIE)")
    if not identity["interp"]:
        problems.append("built payload has no PT_INTERP is interp missing?")
    if identity["load_segments"] < 2:
        problems.append("built payload has fewer than two PT_LOAD segments")
    if reference:
        if reference["machine"] != identity["machine"]:
            problems.append("machine class differs from the reference image")
        if reference["type"] != identity["type"]:
            problems.append("ELF type differs from the reference image")
    return problems


# --------------------------------------------------------------------------- #
# stage entry point
# --------------------------------------------------------------------------- #

def publish_source_helper(source: Path, report_path: Path, out_dir: Path) -> dict:
    """Publish the exact complete-source helper described by its build receipt."""
    source, report_path = source.resolve(), report_path.resolve()
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("readiness") != "source-candidate-unvalidated-device":
        raise ValueError("helper build is not a complete-source candidate")
    surface = report.get("surface") or {}
    if (source.stat().st_size, sha256_file(source)) != (surface.get("bytes"), surface.get("sha256")):
        raise ValueError("helper bytes differ from the source build receipt")
    if (S.REPO_ROOT / str(report.get("out") or "")).resolve() != source:
        raise ValueError("helper input path differs from the source build receipt")
    provenance = report.get("provenance") or {}
    if (provenance.get("schema") != P.SCHEMA or not provenance.get("closure_complete")
            or not provenance.get("translation_units")):
        raise ValueError("helper source dependency receipt is incomplete")
    target = out_dir / "autorun_bootstrap.bin"
    shutil.copyfile(source, target)
    return {
        "name": "autorun_bootstrap", "asset": target.name,
        "member": "maplesu_autorun_bootstrap_install.o",
        "symbol": "_ZN10skroot_envL27autorun_bootstrap_file_dataE",
        "status": "built", "source_dir": S.rel(SDK / "src" / "helper"),
        "readiness": report["readiness"],
        "built": {"path": S.rel(source), "bytes": surface["bytes"], "sha256": surface["sha256"]},
        "published": {"path": S.rel(target), "bytes": target.stat().st_size, "sha256": sha256_file(target)},
        "helper_report": P.file_record(report_path, S.REPO_ROOT),
        "backend": report.get("backend"),
        "reference": sdk_reference_image(target.name),
        "provenance": provenance, "problems": [],
    }


def build_all(only: set[str] | None, out_dir: Path, ndk: str | None,
              log_dir: Path | None = None, build_dir: Path | None = None,
              reference_only: bool = False, source_helper: Path | None = None,
              source_helper_report: Path | None = None,
              sdk_archive: Path | None = None) -> dict:
    wants_helper = only is None or "autorun_bootstrap" in only
    if reference_only and (source_helper or source_helper_report):
        raise ValueError("reference-only mode does not consume a source helper")
    if not reference_only and wants_helper and not (source_helper and source_helper_report):
        raise ValueError("source payloads require --source-helper and --source-helper-report")
    tc = None if reference_only else S.resolve_toolchain(ndk)
    build_dir = build_dir or (BUILD_DIR if out_dir.resolve() == DEFAULT_OUT.resolve()
                              else out_dir.parent / "payload-build")
    log_dir = log_dir or build_dir / "logs"
    build_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    inputs = [(Path(__file__), "build_script"), (Path(S.__file__), "build_script"),
              (Path(P.__file__), "build_script"), (HERE / "extract_payloads.py", "build_script"),
              (REFERENCE_ASSETS_DIR / "ASSETS.json", "manifest")]
    input_snapshots = P.snapshot_inputs(inputs, S.REPO_ROOT)
    spec_files = sorted(PAYLOAD_SRC_DIR.glob("*/" + PROGRAM_SPEC)) if not reference_only else []
    input_snapshots += P.snapshot_inputs([(path, "program_spec") for path in spec_files], S.REPO_ROOT)
    specs = [] if reference_only else load_specs(only)
    if any(spec.get("link_sdk") for spec in specs):
        if sdk_archive is None or not sdk_archive.is_file():
            raise ValueError("source SU payload requires --sdk-archive from this source bootstrap")
        sdk_archive = sdk_archive.resolve()
        inputs.append((sdk_archive, "sdk_archive"))
    reference_entries = reference_assets(only)
    inputs += [(spec["_dir"] / PROGRAM_SPEC, "program_spec") for spec in specs]
    inputs += [(REFERENCE_ASSETS_DIR / entry["asset"], "asset") for entry in reference_entries
               if (REFERENCE_ASSETS_DIR / entry["asset"]).is_file()]
    if source_helper and source_helper_report:
        inputs += [(source_helper, "source_helper"), (source_helper_report, "build_report")]
    input_snapshots += P.snapshot_inputs(inputs, S.REPO_ROOT)
    programs: list[dict] = []
    if not reference_only and wants_helper:
        programs.append(publish_source_helper(source_helper, source_helper_report, out_dir))
    seen: set[str] = set()
    for spec in specs:
        assert tc is not None
        result = build_program(spec, tc, out_dir, log_dir, build_dir, sdk_archive)
        programs.append(result)
        seen.add(str(spec["name"]))

    # Reference bytes are a deliberate comparison input, never a source fallback.
    for entry in reference_entries:
        stem = Path(entry["asset"]).stem
        if stem in seen or any(p["asset"] == entry["asset"] for p in programs):
            continue
        source = REFERENCE_ASSETS_DIR / entry["asset"]
        result = {
            "name": stem,
            "asset": entry["asset"],
            "member": entry.get("member"),
            "symbol": entry.get("symbol"),
            "status": "reference-copy",
            "source_dir": None,
            "reference": sdk_reference_image(entry["asset"]),
            "problems": [],
        }
        if not reference_only:
            result["status"] = "failed"
            result["problems"] = [f"payload source has not been supplied: {entry['asset']}"]
        elif not source.is_file():
            result["status"] = "failed"
            result["problems"] = [f"asset has no source tree and no reference image: {entry['asset']}"]
        else:
            target = out_dir / entry["asset"]
            shutil.copyfile(source, target)
            blob = target.read_bytes()
            try:
                _ep, elf = load_elf(target)
                result["elf"] = elf_identity(elf)
                result["dynsym"] = surface_report(dynamic_symbols(elf),
                                                  dynamic_symbols(elf))
            except Exception as exc:                        # noqa: BLE001
                result["problems"].append(f"reference image is not a readable ELF: {exc}")
                result["status"] = "failed"
            result["built"] = {"bytes": len(blob), "sha256": sha256_bytes(blob),
                               "path": S.rel(target)}
            result["published"] = dict(result["built"])
            reference = result["reference"]
            if (reference.get("declared_sha256") and
                    result["built"]["sha256"] != reference["declared_sha256"]):
                result["status"] = "failed"
                result["problems"].append("reference asset differs from ASSETS.json")
        programs.append(result)

    version = ""
    if tc is not None:
        completed = S.run([str(tc.tool("clang")), "--version"])
        if completed.returncode == 0:
            version = (completed.stdout or "").splitlines()[0].strip()

    manifest = {
        "schema": "maplesu-sdk-payloads/1",
        "generated_by": "runtime/maplesu-sdk/sdk/tools/build_payloads.py",
        "programs_root": S.rel(PAYLOAD_SRC_DIR),
        "asset_root": S.rel(out_dir),
        "build_root": S.rel(build_dir),
        "log_root": S.rel(log_dir),
        "input_mode": "reference" if reference_only else "source",
        "target": ANDROID_TARGET,
        "toolchain": {"clang": version, "ndk": str(tc.ndk) if tc is not None else None},
        "programs": sorted(programs, key=lambda item: item["asset"]),
    }
    manifest["summary"] = {
        "programs": len(programs),
        "built": sum(1 for p in programs if p["status"] == "built"),
        "reference_copy": sum(1 for p in programs if p["status"] == "reference-copy"),
        "failed": sum(1 for p in programs if p["status"] == "failed"),
    }
    units = [row for program in programs for row in program.get("sources") or []]
    manifest["provenance"] = P.build_receipt(
        units, inputs, S.REPO_ROOT,
        P.toolchain_record(tc, S.REPO_ROOT) if tc is not None else None,
        children=[program["provenance"] for program in programs if program.get("provenance")],
        incomplete=[f"payload build failed: {program['asset']}" for program in programs
                    if program["status"] == "failed"], input_snapshots=input_snapshots)
    S.write_json(out_dir / MANIFEST_NAME, manifest)
    return manifest


def list_programs() -> int:
    specs = load_specs(None)
    print(f"{'program':22s} {'asset':26s} {'sources':>7s}  dir")
    for spec in specs:
        print(f"{spec['name']:22s} {spec['asset']:26s} "
              f"{len(spec.get('sources') or []):7d}  {S.rel(spec['_dir'])}")
    known = {str(spec["name"]) for spec in specs}
    for entry in reference_assets(None):
        stem = Path(entry["asset"]).stem
        if stem not in known:
            print(f"{stem:22s} {entry['asset']:26s} {'-':>7s}  "
                  f"(reference image only -- no source tree yet)")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="compile the embedded payload programs under sdk/src/payload")
    parser.add_argument("--out", default=str(DEFAULT_OUT),
                        help="asset output directory (default sdk/build/out/assets)")
    parser.add_argument("--ndk", default=None, help="Android NDK root override")
    parser.add_argument("--build-dir", default=None,
                        help="payload intermediate directory (custom --out stays isolated)")
    parser.add_argument("--log-dir", default=None, help="payload log directory (default <build-dir>/logs)")
    parser.add_argument("--reference-only", action="store_true",
                        help="copy all 4 reference payloads for comparison")
    parser.add_argument("--source-helper", type=Path,
                        help="complete-source helper built by the SDK entry point")
    parser.add_argument("--source-helper-report", type=Path,
                        help="build_helper.py receipt for --source-helper")
    parser.add_argument("--sdk-archive", type=Path,
                        help="same-invocation source bootstrap SDK for the SU payload")
    parser.add_argument("--only", action="append", default=None,
                        help="build only this program (repeatable)")
    parser.add_argument("--list", action="store_true", help="show the program table and exit")
    parser.add_argument("--check", action="store_true",
                        help="verify published assets against the manifest, write nothing")
    args = parser.parse_args(argv)

    only = set(args.only) if args.only else None
    if args.list:
        return list_programs()

    out_dir = Path(args.out)
    if not out_dir.is_absolute():
        out_dir = (SDK / out_dir).resolve()

    if args.check:
        manifest_path = out_dir / MANIFEST_NAME
        if not manifest_path.is_file():
            print(f"[E] payload manifest not found: {manifest_path}", file=sys.stderr)
            return 2
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        problems = 0
        for program in manifest.get("programs") or []:
            published = program.get("published") or {}
            path = out_dir / str(program["asset"])
            if program["status"] == "failed":
                problems += 1
                print(f"[E] {program['asset']}: recorded failure", file=sys.stderr)
            elif not path.is_file():
                problems += 1
                print(f"[E] {program['asset']}: published asset missing", file=sys.stderr)
            elif published.get("sha256") and sha256_file(path) != published["sha256"]:
                problems += 1
                print(f"[E] {program['asset']}: sha256 drift", file=sys.stderr)
        if problems:
            return 1
        print(f"[ok] {len(manifest.get('programs') or [])} payload(s) verified in {out_dir}")
        return 0

    try:
        manifest = build_all(only, out_dir, args.ndk,
                             Path(args.log_dir).resolve() if args.log_dir else None,
                             Path(args.build_dir).resolve() if args.build_dir else None,
                             args.reference_only, args.source_helper, args.source_helper_report,
                             args.sdk_archive)
    except (S.ToolchainMissing, OSError, ValueError) as exc:
        print(f"[E] payload build cannot run: {exc}", file=sys.stderr)
        return 2
    for program in manifest["programs"]:
        status = program["status"]
        details = []
        if program.get("built"):
            details.append(f'{program["built"]["bytes"]:,d} B')
            details.append(program["built"]["sha256"][:16])
        print(f'[{status:14s}] {program["asset"]:26s} ' + " ".join(details))
        for problem in program.get("problems") or []:
            print(f'                 ! {problem}', file=sys.stderr)
    summary = manifest["summary"]
    print(f'\n{summary["built"]} built, {summary["reference_copy"]} reference-copy, '
          f'{summary["failed"]} failed -> {out_dir}')
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
