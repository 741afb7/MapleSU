"""Run actual old/new module ELF exports and versioned WebUI FD handoffs."""
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
relative_inputs = [
    'runtime/tests/ultra_native_module_abi_test.py',
    'runtime/tests/ultra_native_module_abi_test.cpp',
    'runtime/tests/ultra_native_module_abi_fixture.cpp',
    'runtime/tests/ultra_webui_handoff_test.cpp',
    'runtime/tests/ultra_webui_payload_fixture.cpp',
    'runtime/maplesu-sdk/include/maplesu/compat/native_module_abi.h',
    'runtime/maplesu-sdk/sdk/src/maplesu/compat/module_runtime_legacy_abi.h',
    'runtime/maplesu-sdk/sdk/src/maplesu/module/runtime.cpp',
    'runtime/maplesu-sdk/sdk/src/maplesu/module/callback.cpp',
    'runtime/maplesu-sdk/sdk/src/maplesu/webui/module_loader.cpp',
    'runtime/maplesu-sdk/sdk/src/maplesu/webui/loader_compatibility.h',
    *['runtime/maplesu-sdk/sdk/src/payload/webui_loader/src/' + name for name in (
        'module_host.h', 'module_host.cpp', 'startup_receipt.h', 'contract.h',
        'main.cpp', 'root_grant.h', 'root_grant.cpp', 'root_key.h')],
]
hashes = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in relative_inputs}
baseline = subprocess.run(['git', 'show', '3f14fa9:runtime/maplesu-sdk/include/maplesu/compat/native_module_abi.h'],
    cwd=root, capture_output=True, check=True).stdout
(out / 'baseline_native_module_abi.h').write_bytes(baseline)
baseline_prefix = 'runtime/maplesu-sdk/'
baseline_paths = ['include/maplesu/compat/native_module_abi.h',
    'sdk/src/maplesu/module/path_contract.h', 'sdk/src/maplesu/module/types.h',
    *['sdk/src/payload/webui_loader/src/' + name for name in (
        'main.cpp', 'module_host.h', 'module_host.cpp', 'contract.h', 'startup_receipt.h',
        'root_grant.h', 'root_grant.cpp', 'root_key.h', 'parent_watch.h', 'webroot_server.h')]]
baseline_hashes = {}
for name in baseline_paths:
    data = subprocess.run(['git', 'show', '3f14fa9:' + baseline_prefix + name], cwd=root,
        capture_output=True, check=True).stdout
    target = out / 'baseline' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    baseline_hashes[baseline_prefix + name] = hashlib.sha256(data).hexdigest()
docker = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only',
    '--tmpfs', '/tmp:rw,size=64m', '--tmpfs', '/data:rw,exec,size=64m', '-e', 'ASAN_OPTIONS=detect_leaks=1',
    '-e', 'UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1',
    '--mount', f'type=bind,source={root},target=/repo,readonly',
    '--mount', f'type=bind,source={out},target=/work', '-w', '/work', '--entrypoint', '/bin/sh',
    'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e',
    '-c', 'exec "$@"', 'sh']
flags = ['-std=c++20', '-O1', '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
    '-I/work', '-I/repo/runtime/maplesu-sdk/include',
    '-I/repo/runtime/maplesu-sdk/sdk/src/payload/common']
