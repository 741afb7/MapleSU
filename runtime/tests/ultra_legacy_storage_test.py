from pathlib import Path
import argparse,datetime,hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
files=['runtime/tests/ultra_legacy_storage_test.cpp','runtime/tests/legacy_storage_fixture/kernel_module_kit_umbrella.h','runtime/maplesu-sdk/sdk/include/maplesu_storage_worker_private.h','runtime/maplesu-sdk/sdk/include/maplesu_storage_private.h','runtime/maplesu-sdk/sdk/include/maplesu_request_identity.h','runtime/maplesu-sdk/sdk/include/maplesu_request_private.h','runtime/maplesu-sdk/sdk/src/module_base/module_base_disk_storage.cpp','runtime/maplesu-sdk/sdk/src/module/module_current_identity.cpp','runtime/maplesu-sdk/sdk/src/maplesu/module/metadata.cpp','runtime/maplesu-sdk/sdk/src/maplesu/compat/storage_legacy.h']
def snapshot():return {n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() if (ROOT/n).is_file() else None for n in files}
before=snapshot();commands=[]
metadata=ROOT/'runtime/maplesu-sdk/sdk/src/maplesu/module/metadata.cpp'
src=metadata.read_text(encoding='utf-8-sig')
assert src.count('#include "../compat/module_metadata_exports_legacy_abi.h"')==1
src=src.replace('#include "../compat/module_metadata_exports_legacy_abi.h"','// Android-only frozen symbol aliases excluded from this host behavior fixture.')
src=src.replace('#include "../compat/','#include "/repo/runtime/maplesu-sdk/sdk/src/maplesu/compat/')
(out/'metadata.host.cpp').write_text(src,encoding='utf-8')
container=['docker','run','--rm','--pull','never','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--tmpfs','/tmp:rw,exec,size=32m','--tmpfs','/data:rw,size=16m,mode=0777','--mount',f'type=bind,source={ROOT},target=/repo,readonly','--mount',f'type=bind,source={out},target=/work','-w','/work','--entrypoint','/bin/sh','sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e']
prefix='/repo/runtime/maplesu-sdk/'
argv=['g++','-std=c++17','-O1','-ffunction-sections','-fdata-sections','-Wno-packed','-I/repo/runtime/tests/legacy_storage_fixture','-I'+prefix+'include','-I'+prefix+'sdk/include','-I'+prefix+'sdk','-I'+prefix+'sdk/src/maplesu/module','/repo/runtime/tests/ultra_legacy_storage_test.cpp',prefix+'sdk/src/module_base/module_base_disk_storage.cpp',prefix+'sdk/src/module/module_current_identity.cpp','/work/metadata.host.cpp','-Wl,--gc-sections','-o','/work/storage-test']
for label,args in [('compile',[*container,'-c','exec "$@"','sh',*argv]),('cases',[*container,'-c','exec ./storage-test'])]:
 result=subprocess.run(args,cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=55)
 (out/(label+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8');commands.append(dict(label=label,argv=args,exitCode=result.returncode));print(label,result.returncode,(result.stdout+result.stderr)[-7000:],flush=True)
 if result.returncode:break
stable=before==snapshot();report=dict(schema='maplesu-legacy-storage-host-test/1',utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),passed=len(commands)==2 and all(c['exitCode']==0 for c in commands) and stable,sourceSha256=before,inputsUnchanged=stable,commands=commands,metadataHostAdaptation='Original metadata.cpp with only Android frozen symbol alias declarations omitted and relative compat includes resolved; production parser behavior unchanged.',deviceExecuted=False,scope='Actual WorkerScope, StorageScope/current_context/disk reads, metadata parser and module_current_identity; auth Root/session operations are fixtures. Linux /data tmpfs has preexisting legacy files. No full SDK/Android device or kernel effect.')
(out/'RESULT.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');raise SystemExit(0 if report['passed'] else 1)
