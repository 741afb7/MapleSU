#!/usr/bin/env python3
"""Capture and exercise m070 production precheck with inert kernel/process seams."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[5]
SDK = ROOT / 'runtime/maplesu-sdk/sdk'
IMAGE = 'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--ndk', action='store_true', help='Also compile captured m070 with its manifest Android profile')
    parser.add_argument('--owner-only', action='store_true', help='Run only the post-commit owner continuity cases')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = {
        Path(__file__).resolve(): 'run.py',
        HERE / 'test_m070.cpp': 'test_m070.cpp',
        HERE / 'kernel_module_kit_umbrella.h': 'include/kernel_module_kit_umbrella.h',
        SDK / 'src/module/idx070/module_root.cpp': 'module_root.cpp',
        SDK / 'include/maplesu_request_private.h': 'include/maplesu_request_private.h',
        SDK / 'include/maplesu_hook_private.h': 'include/maplesu_hook_private.h',
        SDK / 'include/maplesu_request_identity.h': 'include/maplesu_request_identity.h',
        ROOT / 'runtime/ultra/authorization/request_protocol.h': 'include/request_protocol.h',
        SDK / 'include/maplesu_root_private.h': 'include/maplesu_root_private.h',
        SDK / 'include/maplesu_caps_private.h': 'include/maplesu_caps_private.h',
        SDK / 'include/maplesu_early_fixed_private.h': 'include/maplesu_early_fixed_private.h',
        SDK / 'include/maplesu_storage_private.h': 'include/maplesu_storage_private.h',
        SDK.parent / 'include/module_err_def.h': 'include/module_err_def.h',
        SDK.parent / 'include/kernel_offsets/cred.h': 'include/kernel_offsets/cred.h',
        SDK.parent / 'include/kernel_offsets/task_struct.h': 'include/kernel_offsets/task_struct.h',
    }
    # m070 now includes the shared hook/private diagnostics header, but this
    # fixture only links credential/task-state paths. Keep the hook ABI type
    # surface minimal so unrelated hook code is discarded by gc-sections.
    (out / 'include/module_base_kernel_func_hook.h').parent.mkdir(parents=True, exist_ok=True)
    (out / 'include/module_base_kernel_func_hook.h').write_text(
        '#pragma once\nnamespace kernel_module { struct HookContext; using HookHandle = HookContext*; }\n',
        encoding='utf-8')
    for name in ['cgroup_v1_tasks_self.h', 'cgroup_v2_self.h', 'selinux_procattr_util.h']:
        sources[SDK / 'src/module/idx070' / name] = name
    identities = []
    expected = {}
    for source, target in sources.items():
        data = source.read_bytes()
        if target == 'include/maplesu_request_private.h':
            # The production header is rooted three levels above sdk/include;
            # the fixture flattens headers under /work/include.
            data = data.replace(b'../../../ultra/authorization/request_protocol.h',
                                b'request_protocol.h')
        path = out / target
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        expected[target] = hashlib.sha256(data).hexdigest()
        identities.append(dict(source=str(source), snapshot=target, bytes=len(data),
                               sha256=hashlib.sha256(data).hexdigest()))
    docker = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none',
              '--read-only', '--tmpfs', '/tmp:rw,size=32m',
              '--mount', f'type=bind,source={out},target=/work', '-w', '/work', IMAGE]
    wraps = ['getuid', 'setsid', 'setpgid', 'signal', 'open', 'access', 'opendir', 'prctl',
             'setns', 'unshare']
    commands = [
        ('compile', [*docker, 'g++', '-std=c++20', '-O1', '-g', '-I/work/include',
                     '-ffunction-sections', '-fdata-sections', 'test_m070.cpp',
                     '-Wl,--gc-sections,' + ','.join('--wrap=' + name for name in wraps),
                     '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-no-pie',
                     '-o', 'caps-precheck-test']),
        ('test', [*docker, './caps-precheck-test', *(['owner-only'] if args.owner_only else [])]),
    ]
    manifest_identity = None
    if args.ndk:
        sys.path.insert(0, str(SDK / 'tools'))
        import sdk_common as common
        manifest = SDK / 'manifest/0070_module_root.o.json'
        entry = json.loads(manifest.read_text(encoding='utf-8'))
        compiler = common.DEFAULT_NDK / common.TOOLCHAIN_SUBDIR / 'clang++.exe'
        flags = common.profile_flags(entry['flagsProfile'], 'cxx', entry.get('extraFlags', []))
        commands.append(('ndk', list(map(str, [compiler, *flags,
            '-I', SDK.parent / 'include', '-I', SDK / 'include', '-MD', '-MF', out / 'ndk.d',
            '-MT', 'module-root-object', '-c', out / 'module_root.cpp', '-o', out / 'module_root.o']))))
        manifest_identity = dict(path=str(manifest), sha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
            flagsProfile=entry['flagsProfile'], compiler=str(compiler),
            compilerSha256=hashlib.sha256(compiler.read_bytes()).hexdigest())
    rows = []
    for name, command in commands:
        done = subprocess.run(command, capture_output=True, text=True,
                              encoding='utf-8', errors='replace')
        (out / (name + '.log')).write_text(done.stdout + done.stderr, encoding='utf-8')
        rows.append(dict(name=name, argv=command, exit=done.returncode,
                         sanitizer_diagnostic='runtime error:' in done.stderr))
        print(done.stdout + done.stderr, end='')
        if done.returncode:
            break
    stable = all(hashlib.sha256((out / row['snapshot']).read_bytes()).hexdigest() == expected[row['snapshot']]
                 for row in identities)
    dependencies = []
    depfile = out / 'ndk.d'
    if depfile.exists():
        text = depfile.read_text(encoding='utf-8').replace('\\\n', ' ').split(':', 1)[1]
        for name in re.findall(r'(?:\\.|[^\s])+', text):
            path = Path(name.replace('\\ ', ' '))
            data = path.read_bytes()
            dependencies.append(dict(path=str(path), bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    passed = stable and len(rows) == len(commands) and all(
        row['exit'] == 0 and not row['sanitizer_diagnostic'] for row in rows)
    receipt = dict(sources=identities, sourcesUnchanged=stable, image=IMAGE,
                   manifest=manifest_identity, dependencies=dependencies,
                   commands=rows, passed=passed,
                   scope='Captured m070 TU; mocked patch/process/status boundaries; no archive or device claim')
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
