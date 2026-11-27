#!/usr/bin/env python3
"""Exercise actual m060 receipt bodies with m046 transport and real host fork/pipes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

SDK = Path(__file__).resolve().parents[1]
ROOT = SDK.parents[2]
IMAGE = "sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e"


def extract(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    source_path = SDK / "src/maplesu_core/maplesu_root.cpp"
    fork_path = SDK / "src/maplesu_core/maplesu_fork_helper.cpp"
    fixture_path = SDK / "tests/p4_root_receipt_fixture.cpp"
    sources = [source_path, fork_path, fixture_path, Path(__file__),
        SDK / "include/maplesu_root_private.h", SDK / "include/maplesu_root_command_private.h",
        SDK / "include/maplesu_request_private.h", ROOT / "runtime/ultra/authorization/request_protocol.h",
        ROOT / "runtime/maplesu-sdk/include/module_err_def.h",
        SDK / "src/maplesu/compat/fork_legacy_abi.h"]
    before = {str(path.relative_to(ROOT)).replace("\\", "/"):
        hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}
    source = source_path.read_text(encoding="utf-8")
    fork = fork_path.read_text(encoding="utf-8")
    root_functions = "\n\n".join(extract(source, signature) for signature in (
        "void command_failure(", "void transport_failure(", "int pipe_terminal_error(",
        "void close_command_owner(", "bool send_owner_close(", "bool receive_owner_close(",
        "bool send_root_result(", "bool receive_root_result(", "bool send_command_result(",
        "void receive_command_result("))
    fork_functions = "\n\n".join(extract(fork, signature) for signature in (
        "static bool write_full(", "static bool read_full(", "bool fork_pipe_child_process(",
        "bool write_int_from_child(", "bool read_int_from_child(",
        "bool write_uint64_from_child(", "bool read_uint64_from_child(",
        "bool write_errcode_from_child(", "bool read_errcode_from_child(",
        "bool write_transfer_data_from_child(", "bool read_transfer_data_from_child(",
        "bool write_string_from_child(", "bool read_string_from_child("))
    assert "if (result.root_transition.credentials_managed) close_command_owner(result);" in source
    fixture = fixture_path.read_text(encoding="utf-8").replace("// EXACT_ROOT_FUNCTIONS", root_functions)
    fixture = fixture.replace("// EXACT_FORK_FUNCTIONS", fork_functions)
    generated = out / "p4_root_receipt.cpp"
    generated.write_text(fixture, encoding="utf-8")
    docker = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
        "--tmpfs", "/tmp:rw,size=32m", "-e", "ASAN_OPTIONS=detect_leaks=1",
        "-e", "UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1",
        "--mount", f"type=bind,source={ROOT},target=/repo,readonly",
        "--mount", f"type=bind,source={out},target=/work", "-w", "/work", IMAGE]
    commands = []
    for name, command in (
        ("compile", docker + ["g++", "-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-fno-pie", "-no-pie",
            "-I/repo/runtime/maplesu-sdk/sdk/include", "-I/repo/runtime/maplesu-sdk/include",
            "-I/repo/runtime/maplesu-sdk/sdk/src", "p4_root_receipt.cpp", "-o", "p4-root-receipt"]),
        ("run", docker + ["./p4-root-receipt"])):
        completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        output = completed.stdout + completed.stderr
        (out / f"{name}.log").write_text(output, encoding="utf-8")
        commands.append({"name": name, "argv": command, "exit": completed.returncode,
            "output": output})
        if completed.returncode:
            break
    stable = all(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == value
        for path, value in before.items())
    passed = len(commands) == 2 and all(item["exit"] == 0 for item in commands) and stable
    report = {"passed": passed, "sources": before, "sourcesUnchanged": stable,
        "rootFunctionsSha256": hashlib.sha256(root_functions.encode()).hexdigest(),
        "forkFunctionsSha256": hashlib.sha256(fork_functions.encode()).hexdigest(),
        "commands": commands,
        "scope": "actual m060 receipt/close bodies and actual m046 transport; real host fork/pipes; kernel close API seams; no device"}
    (out / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "sourcesUnchanged": stable,
        "commands": [{"name": item["name"], "exit": item["exit"], "output": item["output"]}
            for item in commands], "receipt": str(out / "results.json")}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
