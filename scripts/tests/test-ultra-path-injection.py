"""Compile current PATH primitive and exercise its host syscall fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--build', type=Path, required=True)
parser.add_argument('--ndk', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
build = args.build.resolve()
build.mkdir(parents=True, exist_ok=True)
relative = build.relative_to(root).as_posix()
sources = [root / name for name in [
    'runtime/src/path_injection.h', 'runtime/src/path_injection.cpp',
    'runtime/tests/path_injection_test.cpp', 'scripts/tests/test-ultra-path-injection.py']]
digests = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
clang = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
subprocess.run([str(clang), '--target=aarch64-linux-android23', '-std=c++20', '-Wall', '-Wextra', '-Werror',
                '-c', str(sources[1]), '-o', str(build / 'path_injection.o')], check=True)
fixture = build / 'libc.c'
fixture.write_text('''void* mmap(void* p, unsigned long s, int a, int b, int c, long d) { return p; }
int munmap(void* p, unsigned long s) { return 0; }
char* getenv(const char* p) { return 0; }
int setenv(const char* n, const char* v, int o) { return 0; }
''', encoding='utf-8')
subprocess.run([str(clang), '--target=aarch64-linux-android23', '-shared', '-fPIC', '-nostdlib', '-x', 'c',
                str(fixture), '-o', str(build / 'libc.so')], check=True)
script = f'''set -eu
C=/opt/ddk/clang/clang-r510928
cd /repo/{relative}
$C/bin/clang++ -std=c++20 -stdlib=libc++ -Wall -Wextra -Werror \\
 '-DMAPLESU_PATH_PROC_ROOT="/tmp/maplesu-path-test/proc"' \\
 -Wl,--wrap=ptrace,--wrap=waitpid,--wrap=getuid,--wrap=geteuid -ldl \\
 -Wl,-rpath,$C/lib/x86_64-unknown-linux-gnu \\
 /repo/runtime/tests/path_injection_test.cpp -o path.test
./path.test libc.so
'''
(build / 'run.sh').write_text(script, encoding='utf-8')
result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--mount',
    f'type=bind,source={root},target=/repo', '--entrypoint', 'sh',
    'ghcr.io/ylarod/ddk-min:android15-6.6-20260828', '-c', script], capture_output=True, text=True)
(build / 'tests.log').write_text(result.stdout + result.stderr, encoding='utf-8')
unchanged = digests == {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
(build / 'RECEIPT.json').write_text(json.dumps({
    'passed': result.returncode == 0 and unchanged, 'sourceSha256': digests,
    'sourcesUnchanged': unchanged, 'ndkAarch64Object': True,
    'checks': result.stdout.count('PASS '), 'syscallFixture': True,
    'network': 'none', 'deviceVerification': False,
}, indent=2) + '\n', encoding='utf-8')
print(result.stdout + result.stderr)
raise SystemExit(result.returncode if unchanged else 1)
