#!/usr/bin/env python3
"""Compile production m029 and execute emitted gates with explicit kernel API seams.

The request API and return-owner provider are fixtures. This proves installer
decisions and emitted AArch64 register/stack flow, not kernel retirement or SMP.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import subprocess
import sys

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

SDK = Path(__file__).resolve().parents[1]
REPO = SDK.parents[2]
BASE, STACK, STOP = 0xffff000000000000, 0x9ff000, 0x200000
X = [globals()[f'UC_ARM64_REG_X{n}'] for n in range(31)]


def build(out, library):
    shim = out / 'include'
    shim.mkdir(exist_ok=True)
    (shim / 'kernel_module_kit_umbrella.h').write_text('''#pragma once
#include <algorithm>
#include <cerrno>
using uid_t = unsigned;
using gid_t = unsigned;
inline uid_t getuid(){return 0;}
inline int getresuid(uid_t* real,uid_t* effective,uid_t* saved){*real=*effective=*saved=0;return 0;}
inline int getresgid(gid_t* real,gid_t* effective,gid_t* saved){*real=*effective=*saved=0;return 0;}
#define LOCK_EX 2
#define LOCK_UN 8
#define O_CLOEXEC 0
#define O_NOFOLLOW 0
inline int flock(int,int){errno=EOPNOTSUPP;return -1;}
#ifndef ESTALE
#define ESTALE 116
#endif
#include "aarch64_asm_helper.h"
#include "module_err_def.h"
#include "module_base_kernel_mem.h"
#include "maplesu_kernel_layout_private.h"
#include "module_base_kernel_symbol_kaddr.h"
#define FOLDER_HEAD_ROOT_KEY_LEN 16
namespace skroot_env { struct module_record; }
struct module_record;
namespace kernel_module {
using HookHandle=struct HookContext*;
void arm64_module_asm_func_start(asmjit::a64::Assembler*);
void arm64_module_asm_func_end(asmjit::a64::Assembler*,asmjit::a64::GpX);
KModErr execute_kernel_asm_func(const std::vector<uint8_t>&,uint64_t&);
KModErr icache_invalidate_all_cpus();
namespace export_symbol {
inline void fixture_call(asmjit::a64::Assembler* a,uint64_t address){aarch64_asm_mov_x(a,asmjit::a64::x16,address);a->blr(asmjit::a64::x16);}
inline void aarch64_insn_patch_text(asmjit::a64::Assembler* a,KModErr&,asmjit::a64::GpX,asmjit::a64::GpX,asmjit::a64::GpW){fixture_call(a,0x980000);}
inline void get_kprobe(asmjit::a64::Assembler* a,KModErr&,asmjit::a64::GpX arg){a->mov(asmjit::a64::x0,arg);fixture_call(a,0x910000);}
inline void mutex_lock(asmjit::a64::Assembler* a,KModErr&,asmjit::a64::GpX arg){a->mov(asmjit::a64::x0,arg);fixture_call(a,0x930000);}
inline void mutex_unlock(asmjit::a64::Assembler* a,KModErr&,asmjit::a64::GpX arg){a->mov(asmjit::a64::x0,arg);fixture_call(a,0x940000);}
inline void copy_to_user(asmjit::a64::Assembler* a,KModErr&,asmjit::a64::GpX dst,asmjit::a64::GpX src,uint64_t bytes){a->mov(asmjit::a64::x0,dst);a->mov(asmjit::a64::x1,src);aarch64_asm_mov_x(a,asmjit::a64::x2,bytes);fixture_call(a,0x970000);}
inline void copy_from_kernel_nofault(asmjit::a64::Assembler* a,KModErr&,asmjit::a64::GpX dst,asmjit::a64::GpX src,uint64_t bytes){a->mov(asmjit::a64::x0,dst);a->mov(asmjit::a64::x1,src);aarch64_asm_mov_x(a,asmjit::a64::x2,bytes);fixture_call(a,0x950000);}
}}
''', encoding='utf-8')
    (shim / 'module_base_kernel_func_hook.h').write_text('#pragma once\n', encoding='utf-8')
    guard = SDK.parent / 'include/aarch64_asm_reg_protect_guard.h'
    (shim / guard.name).write_text(guard.read_text(encoding='utf-8').replace(
        '#include "kernel_module_kit_umbrella.h"', '#include <kernel_module_kit_umbrella.h>'), encoding='utf-8')
    cxx = sorted((REPO / '.research/toolchains').glob('llvm-mingw-*/bin/x86_64-w64-mingw32-clang++.exe'))[-1]
    vendor = SDK / 'third_party/asmjit/1.16.0/src'
    exe = out / 'p5_owner_gate_probe.exe'
    command = [str(cxx), '-std=c++20', '-O1', '-ffunction-sections', '-fdata-sections',
        '-DASMJIT_NO_X86', '-DASMJIT_NO_COMPILER', '-DASMJIT_NO_BUILDER', '-include', 'algorithm',
        '-I' + str(shim), '-I' + str(SDK.parent / 'include'), '-I' + str(SDK / 'include'),
        '-I' + str(REPO / 'runtime/ultra/authorization'), '-I' + str(vendor),
        str(SDK / 'tests/p5_owner_gate_probe.cpp'),
        str(SDK / 'src/func_hook/module_base_kernel_func_hook.cpp'),
        str(SDK / 'src/aarch64/aarch64_simulate_insn.cpp'),
        str(SDK / 'src/aarch64/aarch64_decode_insn.cpp'), str(library),
        '-Wl,--gc-sections', '-static', '-o', str(exe)]
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
    (out / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(f'compile exit {result.returncode}: {result.stderr}')
    return exe, command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--asmjit-lib', required=True, type=Path)
    parser.add_argument('--groups', choices=['all', 'before', 'publication', 'probe', 'pac', 'legacy'], default='all')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = [SDK / 'src/func_hook' / name for name in (
        'module_base_kernel_func_hook.cpp', 'owner_hook_publication.h', 'owner_hook_codegen.h', 'owner_publication_gate.h', 'legacy_hook_publication.h', 'legacy_boot_hook_publication.h')]
    sources.extend([SDK / 'include/maplesu_hook_private.h', SDK / 'include/maplesu_request_private.h', SDK / 'include/maplesu_request_identity.h', Path(__file__), SDK / 'tests/p5_owner_gate_probe.cpp', REPO / 'runtime/ultra/authorization/publication_return_private.h'])
    inputs = [dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sources]

    def finish_report(report):
        assert inputs == [dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sources], 'inputs changed during run'
        report['inputs'] = inputs
        (out / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: report[k] for k in ('passed', 'tests', 'scope')}))

    legacy = args.groups == 'legacy'
    exe, command = build(out, args.asmjit_lib.resolve())
    rows, samples, owners = [], {}, {}
    functions = [BASE + n for n in (0x810000, 0x820000, 0x830000, 0x840000, 0x850000, 0x860000, 0x890000)]

    def sample(name, extra=()):
        result = subprocess.run([str(exe), ('legacy-' if legacy else '') + name, *extra], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=10)
        (out / ('-'.join([name, *extra]) + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        assert result.returncode == 0, (name, result.stdout, result.stderr)
        code = [(int(l.split()[1], 16), bytes.fromhex(l.split()[2])) for l in result.stdout.splitlines() if l.startswith('code ')]
        meta = next(([int(v, 16) for v in l.split()[1:]] for l in result.stdout.splitlines() if l.startswith('meta ')), None)
        return meta, code

    if args.groups == 'probe':
        for name in ['brk-before', 'after-brk', 'before-brk-missing',
                'before-brk-addr-fault', 'before-brk-opcode-fault', 'before-brk-mismatch']:
            sample(name)
            output = (out / (name + '.log')).read_text(encoding='utf-8')
            code = bytes.fromhex(next(l.split()[1] for l in output.splitlines() if l.startswith('snapshot ')))
            machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            machine.mem_map(0x900000, 0x70000)
            machine.mem_map(STACK - 0x10000, 0x20000)
            machine.mem_map(STOP, 0x1000)
            machine.mem_write(0x900000, code)
            target = BASE + 0x300000
            machine.mem_write(0x920000, struct.pack('<QI', target + (4 if 'mismatch' in name else 0), 0xd503201f))
            machine.reg_write(UC_ARM64_REG_SP, STACK)
            machine.reg_write(UC_ARM64_REG_X30, STOP)
            state = dict(locked=False, copies=0, unlocks=0)

            def snapshot_call(u, address, size, unused):
                if address == STOP:
                    u.emu_stop()
                    return
                if address not in (0x910000, 0x930000, 0x940000, 0x950000):
                    return
                result = 0
                if address == 0x930000:
                    assert not state['locked'] and u.reg_read(UC_ARM64_REG_X0) == BASE + 0x960000
                    state['locked'] = True
                elif address == 0x940000:
                    assert state['locked'] and u.reg_read(UC_ARM64_REG_X0) == BASE + 0x960000
                    state['locked'] = False
                    state['unlocks'] += 1
                elif address == 0x910000:
                    assert state['locked'] and u.reg_read(UC_ARM64_REG_X0) == target
                    result = 0 if 'missing' in name else 0x920000
                else:
                    assert state['locked']
                    state['copies'] += 1
                    destination = u.reg_read(UC_ARM64_REG_X0)
                    source = u.reg_read(UC_ARM64_REG_X1)
                    count = u.reg_read(UC_ARM64_REG_X2)
                    failed = ('addr-fault' in name and state['copies'] == 1) or ('opcode-fault' in name and state['copies'] == 2)
                    if failed:
                        result = (1 << 64) - 14
                    else:
                        u.mem_write(destination, bytes(u.mem_read(source, count)))
                u.reg_write(UC_ARM64_REG_X0, result)
                u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_X30))

            machine.hook_add(UC_HOOK_CODE, snapshot_call)
            machine.emu_start(0x900000, STOP, count=10000)
            expected = 0x1d503201f if name in ('brk-before', 'after-brk') else 0
            assert machine.reg_read(UC_ARM64_REG_X0) == expected, (name, hex(machine.reg_read(UC_ARM64_REG_X0)))
            assert machine.reg_read(UC_ARM64_REG_SP) == STACK and state['unlocks'] == 1 and not state['locked']
            rows.append(dict(case=name, passed=True, **state))
        report = dict(passed=True, tests=len(rows), cases=rows, compileCommand=command,
            scope='production BRK normalizer and emitted protected snapshot; native lookup/copy API seams; no native writer runtime claim')
        finish_report(report)
        return 0

    installer_cases = ['select-provider', 'select-boot', 'select-corrupt', 'select-unsupported',
            'select-metadata-failure', 'select-overflow', 'select-read-failure',
            'before-switch-protocol-uninstall', 'alloc-lost-gate', 'alloc-lost-payload', 'prepare-lost', 'control-lost',
            'publish-lost', 'pending-null', 'after-tail-id', 'after-tail-length',
            'before-shared', 'after-shared', 'before-shared-max', 'after-shared-max',
            'before-shared-invalid-duplicate', 'after-shared-invalid-duplicate',
            'brk-before', 'after-brk', 'before-close-uninstall', 'after-close-uninstall']
    for name in installer_cases if args.groups in ('all', 'legacy') else []:
        if legacy and name in ('brk-before', 'after-brk', 'control-lost', 'pending-null'):
            continue
        sample(name)
        rows.append(dict(case=name, passed=True, scope='production installer; request API seam'))
    if args.groups == 'publication':
        for name in ['before-logical-publish', 'after-logical-publish',
                'before-invalid-publish-0-text', 'before-invalid-publish-4-no-text',
                'before-invalid-publish-0-pending']:
            sample(name)
            rows.append(dict(case=name, passed=True, scope='production installer; request API seam'))
        report = dict(passed=True, tests=len(rows), cases=rows, compileCommand=command,
            scope='actual SDK installer accepts logical publication only with exact effects; no native-writer runtime claim')
        finish_report(report)
        return 0
    if legacy:
        output = (out / 'before-close-uninstall.log').read_text(encoding='utf-8').splitlines()
        exchanges = [line.split() for line in output if line.startswith('exchange ')]
        responses = [bytes.fromhex(line.split()[1]) for line in output if line.startswith('response ')]
        assert len(exchanges) == len(responses) and len(exchanges) >= 7
        for index, (exchange, response) in enumerate(zip(exchanges, responses)):
            command, destination = int(exchange[1], 16), int(exchange[2], 16)
            code = bytes.fromhex(exchange[3])
            uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            run, stop, provider, get, put, dispatch, copy = 0x100000, 0x200000, BASE + 0xa30000, BASE + 0xa10000, BASE + 0xa20000, BASE + 0xa40000, 0x970000
            for address in (run, stop, provider, get, put, dispatch, copy, destination):
                page = address & ~0xfff
                try: uc.mem_map(page, 0x2000)
                except Exception: pass
            uc.mem_map(STACK - 0x10000, 0x20000)
            uc.mem_write(run, code)
            # Fixture provider exposes every current optional capability.
            caps = (1 << 63) - 1
            uc.mem_write(provider, struct.pack('<QIIQQQQQQQ', 0x314b484c55534d, 1, 72,
                0x88776655, 0x11223344, caps, 0, 0, 0, dispatch))
            uc.reg_write(UC_ARM64_REG_SP, STACK)
            uc.reg_write(UC_ARM64_REG_X30, stop)
            state = dict(get=0, put=0, dispatch=0, copy=0)
            def exchange_call(u, address, size, unused):
                if address == stop: u.emu_stop(); return
                if address not in (get, put, dispatch, copy): return
                result = 0
                if address == get:
                    u.mem_write(u.reg_read(UC_ARM64_REG_X0), struct.pack('<Q', provider))
                    u.mem_write(u.reg_read(UC_ARM64_REG_X1), struct.pack('<Q', 0x7788)); state['get'] += 1
                elif address == put:
                    assert u.reg_read(UC_ARM64_REG_X0) == 0x7788; state['put'] += 1
                elif address == dispatch:
                    assert u.reg_read(UC_ARM64_REG_X0) == 0 and u.reg_read(UC_ARM64_REG_X1) == command
                    operation = bytes(u.mem_read(u.reg_read(UC_ARM64_REG_X2), 64))
                    assert struct.unpack_from('<QQ', operation) == (0x88776655, 0x11223344)
                    u.mem_write(u.reg_read(UC_ARM64_REG_X3), response[:48]); state['dispatch'] += 1
                else:
                    assert u.reg_read(UC_ARM64_REG_X0) == destination and u.reg_read(UC_ARM64_REG_X2) == 64
                    u.mem_write(destination, bytes(u.mem_read(u.reg_read(UC_ARM64_REG_X1), 64))); state['copy'] += 1
                u.reg_write(UC_ARM64_REG_X0, result); u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_X30))
            uc.hook_add(UC_HOOK_CODE, exchange_call)
            uc.emu_start(run, stop, count=5000)
            assert state == dict(get=1, put=1, dispatch=1, copy=1), (index, command, state)
            assert uc.reg_read(UC_ARM64_REG_SP) == STACK and uc.reg_read(UC_ARM64_REG_X0) == 0
            assert bytes(uc.mem_read(destination, 64)) == response
            rows.append(dict(case=f'legacy-exchange-{index}-command-{command}', passed=True,
                scope='actual emitted Legacy provider get/dispatch/copy/put; native APIs are seams'))

    gate_cases = ['before-continue', 'before-early', 'before-manual']
    if args.groups in ('all', 'legacy'):
        gate_cases.append('after')
    for name in gate_cases:
        samples[name] = sample(name)

    def machine(name, admitted=True, hold=False, disable_in_original=False):
        meta, code = samples[name]
        entry, gate, payload, pool, control, enter, leave, source_size, payload_size = meta[:9]
        root, pac = meta[9:11] if len(meta) > 9 else (entry, 0)
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for address in [entry, gate, payload, control, *functions]:
            uc.mem_map(address & ~0xfff, 0x1000)
        uc.mem_map(STACK - 0x10000, 0x20000)
        uc.mem_map(STOP, 0x1000)
        for address, data in code:
            uc.mem_write(address, data)
        values = [31 + n * 7 for n in range(24)]
        uc.mem_write(STACK, struct.pack('<' + 'Q' * len(values), *values))
        body = []
        for n in range(len(values)):
            body.extend([0xf94003f0 | (n << 10), 0x8b100000])
        if pac:
            body.append(pac + 0x80)
        body.append(0xd65f03c0)
        uc.mem_write(entry + 4, struct.pack('<' + 'I' * len(body), *body))
        for n, reg in enumerate(X):
            uc.reg_write(reg, 0x12340000 + n)
        uc.reg_write(UC_ARM64_REG_X0, 7)
        uc.reg_write(UC_ARM64_REG_X30, STOP)
        uc.reg_write(UC_ARM64_REG_SP, STACK)
        uc.reg_write(UC_ARM64_REG_NZCV, 0x60000000)
        state = dict(original=0, callbacks=0, acquired=0, released=0, paused=False, enabled=admitted)
        signatures = {}

        def observer(u, address, size, unused):
            if address == STOP:
                u.emu_stop()
                return
            word = struct.unpack('<I', u.mem_read(address, 4))[0]
            if word in (0xd503231f, 0xd503233f, 0xd503235f, 0xd503237f):
                signed = 0xa500000000000000 + len(signatures) + 1
                modifier = u.reg_read(UC_ARM64_REG_SP) if word & 0x20 else 0
                signatures[signed] = (u.reg_read(UC_ARM64_REG_X30), word, modifier)
                u.reg_write(UC_ARM64_REG_X30, signed)
                u.reg_write(UC_ARM64_REG_PC, address + 4)
                return
            if word in (0xd503239f, 0xd50323bf, 0xd50323df, 0xd50323ff):
                signed = signatures.get(u.reg_read(UC_ARM64_REG_X30))
                modifier = u.reg_read(UC_ARM64_REG_SP) if word & 0x20 else 0
                assert signed and signed[1:] == (word - 0x80, modifier), (name, hex(address), signed, modifier)
                u.reg_write(UC_ARM64_REG_X30, signed[0])
                u.reg_write(UC_ARM64_REG_PC, address + 4)
                return
            if address == entry + 4:
                state['original'] += 1
                expected_sp = STACK - 336 if name == 'before-manual' and admitted else STACK
                assert u.reg_read(UC_ARM64_REG_SP) == expected_sp, (name, hex(u.reg_read(UC_ARM64_REG_SP)))
                if admitted:
                    assert state['acquired'] == 1 and not state['released']
                if disable_in_original:
                    state['enabled'] = False
                if hold and not state['paused']:
                    state['paused'] = True
                    u.emu_stop()
                    return
            if address == payload:
                state['callbacks'] += 1
                assert u.reg_read(UC_ARM64_REG_SP) == STACK - (128 if name.startswith('after') else 112)
            if address not in functions:
                return
            link, arg = u.reg_read(UC_ARM64_REG_X30), u.reg_read(UC_ARM64_REG_X0)
            assert u.reg_read(UC_ARM64_REG_SP) % 16 == 0
            result = 0
            if address == enter:
                assert arg == 0x1234
                result = payload if state['enabled'] else 0
            elif address == leave:
                assert arg == 0x1234
                u.mem_unmap(payload, 0x1000)
            elif address == BASE + 0x830000:
                assert arg == 0x1234
                assert u.reg_read(UC_ARM64_REG_X1) == 0x1234001c
                assert u.reg_read(UC_ARM64_REG_X3) == STACK
                if state['enabled']:
                    result = BASE + 0x920000 + len(owners) * 0x40
                    owners[result] = (u.reg_read(UC_ARM64_REG_X1), u.reg_read(UC_ARM64_REG_X2))
                    state['acquired'] += 1
            elif address == BASE + 0x840000:
                assert arg in owners
                del owners[arg]
                state['released'] += 1
                u.mem_unmap(payload, 0x1000)
            else:
                assert arg in owners
                result = payload if address == BASE + 0x890000 else owners[arg][0 if address == BASE + 0x850000 else 1]
            for n in range(19):
                u.reg_write(X[n], 0xbad00000 + n)
            u.reg_write(UC_ARM64_REG_NZCV, 0xf0000000)
            u.reg_write(UC_ARM64_REG_X0, result)
            u.reg_write(UC_ARM64_REG_PC, link)

        uc.hook_add(UC_HOOK_CODE, observer)
        return uc, state, values, meta

    def finish(name, uc, state, values, meta, admitted, start=None):
        uc.emu_start(start or (meta[9] if len(meta) > 9 else meta[0]), STOP, count=100000)
        early = 'early' in name and admitted
        assert uc.reg_read(UC_ARM64_REG_PC) == STOP
        assert uc.reg_read(UC_ARM64_REG_X0) == (107 if early else sum(values) + 7 + (100 if admitted else 0))
        assert state['original'] == (0 if early else 1), state
        assert state['callbacks'] == int(admitted), state
        for n in (18, 28, 29):
            assert uc.reg_read(X[n]) == 0x12340000 + n, (n, hex(uc.reg_read(X[n])))
        assert uc.reg_read(UC_ARM64_REG_NZCV) == 0x60000000
        assert uc.reg_read(UC_ARM64_REG_SP) == STACK
        assert state['acquired'] == int(admitted) and state['released'] == int(admitted)
        return dict(passed=True, **state)

    if args.groups == 'pac':
        for prefix, first, second, pac in [
            ('paciasp', 0xd503233f, 0xd503201f, 0xd503233f),
            ('pacibsp', 0xd503237f, 0xd503201f, 0xd503237f),
            ('bti-paciasp', 0xd503245f, 0xd503233f, 0xd503233f),
            ('bti', 0xd503245f, 0xd503201f, 0),
        ]:
            for hook in ('before', 'before-early', 'after'):
                name = hook + '-pac-' + prefix
                samples[name] = sample(name, [f'{n:x}' for n in (first, second, pac)])
                for admitted in (False, True):
                    rows.append(dict(case=name + f'-admitted-{int(admitted)}',
                        **finish(name, *machine(name, admitted), admitted)))
        report = dict(passed=True, tests=len(rows), cases=rows, compileCommand=command,
            scope='actual emitted gates; PAC signature/modifier emulator seam; no hardware PAC/BTI certification')
        finish_report(report)
        return 0

    for name in gate_cases:
        for admitted in (False, True):
            rows.append(dict(case=f'{name}-admitted-{int(admitted)}',
                **finish(name, *machine(name, admitted), admitted)))
    sleep_name = 'before-continue' if args.groups == 'before' else 'after'
    rows.append(dict(case=f'admitted-{sleep_name}-survives-disable',
        **finish(sleep_name, *machine(sleep_name, True, disable_in_original=True), True)))
    waiting = [machine(sleep_name, True, hold=True) for _ in range(80)]
    for uc, state, values, meta in waiting:
        uc.emu_start(meta[0], STOP, count=100000)
        assert state['paused'] and state['acquired'] and state['callbacks'] == int(sleep_name != 'after')
    assert len(owners) == 80
    for uc, state, values, meta in waiting:
        state['original'] = 0
        state['enabled'] = False
        finish(sleep_name, uc, state, values, meta, True, meta[0] + 4)
    assert not owners
    rows.append(dict(case=f'80-paused-{sleep_name}-originals-disable-resume', passed=True,
        scope='emitted gates; dynamic provider seam; no fixed64/native allocator claim'))
    report = dict(passed=True, tests=len(rows), cases=rows, compileCommand=command,
        scope='production SDK code and emitted AArch64; explicit request/return-provider fixture; no native kernel/SMP/device execution')
    finish_report(report)
    return 0


if __name__ == '__main__':
    sys.exit(main())
