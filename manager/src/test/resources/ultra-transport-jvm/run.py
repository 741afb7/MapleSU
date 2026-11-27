"""Compile six production transports against explicit host boundary fixtures; no product build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[5]
HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--out', required=True)
args = parser.parse_args()
out = (ROOT / args.out).resolve()
out.mkdir(parents=True, exist_ok=False)

def jar(group, name, version):
    base = Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1' / group / name / version
    return next(base.glob('*/*.jar'))

stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.20'),
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'), coroutines,
    jar('org.jetbrains', 'annotations', '23.0.0')]
dependencies = [stdlib, coroutines, jar('org.jetbrains', 'annotations', '23.0.0'),
    jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
production = ROOT / 'manager/src/main/java/dev/maplesu/manager/runtime'
sources = [production / (name + '.kt') for name in ['MapleSuCommandExecutor', 'MapleSuSupervisedProcess',
    'MapleSuRuntime', 'DirectRoot', 'RuntimeDeployment', 'HookSuCompatibility']]
sources += [ROOT / 'manager/src/main/java/dev/maplesu/manager/workmode/WorkMode.kt']
sources += sorted(HERE.glob('*.kt'))
sources += [ROOT / 'manager/src/test/java/dev/maplesu/manager/runtime' / (name + '.kt') for name in
    ['RuntimeDeploymentRequestsTest', 'HookSuCompatibilityTest']]

def record(path):
    return dict(path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())

inputs = [record(path) for path in sources + [HERE / 'runtime_probe.go', Path(__file__)]]
result = dict(inputs=inputs, executions=[], scope='Six actual production Kotlin files plus real request/Hook tests. Android, native supervisor, SDK owner facts, libsu and FD endpoints are explicit fixtures. Windows Go executable checks actual ProcessBuilder argv/stdin/exit. No SDK/helper/image/APK build or device operations.')

def run(name, command):
    completed = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True)
    log = out / (name + '.log')
    log.write_bytes(completed.stdout + completed.stderr)
    result['executions'].append(dict(name=name, argv=list(map(str, command)), exitCode=completed.returncode,
        log=record(log)))
    print(log.read_text(encoding='utf-8', errors='replace')[-8000:], flush=True)
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    if completed.returncode:
        raise SystemExit(completed.returncode)

native = out / 'native'
native.mkdir()
(native / 'manager.apk').write_bytes(b'fixture')
run('fixture-native', [shutil.which('go'), 'build', '-o', native / 'libmaplesu_runtime.so', HERE / 'runtime_probe.go'])
classpath = os.pathsep.join(map(str, dependencies))
run('compile', [shutil.which('java'), '-Xmx2g', '-cp', os.pathsep.join(map(str, compiler)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '21',
    '-classpath', classpath, '-d', out / 'classes'] + sources)
run('junit', [shutil.which('java'), '-Dmaplesu.transport.fixture=' + str(native), '-cp',
    str(out / 'classes') + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
    'dev.maplesu.manager.runtime.UltraTransportBoundaryTest',
    'dev.maplesu.manager.runtime.RuntimeDeploymentRequestsTest',
    'dev.maplesu.manager.runtime.HookSuCompatibilityTest'])
result['inputsStable'] = inputs == [record(path) for path in sources + [HERE / 'runtime_probe.go', Path(__file__)]]
result['passed'] = result['inputsStable'] and all(x['exitCode'] == 0 for x in result['executions'])
(out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(passed=result['passed'], receipt=str(out / 'RESULT.json'))))
sys.exit(0 if result['passed'] else 1)
