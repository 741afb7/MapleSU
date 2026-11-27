#!/usr/bin/env python3
"""Generate a keyless Ultra kernel spec from a stock boot image.

The applier (``UltraKernelSpec`` / ``UltraKernelBuilder``) consumes ``offset|orig|repl`` records that
are pinned to one kernel build. This tool produces those records for *any* kernel build:

    boot image -> decoded raw Image -> kallsyms -> site offsets
                -> recipe (templates + relocations) -> records -> spec

Nothing is pinned to a release. Every site is a symbol reference plus a byte delta, every address
embedded in a replacement blob is a relocation resolved from the target kernel, and every digest
in the receipt is computed from the data that was just produced.

Usage
-----
    make_spec.py --boot Ace6/boot.img --out k22.spec
    make_spec.py --boot Ace6/boot.img --out k22.spec --receipt r.json
    make_spec.py --self-check                    # reproduces the shipped k22 on 6.6.89

Exit codes: 0 ok, 2 self-check failed, 3 input/recipe error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from specgen import bootimg, emit, kallsyms  # noqa: E402
from specgen.recipe import Recipe, RecipeError, check_credential  # noqa: E402

ROOT = HERE.parents[1]
DEFAULT_RECIPE = HERE / "specgen" / "recipes" / "k22.json"
DEFAULT_BOOT = ROOT / "Ace6" / "boot.img"
DEFAULT_JAR = ROOT / "image-core" / "build" / "libs" / "image-core.jar"

# The shipped 6.6.89 spec. It is the oracle for --self-check only: the generator never reads it
# when producing a spec, and no value in it is a constant in the recipe.
REFERENCE_SPEC = ROOT / "manager" / "src" / "main" / "assets" / "ultra-kernels" / "k22-ultra-6.6.89.spec"
REFERENCE_RELEASE = "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k"
REFERENCE_CREDENTIAL = b"8eORURvE0cAkp3mlIzCuUsEJrr4OfbjKfT6RvkjeIP6aKm6r"
REFERENCE_KERNEL_SHA = "287fbdd08c14bf62488e624d60fd5c5fd4de783712fd17673dd1e7c8c5c00f80"
REFERENCE_BOOT_SHA = "47aa147acbe3edf8e58ce60d334ad4c4b26cad26b5cb93aa6acb59a67fdd5f59"
REFERENCE_CHANGED = 842
REFERENCE_RECORDS = 18


class Failure(Exception):
    pass


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_credential(args) -> bytes:
    if args.credential and args.credential_file:
        raise Failure("--credential and --credential-file are mutually exclusive")
    if args.credential:
        return args.credential.encode("ascii")
    if args.credential_file:
        text = Path(args.credential_file).read_text(encoding="utf-8").strip()
        return text.encode("ascii")
    raise Failure("a 48 character credential is required (--credential/--credential-file)")


def load_symbols(raw: bytes, symbols_path) -> kallsyms.SymbolTable:
    if symbols_path:
        table = kallsyms.load_tsv(symbols_path)
        if table.image_size == 0:
            table.image_size = len(raw)
        return table
    return kallsyms.decode(raw)


def parse_spec_records(path: Path):
    """The oracle's records, in file order."""
    kernel, key, records = "", "", []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#kernel="):
            kernel = line[len("#kernel="):].strip()
            continue
        if line.startswith("#key="):
            key = line[len("#key="):].strip()
            continue
        if line.startswith("#"):
            continue
        offset, original, replacement = line.split("|")
        records.append((int(offset), bytes.fromhex(original), bytes.fromhex(replacement)))
    return kernel, key, records


def generate(recipe: Recipe, raw: bytes, credential: bytes, symbols_path=None,
             struct_overrides=None):
    table = load_symbols(raw, symbols_path)
    records, application = emit.generate(recipe, table, raw, credential, struct_overrides)
    return table, records, application


def parse_struct_offsets(values) -> dict:
    """``--struct-offsets task_struct.seccomp=0x9c8`` for builds whose anchor shape changed."""
    parsed = {}
    for value in values or ():
        name, _, number = value.partition("=")
        if not name or not number:
            raise Failure("--struct-offsets expects name=0xOFFSET")
        try:
            parsed[name.strip()] = int(number.strip(), 0)
        except ValueError:
            raise Failure("--struct-offsets value is not a number: %s" % number)
    return parsed


# ---------------------------------------------------------------------------------------------
# self-check
# ---------------------------------------------------------------------------------------------

