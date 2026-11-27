"""Current keyless ABI checks, separate from historical T/U equivalence."""
from __future__ import annotations

import re
import ast


# The explicit legacy TLS adapter remains linkable; current public APIs must not require it.
LEGACY_CREDENTIAL_SYMBOLS = (
    "__set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW",
    "__get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW",
)

RETIRED_SYMBOLS = (
    "_ZN10skroot_env29set_boot_fail_protect_enabledEPKcb",
    "_ZN10skroot_env28is_boot_fail_protect_enabledEPKc",
    "_ZN10skroot_env23set_adb_forced_disabledEPKcb",
    "_ZN10skroot_env22is_adb_forced_disabledEPKc",
    "_ZN10skroot_env22get_root_status_reportEPKc",
    "_ZN10skroot_env16restart_zygote64EPKc",
    "_ZN10skroot_env26test_skroot_basics_with_cbEPKcNS_9BasicItemEPFvS1_E",
    "_ZN10skroot_env34test_skroot_deafult_module_with_cbEPKcNS_17DeafultModuleNameEPFvS1_E",
)


def module_entry_symbols(header: str) -> list[str]:
    result = []
    for label in ("ENTRY", "IDENTITY", "INSTALL", "UNINSTALL", "WEBUI",
                  "LAST_SERVER_PORT", "BEFORE_SERVER_EXIT"):
        match = re.search(r"^#define MAPLESU_MODULE_" + label + r"_SYMBOL\s+(.+)$", header, re.M)
        if match is None:
            raise ValueError("Missing current module ABI symbol: " + label)
        strings = re.findall(r'"(?:\\.|[^"\\])*"', match.group(1))
        if not strings:
            raise ValueError("Invalid current module ABI symbol: " + label)
        result.append("".join(ast.literal_eval(item) for item in strings))
    return result


def structural_problems(gate: dict) -> list[str]:
    """Only measured T/U migration differs; every structural/tool failure stays fatal."""
    migrated = set()
    problems = []
    rows = gate.get("members") or []
    if gate.get("scope") != "full" or len(rows) != gate.get("expected_members") or not rows:
        problems.append("current ABI requires every archive member")
    for row in rows:
        if row.get("status") == "MISSING" or row.get("layout", {}).get("ref") != row.get("layout", {}).get("new"):
            problems.append("missing/layout-changing member: " + str(row.get("idx")))
        for problem in row.get("problems") or []:
            if re.fullmatch(r"[TU] (?:missing|extra) [0-9]+", problem):
                migrated.add(row["name"] + ": " + problem)
    problems.extend(problem for problem in gate.get("problems") or [] if problem not in migrated)
    if gate.get("deferred_idx") or gate.get("allowlist", {}).get("invalid") or gate.get("baseline", {}).get("invalid"):
        problems.append("deferred members or invalid comparison rules")
    return problems


def _function(signature: str):
    # Count top-level parameters, preserving template and callback commas.
    at = signature.find("(")
    if at < 0 or not signature.endswith(")"):
        return None
    name = signature[:at].split()[-1]
    parameters = signature[at + 1:-1]
    depth = 0
    count = int(bool(parameters) and parameters != "void")
    for char in parameters:
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            depth -= 1
        elif char == "," and depth == 0:
            count += 1
    return name, count


def legacy_credential_problems(required_symbols) -> list[str]:
    return ["legacy credential required by keyless public API: " + symbol
            for symbol in sorted(set(required_symbols).intersection(LEGACY_CREDENTIAL_SYMBOLS))]


def retired_api_problems(raw_symbols: list[str], signatures: list[str],
                         required_signatures: list[str]) -> list[str]:
    problems = ["retired RootKey symbol: " + symbol for symbol in raw_symbols
                if symbol in RETIRED_SYMBOLS]
    expected = {}
    for signature in required_signatures:
        parsed = _function(signature)
        if parsed and parsed[0].startswith(("maplesu::sdk::", "maplesu::", "kernel_root::")):
            name, count = parsed
            expected.setdefault(name.rsplit("::", 1)[-1], set()).add(count)
    for signature in signatures:
        parsed = _function(signature)
        if not parsed or not parsed[0].startswith(("maplesu::sdk::", "skroot_env::", "kernel_root::")):
            continue
        name, count = parsed
        counts = expected.get(name.rsplit("::", 1)[-1])
        if counts and count not in counts:
            problems.append("retired parameter ABI: " + signature)
        if name.endswith("::parser_root_key_info"):
            problems.append("retired parser ABI: " + signature)
    return sorted(set(problems))
