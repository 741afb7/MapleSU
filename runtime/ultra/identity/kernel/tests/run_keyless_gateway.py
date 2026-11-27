#!/usr/bin/env python3
"""Run exact identity gateway/init bodies with task and kernel API fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
KERNEL = HERE.parent


def extract(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    management = (KERNEL / 'manager_management.c').read_text(encoding='utf-8')
    resident = (KERNEL / 'manager_resident_host.c').read_text(encoding='utf-8')
    functions = [extract(management, signature) for signature in (
        'static bool msu_management_task_matches(',
        'static bool msu_management_root_current(',
        'static bool msu_management_root_operation(',
        'static int msu_management_check_expected_cred(',
        'static int msu_management_check(',
        'bool msu_management_current_helper(',
        'int msu_management_authorize(',
        'int msu_management_authorize_request(')]
    (out / 'gateway.inc').write_text('\n\n'.join(functions), encoding='utf-8')
    init = extract(resident, 'int msu_manager_resident_init(')
    # The production kernel is LP64; Windows' host compiler is LLP64.
    init = init.replace('(unsigned long)', '(uintptr_t)')
    host = extract(resident, 'static struct {') + ' host;'
    (out / 'resident.inc').write_text(host + '\n' + init, encoding='utf-8')
    toolchains = ROOT / '.research/toolchains'
    cc = sorted(toolchains.glob('llvm-mingw-*/bin/x86_64-w64-mingw32-clang.exe'))[-1]
    fixture = HERE / 'keyless_gateway_fixture.c'
    commands = [[str(cc), '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror',
                 '-Wno-unused-function', '-Wno-unused-variable', '-I' + str(out),
                 '-I' + str(KERNEL), '-I' + str(ROOT / 'runtime/ultra/authorization'),
                 str(fixture), '-o', str(out / 'keyless-gateway.exe')],
                [str(out / 'keyless-gateway.exe')]]
    results = []
    for name, command in zip(('compile', 'run'), commands):
        result = subprocess.run(command, capture_output=True, text=True)
        (out / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        print(result.stdout + result.stderr, end='')
        results.append({'command': command, 'exitCode': result.returncode})
        if result.returncode:
            break
    inputs = [Path(__file__), fixture, KERNEL / 'manager_management.c',
              KERNEL / 'manager_management.h', KERNEL / 'manager_resident_host.c',
              KERNEL / 'manager_resident_abi.h', KERNEL / 'manager_claim_bootstrap.c',
              KERNEL / 'manager_claim_entry.c', ROOT / 'runtime/ultra/authorization/request_protocol.h']
    receipt = {'passed': len(results) == 2 and all(not r['exitCode'] for r in results),
               'commands': results,
               'sources': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
               'scope': 'exact production authorization/session validation and resident initialization bodies; kernel task, owner verification, allocator, lock and runtime providers are fixture seams; not device/ABI/concurrency proof',
               'formalArtifactBuilt': False, 'deviceOperations': False}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