class Checks:
    def __init__(self):
        self.rows = []
        self.failed = 0

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.rows.append((name, ok, detail))
        if not ok:
            self.failed += 1
        return ok

    def skip(self, name: str, detail: str) -> None:
        self.rows.append((name, None, detail))

    def report(self) -> str:
        lines = []
        for name, ok, detail in self.rows:
            mark = "SKIP" if ok is None else ("PASS" if ok else "FAIL")
            lines.append("%-5s %-46s %s" % (mark, name, detail))
        return "\n".join(lines)


def self_check(args) -> int:
    checks = Checks()
    boot = Path(args.boot or DEFAULT_BOOT)
    recipe_path = Path(args.recipe or DEFAULT_RECIPE)
    print("self-check inputs")
    print("  boot   :", boot)
    print("  recipe :", recipe_path)
    print("  oracle :", REFERENCE_SPEC)
    print()

    try:
        recipe = Recipe.load(recipe_path)
    except (OSError, RecipeError) as error:
        print("FAIL  recipe load: %s" % error)
        return 3
    report = bootimg.inspect(boot)
    container = bootimg.kernel_container(boot)
    raw = bootimg.decode(container)
    print("  boot header v%d, kernel %d B at %d, compression %s"
          % (report["headerVersion"], report["kernelSize"], report["kernelOffset"],
             "RAW_ARM64" if raw is container else "GZIP"))
    print("  raw kernel sha256:", sha256(raw))
    print()

    release = kallsyms.release_of(raw)
    checks.check("kernel release readable", bool(release), release)
    if release != recipe.reference_release:
        checks.check("release differs from recipe reference (experimental path)", True,
                     "%s (recipe reference %s)" % (release, recipe.reference_release))
    else:
        checks.check("release equals recipe reference", True, release)

    # 1. the symbol table the sites are resolved from
    table = load_symbols(raw, args.symbols)
    checks.check("kallsyms decoded", len(table.rows) > 1000,
                 "%d symbols, text base %s" % (len(table.rows),
                                               table.layout.get("text_base", "n/a")))
    checks.check("_text at file offset 0", table.offset_of("_text") == 0,
                 "offset=%s" % table.offset_of("_text"))

    # 2. every record's site symbol exists
    missing = [record.symbol for record in recipe.records if not table.has(record.symbol)]
    checks.check("all site symbols resolved", not missing,
                 "missing=%s" % missing if missing else "%d symbols" % len(recipe.records))

    # 3. render + apply
    try:
        records, application = generate(recipe, raw, REFERENCE_CREDENTIAL, args.symbols)[1:]
    except Exception as error:                                   # noqa: BLE001 - reported
        checks.check("render and apply", False, "%s: %s" % (type(error).__name__, error))
        print(checks.report())
        return 2
    checks.check("render and apply", True, "%d records" % len(records))
    checks.check("no byte outside a record changed", application.outside == 0,
                 "outside=%d" % application.outside)
    checks.check("changed byte count", application.changed == REFERENCE_CHANGED,
                 "%d (expected %d)" % (application.changed, REFERENCE_CHANGED))
    checks.check("patched kernel sha256", application.sha256 == REFERENCE_KERNEL_SHA,
                 application.sha256)
    matching, total, differing = emit.site_match(recipe, records)
    checks.check("every site still carries the reference pristine bytes", matching == total,
                 "%d/%d%s" % (matching, total,
                              "" if not differing else " differing=" + ",".join(differing)))
    residue = emit.address_residue(recipe, table, records)
    checks.check("no address left unrelocated in any blob", not residue,
                 "; ".join(residue[:3]) if residue else "no stale adr/symbol offsets")

    # 4. the generated records must equal the shipped ones, field by field
    if REFERENCE_SPEC.is_file():
        _oracle_release, _oracle_key, expected = parse_spec_records(REFERENCE_SPEC)
        same = len(expected) == len(records)
        detail = "%d vs %d records" % (len(records), len(expected))
        if same:
            for index, (record, (offset, original, replacement)) in enumerate(zip(records, expected)):
                if record.offset != offset or record.original != original \
                        or record.replacement != replacement:
                    same = False
                    detail = "record %d differs" % index
                    break
        checks.check("generated records == shipped k22 records", same, detail)
        checks.check("record count", len(records) == REFERENCE_RECORDS, str(len(records)))
    else:
        checks.skip("generated records == shipped k22 records", "oracle spec absent")

    # 5. the spec text round-trips through the applier's parser rules
    text = emit.spec_text(recipe, table, records, release, REFERENCE_CREDENTIAL)
    reparsed = [(int(parts[0]), bytes.fromhex(parts[1]), bytes.fromhex(parts[2]))
                for parts in (line.split("|") for line in text.splitlines()
                              if line and not line.startswith("#"))]
    checks.check("spec text round-trips", reparsed == [(r.offset, r.original, r.replacement)
                                                       for r in records],
                 "%d lines" % len(reparsed))

    # 6. negative control: an address inside a blob cannot be a constant. Moving a *target*
    #    symbol (never a site symbol, so every orig still matches) must change the bytes. One
    #    branch target and one envelope field, because they use different relocation kinds.
    for control in ("module_alloc", "kallsyms_lookup_name"):
        if not table.has(control):
            checks.skip("negative control: %s" % control, "symbol absent from this kernel")
            continue
        moved_rows = sorted((offset + 0x40 if name == control else offset, kind, name)
                            for offset, kind, name in table.rows)
        moved = kallsyms.SymbolTable(table.image_size, table.text_base, table.relative_base,
                                     moved_rows, dict(table.layout))
        try:
            moved_records, moved_application = emit.generate(recipe, moved, raw,
                                                             REFERENCE_CREDENTIAL)
            rewritten = sum(1 for before, after in zip(records, moved_records)
                            if before.replacement != after.replacement)
            checks.check("negative control: moving %s rewrites its call sites" % control,
                         moved_application.sha256 != application.sha256 and rewritten >= 1,
                         "%d record(s) rewritten, sha %s"
                         % (rewritten, moved_application.sha256[:16]))
        except Exception as error:                               # noqa: BLE001 - reported
            checks.check("negative control: moving %s rewrites its call sites" % control,
                         False, "%s: %s" % (type(error).__name__, error))

    # 7. a different credential changes only the credential fields
    other = b"A1B2C3D4E5F6G7H8" * 3
    other_records, other_application = generate(recipe, raw, other, args.symbols)[1:]
    credential_record = recipe.credential_record()
    site_of_credential = None
    for record, other_record in zip(records, other_records):
        if record.role == credential_record.role:
            site_of_credential = record.offset
    untouched = all(
        before.original == after.original
        for before, after in zip(records, other_records))
    checks.check("credential swap keeps every orig", untouched, "18/18")
    checks.check("credential swap changes the credential record",
                 any(before.offset == site_of_credential and before.replacement != after.replacement
                     for before, after in zip(records, other_records)),
                 "site 0x%X" % (site_of_credential or 0))
    checks.check("credential swap keeps the site map",
                 [r.offset for r in records] == [r.offset for r in other_records],
                 "%d sites" % len(records))
    checks.check("credential swap changes the kernel digest",
                 other_application.sha256 != application.sha256,
                 other_application.sha256[:16] + "...")

    # 8. boot digest, via the applier's own container rebuild
    jar = Path(args.image_core or DEFAULT_JAR)
    if jar.is_file() and args.verify_boot:
        java = args.java or "java"
        out = Path(args.work or (ROOT / ".tmp" / "specgen-selfcheck"))
        out.mkdir(parents=True, exist_ok=True)
        patched = out / "patched.raw"
        rebuilt = out / "rebuilt.img"
        patched.write_bytes(application.kernel)
        rebuilt.unlink(missing_ok=True)
        command = [java, "-cp", str(jar), "dev.maplesu.image.ImageTool", "rebuild-v4",
                   str(boot), str(patched), str(rebuilt)]
        try:
            subprocess.run(command, check=True, capture_output=True, timeout=600)
            checks.check("rebuilt boot sha256 == 47aa147a...",
                         sha256(rebuilt.read_bytes()) == REFERENCE_BOOT_SHA,
                         sha256(rebuilt.read_bytes()))
        except (subprocess.SubprocessError, OSError) as error:
            checks.skip("rebuilt boot sha256 == 47aa147a...",
                        "image-core rebuild unavailable: %s" % type(error).__name__)
    else:
        checks.skip("rebuilt boot sha256 == 47aa147a...",
                    "pass --verify-boot with an image-core.jar to run the applier's rebuild")

    print(checks.report())
    print()
    print("SELF-CHECK %s" % ("PASS" if checks.failed == 0 else "FAIL"))
    return 0 if checks.failed == 0 else 2


