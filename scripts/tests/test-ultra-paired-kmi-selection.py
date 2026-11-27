"""Exercise paired boot selection through the real Context entry and catalog importer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/specgen'))
from specgen.product import cached_jar


def identity(path):
    path = Path(path).resolve()
    return {'path': str(path), 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-receipt', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    built = json.loads(args.runtime_receipt.read_bytes())
    runtime = built['runtime']
    flow = ROOT / 'manager/src/main/java/dev/maplesu/manager/ultrakernel/UltraKernelFlow.kt'
    # The current Flow is compiled below; the other runtime sources retain their source binding.
    for row in built['sources']:
        if Path(row['path']).resolve() != flow:
            assert identity(row['path']) == {name: row[name] for name in ('path', 'bytes', 'sha256')}, row['path']
    assert identity(runtime['jar']['path']) == runtime['jar']
    fixture = ROOT / 'manager/src/test/resources/ultra-paired-selection-jvm'
    sources = [flow, fixture / 'Context.java', fixture / 'AssetManager.java',
               fixture / 'PairedKmiSelectionTest.kt',
               ROOT / 'image-core/src/test/java/dev/maplesu/image/UltraInitBootProducerTest.java',
               ROOT / 'manager/src/test/java/dev/maplesu/manager/ultrakernel/UltraInitBootCatalogTest.kt',
               Path(__file__)]
    before = [identity(path) for path in sources]
    stdlib, annotations, coroutines, junit, hamcrest, compiler = [cached_jar(*entry) for entry in [
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20'), ('org.jetbrains', 'annotations', '13.0'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
        ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3'),
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20')]]
    java = Path(runtime['java']['path'])
    classes = out / 'classes'
    classes.mkdir()
    cp = os.pathsep.join([str(classes), runtime['jar']['path'], runtime['classpath'], str(junit), str(hamcrest)])
    jar = out / 'tests.jar'
    commands = [
        [str(java.with_name('javac.exe' if os.name == 'nt' else 'javac')), '-cp', cp, '-d', str(classes),
         str(fixture / 'Context.java'), str(fixture / 'AssetManager.java'), str(sources[4])],
        [str(java), '-Xmx512m', '-cp', os.pathsep.join(map(str, [compiler, stdlib, annotations, coroutines])),
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
         '-Xfriend-paths=' + runtime['jar']['path'], '-classpath', cp, '-d', str(jar),
         str(flow), str(fixture / 'PairedKmiSelectionTest.kt'), str(sources[5])],
        [str(java), '-Xmx512m', '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
         'dev.maplesu.manager.ultrakernel.PairedKmiSelectionTest',
         'dev.maplesu.manager.ultrakernel.UltraInitBootCatalogTest'],
    ]
    receipt = {'sources': before, 'runtimeReceipt': identity(args.runtime_receipt),
               'boundary': 'Android Context/assets only; actual Flow, KMI parser and catalog importer',
               'deviceValidated': False, 'commands': []}
    for index, command in enumerate(commands):
        result = subprocess.run(command, capture_output=True, timeout=120)
        log = out / f'step-{index}.log'
        log.write_bytes(result.stdout + result.stderr)
        print(log.read_text(encoding='utf-8', errors='replace'), end='', flush=True)
        receipt['commands'].append({'argv': command, 'exitCode': result.returncode, 'log': identity(log)})
        if result.returncode:
            break
    receipt['sourceUnchanged'] = before == [identity(path) for path in sources]
    receipt['passed'] = len(receipt['commands']) == len(commands) and all(
        row['exitCode'] == 0 for row in receipt['commands']) and receipt['sourceUnchanged']
    (out / 'RESULT.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
