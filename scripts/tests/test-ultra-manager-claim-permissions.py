"""Replay the production AArch64 deployment-permission branch in a host emulator.

The support bytes under test are the ones the claim build compiles from
runtime/ultra/identity/kernel. Anchor v4 addresses a 56-byte deployment binding
whose resident object is its first field; v2/v3 address the resident binding
directly. Kernel permission APIs, resident init and dispatch are explicit
Unicorn stubs, so this proves the support branch and its validation only.
"""
import argparse
import hashlib
import io
import json
from collections import Counter
from pathlib import Path
import struct
import zlib

from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_READ
from unicorn import UC_PROT_READ as R, UC_PROT_WRITE as W, UC_PROT_EXEC as X
from unicorn import arm64_const as reg

ROOT = Path(__file__).resolve().parents[2]
IMAGE, DEST, SOURCE, STACK, STOP = (0x100000000, 0x200000000, 0x300000000,
                                    0x400000000, 0x500000000)
SIZE, EXTENT, ANCHOR, DESC, TABLE, BIND, NAMESPACE = 0x20000, 0x24000, 0x400, 0x800, 0xa00, 0xb00, 0xc00
APPEND, SLOT = 0xd00, 0x20000
AT = {'bootstrap': 0x1000, 'materializer': 0x4000, 'permissions': 0x7000}
STUB = {name: 0xa000 + index * 0x100
        for index, name in enumerate(('allocate', 'release', 'nx', 'rw', 'ro', 'x', 'cache'))}
RET = bytes.fromhex('c0035fd6')
NAME = b'/.msu-ultra-identity-v1/0000000012345678'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def s32(value):
    return (value & 0xffffffff) - (0x100000000 if value & 0x80000000 else 0)


def support(folder):
    """Load the three freestanding support blobs and verify their ELF identity."""
    blobs, symbols, identities = {}, {}, {}
    for name in AT:
        elfdata = (folder / f'{name}.elf').read_bytes()
        elf = ELFFile(io.BytesIO(elfdata))
        text = elf.get_section_by_name('.text')
        assert elf['e_machine'] == 'EM_AARCH64'
        assert not any(isinstance(section, RelocationSection) and section.num_relocations()
                       for section in elf.iter_sections())
        assert not [symbol.name for symbol in elf.get_section_by_name('.symtab').iter_symbols()
                    if symbol.name and symbol['st_shndx'] == 'SHN_UNDEF']
        blobs[name] = (folder / f'{name}.bin').read_bytes()
        assert blobs[name] == text.data() and text['sh_addr'] == 0 and len(blobs[name]) % 4 == 0
        symbols[name] = {symbol.name: symbol['st_value']
                         for symbol in elf.get_section_by_name('.symtab').iter_symbols() if symbol.name}
        identities[name] = {'bytes': len(blobs[name]), 'sha256': digest(blobs[name]),
                            'elfSha256': digest(elfdata)}
    return blobs, symbols, identities


