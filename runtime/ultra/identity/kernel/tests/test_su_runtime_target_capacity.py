"""Compile the real native/compat binder against the production protocol cap."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[5]
HERE = Path(__file__).resolve().parent


def definition(source, signature):
    start = source.index(signature)
    cursor = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    return source[start:cursor]


class TargetCapacityTest(unittest.TestCase):
    def test_real_binder_all_optional_and_compat_subsets(self):
        compiler = os.environ.get('MAPLESU_HOST_CC') or shutil.which('cc') or shutil.which('gcc')
        if not compiler:
            self.skipTest('No LP64 host compiler configured')
        environment = dict(os.environ)
        if os.name == 'nt':
            environment['PATH'] = str(Path(compiler).parent) + os.pathsep + environment.get('PATH', '')
        source = (ROOT / 'runtime/ultra/identity/kernel/manager_su_runtime_target.c').read_text()
        profile = (ROOT / 'runtime/ultra/identity/kernel/manager_su_runtime_target_profile.h').read_text()
        factory = (ROOT / 'runtime/ultra/identity/kernel/manager_su_runtime_factory.h').read_text()
        include = re.sub(r'^#include[^\n]*\n', '', profile, flags=re.MULTILINE)
        include += '\n' + definition(factory, 'enum msu_su_hook_kind {') + ';\n'
        include += definition(factory, 'struct msu_su_runtime_hook {') + ';\n'
        include += definition(source, 'struct msu_su_runtime_profile_table msu_su_runtime_profiles') + ';\n'
        include += ('static DEFINE_MUTEX(msu_su_target_lock);\n'
                    'static struct msu_su_runtime_hook msu_su_targets[MSU_SU_RUNTIME_MAX_HOOKS];\n'
                    'static u32 msu_su_runtime_target_facts[9];\n')
        include += definition(source, 'static noinline long msu_su_syscall_reference(') + '\n'
        begin = source.index('struct msu_su_compat_target {')
        end = source.index('\n#endif\n#endif\n\nint msu_su_runtime_target_bind(', begin)
        include += '#ifdef CONFIG_COMPAT\n' + source[begin:end] + '\n#endif\n'
        include += definition(source, 'int msu_su_runtime_target_bind(') + '\n'
        include = include.replace('__attribute__((section(".msu.su-runtime.profiles"), aligned(4)))', '__attribute__((aligned(4)))')
        fixture = (HERE / 'su_runtime_target_capacity_fixture.c').read_text()
        if os.name == 'nt':
            # Windows LLP64 needs explicit ARM64-width words in the host seam.
            include = include.replace('unsigned long', 'uintptr_t').replace('~0UL', 'UINTPTR_MAX')
            fixture = fixture.replace('unsigned long', 'uintptr_t')
        with tempfile.TemporaryDirectory(prefix='su-target-capacity-') as directory:
            directory = Path(directory)
            (directory / 'target.inc').write_text(include)
            (directory / 'fixture.c').write_text(fixture)
            for compat, cases, maximum in ((False, 8, 8), (True, 2048, 13)):
                with self.subTest(compat=compat):
                    executable = directory / ('compat' if compat else 'native')
                    command = [str(compiler), '-std=gnu11', '-O1', '-Wall', '-Wextra', '-Werror',
                               '-DCONFIG_ARM64=1', '-DCONFIG_ARCH_HAS_SYSCALL_WRAPPER=1',
                               '-I' + str(directory), '-I' + str(ROOT / 'runtime/ultra/authorization')]
                    if os.name != 'nt':
                        command += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
                    if compat:
                        command.append('-DCONFIG_COMPAT=1')
                    subprocess.run(command + [str(directory / 'fixture.c'), '-o', str(executable)], check=True, env=environment)
                    result = subprocess.run([str(executable)], capture_output=True, text=True, env=environment)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn(f'positives={cases} failures=0 maxHooks={maximum} wireBytes=104', result.stdout)
                    print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()