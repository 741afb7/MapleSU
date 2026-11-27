"""Exercise current module IDs with the real worker and storage scope implementation."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from worker_admission_fixture import prepare_worker_admission

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--helper', type=Path, required=True,
                        help='Source-built helper ELF containing the module boot gate capability')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.helper.resolve(), out / 'helper.elf')
    properties = out / 'include/sys/system_properties.h'
    properties.parent.mkdir(parents=True, exist_ok=True)
    properties.write_text('#pragma once\n#define PROP_VALUE_MAX 92\n'
                          'extern "C" int __system_property_get(const char*, char*);\n', encoding='utf-8')
    admission_source = prepare_worker_admission(ROOT, out)
    names = ['runtime/tests/module_control_id_test.cpp', 'runtime/ultra/modules/module_control.cpp',
             'runtime/maplesu-sdk/sdk/src/module_base/module_base_disk_storage.cpp',
             'runtime/maplesu-sdk/sdk/include/maplesu_storage_worker_private.h',
             'runtime/maplesu-sdk/sdk/include/maplesu_storage_private.h',
             'runtime/maplesu-sdk/sdk/include/maplesu_request_private.h',
             'runtime/maplesu-sdk/include/maplesu_module_control.h',
             'scripts/tests/test-module-control-id.py', 'scripts/tests/worker_admission_fixture.py',
             'runtime/maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp']
    inputs = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names}
    command = (
        'set -eu\nulimit -c 0\n'
        '/opt/ddk/clang/clang-r510928/bin/clang++ -std=c++17 -stdlib=libc++ -O1 -UNDEBUG -Wall -Wextra -Werror '
        '-Wno-packed-non-pod -Wno-unused-variable -Wno-unused-function '
        '-Wno-unused-parameter -Wno-overloaded-virtual -include algorithm '
        '-fsanitize=address,undefined -fno-sanitize=vptr '
        '-fno-omit-frame-pointer -ffunction-sections -fdata-sections '
        '-Wl,-rpath,/opt/ddk/clang/clang-r510928/lib/x86_64-unknown-linux-gnu '
        '-I/output/include -I/source/runtime/maplesu-sdk/include -I/source/runtime/maplesu-sdk/sdk/include '
        '/source/runtime/tests/module_control_id_test.cpp '
        '/source/runtime/maplesu-sdk/sdk/src/module_base/module_base_disk_storage.cpp '
        '/output/worker_admission.cpp '
        '-Wl,--gc-sections -Wl,--wrap=fork -o /output/module-control-id-test\n'
        '/output/module-control-id-test /output/helper.elf\n')
    execution = subprocess.run(['docker', 'run', '--rm', '--pull', 'never', '--network', 'none',
        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--mount',
        f'type=bind,source={ROOT},target=/source,readonly', '--mount', f'type=bind,source={out},target=/output',
        'ghcr.io/ylarod/ddk-min:android15-6.6-20260828', 'sh', '-c', command],
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=55)
    stable = inputs == {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names}
    record = dict(passed=execution.returncode == 0 and stable,
        recordedAt=datetime.now(timezone.utc).isoformat(), exitCode=execution.returncode,
        stdout=execution.stdout, stderr=execution.stderr, sourceSha256=inputs, inputsUnchanged=stable,
        helperSha256=hashlib.sha256((out / 'helper.elf').read_bytes()).hexdigest(), admissionSource=admission_source,
        sanitizers=['ASan', 'UBSan (vptr excluded: pinned DDK runtime lacks its symbols)'], deviceAccess=False,
        scope='Real module_control.cpp, WorkerScope, StorageScope/current_context, fork and filesystem; authentication transport and deployment paths are boundary fixtures')
    (out / 'verification.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print(execution.stdout + execution.stderr)
    raise SystemExit(0 if record['passed'] else 1)


if __name__ == '__main__':
    main()
