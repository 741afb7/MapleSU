#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_helper.py -- build the first-party boot helper from sdk/src/helper.

One command compiles the sources listed in ``sdk/src/helper/manifest.json`` with
the pinned flag profile, links them as an AArch64 PIE, exports exactly the two
privileged primitives, strips the result and verifies the emitted identity:

    py runtime/maplesu-sdk/sdk/tools/build_helper.py
    py runtime/maplesu-sdk/sdk/tools/build_helper.py --out build/out/helper/autorun_bootstrap

The vendor binary under ``runtime/maplesu-sdk/helper/`` is **not** read: the
only inputs are the manifest and the declared sources.  Byte identity with the
vendor helper is not a goal and is not checked; the emitted dynamic surface is
verified against the contract in ``sdk/notes/HELPER.md`` instead.

Exit codes: 0 ok, 2 toolchain, 3 manifest, 4 compile/link, 5 verification.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import struct
import sys
from pathlib import Path
from typing import Any

SDK_DIR = Path(__file__).resolve().parent.parent
TOOLS_DIR = SDK_DIR / "tools"
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import sdk_common as S  # noqa: E402
import source_provenance as P  # noqa: E402

MANIFEST = SDK_DIR / "src" / "helper" / "manifest.json"
DEFAULT_OUT = SDK_DIR / "build" / "helper" / "autorun_bootstrap"
DEFAULT_REPORT = SDK_DIR / "build" / "report" / "helper-build.json"
LOG_DIR = SDK_DIR / "build" / "logs"

EXIT_OK = 0
EXIT_TOOLCHAIN = 2
EXIT_MANIFEST = 3
EXIT_BUILD = 4
EXIT_VERIFY = 5


class HelperBuildError(RuntimeError):
    def __init__(self, message: str, code: int) -> None:
        super().__init__(message)
        self.code = code


def load_manifest(path: Path = MANIFEST) -> dict[str, Any]:
    if not path.is_file():
        raise HelperBuildError(f"helper manifest not found: {path}", EXIT_MANIFEST)
    try:
        manifest_input = P.file_record(path, S.REPO_ROOT)
        record = json.loads(path.read_text(encoding="utf-8"))
        if P.file_record(path, S.REPO_ROOT) != manifest_input:
            raise HelperBuildError("helper manifest changed during parsing", EXIT_MANIFEST)
    except (OSError, json.JSONDecodeError) as exc:
        raise HelperBuildError(f"helper manifest unreadable: {exc}", EXIT_MANIFEST) from exc
    sources = record.get("sources")
    if not isinstance(sources, list) or not sources:
        raise HelperBuildError("helper manifest declares no sources", EXIT_MANIFEST)
    resolved: list[Path] = []
    for raw in sources:
        if not isinstance(raw, str) or not raw:
            raise HelperBuildError(f"bad source entry: {raw!r}", EXIT_MANIFEST)
        candidate = (SDK_DIR / raw).resolve()
        try:
            candidate.relative_to(SDK_DIR.resolve())
        except ValueError as exc:
            raise HelperBuildError(f"source escapes the SDK tree: {raw}", EXIT_MANIFEST) from exc
        if not candidate.is_file():
            raise HelperBuildError(f"helper source missing: {raw}", EXIT_MANIFEST)
        resolved.append(candidate)
    for raw in record.get("repoSources", []):
        candidate = (S.REPO_ROOT / raw).resolve()
        try:
            candidate.relative_to(S.REPO_ROOT.resolve())
        except ValueError as exc:
            raise HelperBuildError(f"source escapes repository: {raw}", EXIT_MANIFEST) from exc
        if not candidate.is_file():
            raise HelperBuildError(f"helper source missing: {raw}", EXIT_MANIFEST)
        resolved.append(candidate)
    record["_resolved_sources"] = resolved
    record["_manifest_path"] = path.resolve()
    record["_manifest_input_snapshot"] = manifest_input
    profile = record.get("flagsProfile")
    if profile not in S.FLAG_PROFILES:
        raise HelperBuildError(f"unknown flagsProfile {profile!r}", EXIT_MANIFEST)
    if S.profile_emit_llvm(profile):
        raise HelperBuildError("helper flagsProfile must emit native objects", EXIT_MANIFEST)
    return record


