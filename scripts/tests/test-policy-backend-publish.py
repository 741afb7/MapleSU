"""Exercise the shared atomic writer's private-backend and legacy contracts."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
from worker_admission_fixture import prepare_worker_admission

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    admission_source = prepare_worker_admission(ROOT, out)
    unit = out / "worker_admission.cpp"
    unit.write_text('#include "maplesu_request_identity.h"\n' + unit.read_text(encoding="utf-8"), encoding="utf-8")
    scenarios = ["fresh", "replace", "held_inode", "concurrent", "stale_stage", "short_write", "interrupted",
                 "write_failure", "zero_write", "sync_failure", "directory_sync_failure", "close_failure",
                 "rename_failure", "label_failure", "mode_failure", "symlink", "hardlink", "unsafe_directory", "legacy_mode"]
    command = (
        "set -eu\n"
        "c++ -std=c++17 -UNDEBUG -Wall -Wextra -Werror -fsanitize=address,undefined "
        "-fno-omit-frame-pointer -I/source/runtime/maplesu-sdk/include -I/source/runtime/ultra/upgrade "
        "/source/runtime/ultra/upgrade/executable_publish.cpp /source/runtime/tests/policy_publish_test.cpp "
        "-Wl,--wrap=write -Wl,--wrap=fsync -Wl,--wrap=close -Wl,--wrap=fchmod "
        "-Wl,--wrap=renameat -Wl,--wrap=fsetxattr -o /output/publish-test\n" +
        "\n".join("/output/publish-test " + scenario for scenario in scenarios))
    entry_scenarios = ["fresh", "idempotent", "repair_label", "write_failure", "root_failure", "unsafe_bin", "symlink_parent",
                       "symlink_file", "hardlink_file", "invalid_digest", "invalid_elf", "invalid_auth_tag", "oversize",
                       "owner_fresh", "manager_generation", "invalid_auth_zero", "invalid_auth_exhausted", "helper_identity",
                       "owner_root_failure", "owner_select_failure", "owner_close_failure", "owner_first_failure",
                       "root_identity_failure", "label_failure", "readback_failure", "file_close_failure",
                       "write_close_failure", "label_close_failure", "readback_close_failure",
                       "admission_denied", "admission_transport_failure", "legacy_fresh", "legacy_root_failure", "legacy_write_failure", "legacy_invalid_credential"]
    command += ("\nc++ -std=c++20 -UNDEBUG -Wall -Wextra -Werror -fsanitize=address,undefined "
        "-fno-omit-frame-pointer -I/source/runtime/maplesu-sdk/include -I/source/runtime/ultra/policy "
        "-I/source/runtime/maplesu-sdk/sdk/include "
        "'-DMAPLESU_POLICY_DIRECTORY=\"/tmp/maplesu-policy-publish-entry\"' "
        "/source/runtime/ultra/upgrade/executable_publish.cpp /source/runtime/ultra/policy/backend_publish.cpp "
        "/output/worker_admission.cpp /source/runtime/maplesu-sdk/sdk/src/module/module_current_identity.cpp "
        "/source/runtime/tests/policy_backend_publish_test.cpp -Wl,--wrap=write -Wl,--wrap=fsetxattr -Wl,--wrap=fgetxattr "
        "-Wl,--wrap=close -Wl,--wrap=getuid -Wl,--wrap=geteuid -Wl,--wrap=fork "
        "-o /output/backend-entry-test\n" +
        "\n".join("/output/backend-entry-test " + scenario for scenario in entry_scenarios))
    execution = subprocess.run(["docker", "run", "--rm", "--pull", "never", "--network", "none", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--mount", f"type=bind,source={ROOT},target=/source,readonly",
        "--mount", f"type=bind,source={out},target=/output", "ghcr.io/ylarod/ddk-min:android15-6.6-20260828",
        "sh", "-c", command], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=55)
    names = ["runtime/ultra/upgrade/executable_publish.cpp", "runtime/ultra/upgrade/executable_publish.h",
             "runtime/tests/policy_publish_test.cpp", "scripts/tests/test-policy-backend-publish.py",
             "runtime/ultra/policy/backend_publish.cpp", "runtime/ultra/policy/backend_publish.h",
             "runtime/tests/policy_backend_publish_test.cpp", "scripts/tests/worker_admission_fixture.py",
             "runtime/maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp",
             "runtime/maplesu-sdk/sdk/include/maplesu_request_private.h", "runtime/maplesu-sdk/sdk/include/maplesu_request_identity.h",
             "runtime/maplesu-sdk/sdk/src/module/module_current_identity.cpp"]
    record = dict(passed=execution.returncode == 0, recordedAt=datetime.now(timezone.utc).isoformat(),
        exitCode=execution.returncode, stdout=execution.stdout, stderr=execution.stderr,
        scenarios=scenarios, entryScenarios=entry_scenarios,
        cases=len(scenarios) + len(entry_scenarios), sanitizers=["ASan", "UBSan"], admissionSource=admission_source,
        sourceSha256={n: hashlib.sha256((ROOT / n).read_bytes()).hexdigest() for n in names},
        deviceAccess=False, scope="Real Linux atomic writer; security.selinux modeled as a user xattr")
    (out / "verification.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(execution.stdout + execution.stderr)
    raise SystemExit(execution.returncode)


if __name__ == "__main__":
    main()