class Replay:
    """Fixture built from the production descriptor/anchor/binding layout."""

    def __init__(self, folder, version=5, protocol=4, shift=12, state=0, failures=(), alter=None):
        self.blobs, self.symbols, self.identity = support(folder)
        self.version, self.protocol, self.shift = version, protocol, shift
        self.page = 1 << shift
        self.pages = 3
        self.capacity = self.pages * self.page
        self.failures = set(failures)
        self.counts = Counter()
        self.events, self.reads, self.maint = [], [], []
        self.code = RET * 4
        self.data = bytes(range(16))
        self.payload = self.code + bytes(16) + self.data + bytes(16)
        image = bytearray(EXTENT)
        for name, at in AT.items():
            assert at + len(self.blobs[name]) < min([v for v in (*AT.values(), *STUB.values()) if v > at],
                                                    default=SIZE)
            image[at:at + len(self.blobs[name])] = self.blobs[name]
        for at in STUB.values():
            image[at:at + 4] = RET
        self.headerbytes = 56 if protocol == 4 else 52
        sections = struct.pack('<8I', 0, 16, 16, 5, 2 * self.page, 16, 32, 3)
        descriptor = struct.pack('<13I', 0x4355534d, protocol, self.headerbytes + 32, SIZE,
                                 2 * self.page + 32, 0, 2, 2, 0, 0, zlib.crc32(self.code + self.data),
                                 EXTENT, TABLE)
        if protocol == 4:
            descriptor += struct.pack('<I', 64)
        descriptor += sections
        sourceat = (0, 32) if protocol == 4 else (0x10000, 0x10020)
        table = struct.pack('<4I', sourceat[0], 16, sourceat[1], 16)
        image[DESC:DESC + len(descriptor)] = descriptor
        image[TABLE:TABLE + 16] = table
        if protocol == 3:
            image[0x10000:0x10040] = self.payload
        basic = struct.pack('<8I', SLOT, EXTENT, 0, 4, 8, APPEND, 88, 0)
        if version >= 4:
            namespace = struct.pack('<IHH16s', 0x4e44534d, 1, 24, b'Ab0123456789CdEF')
            image[NAMESPACE:NAMESPACE + 24] = namespace
            binding = bytearray(struct.pack('<2I', 1, 56) + basic + struct.pack('<4I', NAMESPACE, 24, 0, 0))
            struct.pack_into('<I', binding, 48, zlib.crc32(namespace, zlib.crc32(binding)))
            image[BIND:BIND + 56] = binding
        else:
            image[BIND:BIND + 32] = basic
        materializer = 'msu_manager_claim_materialize_source' if version == 5 else 'msu_manager_claim_materialize'
        offset = self.symbols['materializer'].get(materializer, 0)
        fields = [0x4143534d, version, ANCHOR, shift, AT['materializer'] + offset, 0,
                  STUB['allocate'], STUB['release'], STUB['nx'], STUB['rw'], STUB['ro'], STUB['x'],
                  STUB['cache'], 0, SIZE, DESC, len(descriptor),
                  zlib.crc32(table, zlib.crc32(descriptor)), AT['materializer'],
                  len(self.blobs['materializer']), BIND, AT['permissions'], 0, 0]
        image[ANCHOR:ANCHOR + 96] = struct.pack('<24I', *fields)
        image[SLOT:SLOT + 64] = struct.pack('<IIQQIi4Q', 0x5242534d, state, DEST if state == 2 else 0,
                                            DEST + 8 if state == 2 else 0,
                                            self.capacity if state == 2 else 0, 0, 0, 0, 0, 0)
        if alter:
            alter(image)
        self.image = bytes(image)
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for base, size, rights in ((IMAGE, EXTENT, R | W | X), (DEST, 0x40000, R | W),
                                   (SOURCE, 0x1000, R | W), (STACK, 0x10000, R | W), (STOP, 0x1000, R | X)):
            self.uc.mem_map(base, size, rights)
        self.uc.mem_write(IMAGE, self.image)
        self.uc.mem_protect(IMAGE, SIZE, R | X)
        self.uc.mem_protect(IMAGE + SIZE, EXTENT - SIZE, R | W)
        self.uc.mem_write(DEST, b'\xa5' * 0x40000)
        self.uc.mem_write(SOURCE, self.payload)
        self.uc.mem_protect(SOURCE, 0x1000, R)
        if state == 2:
            self.uc.mem_write(DEST, self.code)
            self.uc.mem_protect(DEST, self.page, R | X)
        self.uc.mem_write(STACK, NAME + b'\0')
        self.uc.mem_write(STOP, RET)
        self.rights = {page: R | W for page in range(0, 0x40000, 0x1000)}
        self.uc.hook_add(UC_HOOK_CODE, self.hook)
        self.uc.hook_add(UC_HOOK_MEM_READ, self.source_read, None, SOURCE, SOURCE + 0xfff)

    def x(self, number):
        return self.uc.reg_read(getattr(reg, f'UC_ARM64_REG_X{number}'))

    def returned(self, value=0):
        link = self.x(30)
        for number in range(19):
            self.uc.reg_write(getattr(reg, f'UC_ARM64_REG_X{number}'), 0xbad000 + number)
        self.uc.reg_write(reg.UC_ARM64_REG_X0, value & ((1 << 64) - 1))
        self.uc.reg_write(reg.UC_ARM64_REG_PC, link)

    def source_read(self, uc, access, at, size, value, data):
        self.reads.append([at - SOURCE, size])

    def hook(self, uc, pc, size, data):
        word = int.from_bytes(uc.mem_read(pc, 4), 'little')
        if word & 0xffffffe0 == 0xd5088360:
            self.maint.append(['tlbi', self.x(word & 31)])
            uc.reg_write(reg.UC_ARM64_REG_PC, pc + 4)
            return
        if word in (0xd5033a9f, 0xd5033b9f, 0xd5033fdf):
            self.maint.append([{0xd5033a9f: 'dsb-ishst', 0xd5033b9f: 'dsb-ish', 0xd5033fdf: 'isb'}[word]])
            uc.reg_write(reg.UC_ARM64_REG_PC, pc + 4)
            return
        kind = next((key for key, value in STUB.items() if pc == IMAGE + value), None)
        if kind:
            self.counts[kind] += 1
            failed = (kind, self.counts[kind]) in self.failures
            self.events.append([kind, self.x(0), self.x(1), failed])
            if kind == 'allocate':
                assert self.x(0) == self.capacity
                self.returned(DEST)
                return
            if kind == 'release':
                assert self.x(0) == DEST
                self.returned()
                return
            if kind == 'cache':
                assert (self.x(0), self.x(1)) == (DEST, DEST + self.capacity)
                expected = [((DEST >> 12) & ((1 << 44) - 1)) + (index << (self.shift - 12))
                            for index in range(self.pages)]
                assert [event[1] for event in self.maint if event[0] == 'tlbi'] == expected
                self.returned()
                return
            start, count = self.x(0), self.x(1) * (1 << self.shift)
            assert DEST <= start and start + count <= DEST + self.capacity
            if not failed:
                for at in range(start - DEST, start - DEST + count, 0x1000):
                    old = self.rights[at]
                    rights = {'nx': old & ~X, 'rw': old | W, 'ro': old & ~W, 'x': old | X}[kind]
                    assert rights & (W | X) != (W | X)
                    self.rights[at] = rights
                    uc.mem_protect(DEST + at, 0x1000, rights)
            self.returned(-5 if failed else 0)
            return
        if pc in (DEST, DEST + 4, DEST + 8):
            kind = {DEST: 'init', DEST + 4: 'discard', DEST + 8: 'dispatch'}[pc]
            self.events.append([kind, self.x(0)])
            if kind == 'init':
                config = bytes(uc.mem_read(self.x(0), 88))
                fields = struct.unpack('<4I9Q', config)
                assert fields[:4] == (3, 88, 88, 24 if self.version >= 4 else 0)
                assert fields[4:8] == (IMAGE, IMAGE + APPEND, 0, IMAGE + NAMESPACE if self.version >= 4 else 0)
                assert fields[8:12] == (IMAGE + ANCHOR, IMAGE + SLOT, DEST, self.capacity)
                assert bytes(uc.mem_read(DEST, 16)) == self.code
                assert bytes(uc.mem_read(DEST + 2 * self.page, 16)) == self.data
                assert bytes(uc.mem_read(DEST + 2 * self.page + 16, 16)) == bytes(16)
            self.returned()
            return

    def run(self, which='boot', source=SOURCE, sourcebytes=64):
        uc = self.uc
        uc.reg_write(reg.UC_ARM64_REG_SP, STACK + 0xf000)
        uc.reg_write(reg.UC_ARM64_REG_X30, STOP)
        saved = {getattr(reg, f'UC_ARM64_REG_X{number}'): 0xdead0000 + number for number in range(19, 30)}
        for register, value in saved.items():
            uc.reg_write(register, value)
        uc.reg_write(reg.UC_ARM64_REG_X0, IMAGE + ANCHOR)
        if which == 'permission':
            entry = IMAGE + AT['permissions']
            uc.reg_write(reg.UC_ARM64_REG_X1, IMAGE + DESC)
            uc.reg_write(reg.UC_ARM64_REG_X2, DEST)
            uc.reg_write(reg.UC_ARM64_REG_X3, self.pages)
        elif which == 'request':
            entry = IMAGE + AT['bootstrap'] + self.symbols['bootstrap']['msu_manager_claim_bootstrap']
            uc.reg_write(reg.UC_ARM64_REG_X1, STACK)
        else:
            name = {2: 'msu_manager_claim_bootstrap', 3: 'msu_manager_resident_boot',
                    4: 'msu_manager_resident_boot_deployment',
                    5: 'msu_manager_resident_boot_deployment_source'}[self.version]
            entry = IMAGE + AT['bootstrap'] + self.symbols['bootstrap'][name]
            uc.reg_write(reg.UC_ARM64_REG_X1, STACK if self.version == 2 else source)
            uc.reg_write(reg.UC_ARM64_REG_X2, sourcebytes)
        uc.emu_start(entry, STOP, count=3000000, timeout=5000000)
        assert uc.reg_read(reg.UC_ARM64_REG_PC) == STOP
        assert uc.reg_read(reg.UC_ARM64_REG_SP) == STACK + 0xf000
        assert all(uc.reg_read(register) == value for register, value in saved.items())
        assert bytes(uc.mem_read(IMAGE, SIZE)) == self.image[:SIZE]
        assert bytes(uc.mem_read(SOURCE, 64)) == self.payload
        slot = struct.unpack('<IIQQIi4Q', bytes(uc.mem_read(IMAGE + SLOT, 64)))
        return {'return': s32(self.x(0)), 'events': self.events, 'sourceReads': len(self.reads),
                'maintenance': self.maint, 'state': slot[1], 'lastError': slot[5], 'dispatch': slot[3],
                'immutableKernel': True, 'immutableSource': True,
                'calleeRegistersAndStackPreserved': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True, help='new directory for receipts and logs')
    parser.add_argument('--support', type=Path, required=True, help='claim-build support directory')
    parser.add_argument('--retirement-bootstrap', action='store_true',
                        help='support carry a retirement-KCFI bootstrap; restrict replay to the permission branch')
    args = parser.parse_args()
    args.build = args.build.resolve()
    args.build.mkdir(parents=True, exist_ok=False)
    support_dir = args.support.resolve()
    rows = []
    skipped = []
    # A retirement-KCFI bootstrap checks the guard word before every indirect call and
    # aborts on mismatch. This fixture's resident stubs share one code page without
    # per-target guard words, so the request-dispatch cases can only be replayed on the
    # plain freestanding bootstrap; the retirement path is covered by the claim build and
    # the bootstrap replay test.
    bootstrap_bytes = (support_dir / "bootstrap.bin").read_bytes()
    kcfi_bootstrap = any(
        int.from_bytes(bootstrap_bytes[at:at + 4], "little") & 0xffe00c00 == 0xb8400000 and
        (int.from_bytes(bootstrap_bytes[at:at + 4], "little") >> 12) & 0x1ff == 0x1fc and
        int.from_bytes(bootstrap_bytes[at:at + 4], "little") & 31 == 16
        for at in range(0, len(bootstrap_bytes) - 3, 4))

    def case(name, options, which, expected):
        if kcfi_bootstrap:
            if not args.retirement_bootstrap:
                raise SystemExit('RETIREMENT_KCFI_BOOTSTRAP_REQUIRES_FLAG: use a plain freestanding '
                                 'bootstrap or pass --retirement-bootstrap')
            if which != 'permission':
                skipped.append({'name': name,
                                'reason': 'RETIREMENT_KCFI_BOOTSTRAP_NOT_REPLAYABLE_BY_THIS_FIXTURE'})
                return
        try:
            result = Replay(support_dir, **options).run(which)
            if which == 'request':
                kinds = [event[0] for event in result['events']]
                wanted = ['dispatch'] if options.get('state') == 2 else []
                assert kinds == wanted, kinds
                passed = True
            else:
                passed = result['return'] == expected
            rows.append({'name': name, 'which': which, 'options': {
                key: (sorted(map(list, value)) if isinstance(value, set) else value)
                for key, value in options.items() if key != 'alter'},
                'expected': expected, 'observed': result['return'], 'passed': passed})
        except Exception as error:  # noqa: BLE001 - every failure is a receipt row
            rows.append({'name': name, 'which': which, 'passed': False, 'error': repr(error)})

    for shift in (12, 14, 16):
        page = f'page{1 << shift}'
        for version in (2, 3):
            case(f'legacy-permissions-v{version}-{page}', dict(version=version, protocol=3, shift=shift), 'permission', 0)
        case(f'legacy-boot-v3-{page}', dict(version=3, protocol=3, shift=shift), 'boot', 0)
        case(f'deployment-permissions-v4-{page}', dict(version=4, protocol=3, shift=shift), 'permission', 0)
        case(f'deployment-boot-v4-{page}', dict(version=4, protocol=3, shift=shift), 'boot', 0)
        for state in (0, 2):
            case(f'deployment-request-state{state}-{page}', dict(version=4, protocol=3, shift=shift, state=state), 'request', 0)
        for kind, count in (('ro', 1), ('rw', 1), ('ro', 2), ('x', 1)):
            case(f'legacy-permission-error-{kind}-{count}-{page}',
                 dict(version=3, protocol=3, shift=shift, failures={(kind, count)}), 'permission', -5)
        for field, offset, value in (('binding-version', BIND, 2), ('binding-size', BIND + 4, 52),
                                     ('binding-namespace-size', BIND + 44, 23), ('binding-crc', BIND + 48, 0),
                                     ('resident-init-gap', BIND + 16, 16),
                                     ('resident-discard-data', BIND + 20, 2 * (1 << shift)),
                                     ('resident-dispatch-unaligned', BIND + 24, 2)):
            def alter(image, at=offset, replacement=value):
                struct.pack_into('<I', image, at, replacement)
                if at in (BIND + 16, BIND + 20, BIND + 24):
                    binding = bytearray(image[BIND:BIND + 56])
                    struct.pack_into('<I', binding, 48, 0)
                    struct.pack_into('<I', image, BIND + 48,
                                     zlib.crc32(image[NAMESPACE:NAMESPACE + 24], zlib.crc32(binding)))
            case(f'reject-{field}-{page}', dict(version=4, protocol=3, shift=shift, alter=alter), 'boot', -22)

        def badnamespace(image):
            image[NAMESPACE + 8] = ord('/')
        case(f'reject-namespace-character-{page}',
             dict(version=4, protocol=3, shift=shift, alter=badnamespace), 'boot', -22)

        def badpayload(image):
            image[0x10000] ^= 1
        case(f'reject-payload-crc-{page}', dict(version=4, protocol=3, shift=shift, alter=badpayload), 'boot', -7)

        def wx(image):
            struct.pack_into('<I', image, DESC + 52 + 16, 32)
            struct.pack_into('<I', image, ANCHOR + 68,
                             zlib.crc32(image[TABLE:TABLE + 16], zlib.crc32(image[DESC:DESC + 84])))
        case(f'reject-wx-shared-page-{page}', dict(version=4, protocol=3, shift=shift, alter=wx), 'boot', -22)

    identity = support(support_dir)[2]
    failures = [row for row in rows if not row['passed']]
    report = {'passed': not failures, 'cases': len(rows) + len(skipped), 'executedCases': len(rows),
              'passedCases': len(rows) - len(failures), 'skippedCases': len(skipped),
              'failedCases': [row['name'] for row in failures], 'skipped': skipped,
              'runnerSha256': digest(Path(__file__).read_bytes()),
              'support': identity,
              'results': rows,
              'scope': ('host Unicorn execution of the production AArch64 permissions support against the '
                        'production anchor v4 deployment-binding layout; kernel permission APIs, resident '
                        'init and dispatch are explicit stubs, so hardware bootability is not proven'),
              'deviceExecuted': False, 'hardwareBootability': False}
    (args.build / 'permissions.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    (args.build / 'permissions.log').write_text(
        '\n'.join(f"{row['name']}: {'PASS' if row['passed'] else 'FAIL ' + row.get('error', '')}" for row in rows)
        + ''.join(f"\n{row['name']}: SKIP {row['reason']}" for row in skipped)
        + f"\npassed={report['passed']} cases={report['cases']} executed={report['executedCases']} "
          f"skipped={report['skippedCases']} failures={len(failures)}\n", encoding='utf-8')
    print(json.dumps({key: report[key] for key in
                      ('passed', 'cases', 'executedCases', 'passedCases', 'skippedCases', 'failedCases')}))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