# ---------------------------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--boot", type=Path, help="stock boot image (read only)")
    parser.add_argument("--kernel", type=Path, help="raw ARM64 Image, instead of --boot")
    parser.add_argument("--recipe", type=Path, default=DEFAULT_RECIPE)
    parser.add_argument("--symbols", type=Path,
                        help="pre-decoded offset<TAB>type<TAB>name dump (else decode the image)")
    parser.add_argument("--out", type=Path, help="spec file to write")
    parser.add_argument("--receipt", type=Path, help="JSON receipt to write")
    parser.add_argument("--assets", type=Path, default=ROOT / "manager/src/main/assets",
                        help="APK assets root containing the matching ultra-manager-claim payload")
    parser.add_argument("--patched-kernel", type=Path, help="also write the validated raw kernel")
    parser.add_argument("--patched-boot", type=Path, help="also rebuild a candidate with the product builder")
    parser.add_argument("--require-release", help="refuse when the kernel release differs")
    parser.add_argument("--struct-offsets", action="append", metavar="NAME=0xOFFSET",
                        help="override a task_struct field offset the scan cannot resolve on this "
                             "build (repeatable)")
    parser.add_argument("--self-check", action="store_true",
                        help="reproduce the shipped k22 plan on the reference kernel")
    parser.add_argument("--verify-boot", action="store_true",
                        help="with --self-check: rebuild the boot through image-core.jar")
    parser.add_argument("--image-core", type=Path, help="image-core.jar for --verify-boot")
    parser.add_argument("--java", help="java executable for --verify-boot")
    parser.add_argument("--work", type=Path, help="scratch directory for --verify-boot")
    args = parser.parse_args(argv)

    if args.self_check:
        return self_check(args)

    try:
        if not args.out:
            raise Failure("--out is required")
        from specgen import product
        return product.generate(args)
    except (Failure, RecipeError, RuntimeError, OSError, ValueError) as error:
        print("ERROR %s" % error, file=sys.stderr)
        return 3


