"""Local source regression; root, package and proc inputs are isolated fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--repo-root', type=Path)
parser.add_argument('--build', type=Path, required=True)
parser.add_argument('--ndk', type=Path, required=True)
args = parser.parse_args()
root = (args.repo_root or Path(__file__).resolve().parents[2]).resolve()
build = args.build.resolve()
build.mkdir(parents=True, exist_ok=True)
sources = [root / 'runtime/tests/ultra_parasite_sdk_test.cpp',
           root / 'runtime/maplesu-sdk/sdk/src/maplesu/maplesu_parasite_sdk.cpp',
           root / 'runtime/maplesu-sdk/sdk/src/parasite/parasite_patch_elf.cpp']
fixture = build / 'fixture.c'
fixture.write_text('void fixture(void) {}\n', encoding='utf-8')
clang = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
ndk_identity = subprocess.check_output([str(clang), '--version'], text=True)
ndk_sha256 = hashlib.sha256(clang.read_bytes()).hexdigest()
fixture_command = [str(clang), '--target=aarch64-linux-android26', '-fPIC', '-shared', '-nostdlib',
                   '-Wl,-soname,libfixture.so', str(fixture), '-o', str(build / 'libfixture.so')]
subprocess.run(fixture_command, check=True)
inputs = ' '.join('/repo/' + p.relative_to(root).as_posix() for p in sources)
common = '''$C/bin/clang++ -std=c++17 -stdlib=libc++ -Wall -Wextra -Werror
 -DMAPLESU_PARASITE_HOST_TEST -DMAPLESU_PARASITE_TIMEOUT_MS=1500
 '-DMAPLESU_PARASITE_PROC_ROOT="/tmp/maplesu-parasite-test/proc"'
 '-DMAPLESU_PARASITE_PM="/tmp/maplesu-parasite-test/pm"'
 '-DMAPLESU_PARASITE_START="/tmp/maplesu-parasite-test/start.sh"'
 -I/repo/runtime/maplesu-sdk/include -I/repo/runtime/maplesu-sdk/sdk/include
 -I/repo/runtime/maplesu-sdk/sdk/include/maplesu'''.replace('\n', ' ')
prefix = 'set -eu\nC=/opt/ddk/clang/clang-r510928\n'
image = subprocess.check_output(['docker', 'image', 'inspect',
    'ghcr.io/ylarod/ddk-min:android15-6.6-20260828', '--format', '{{.Id}}'], text=True).strip()


def container(script, name):
    (build / (name + '.sh')).write_text(script, encoding='utf-8', newline='\n')
    result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--mount',
        f'type=bind,source={root},target=/repo,readonly', '--mount',
        f'type=bind,source={build},target=/out', '--entrypoint', 'sh', image, '/out/' + name + '.sh'],
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
    (build / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    return result


def project_inputs(name):
    dependencies = set()
    for token in shlex.split((build / name).read_text(encoding='utf-8').replace('\\\n', ' ')):
        if token.startswith('/repo/'):
            dependencies.add((root / token[len('/repo/'):]).resolve().relative_to(root).as_posix())
    assert {p.relative_to(root).as_posix() for p in sources} <= dependencies
    dependencies.add(Path(__file__).resolve().relative_to(root).as_posix())
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sorted(dependencies)}


preflight = container(prefix + '$C/bin/clang++ --version > /out/compiler-identity.txt\n'
    + 'sha256sum "$C/bin/clang++" > /out/compiler-sha256.txt\n'
    + f'{common} -MM {inputs} > /out/project-dependencies-before.mk\n', 'dependencies')
if preflight.returncode:
    print(preflight.stdout + preflight.stderr, end='')
    raise SystemExit(preflight.returncode)
digests = project_inputs('project-dependencies-before.mk')
(build / 'project-inputs-before.json').write_text(json.dumps(digests, indent=2) + '\n', encoding='utf-8')
script = prefix + f'''{common} -Wl,--export-dynamic -Wl,--wrap=fork,--wrap=pipe2 -ldl -Wl,-rpath,$C/lib/x86_64-unknown-linux-gnu {inputs} -o /out/parasite.test
failed=0
/out/parasite.test /out/libfixture.so || failed=$?
{common} -MM {inputs} > /out/project-dependencies-after.mk
exit $failed
'''
result = container(script, 'run')
output = result.stdout + result.stderr
(build / 'native-regression.log').write_text(output, encoding='utf-8')
current = project_inputs('project-dependencies-after.mk') if (build / 'project-dependencies-after.mk').is_file() else {}
unchanged = digests == current
(build / 'native-regression.json').write_text(json.dumps({
    'passed': result.returncode == 0 and unchanged, 'exitCode': result.returncode,
    'sourceSha256': digests, 'afterSourceSha256': current,
    'identityScope': 'All project dependencies emitted by the actual same-parameter clang -MM before and after compilation, plus this runner. Host system headers/toolchain are bound by the Docker image ID.',
    'projectInputCount': len(digests), 'dependencyClosureUnchanged': digests.keys() == current.keys(),
    'dockerImageId': image,
    'compilerIdentity': (build / 'compiler-identity.txt').read_text(encoding='utf-8'),
    'compilerSha256': (build / 'compiler-sha256.txt').read_text(encoding='utf-8').split()[0],
    'fixtureCompilerIdentity': ndk_identity, 'fixtureCompilerSha256': ndk_sha256,
    'fixtureCompilerCommand': fixture_command,
    'fixtureSourceSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
    'fixtureElfSha256': hashlib.sha256((build / 'libfixture.so').read_bytes()).hexdigest(),
    'patchEngine': 'current production source', 'sourcesUnchanged': unchanged,
    'checks': result.stdout.count('PASS '), 'network': 'none', 'sourceMountReadOnly': True,
    'deviceVerification': False,
    'scope': 'Current native worker and ELF implant transaction. Manager removal/reimplant and device host behavior require current-generation classes and device evidence.',
}, indent=2) + '\n', encoding='utf-8')
print(output, end='')
raise SystemExit(result.returncode if unchanged else 1)
