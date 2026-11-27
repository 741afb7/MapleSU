"""Exercise actual policy-management IPC with SDK/store fixtures and syscall faults."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    names = ["runtime/ultra/policy/module_policy_management.cpp", "runtime/ultra/policy/module_policy_management.h",
             "runtime/tests/module_policy_management_test.cpp", "scripts/tests/test-module-policy-management.py"]
    before = {n: hashlib.sha256((ROOT / n).read_bytes()).hexdigest() for n in names}
    scenarios = ["invalid_auth", "exhausted_auth", "admission_error", "helper_success", "close_error", "store_close_error", "invalid_request", "pipe_error", "fork_error",
                 "poll_timeout", "poll_error", "poll_eintr", "read_error", "read_short", "read_eof",
                 "wait_error", "wait_mismatch", "wait_eintr", "child_signal", "child_exit", "root_error",
                 "store_error", "success", "save_0", "save_1", "save_3", "save_7"]
    command = (
        "set -eu\nulimit -c 0\n"
        "c++ -std=c++20 -UNDEBUG -Wall -Wextra -Werror -fsanitize=address,undefined "
        "-fno-omit-frame-pointer -I/source/runtime/maplesu-sdk/include -I/source/runtime/maplesu-sdk/sdk/include -I/source/runtime/ultra/policy "
        "/source/runtime/ultra/policy/module_policy_management.cpp "
        "/source/runtime/tests/module_policy_management_test.cpp "
        "-Wl,--wrap=pipe2 -Wl,--wrap=fork -Wl,--wrap=poll -Wl,--wrap=read "
        "-Wl,--wrap=waitpid -Wl,--wrap=_exit -o /output/management-test\n" +
        "\n".join("/output/management-test " + scenario for scenario in scenarios))
    execution = subprocess.run(["docker", "run", "--rm", "--pull", "never", "--network", "none",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--mount",
        f"type=bind,source={ROOT},target=/source,readonly", "--mount", f"type=bind,source={out},target=/output",
        "ghcr.io/ylarod/ddk-min:android15-6.6-20260828", "sh", "-c", command],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=55)
    stable = before == {n: hashlib.sha256((ROOT / n).read_bytes()).hexdigest() for n in names}
    report = dict(passed=execution.returncode == 0 and stable,
        recordedAt=datetime.now(timezone.utc).isoformat(), exitCode=execution.returncode,
        stdout=execution.stdout, stderr=execution.stderr, scenarios=scenarios, cases=len(scenarios),
        sourceSha256=before, inputsUnchanged=stable, sanitizers=["ASan", "UBSan"], deviceAccess=False,
        scope="Real fork/pipe IPC and inline WorkerScope, injected syscall failures; SDK authorization/storage and policy store boundaries are fixtures. Invalid auth is checked before fork.")
    (out / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(execution.stdout + execution.stderr)
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