def legacy_reference_generation(args) -> int:
    """Retained only for historical oracle reading; product outputs use HostMainKt above."""
    try:
        credential = read_credential(args)
        recipe = Recipe.load(Path(args.recipe))
        check_credential(credential, recipe.credential_length)
        if args.kernel:
            raw = Path(args.kernel).read_bytes()
            boot_sha = ""
        elif args.boot:
            raw = bootimg.kernel(Path(args.boot))
            boot_sha = sha256(Path(args.boot).read_bytes())
        else:
            raise Failure("--boot or --kernel is required")
        release = kallsyms.release_of(raw)
        if not release:
            raise Failure("KERNEL_RELEASE_UNREADABLE")
        if args.require_release and release != args.require_release:
            raise Failure("ULTRA_KERNEL_RELEASE_MISMATCH:%s" % release)
        table, records, application = generate(recipe, raw, credential, args.symbols,
                                               parse_struct_offsets(args.struct_offsets))
        matching, total, differing = emit.site_match(recipe, records)
        text = emit.spec_text(recipe, table, records, release, credential)
        Path(args.out).write_text(text, encoding="utf-8", newline="\n")
        verified = release == recipe.reference_release and matching == total and total > 0
        receipt = {
            "schemaVersion": 1,
            "operation": "ULTRA_SPEC_GENERATE",
            "recipe": recipe.id,
            "recipeReferenceRelease": recipe.reference_release,
            "kernelRelease": release,
            "origin": "GENERATED",
            "verification": "verified" if verified else "experimental",
            "sites": len(records),
            "siteOrigMatch": "%d/%d" % (matching, total),
            "siteOrigDiffering": differing,
            "changedBytes": application.changed,
            "baseKernelSha256": sha256(raw),
            "patchedKernelSha256": application.sha256,
            "sourceBootSha256": boot_sha,
            "credentialSlotRecord": recipe.credential_record().role,
            "initPathRecords": [record.role for record in recipe.init_path_records()],
            "siteRoles": [record.role for record in records],
            "crossChecks": {
                "referenceKernelSha256Match": application.sha256 == REFERENCE_KERNEL_SHA,
                "referenceChangedBytesMatch": application.changed == REFERENCE_CHANGED,
                "noByteOutsideSites": application.outside == 0,
            },
            "specSha256": sha256(text.encode("utf-8")),
            "symbolLayout": table.layout,
        }
        if args.receipt:
            Path(args.receipt).write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n",
                                          encoding="utf-8", newline="\n")
        print("wrote %s  sites=%d changedBytes=%d" % (args.out, len(records), application.changed))
        print("  release        :", release)
        print("  base kernel    :", receipt["baseKernelSha256"])
        print("  patched kernel :", receipt["patchedKernelSha256"])
        print("  site orig match:", receipt["siteOrigMatch"])
        print("  verification   :", receipt["verification"])
        if not verified:
            print("  NOTE: this kernel's site bytes differ from the reference build; the payload's")
            print("        code blobs were compiled for %s. Treat the result as experimental."
                  % recipe.reference_release)
        return 0
    except (Failure, RecipeError, kallsyms.KallsymsError, bootimg.BootImageError,
            emit.ApplyError, OSError, ValueError) as error:
        print("ERROR %s: %s" % (type(error).__name__, error), file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