fixture = '/repo/runtime/tests/ultra_native_module_abi_fixture.cpp'
payload = '/repo/runtime/maplesu-sdk/sdk/src/payload/webui_loader/src/'
baseline_payload = '/work/baseline/sdk/src/payload/webui_loader/src/'
payload_flags = [*flags, '-O2', '-flto', '-fno-pie', '-no-pie']
commands = [
    ('keyless-fixture', [*docker, 'g++', *flags, '-shared', '-fPIC', fixture, '-o', '/work/keyless.so']),
    ('legacy-fixture', [*docker, 'g++', *flags, '-shared', '-fPIC', '-DFIXTURE_LEGACY', fixture, '-o', '/work/legacy.so']),
    ('keyless-object', [*docker, 'g++', *flags, '-fPIC', '-c', fixture, '-o', '/work/keyless.o']),
    ('legacy-object', [*docker, 'g++', *flags, '-fPIC', '-DFIXTURE_LEGACY', '-DFIXTURE_OMIT_SHARED_EXPORTS',
        '-c', fixture, '-o', '/work/legacy.o']),
    ('dual-fixture', [*docker, 'g++', '-fsanitize=address,undefined', '-shared', '/work/keyless.o', '/work/legacy.o', '-o', '/work/dual.so']),
    ('missing-fixture', [*docker, 'g++', *flags, '-shared', '-fPIC', '-DFIXTURE_OMIT_SHARED_EXPORTS', fixture, '-o', '/work/missing.so']),
    ('abi-compile', [*docker, 'g++', *flags, '-fno-pie', '-no-pie',
        '/repo/runtime/tests/ultra_native_module_abi_test.cpp',
        '/repo/runtime/maplesu-sdk/sdk/src/payload/webui_loader/src/module_host.cpp', '-ldl', '-o', '/work/abi']),
    ('abi', [*docker, './abi']),
    ('flow-keyless-fixture', [*docker, 'g++', *flags, '-shared', '-fPIC', '-DFIXTURE_LOADER_FLOW', fixture, '-o', '/work/flow-keyless.so']),
    ('flow-legacy-fixture', [*docker, 'g++', *flags, '-shared', '-fPIC', '-DFIXTURE_LOADER_FLOW', '-DFIXTURE_LEGACY', fixture, '-o', '/work/flow-legacy.so']),
    ('payload-compile', [*docker, 'g++', *payload_flags,
        payload + 'main.cpp', payload + 'module_host.cpp', payload + 'root_grant.cpp',
        '/repo/runtime/tests/ultra_webui_payload_fixture.cpp', '-ldl', '-o', '/work/payload']),
    ('baseline-payload-compile', [*docker, 'g++', '-I/work/baseline/include', *payload_flags,
        baseline_payload + 'main.cpp', baseline_payload + 'module_host.cpp', baseline_payload + 'root_grant.cpp',
        '/repo/runtime/tests/ultra_webui_payload_fixture.cpp', '-ldl', '-o', '/work/baseline-payload']),
    ('wire-compile', [*docker, 'g++', *flags, '-fno-pie', '-no-pie',
        '/repo/runtime/tests/ultra_webui_handoff_test.cpp', '-o', '/work/wire']),
    ('wire', [*docker, './wire']),
]
rows = []
patches = []
for name, command in commands:
    if name == 'wire':
        marker = (b'8ba9d457344d22b85d3e7f7263c3dd04d9effeb1becb41a806eaaa9926087fae'
            b'49c43c1c3d64c98333e8017195c6ab7bfe7e72a99c4530b267705824e5d6e700')
        slot = marker.ljust(4096, b'\0')
        for source in ('payload', 'baseline-payload'):
            data = (out / source).read_bytes()
            hits = data.count(slot)
            if hits < 1:
                raise RuntimeError(f'{source}: physical compatibility slot absent')
            patched = data.replace(slot, b'K' * 48 + b'\0' + slot[49:])
            (out / (source + '-patched')).write_bytes(patched)
            patches.append({'name': source, 'hits': hits, 'method': 'fixed baseline 49-byte overwrite',
                'before': hashlib.sha256(data).hexdigest(), 'after': hashlib.sha256(patched).hexdigest()})
    done = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    output = done.stdout + done.stderr
    (out / (name + '.log')).write_text(output, encoding='utf-8')
    print(name, done.returncode)
    print(output[-8000:], end='')
    rows.append({'name': name, 'argv': command, 'exit': done.returncode})
    if done.returncode:
        break
stable = all(hashlib.sha256((root / name).read_bytes()).hexdigest() == sha for name, sha in hashes.items())
passed = len(rows) == len(commands) and all(not row['exit'] for row in rows) and stable
(out / 'receipt.json').write_text(json.dumps({'passed': passed, 'sourceInputsStable': stable,
    'inputs': hashes, 'baselineAbi': {'commit': '3f14fa9', 'sha256': hashlib.sha256(baseline).hexdigest(), 'inputs': baseline_hashes},
    'commands': rows, 'patchedPayloads': patches,
    'scope': 'Actual Linux dlopen and fork/exec of current and fixed old payload main/host/root-grant; only HTTP listener and owner wait are fixtures. No SDK release or device evidence.'},
    indent=2), encoding='utf-8')
raise SystemExit(0 if passed else 1)
