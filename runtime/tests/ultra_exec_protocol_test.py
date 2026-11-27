"""Narrow runtime protocol and SU layout regression with real Linux processes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', type=Path)
parser.add_argument('--inside', type=Path)
parser.add_argument('--su-inside', type=Path)
args = parser.parse_args()

if args.su_inside:
    fixture = Path('/work/su-layout')
    manager = fixture / 'dev.maplesu.manager-fixture'
    source = manager / 'lib/arm64/libmaplesu_su.so'
    target = fixture / 'target/lib/arm64'
    shared = fixture / 'shared'
    for directory in (source.parent, target, shared / 'u'):
        directory.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.su_inside, source)
    source.chmod(0o755)
    (manager / 'base.apk').write_bytes(b'fixture apk')
    (source.parent / 'libmaplesu_bootstrap.so').write_bytes(b'fixture bootstrap')
    (target / 'su').symlink_to(source)
    shutil.copyfile(source, shared / 'su')
    (shared / 'su').chmod(0o711)
    (shared / 'authorized').write_text('110001\n')
    (shared / 'u').chmod(0o711)
    (shared / 'u/110001').symlink_to(target)
    def invoke(path, uid):
        def identity():
            os.setgid(uid)
            os.setuid(uid)
        return subprocess.run([str(path)], capture_output=True, preexec_fn=identity, timeout=5)
    expected = f'LAYOUT:{manager}/base.apk:{source.parent}/libmaplesu_bootstrap.so:UID:110001\n'.encode()
    for path in (source, target / 'su', shared / 'su'):
        done = invoke(path, 110001)
        assert done.returncode == 0 and done.stdout == expected, (path, done)
    done = invoke(shared / 'su', 10001)
    assert done.returncode == 76 and not done.stdout, done
    wrong = fixture / 'target/libmaplesu_su.so'
    shutil.copyfile(source, wrong)
    wrong.chmod(0o755)
    done = invoke(wrong, 110001)
    assert done.returncode == 91, done
    assert source.read_bytes() == (shared / 'su').read_bytes()
    print('PASS production SU shared forwarder -> fullUID symlink -> canonical Manager layout; other UID and misplaced image rejected')
    sys.exit(0)

if args.inside:
    exe = str(args.inside)
    payload = b'\x00\x00\x000' + bytes(range(256)) + b'ordinary stdin\n'
    done = subprocess.run([exe, '--ultra', '--', '/bin/cat'], input=payload, capture_output=True)
    assert done.returncode == 0 and done.stdout == payload, (done.returncode, done.stderr)
    done = subprocess.run([exe, '--ultra', '--', '/bin/cat'], input=b'', capture_output=True)
    assert done.returncode == 0 and not done.stdout, (done.returncode, done.stderr)
    done = subprocess.run([exe, '--ultra', '--', '/bin/sh', '-c', 'exit 23'], capture_output=True)
    assert done.returncode == 23, done
    done = subprocess.run([exe, '--ultra', '--', '/missing/fixture'], capture_output=True)
    assert done.returncode == 127, done
    done = subprocess.run([exe, '--ultra', '--', '/bin/echo', 'must not execute'],
        env={**os.environ, 'FIXTURE_DENY_ADMISSION': '1'}, capture_output=True)
    assert done.returncode == 69 and not done.stdout and b'code=-20000' in done.stderr, done
    done = subprocess.run([exe, '--ultra-global', '--', '/bin/echo', 'must not execute'],
        env={**os.environ, 'FIXTURE_DENY_ADMISSION': '1', 'FIXTURE_FORBID_SETNS': '1'}, capture_output=True)
    assert done.returncode == 69 and not done.stdout and b'FIXTURE_SETNS' not in done.stderr, done
    done = subprocess.run([exe, '--ultra-global', '--', '/bin/cat'], input=payload, capture_output=True)
    assert done.returncode == 0 and done.stdout == payload and done.stderr == b'FIXTURE_SETNS\n', done
    for mode in ('--direct-stdin', '--maplesu-stdin', '--maplesu-session-stdin',
                 '--maplesu-global-stdin', '--maplesu-su-stdin', '--skroot-stdin'):
        done = subprocess.run([exe, mode, '--', '/bin/echo', 'must not execute'], input=payload, capture_output=True)
        assert done.returncode == 64 and not done.stdout, (mode, done)
    operations = [
        (['--wire-su-precheck', 'fixture.app'], b'1 /fixture/app/lib.so\n', 66),
        (['--wire-su-env', 'fixture.app', '/fixture/lib.so', '/fixture/su'], b'WIRED:/fixture/lib.so\n', 67),
        (['--implant-app', 'fixture.app', '/fixture/lib.so'], b'IMPLANTED:/fixture/lib.so\n', 68),
        (['--inject-path', 'fixture.app', '110001', '/fixture/su', '3000'],
         b'INJECT_ARGS:fixture.app:110001:/fixture/su:3000\n', None),
    ]
    for arguments, expected, failure in operations:
        done = subprocess.run([exe, *arguments], input=b'', capture_output=True)
        assert done.returncode == 0 and done.stdout == expected, (arguments, done)
        done = subprocess.run([exe, *arguments], input=b'', capture_output=True,
            env={**os.environ, 'FIXTURE_DENY_ADMISSION': '1'})
        assert done.returncode == 69 and not done.stdout, (arguments, done)
        if failure:
            done = subprocess.run([exe, *arguments], input=b'', capture_output=True,
                env={**os.environ, 'FIXTURE_DENY_OPERATION': '1'})
            assert done.returncode == failure and not done.stdout, (arguments, done)
    for uid in ('', '1000', '-1', '110001x', '4294967296'):
        done = subprocess.run([exe, '--inject-path', 'fixture.app', uid, '/fixture/su', '0'], capture_output=True)
        assert done.returncode == 64 and not done.stdout, (uid, done)
    done = subprocess.run([exe, '--ultra-session', '--', '/bin/true'], capture_output=True)
    assert done.returncode == 65, done

    def launch(command, mode='--ultra-session'):
        parent, child = socket.socketpair()
        def setup():
            os.dup2(child.fileno(), 3)
        process = subprocess.Popen([exe, mode, '--', *command],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            pass_fds=(parent.fileno(), child.fileno()), preexec_fn=setup)
        child.close()
        parent.settimeout(5)
        parent.sendall(b'A')
        assert parent.recv(1) == b'R'
        return process, parent

    process, control = launch(['/bin/cat'])
    output, error = process.communicate(payload, timeout=5)
    control.close()
    assert process.returncode == 0 and output == payload, (process.returncode, error)
    process, control = launch(['/bin/sh', '-c', 'sleep 20 & wait'])
    control.close()
    output, error = process.communicate(timeout=5)
    assert process.returncode == 137, (process.returncode, error)
    process, control = launch(['/bin/sh', '-c', 'exit 24'], '--root-session')
    output, error = process.communicate(timeout=5)
    control.close()
    assert process.returncode == 24, (process.returncode, error)
    print('PASS Ultra executor: stdin/exit/control/cancel, explicit Root, denied namespace gate, retired modes and keyless tools/fullUID')
    sys.exit(0)

if not args.out:
    parser.error('--out is required')
root = Path(__file__).resolve().parents[2]
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
inputs = [root / p for p in ('runtime/src/exec_main.cpp', 'runtime/src/su_session.cpp',
    'runtime/src/su_protocol.h', 'runtime/src/ultra_admission.h',
    'runtime/src/path_injection.h', 'runtime/src/su_main.cpp',
    'runtime/maplesu-sdk/include/maplesu_parasite.h', 'runtime/tests/ultra_su_layout_probe.cpp',
    'runtime/tests/ultra_exec_boundary.cpp', 'runtime/tests/ultra_exec_protocol_test.py')]
hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
docker = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only',
    '--mount', f'type=bind,source={root},target=/repo,readonly',
    '--mount', f'type=bind,source={out},target=/work', '-w', '/work',
    '--entrypoint', '/bin/sh', 'sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e']
commands = [
    [*docker, '-c', 'exec "$@"', 'sh', 'g++', '-std=c++20', '-O1', '-Wall', '-Wextra', '-Werror',
        '-I/repo/runtime/maplesu-sdk/include', '-I/repo/runtime/src',
        '/repo/runtime/src/exec_main.cpp', '/repo/runtime/src/su_session.cpp',
        '/repo/runtime/tests/ultra_exec_boundary.cpp', '-Wl,--wrap=setns', '-o', '/work/runtime-probe'],
    [*docker, '-c', 'exec "$@"', 'sh', 'python3', '/repo/runtime/tests/ultra_exec_protocol_test.py',
        '--inside', '/work/runtime-probe'],
    [*docker, '-c', 'exec "$@"', 'sh', 'g++', '-std=c++20', '-O1', '-Wall', '-Wextra', '-Werror',
        '/repo/runtime/tests/ultra_su_layout_probe.cpp', '-o', '/work/su-layout-probe'],
    [*docker, '-c', 'exec "$@"', 'sh', 'python3', '/repo/runtime/tests/ultra_exec_protocol_test.py',
        '--su-inside', '/work/su-layout-probe'],
]
results = []
for index, command in enumerate(commands):
    done = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=45)
    (out / f'{index}.log').write_text(done.stdout + done.stderr, encoding='utf-8')
    results.append({'argv': command, 'exitCode': done.returncode})
    print(done.stdout + done.stderr, end='')
    if done.returncode:
        break
stable = all(hashlib.sha256(p.read_bytes()).hexdigest() == hashes[str(p)] for p in inputs)
passed = len(results) == len(commands) and all(not item['exitCode'] for item in results) and stable
(out / 'RESULT.json').write_text(json.dumps({'passed': passed, 'sourceInputsStable': stable,
    'inputs': hashes, 'commands': results, 'deviceTested': False,
    'scope': 'Production exec/su_session and SU forwarder/layout; SDK operations, PATH and setns effects replaced. No SDK link or device authority claim.'}, indent=2), encoding='utf-8')
sys.exit(0 if passed else 1)