def _run(tc: S.Toolchain, argv: list[str], log_path: Path, stage: str) -> None:
    completed = S.run(argv)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    S.write_text(log_path, "$ " + S.fmt_cmd(argv) + f"\n\n[exit code] {completed.returncode}\n\n"
                           f"--- stdout ---\n{completed.stdout}\n--- stderr ---\n{completed.stderr}\n")
    if completed.returncode != 0:
        raise HelperBuildError(
            f"{stage} failed (exit {completed.returncode}); log {log_path}:\n"
            f"{(completed.stderr or completed.stdout or '')[-4000:]}", EXIT_BUILD)


def _readelf(tc: S.Toolchain, args: list[str], binary: Path) -> str:
    completed = S.run([str(tc.tool("llvm-readelf")), *args, str(binary)])
    if completed.returncode != 0:
        raise HelperBuildError(f"llvm-readelf {' '.join(args)} failed: {completed.stderr}",
                               EXIT_VERIFY)
    return completed.stdout


def _dynamic_surface(tc: S.Toolchain, binary: Path) -> dict[str, Any]:
    header = _readelf(tc, ["-h"], binary)
    programs = _readelf(tc, ["-l"], binary)
    dynamic = _readelf(tc, ["-d"], binary)
    symbols = _readelf(tc, ["--dyn-syms"], binary)
    sections = _readelf(tc, ["-S"], binary)
    elf_type = ""
    machine = ""
    entry = ""
    for line in header.splitlines():
        if line.strip().startswith("Type:"):
            elf_type = line.split(":", 1)[1].strip()
        elif line.strip().startswith("Machine:"):
            machine = line.split(":", 1)[1].strip()
        elif line.strip().startswith("Entry point address:"):
            entry = line.split(":", 1)[1].strip()
    interpreter = ""
    for line in programs.splitlines():
        if "Requesting program interpreter" in line:
            interpreter = line.split(":", 1)[1].strip().rstrip("]")
    needed = [line.split("[")[1].split("]")[0] for line in dynamic.splitlines()
              if "NEEDED" in line]
    exported: list[str] = []
    undefined: list[str] = []
    for line in symbols.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        name = fields[7].split("@")[0]
        if fields[6] == "UND":
            undefined.append(name)
        elif fields[4] == "GLOBAL" and fields[5] == "DEFAULT":
            exported.append(name)
    has_symtab = any(".symtab" in line for line in sections.splitlines())
    return {
        "elfType": elf_type,
        "machine": machine,
        "entry": entry,
        "interpreter": interpreter,
        "needed": needed,
        "exported": sorted(exported),
        "undefined": sorted(set(undefined)),
        "hasSymtab": has_symtab,
        "bytes": binary.stat().st_size,
        "sha256": S.sha256_file(binary),
    }


