#!/usr/bin/env python3
"""Compile actual metadata/base/read consumers with captured request/legacy boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[5]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--compiler', type=Path, default=Path('C:/msys64/ucrt64/bin/g++.exe'))
    parser.add_argument('--ndk-compiler', type=Path,
                        help='Also syntax-check actual Android production TUs')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = [HERE/'test_metadata.cpp', ROOT/'runtime/ultra/authorization/kernel_read.cpp',
               ROOT/'runtime/ultra/authorization/kernel_read_legacy.cpp',
               ROOT/'runtime/maplesu-sdk/sdk/src/module/module_append_package_parser.cpp',
               ROOT/'runtime/maplesu-sdk/sdk/src/kernel_mem/module_base_kernel_mem.cpp']
    includes = [HERE, ROOT/'runtime/maplesu-sdk/sdk/include',
                ROOT/'runtime/maplesu-sdk/include', ROOT/'runtime/ultra/authorization']
    command = [str(args.compiler), '-std=c++20', '-O1', '-pthread', '-ffunction-sections',
               '-fdata-sections', '-DMAPLESU_KERNEL_READ_CANONICAL', '-Wno-attributes',
               *['-I'+str(p) for p in includes], *map(str, sources),
               '-Wl,--gc-sections', '-o', str(out/'metadata-test.exe')]
    environment = dict(os.environ)
    environment['PATH'] = str(args.compiler.resolve().parent)+os.pathsep+environment.get('PATH','')
    environment['TMP'] = environment['TEMP'] = str(out)
    avc_source = ROOT/'runtime/maplesu-sdk/sdk/src/patch/patch_avc_denied.cpp'
    history_sources = [ROOT/'runtime/maplesu-sdk/sdk/src/module_base'/name for name in (
        'module_base_kernel_idle_mem.cpp','module_base_kernel_hook_disable_flag.cpp')]
    identities = [Path(__file__).resolve(), *sources, avc_source, *history_sources, *HERE.glob('*.h'),
                  ROOT/'runtime/ultra/authorization/kernel_read_metadata.h',
                  ROOT/'runtime/ultra/authorization/request_protocol.h',
                  ROOT/'runtime/maplesu-sdk/sdk/include/maplesu_request_private.h',
                  ROOT/'runtime/maplesu-sdk/sdk/include/maplesu_request_identity.h',
                  ROOT/'runtime/maplesu-sdk/include/module_base_kernel_mem.h',
                  ROOT/'runtime/maplesu-sdk/include/module_err_def.h']
    initial = {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in identities}
    commands = [('compile',command),('test',[str(out/'metadata-test.exe')])]
    if args.ndk_compiler:
        for source in [*sources[1:], avc_source, *history_sources]:
            flags = ['--target=aarch64-linux-android21','-std=c++20','-fexceptions',
                     '-fsyntax-only','-DMAPLESU_KERNEL_READ_CANONICAL']
            commands.append((f'syntax-{source.stem}',
                [str(args.ndk_compiler),*flags,*['-I'+str(p) for p in includes[1:]],str(source)]))
    rows = []
    for name, argv in commands:
        done = subprocess.run(argv,cwd=out,env=environment,capture_output=True,text=True,
                              encoding='utf-8',errors='replace')
        (out/(name+'.log')).write_text(done.stdout+done.stderr,encoding='utf-8')
        rows.append(dict(name=name,argv=argv,exit=done.returncode))
        print(done.stdout+done.stderr,end='')
        if done.returncode:
            break
    stable = all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==value for p,value in initial.items())
    receipt = dict(commands=rows,sources=initial,inputsStable=stable,
                   passed=stable and len(rows)==len(commands) and all(row['exit']==0 for row in rows),
                   scope='Actual consumer TUs; mocked request I/O and compile-only legacy assembler. '
                         'Simulated pid change, no real fork, device, hardware or emitter execution.')
    (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if receipt['passed'] else 1)


if __name__ == '__main__':
    main()
