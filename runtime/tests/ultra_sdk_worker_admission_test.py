"""Narrow admission regression on actual m023; kernel authentication remains a fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
paths = ['runtime/tests/ultra_sdk_worker_admission_test.cpp',
    'runtime/tests/ultra_sdk_worker_admission_test.py',
    'runtime/maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp',
    'runtime/maplesu-sdk/sdk/include/maplesu_request_private.h',
    'runtime/maplesu-sdk/sdk/include/maplesu_request_identity.h',
    'runtime/maplesu-sdk/sdk/include/module_base_kernel_request.h',
    'runtime/ultra/authorization/kernel_read.cpp',
    'runtime/ultra/authorization/kernel_read_metadata.h',
    'runtime/ultra/authorization/request_protocol.h']
hashes = {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in paths}
(out / 'kernel_module_kit_umbrella.h').write_text('#pragma once\n#include "module_err_def.h"\n', encoding='utf-8')
(out / 'module_base_kernel_mem.h').write_text('#pragma once\n#include <cstdint>\nnamespace kernel_module {uint32_t get_page_size();}\n', encoding='utf-8')
docker = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only',
    '--tmpfs', '/tmp:rw,size=64m', '-e', 'ASAN_OPTIONS=detect_leaks=1',
    '-e', 'UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1',
    '--mount', f'type=bind,source={root},target=/repo,readonly',
    '--mount', f'type=bind,source={out},target=/work', '-w', '/work', '--entrypoint', '/bin/sh',
    'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e',
    '-c', 'exec "$@"', 'sh']
commands = [('admission-compile', [*docker, 'g++', '-std=c++20', '-O1', '-g', '-pthread',
    '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-pie', '-no-pie',
    '-I/work', '-I/repo/runtime/maplesu-sdk/sdk/include', '-I/repo/runtime/maplesu-sdk/include',
    '-DMAPLESU_KERNEL_READ_CANONICAL', '/repo/runtime/tests/ultra_sdk_worker_admission_test.cpp',
    '/repo/runtime/ultra/authorization/kernel_read.cpp', '-Wl,--wrap=syscall,--wrap=getuid,--wrap=geteuid',
    '-o', '/work/admission']), ('admission', [*docker, './admission']),
    ('ndk-syntax', ['D:/Maple_Env/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe',
    '--target=aarch64-linux-android21', '-std=c++20', '-fexceptions', '-fsyntax-only',
    '-Iruntime/maplesu-sdk/include', '-Iruntime/maplesu-sdk/sdk/include', paths[2]])]
rows = []
for name, command in commands:
    done = subprocess.run(command, cwd=root, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    output = done.stdout + done.stderr
    (out / (name + '.log')).write_text(output, encoding='utf-8')
    print(name, done.returncode, output[-3000:], flush=True)
    rows.append({'name': name, 'argv': command, 'exit': done.returncode})
    if done.returncode:
        break
stable = all(hashlib.sha256((root / p).read_bytes()).hexdigest() == sha for p, sha in hashes.items())
passed = len(rows) == len(commands) and all(not row['exit'] for row in rows) and stable
(out / 'receipt.json').write_text(json.dumps({'passed': passed, 'sourceInputsStable': stable,
    'inputs': hashes, 'commands': rows,
    'scope': 'Actual selected m023 admission + existing metadata READ, ASan/UBSan syscall fixture and one changed NDK TU. No official artifact/device evidence. Vendor READ kernel authentication/enrollment is fixture, normal helper existing parent READ is source-reviewed separately.'}, indent=2), encoding='utf-8')
raise SystemExit(0 if passed else 1)
