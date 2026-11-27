#!/usr/bin/env python3
"""Check configuration requirements for a selected Ultra build route.

A .config alone does not establish kernel/module BTF compatibility or bootability.
Use --module-btf-mismatch only after checking the actual kernel/module pair.
"""
from __future__ import annotations

import argparse
from pathlib import Path


def parse(path: str | Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("# CONFIG_") and line.endswith(" is not set"):
            values[line.split()[1]] = "n"
        elif line.startswith("CONFIG_") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"')
    return values


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--require-modversions", action="store_true",
                        help="the selected module ABI requires symbol versions")
    parser.add_argument("--require-btf", action="store_true",
                        help="the selected layout producer requires vmlinux BTF")
    parser.add_argument("--module-btf-mismatch", action="store_true",
                        help="the actual kernel/module pair has a confirmed BTF mismatch")
    args = parser.parse_args(argv)
    try:
        values = parse(args.config)
    except OSError as error:
        parser.error(str(error))

    problems: list[str] = []
    for required, key in ((args.require_modversions, "CONFIG_MODVERSIONS"),
                          (args.require_btf, "CONFIG_DEBUG_INFO_BTF")):
        if required and values.get(key) != "y":
            problems.append(f"{key}=y is required by the selected route")
    module_btf = values.get("CONFIG_DEBUG_INFO_BTF_MODULES") == "y"
    allow_mismatch = values.get("CONFIG_MODULE_ALLOW_BTF_MISMATCH") == "y"
    if args.module_btf_mismatch and module_btf and not allow_mismatch:
        problems.append("confirmed module BTF mismatch: module BTF validation is enabled "
                        "and CONFIG_MODULE_ALLOW_BTF_MISMATCH is disabled; rebuild matching "
                        "kernel/modules or explicitly select the kernel's mismatch tolerance")

    print("ULTRA_KERNEL_CONFIG_UNSAFE" if problems else "ULTRA_KERNEL_CONFIG_OK")
    print("Scope: requested configuration checks; module ABI and device boot remain separate.")
    for problem in problems:
        print(f"  - {problem}")
    if module_btf and not args.module_btf_mismatch:
        print("Module BTF compatibility: unknown; .config does not compare BTF data.")
    elif args.module_btf_mismatch and module_btf and allow_mismatch:
        print("Module BTF mismatch is tolerated; affected modules load without their BTF.")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