def build_helper(tc: S.Toolchain, out_path: Path, record: dict[str, Any] | None = None,
                 report_path: Path | None = None,
                 log_dir: Path | None = None, *, sdk_archive: Path | None = None,
                 backend_identity: Path | None = None) -> dict[str, Any]:
    """Compile, link, strip and verify one helper executable."""
    input_snapshots = P.snapshot_inputs([(Path(__file__), "build_script"),
        (Path(S.__file__), "build_script"), (Path(P.__file__), "build_script")], S.REPO_ROOT)
    record = record if record is not None else load_manifest()
    manifest_path = Path(record.get("_manifest_path", MANIFEST))
    manifest_input = record.get("_manifest_input_snapshot")
    if not isinstance(manifest_input, dict):
        raise HelperBuildError("helper manifest parse-time input snapshot is missing", EXIT_MANIFEST)
    try:
        if P.file_record(manifest_path, S.REPO_ROOT) != manifest_input:
            raise HelperBuildError("helper manifest changed since parsing", EXIT_MANIFEST)
    except OSError as exc:
        raise HelperBuildError(f"helper manifest unreadable: {exc}", EXIT_MANIFEST) from exc
    input_snapshots.append({**manifest_input, "roles": ["manifest"]})
    if not sdk_archive or not Path(sdk_archive).is_file() or not backend_identity:
        raise HelperBuildError("complete helper requires explicit sdk_archive and backend_identity", EXIT_MANIFEST)
    sdk_archive = Path(sdk_archive).resolve()
    identity_header = Path(backend_identity).resolve()
    if identity_header.is_dir():
        identity_header /= "environment_backend_identity.h"
    identity_json = identity_header.parent / "backend-identity.json"
    try:
        input_snapshots += P.snapshot_inputs([(identity_header, "backend_identity"),
            (identity_json, "backend_identity"), (sdk_archive, "sdk_archive")], S.REPO_ROOT)
        identity = json.loads(identity_json.read_text(encoding="utf-8"))
        backend = identity_header.parent / identity["fileName"]
        input_snapshots += P.snapshot_inputs([(backend, "paired_backend")], S.REPO_ROOT)
        backend_bytes = backend.read_bytes()
        header_text = identity_header.read_text(encoding="utf-8")
        note = bytes.fromhex(identity["noteHex"])
        offset = identity["noteOffset"]
        if (len(backend_bytes) != identity["size"] or S.sha256_file(backend) != identity["sha256"] or
            backend_bytes[offset:offset + len(note)] != note or
            f'"{backend.name}"' not in header_text or
            f'#define MAPLESU_POLICY_BACKEND_SIZE {len(backend_bytes)}' not in header_text or
            f'#define MAPLESU_POLICY_BACKEND_NOTE_OFFSET {offset}' not in header_text or
            "{" + ",".join(str(v) for v in note) + "}" not in header_text):
            raise ValueError("backend/header identity mismatch")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise HelperBuildError(f"paired backend input invalid: {exc}", EXIT_MANIFEST) from exc
    out_path = Path(out_path).resolve()
    work = out_path.parent / ".helper-build"
    if work.is_dir():
        for child in work.iterdir():
            child.unlink()
    work.mkdir(parents=True, exist_ok=True)
    version_source = SDK_DIR / "src/maplesu/install/lifecycle.h"
    dynamic_list = (SDK_DIR / record["link"]["dynamicList"]).resolve()
    version_script = (SDK_DIR / record["link"]["versionScript"]).resolve()
    input_snapshots += P.snapshot_inputs([(version_source, "sdk_version_source"),
        (dynamic_list, "build_rule"), (version_script, "build_rule")], S.REPO_ROOT)
    version_match = re.search(r'kSdkVersionText\s*\[\s*\]\s*=\s*"(\d+)\.(\d+)\.(\d+)"',
                              version_source.read_text(encoding="utf-8"))
    if not version_match:
        raise HelperBuildError("SDK version source was not found", EXIT_MANIFEST)
    version_header = work / "helper_sdk_version.h"
    major, minor, patch = map(int, version_match.groups())
    version_code = major * 10000 + minor * 100 + patch
    version_header.write_text("#pragma once\n#define MAPLESU_HELPER_SDK_VERSION "
        "maplesu::sdk::MapleSUSdkVersion{" + ",".join(version_match.groups()) + "}\n"
        f"#define MAPLESU_HELPER_SDK_VERSION_CODE {version_code}\n", encoding="utf-8")
    log_dir = Path(log_dir) if log_dir is not None else LOG_DIR
    log_dir.mkdir(parents=True, exist_ok=True)

    flags = S.profile_flags(record["flagsProfile"], "cxx", record.get("extraFlags") or ())
    include_dirs = [flag for entry in record.get("includeDirs") or []
                    for flag in ("-I", str(SDK_DIR / entry))]
    include_dirs = ["-I", str(identity_header.parent), "-I", str(work), *include_dirs]
    objects: list[Path] = []
    compile_logs: list[str] = []
    translation_units: list[dict] = []
    for source in record["_resolved_sources"]:
        obj = work / (source.stem + ".o")
        is_c = source.suffix == ".c"
        source_flags = S.profile_flags(record["flagsProfile"], "c" if is_c else "cxx", record.get("extraFlags") or ())
        if source == (S.REPO_ROOT / 'runtime/ultra/modules/helper_module_gate.c').resolve():
            source_flags += ['-DMAPLESU_SOURCE_HELPER=1']
        argv = [str(tc.tool("clang" if is_c else "clang++")), *source_flags, *include_dirs,
                *P.dependency_flags(source, obj), "-c", str(source), "-o", str(obj)]
        precompile = P.precompile_snapshot(argv, source, obj, Path.cwd(), S.REPO_ROOT)
        log_path = log_dir / f"helper-compile-{source.stem}.log"
        compile_logs.append(S.rel(log_path))
        obj.unlink(missing_ok=True)
        P.depfile_path(obj).unlink(missing_ok=True)
        _run(tc, argv, log_path, f"compile {source.name}")
        objects.append(obj)
        translation_units.append({"source": S.rel(source), "build_source": S.rel(source),
                                  "object": S.rel(obj), "program": "autorun_bootstrap",
                                  "bytes": obj.stat().st_size, "sha256": S.sha256_file(obj),
                                  "stage": "source-helper",
                                  **P.dependency_receipt(source, obj, Path.cwd(), S.REPO_ROOT,
                                                         precompile=precompile)})

    linker_flags = [str(flag) for flag in record["link"].get("flags") or []]
    argv = [str(tc.tool("clang++")), S.profile_target(), *linker_flags,
            f"-Wl,--dynamic-list={dynamic_list}",
            f"-Wl,--version-script={version_script}",
            *[str(obj) for obj in objects], str(sdk_archive), "-o", str(out_path)]
    link_log = log_dir / "helper-link.log"
    out_path.unlink(missing_ok=True)
    _run(tc, argv, link_log, "link helper")

    strip = tc.bin_dir / ("llvm-strip.exe" if os.name == "nt" else "llvm-strip")
    stripped = False
    if strip.is_file():
        _run(tc, [str(strip), "--strip-all", str(out_path)], log_dir / "helper-strip.log", "strip helper")
        stripped = True

    surface = _dynamic_surface(tc, out_path)
    image = out_path.read_bytes()
    expected_exports = sorted(record.get("exportedSymbols") or [])
    expected_needed = sorted(record.get("neededLibraries") or [])
    problems: list[str] = []
    if surface["elfType"] != "DYN (Shared object file)":
        problems.append(f"not a PIE/DYN image: {surface['elfType']}")
    if surface["machine"] != "AArch64":
        problems.append(f"wrong machine: {surface['machine']}")
    if surface["interpreter"] != "/system/bin/linker64":
        problems.append(f"wrong interpreter: {surface['interpreter']!r}")
    if surface["exported"] != expected_exports:
        problems.append(f"exported symbols {surface['exported']} != {expected_exports}")
    if sorted(surface["needed"]) != expected_needed:
        problems.append(f"needed libraries {surface['needed']} != {expected_needed}")
    if surface["hasSymtab"]:
        problems.append(".symtab still present after strip")
    if problems:
        raise HelperBuildError("; ".join(problems), EXIT_VERIFY)

    from elftools.elf.elffile import ELFFile
    elf = ELFFile(io.BytesIO(image))
    capability_notes = []
    for segment in elf.iter_segments():
        if segment['p_type'] != 'PT_NOTE':
            continue
        for note in segment.iter_notes():
            if note['n_name'] == 'MapleSU' and note['n_type'] == 0x4d534831:
                if segment['p_flags'] != 4 or note['n_namesz'] != 8 or note['n_descsz'] != 16:
                    raise HelperBuildError('invalid helper capability note layout', EXIT_VERIFY)
                capability_notes.append(note)
    section = elf.get_section_by_name('.note.maplesu.helper')
    expected_capability = struct.pack('<IIII', 1, version_code, 1, 0)
    if (len(capability_notes) != 1 or capability_notes[0]['n_desc'] != expected_capability or
            section is None or section['sh_flags'] & 5 or not any(
                segment['p_type'] == 'PT_LOAD' and segment['p_flags'] == 4 and
                segment['p_offset'] <= section['sh_offset'] and
                section['sh_offset'] + section['sh_size'] <= segment['p_offset'] + segment['p_filesz']
                for segment in elf.iter_segments())):
        raise HelperBuildError('source helper module capability missing or not read-only', EXIT_VERIFY)

    report = {
        "schema": "maplesu-sdk-helper-build/1",
        "schemaVersion": 1,
        "generated_by": "runtime/maplesu-sdk/sdk/tools/build_helper.py",
        "readiness": "source-candidate-unvalidated-device",
        "backend": {"source": str(backend), "bytes": len(backend_bytes), "sha256": identity["sha256"],
                    "filename": backend.name, "identityHeader": str(identity_header),
                    "noteOffset": offset, "noteHex": identity["noteHex"]},
        "sdkArchive": {"source": str(sdk_archive), "bytes": sdk_archive.stat().st_size,
                       "sha256": S.sha256_file(sdk_archive)},
        "keyless": True,
        "manifest": S.rel(record.get("_manifest_path", MANIFEST)),
        "name": record.get("name"),
        "out": S.rel(out_path),
        "flagsProfile": record.get("flagsProfile"),
        "compileFlags": flags,
        "objects": [S.rel(obj) for obj in objects],
        "sources": [S.rel(source) for source in record["_resolved_sources"]],
        "linkCommand": argv,
        "stripped": stripped,
        "surface": surface,
        "contract": {
            "moduleBootGate": {"abiVersion": 1, "helperVersionCode": version_code,
                               "capabilities": 1, "noteType": 0x4d534831},
            "exportedSymbols": expected_exports,
            "neededLibraries": expected_needed,
            "byteIdentityWithVendor": False,
            "note": "first-party rebuild; vendor bytes are neither input nor acceptance target",
        },
        "logs": compile_logs + [S.rel(link_log)],
    }
    report["provenance"] = P.build_receipt(
        translation_units,
        [(Path(__file__), "build_script"), (Path(S.__file__), "build_script"),
         (Path(P.__file__), "build_script"),
         (Path(record.get("_manifest_path", MANIFEST)), "manifest"),
         (dynamic_list, "build_rule"), (version_script, "build_rule"),
         (version_source, "sdk_version_source"), (version_header, "generated_header"),
         (identity_header, "backend_identity"), (identity_json, "backend_identity"),
         (backend, "paired_backend"), (sdk_archive, "sdk_archive")],
        S.REPO_ROOT, P.toolchain_record(tc, S.REPO_ROOT), input_snapshots=input_snapshots)
    destination = Path(report_path) if report_path else DEFAULT_REPORT
    destination.parent.mkdir(parents=True, exist_ok=True)
    S.write_json(destination, report)
    report["reportPath"] = S.rel(destination)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--ndk", default=None)
    parser.add_argument("--sdk-archive", type=Path, required=True)
    parser.add_argument("--backend-identity", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        tc = S.resolve_toolchain(args.ndk)
        record = load_manifest(Path(args.manifest))
        report = build_helper(tc, Path(args.out), record, Path(args.report),
                              sdk_archive=args.sdk_archive, backend_identity=args.backend_identity)
    except S.ToolchainMissing as exc:
        print(f"[helper] toolchain: {exc}", file=sys.stderr)
        return EXIT_TOOLCHAIN
    except HelperBuildError as exc:
        print(f"[helper] FAILED: {exc}", file=sys.stderr)
        return exc.code
    surface = report["surface"]
    print(f"[helper] {report['out']} {surface['bytes']:,d} B sha256={surface['sha256'][:16]}")
    print(f"[helper] exports={surface['exported']} needed={surface['needed']} "
          f"stripped={report['stripped']}")
    print(f"[helper] report: {report['reportPath']}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
