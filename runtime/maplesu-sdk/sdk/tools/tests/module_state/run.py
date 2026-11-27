#!/usr/bin/env python3
"""Run focused module-state fixtures against the actual SDK source files.

Windows host requirements: the pinned NDK from sdk_common and the existing
Docker image named by IMAGE. All build outputs go to the explicit --out folder.
This does not build or install the full SDK, helper, APK, or device environment.
"""
import argparse
import concurrent.futures
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
SDK = HERE.parents[2]
FORK_SOURCE = SDK / json.loads((SDK / 'manifest/0046_maplesu_fork_helper.o.json').read_text(encoding='utf-8-sig'))['sources'][0]
sys.path.insert(0, str(SDK / 'tools'))
import sdk_common as S

IMAGE = 'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    for directory in ['host', 'ndk']:
        (out / directory).mkdir(exist_ok=True)
    core = SDK / 'src/maplesu/module'
    pro_include = SDK.parent / 'include'
    commands = []

    def run(name, argv):
        done = subprocess.run([str(a) for a in argv], capture_output=True, text=True, encoding='utf-8', errors='replace')
        (out / (name + '.log')).write_text(done.stdout + done.stderr, encoding='utf-8')
        commands.append({'name': name, 'argv': [str(a) for a in argv], 'exit': done.returncode})
        if done.returncode:
            raise RuntimeError(name + ': ' + done.stderr[-3000:])
        return done

    docker = ['docker.exe', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only',
              '--tmpfs', '/tmp:rw,size=128m', '--tmpfs', '/data:rw,size=32m,mode=0777',
              '--mount', 'type=bind,source=' + str(SDK) + ',target=/sdk,readonly',
              '--mount', 'type=bind,source=' + str(pro_include) + ',target=/upstream-include,readonly',
              '--mount', 'type=bind,source=' + str(SDK.parents[1] / 'ultra') + ',target=/ultra,readonly',
              '--mount', 'type=bind,source=' + str(out) + ',target=/work', '-w', '/work',
              '--entrypoint', '/bin/sh', IMAGE]
    fixtures = '/sdk/tools/tests/module_state/'
    host_cpp = [fixtures + name for name in ['test_state.cpp', 'boundaries.cpp', 'install_bindings.cpp',
                                            'list_bindings.cpp', 'zip_boundary.cpp']]
    host_cpp += ['/sdk/src/maplesu/module/' + name for name in ['list.cpp', 'install.cpp', 'state.cpp']]
    host_cpp += ['/sdk/' + FORK_SOURCE.relative_to(SDK).as_posix()]
    host_cpp += ['/sdk/src/module_base/module_base_disk_storage.cpp']
    host_argv = ['g++', '-std=c++20', '-O1', '-g', '-ffunction-sections', '-fdata-sections', '-Wno-packed',
                 '-I' + fixtures + 'include', '-I' + fixtures, '-I/sdk/src/maplesu/module',
                 '-I/upstream-include', '-I/sdk/include', '-I/sdk/third_party/libzip/1.0.1/lib',
                 '-I/sdk/third_party/libzip/1.0.1/config', *host_cpp,
                 '-Wl,--gc-sections,--wrap=fork,--wrap=chmod,--wrap=fchmod,--wrap=setxattr,--wrap=fsetxattr', '-o', 'host/new-test']
    run('host/compile', [*docker, '-c', 'exec "$@"', 'sh', *host_argv])
    host_done = run('host/runtime', [*docker, '-c', 'exec "$@"', 'sh', './host/new-test'])
    host_rows = [json.loads(line) for line in host_done.stdout.splitlines() if line.startswith('{')]
    assert host_rows[-1]['test'] == 'NEW-ALL' and host_rows[-1]['assertionsSoFar'] == 198

    runtime_args = [part for part in host_argv if not str(part).endswith('.cpp')]
    runtime_args[-1] = 'host/runtime-worker-test'
    runtime_args[-2:-2] = [fixtures + 'test_runtime_worker.cpp', fixtures + 'boundaries.cpp',
        '/sdk/src/module_base/module_base_disk_storage.cpp']
    run('host/runtime-worker-compile', [*docker, '-c', 'exec "$@"', 'sh', *runtime_args])
    runtime_done = run('host/runtime-worker', [*docker, '-c', 'exec "$@"', 'sh', './host/runtime-worker-test'])

    manifest = [json.loads(path.read_text(encoding='utf-8-sig')) for path in (SDK / 'manifest').glob('*.json')]
    bin_dir = S.DEFAULT_NDK / S.TOOLCHAIN_SUBDIR
    compiler = bin_dir / 'clang++.exe'
    run('ndk/version', [compiler, '--version'])

    def compile_one(job):
        idx, name = job
        entry = next(row for row in manifest if row['idx'] == idx)
        flags = S.profile_flags(entry['flagsProfile'], 'cxx', entry.get('extraFlags', []))
        flags += ['-Wall', '-Wextra', '-I', str(pro_include), '-I', str(SDK / 'include')]
        source = core / name
        stem = 'src-' + source.stem
        target = out / 'ndk' / (stem + '.o')
        stage_source = source
        stages = int(entry.get('recipe', {}).get('stages', 1))
        if stages >= 2:
            stage_source = out / 'ndk' / (stem + '.stage1.bc')
            run('ndk/' + stem + '-ir', [compiler, *flags, '-MMD', '-MF', str(stage_source) + '.d',
                                       '-emit-llvm', '-c', source, '-o', stage_source])
        run('ndk/' + stem, [compiler, *flags, '-MMD', '-MF', str(target) + '.d', '-c', stage_source, '-o', target])
        return {'idx': idx, 'source': str(source), 'sourceSha256': S.sha256_file(source), 'stages': stages,
                'object': str(target), 'bytes': target.stat().st_size, 'sha256': S.sha256_file(target),
                'flagsProfile': entry['flagsProfile'], 'extraFlags': entry.get('extraFlags', [])}

    jobs = [(55, 'list.cpp'), (56, 'install.cpp'), (55, 'state.cpp')]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        objects = list(pool.map(compile_one, jobs))
    composed = out / 'ndk/maplesu_module_list.composed.o'
    combined = out / 'ndk/consumers.partial-link.o'
    run('ndk/compose-55', [bin_dir / 'ld.lld.exe', '-r', '-o', composed,
                         out / 'ndk/src-list.o', out / 'ndk/src-state.o'])
    run('ndk/link-consumers', [bin_dir / 'ld.lld.exe', '-r', '-o', combined,
                             composed, out / 'ndk/src-install.o'])
    symbols = run('ndk/nm', [bin_dir / 'llvm-nm.exe', '-P', str(combined)]).stdout
    shared = [line for line in symbols.splitlines() if line.startswith('_ZN7maplesu12module_state') and ' T ' in line]
    assert len(shared) == 5 and all(' T ' in line for line in shared), shared
    elf_symbols = run('ndk/readelf', [bin_dir / 'llvm-readelf.exe', '-s', combined]).stdout
    hidden = [line for line in elf_symbols.splitlines() if 'FUNC' in line and '_ZN7maplesu12module_state' in line and line.split()[4] == 'GLOBAL']
    assert len(hidden) == 5 and all('GLOBAL HIDDEN' in line for line in hidden), hidden
    inputs = [*core.glob('*'), SDK / 'src/maplesu/compat/module_list_legacy_abi.h',
              SDK / 'src/maplesu/compat/module_install_legacy_abi.h', FORK_SOURCE,
              SDK / 'src/maplesu/compat/fork_legacy_abi.h', SDK / 'src/maplesu_core/fixed_transfer_io.h',
              SDK / 'manifest/0046_maplesu_fork_helper.o.json', SDK / 'manifest/0055_maplesu_module_list.o.json',
              SDK / 'manifest/0056_maplesu_module_install.o.json', SDK / 'tools/sdk_common.py',
              *[path for path in HERE.rglob('*') if path.is_file()]]
    result = {'source': 'actual production SDK files; host boundaries documented in README.md',
              'productionOutputsWritten': False, 'deviceAccess': False, 'host': host_rows,
              'runtimeWorker': runtime_done.stdout,
              'ndkObjects': objects, 'sharedStrongFunctions': 5, 'sharedFunctionsHidden': True,
              'inputs': [{'path': str(path), 'sha256': S.sha256_file(path)} for path in inputs],
              'commands': commands, 'passed': True}
    (out / 'RESULTS.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'assertions': 198, 'sharedStrongFunctions': 5, 'out': str(out)}))


if __name__ == '__main__':
    main()
