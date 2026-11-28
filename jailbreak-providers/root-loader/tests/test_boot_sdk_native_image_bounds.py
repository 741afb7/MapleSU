"""Native CONFIGURE must work when the ARM64 boot header is not mapped."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / 'jailbreak-providers/dirtyfrag/tests'))
from test_l3_arm import definition, host_compiler


class NativeImageBoundsTest(unittest.TestCase):
    def test_live_bounds_keep_header_unreadable_and_failure_cleanup(self):
        source = (HERE.parent / 'module/maplesu_boot_sdk.c').read_text()
        functions = '\n'.join(definition(source, name) for name in (
            'sdk_observation_entry', 'msu_boot_sdk_allocator_pre',
            'sample_allocator', 'configure', 'msu_boot_sdk_control'))
        fixture = (HERE / 'boot_sdk_configuration_test.c').read_text()
        fixture = fixture.replace('static char root_key[49];',
            'static uptr image_end; static unsigned int image_header_reads;')
        fixture = fixture.replace('    return 0;\n}\nstatic long read_kernel',
            '    if(!strcmp(name,"_end"))return image_end;\n'
            '    return 0;\n}\nstatic long read_kernel')
        fixture = fixture.replace('if(failed_read==1)return -14;',
            '++image_header_reads; return -14; /* ARM64 header stays unmapped. */')
        for name in ('bad-header', 'zero-image', 'oversize-image', 'image-read'):
            fixture, count = re.subn(
                r'\{"' + name + r'",-\d+,MSU_BS_CONFIG_IMAGE_(?:HEADER|READ),0,0\}',
                '{"' + name + '",0,MSU_BS_CONFIG_NONE,1,1}', fixture)
            self.assertEqual(count, 1, name)
        fixture = fixture.replace('    {"busy",-16,MSU_BS_CONFIG_NONE,0,0},',
            '    {"busy",-16,MSU_BS_CONFIG_NONE,0,0},\n'
            '    {"missing-end",-95,MSU_BS_CONFIG_IMAGE_HEADER,0,0},\n'
            '    {"equal-end",-95,MSU_BS_CONFIG_IMAGE_HEADER,0,0},\n'
            '    {"reversed-end",-95,MSU_BS_CONFIG_IMAGE_HEADER,0,0},\n'
            '    {"oversize-end",-95,MSU_BS_CONFIG_IMAGE_HEADER,0,0},\n'
            '    {"four-gib-end",0,MSU_BS_CONFIG_NONE,1,1},')
        fixture = fixture.replace('    probe_layout=request.layout;',
            '    image_end=kernel_base+0x10000000;\n'
            '    if(!strcmp(name,"missing-end"))image_end=0;\n'
            '    if(!strcmp(name,"equal-end"))image_end=kernel_base;\n'
            '    if(!strcmp(name,"reversed-end"))image_end=kernel_base-4096;\n'
            '    if(!strcmp(name,"oversize-end"))image_end=kernel_base+0x100000001UL;\n'
            '    if(!strcmp(name,"four-gib-end"))image_end=kernel_base+0x100000000UL;\n'
            '    probe_layout=request.layout;')
        fixture = fixture.replace('sdk_vm.image_bytes!=0x10000000',
                                  'sdk_vm.image_bytes!=image_end-kernel_base')
        fixture = fixture.replace('    printf("PASS %s;',
            '    if(image_header_reads)return 1;\n    printf("PASS %s;')
        cases = re.findall(r'^\s*\{"([^"\n]+)",', fixture, re.MULTILINE)
        compiler = host_compiler()
        if compiler is None:
            self.skipTest('No LP64 host C compiler configured')
        with tempfile.TemporaryDirectory(prefix='sdk-native-image-bounds-') as directory:
            path = Path(directory)
            (path / 'configuration.inc').write_text(functions)
            (path / 'fixture.c').write_text(fixture)
            for page in (4096, 16384, 65536):
                executable = path / ('native-' + str(page))
                subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                    '-Wno-unused-function', '-fsanitize=address,undefined',
                    '-fno-omit-frame-pointer', '-DMSU_BOOT_SDK_NATIVE=1',
                    '-DMSU_BS_PAGE_BYTES=' + str(page), '-I' + str(path),
                    '-I' + str(HERE.parent / 'module'), str(path / 'fixture.c'),
                    '-o', str(executable)], check=True)
                for case in cases:
                    with self.subTest(case=case, page=page):
                        result = subprocess.run([executable, case],
                            capture_output=True, text=True)
                        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                        self.assertIn('PASS ' + case + ';', result.stdout)


if __name__ == '__main__':
    unittest.main()
