"""Replay helper labels through the selected production context/transaction.

SELinux xattrs and typed SDK callbacks are fixtures, not device certification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--repo-root', type=Path)
parser.add_argument('--transaction', type=Path)
parser.add_argument('--fixture', type=Path)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--without-context', action='store_true')
args = parser.parse_args()
root = (args.repo_root or Path(__file__).resolve().parents[2]).resolve()
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
selected = root / 'runtime/maplesu-sdk/sdk/src/module/su_redirect_tu'
transaction = (args.transaction or selected / 'su_auth_transaction.cpp').resolve()
fixture = (args.fixture or root / 'runtime/tests/ultra_su_execution_test.cpp').resolve()
context = selected / 'su_exec_context.cpp'
sources = [fixture, transaction] + ([] if args.without_context else [context])
inputs = ' '.join('/repo/' + p.relative_to(root).as_posix() for p in sources)
common = '''$C/bin/clang++ -stdlib=libc++ -std=c++17 -Wall -Wextra -Werror
 -I/repo/runtime/maplesu-sdk/include -I/repo/runtime/maplesu-sdk/sdk/include
 -I/repo/runtime/maplesu-sdk/sdk/include/module -I/repo/runtime/maplesu-sdk/sdk/src/module/su_redirect_tu'''.replace('\n', ' ')
prefix = 'set -eu\nC=/opt/ddk/clang/clang-r510928\n'
image = subprocess.check_output(['docker', 'image', 'inspect',
    'ghcr.io/ylarod/ddk-min:android15-6.6-20260828', '--format', '{{.Id}}'], text=True).strip()


def container(script, name):
    (out / (name + '.sh')).write_text(script, encoding='utf-8', newline='\n')
    result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--mount',
        f'type=bind,source={root},target=/repo,readonly', '--mount',
        f'type=bind,source={out},target=/out', '--entrypoint', 'sh', image, '/out/' + name + '.sh'],
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
    (out / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    return result


def project_inputs(name):
    dependencies = set()
    for token in shlex.split((out / name).read_text(encoding='utf-8').replace('\\\n', ' ')):
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
(out / 'project-inputs-before.json').write_text(json.dumps(digests, indent=2) + '\n', encoding='utf-8')
script = prefix + f'''mkdir -p /data/adb
{common} -Wl,-rpath,$C/lib/x86_64-unknown-linux-gnu -Wl,--wrap=fgetxattr -Wl,--wrap=fsetxattr {inputs} -o /out/su-execution.test
failed=0
for t in report directory_label already refresh missing symlink foreign_owner relabel_denied bad_readback revoke owner_add owner_remove owner_clear unusable_identity root_denied storage_denied refresh_error revoke_error; do
 /out/su-execution.test "$t" || failed=1
done
{common} -MM {inputs} > /out/project-dependencies-after.mk
exit $failed
'''
result = container(script, 'run')
output = result.stdout + result.stderr
(out / 'su-execution.log').write_text(output, encoding='utf-8')
current = project_inputs('project-dependencies-after.mk') if (out / 'project-dependencies-after.mk').is_file() else {}
unchanged = digests == current
(out / 'su-execution.json').write_text(json.dumps({
    'passed': result.returncode == 0 and unchanged, 'exitCode': result.returncode,
    'sourceSha256': digests, 'afterSourceSha256': current, 'sourcesUnchanged': unchanged,
    'identityScope': 'All project dependencies emitted by the actual same-parameter clang -MM before and after compilation, plus this runner. Host system headers/toolchain are bound by the Docker image ID.',
    'projectInputCount': len(digests), 'dependencyClosureUnchanged': digests.keys() == current.keys(),
    'dockerImageId': image,
    'compilerIdentity': (out / 'compiler-identity.txt').read_text(encoding='utf-8'),
    'compilerSha256': (out / 'compiler-sha256.txt').read_text(encoding='utf-8').split()[0],
    'checks': result.stdout.count('PASS '), 'network': 'none', 'sourceMountReadOnly': True,
    'productionSelection': 'sdk/src/module/su_redirect_tu', 'deviceVerification': False,
    'scope': 'Actual helper context and transaction dispatch. SELinux xattrs, storage locator and typed backend results are fixture boundaries; no real UID grant or revocation is claimed.',
}, indent=2) + '\n', encoding='utf-8')
print(output, end='')
raise SystemExit(result.returncode if unchanged else 1)
