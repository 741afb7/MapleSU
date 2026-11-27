"""Exercise the production Manager protocol selection and native lexical credential scope."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[2]
block = runpy.run_path(str(ROOT / 'scripts/tests/test-root-command-identity.py'))['block']


def native(out):
    source = (ROOT / 'runtime/ultra/bridge/native_lib_ultra.cpp').read_text(encoding='utf-8-sig')
    names = ['std::string jstring_to_utf8(', 'std::string url_encode_copy(', 'cJSON* begin(',
             'void mark_ok(', 'void mark_sdk_error(', 'void mark_bridge_error(',
             'void mark_call_bridge_error(', 'jstring finish(', 'struct JsonDeleter',
             'cJSON* owner_u64(', 'cJSON* owner_error(',
             'bool attach_owned_json(cJSON* object, const char* field, cJSON* child) {',
             'cJSON* owner_root_command_json(', 'cJSON* root_command_result_json(',
             'template <typename Body>\njstring guarded(', 'KModErr authenticated_primary_error(',
             'void authenticated_receipt_alloc(', 'void mark_authenticated_ok(',
             'void restore_authenticated_mode(', 'template <typename Body>\njstring guarded_authenticated(',
             'extern "C" JNIEXPORT jstring JNICALL\nJava_dev_maplesu_manager_runtime_ultra_bridge_NativeBridge_runRootCmd(',
             'extern "C" JNIEXPORT jstring JNICALL\nJava_dev_maplesu_manager_runtime_ultra_bridge_NativeBridge_invokeWithLegacyCredential(']
    helpers = [block(source, name) for name in names]
    helpers[8] += ';\nusing OwnedJson = std::unique_ptr<cJSON, JsonDeleter>;'
    (out/'legacy_manager_production.inc').write_text('\n\n'.join(helpers), encoding='utf-8')
    executable = out/'legacy-manager.exe'
    includes = ['runtime/ultra/bridge', 'runtime/maplesu-sdk/include', 'runtime/maplesu-sdk/sdk/include',
                'runtime/ultra/third_party/cJSON', 'runtime/ultra/third_party/include']
    return [['C:/msys64/ucrt64/bin/g++.exe', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function',
             '-I'+str(out), *['-I'+str(ROOT/name) for name in includes],
             str(ROOT/'runtime/tests/ultra_legacy_manager_scope_test.cpp'),
             str(ROOT/'runtime/maplesu-sdk/sdk/src/module/module_current_identity.cpp'),
             str(ROOT/'runtime/ultra/third_party/cJSON/cJSON.cpp'), '-static-libgcc', '-static-libstdc++', '-o', str(executable)],
            [str(executable)]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    inputs = ['runtime/ultra/bridge/native_lib_ultra.cpp', 'runtime/ultra/bridge/manager_owner_identity.h',
              'runtime/maplesu-sdk/sdk/src/module/module_current_identity.cpp',
              'runtime/tests/ultra_legacy_manager_scope_test.cpp']
    record = {'sourceSha256': {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in inputs},
              'deviceExecuted': False, 'sdkBuild': False, 'apkBuild': False, 'commands': []}
    for index, command in enumerate(native(out)):
        env = os.environ.copy(); env['PATH'] = 'C:/msys64/ucrt64/bin'+os.pathsep+env['PATH']
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=55)
        (out/f'native-{index}.log').write_text(result.stdout+result.stderr, encoding='utf-8')
        record['commands'].append({'command': command, 'exitCode': result.returncode})
        print(result.stdout+result.stderr)
        if result.returncode: break
    record['recordedAt'] = datetime.now(timezone.utc).isoformat()
    record['passed'] = all(item['exitCode'] == 0 for item in record['commands'])
    (out/'RESULT.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    raise SystemExit(0 if record['passed'] else 1)


if __name__ == '__main__': main()
