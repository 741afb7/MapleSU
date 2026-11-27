#!/usr/bin/env python3
"""Single-module helper return/close contract; no boot matrix replay."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
here = Path(__file__).resolve().parent
root = here.parents[5]
sdk = root / 'runtime/maplesu-sdk/sdk'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=False)
sources = [sdk / 'src/helper/bootstrap_runtime.cpp', sdk / 'src/helper/private/bootstrap_runtime.h',
           sdk / 'include/maplesu_storage_worker_private.h', sdk / 'include/maplesu_storage_private.h',
           sdk / 'include/maplesu_request_private.h', sdk / 'src/maplesu/install/lifecycle.h',
           here / 'worker_boundary.cpp', here / 'worker_boundary.h', here / 'test_single_close.cpp',
           here / 'test_worker_modes.cpp']
inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}
(out / 'INPUTS.json').write_text(json.dumps(inputs, indent=2), encoding='utf-8')
version = re.search(r'kSdkVersionText\[\] = "(\d+)\.(\d+)\.(\d+)"', sources[5].read_text()).groups()
(out / 'helper_sdk_version.h').write_text('#pragma once\n#define MAPLESU_HELPER_SDK_VERSION '
    'maplesu::sdk::MapleSUSdkVersion{' + ','.join(version) + '}\n', encoding='utf-8')
commands = []

def run(name, argv):
    result = subprocess.run(list(map(str, argv)), cwd=root, capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=90)
    (out / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    commands.append({'name': name, 'argv': list(map(str, argv)), 'exitCode': result.returncode})
    print(name, result.returncode, (result.stdout + result.stderr)[-3000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

container = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only',
    '--tmpfs', '/tmp:rw,exec,size=32m', '--mount', f'type=bind,source={root},target=/repo,readonly',
    '--mount', f'type=bind,source={out},target=/work', '-w', '/work', '--entrypoint', '/bin/sh',
    'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e']
prefix = '/repo/runtime/maplesu-sdk/sdk/'
compile_args = ['g++', '-std=c++20', '-O1', '-Wall', '-Wextra', '-Werror',
    '-ffunction-sections', '-fdata-sections', '-I/work', '-I' + prefix + 'src/helper',
    '-I' + prefix + 'include', '-I/repo/runtime/maplesu-sdk/include',
    '-I' + prefix + 'tools/tests/p6_boot', prefix + 'tools/tests/p6_boot/test_single_close.cpp',
    prefix + 'tools/tests/p6_boot/worker_boundary.cpp', prefix + 'src/helper/bootstrap_runtime.cpp',
    '-Wl,--gc-sections', '-o', 'single-close']
try:
    run('host-compile', [*container, '-c', 'exec "$@"', 'sh', *compile_args])
    run('cases', [*container, '-c', 'exec ./single-close'])
    modes_args = [part.replace('test_single_close.cpp', 'test_worker_modes.cpp')
                  if isinstance(part, str) else part for part in compile_args]
    modes_args[-1] = 'worker-modes'
    run('worker-modes-compile', [*container, '-c', 'exec "$@"', 'sh', *modes_args])
    run('worker-modes', [*container, '-c', 'exec ./worker-modes'])
    ndk = Path('D:/Maple_Env/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin')
    run('android-compile', [ndk / 'clang++.exe', '--target=aarch64-linux-android21', '-std=c++20', '-Oz',
        '-fPIC', '-fexceptions', '-I' + str(out), '-I' + str(sdk / 'src/helper'),
        '-I' + str(sdk / 'include'), '-I' + str(root / 'runtime/maplesu-sdk/include'),
        '-c', sdk / 'src/helper/bootstrap_runtime.cpp', '-o', out / 'bootstrap_runtime.android.o'])
finally:
    stable = all(hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest for path, digest in inputs.items())
    (out / 'RESULT.json').write_text(json.dumps({'passed': len(commands) == 5 and
        all(command['exitCode'] == 0 for command in commands) and stable, 'sourceInputsStable': stable,
        'commands': commands, 'scope': 'Complete bootstrap_runtime TU and actual WorkerScope; injected kernel/storage/module callback boundaries, Linux process, Android API21 compile only. No full SDK/helper link, boot or device.'}, indent=2), encoding='utf-8')
