"""Narrow real helper-byte producer validation; authentication is tested separately."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=False)
(out / 'kernel_module_kit_umbrella.h').write_text('#pragma once\n#include "module_err_def.h"\n#include "module_base_kernel_mem.h"\nnamespace kernel_module { uint32_t get_page_size(); }\n')
docker = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only',
          '--tmpfs', '/tmp:rw,size=64m', '--mount', f'type=bind,source={root},target=/repo,readonly',
          '--mount', f'type=bind,source={out},target=/case', '-w', '/case',
          'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e']
compile_argv = ['g++', '-std=c++17', '-O2', '-ffunction-sections', '-fdata-sections',
                '-I/case', '-I/repo/runtime/maplesu-sdk/sdk/include', '-I/repo/runtime/maplesu-sdk/include',
                '/repo/runtime/tests/ultra_helper_readback_source_test.cpp', '-Wl,--gc-sections',
                '-Wl,--wrap=fstat', '-Wl,--wrap=lstat', '-Wl,--wrap=close', '-pthread', '-lrt', '-o', 'source-test']
records = []
for label, argv in [('compile', compile_argv), ('run', ['./source-test'])]:
    result = subprocess.run(docker + argv, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    (out / f'{label}.log').write_text(result.stdout + result.stderr)
    records.append(dict(label=label, argv=docker + argv, exitCode=result.returncode))
    print(result.stdout + result.stderr, end='')
    if result.returncode:
        break
sources = ['runtime/ultra/bridge/installed_helper_readback.cpp', 'runtime/ultra/bridge/manager_owner_identity.h',
           'runtime/tests/ultra_helper_readback_source_test.cpp', 'scripts/tests/test-ultra-helper-readback-source.py']
(out / 'RESULT.json').write_text(json.dumps(dict(commands=records,
    inputs=[dict(path=p, sha256=hashlib.sha256((root / p).read_bytes()).hexdigest()) for p in sources],
    scope='Actual complete-byte producer/framing, real temporary files, EOF and failure cleanup. fstat/lstat/close fault seams only. No SDK authentication, official artifact or device acceptance.',
    formalArtifact=False, device=False), indent=2) + '\n')
raise SystemExit(records[-1]['exitCode'])
