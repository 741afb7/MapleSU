"""Replay real selected SU decisions getter/collector and NativeReceipt JSON parser."""
import argparse,datetime,hashlib,json,os,re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/specgen'))
from specgen.product import cached_jar
sys.path.insert(0,str(ROOT/'tools'))
from native_toolchain import jdk_tool
def block(source,marker):
 start=source.index(marker);opening=source.index('{',start);depth=0
 for token in re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]').finditer(source,opening):
  if token[0]=='{':depth+=1
  elif token[0]=='}':
   depth-=1
   if depth==0:return source[start:token.end()]
 raise AssertionError(marker)
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--getter',type=Path);p.add_argument('--client',type=Path);p.add_argument('--bridge',type=Path);a=p.parse_args()
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
selected=ROOT/'runtime/maplesu-sdk/sdk/src/module/su_redirect_tu'
getter=a.getter or selected/'module_su_redirect_part1.cpp'
client=a.client or ROOT/'manager/src/main/java/dev/maplesu/manager/runtime/ultra/UltraRuntimeClient.kt'
bridge=a.bridge or ROOT/'runtime/ultra/bridge/native_lib_ultra.cpp'
writer=selected/'su_allowlist_writer.cpp'
receipt=ROOT/'manager/src/main/java/dev/maplesu/manager/runtime/ultra/UltraReceipt.kt'
fixture=ROOT/'runtime/tests/ultra_su_decisions_test.cpp'
inputs=[getter,client,bridge,writer,receipt,fixture,Path(__file__)]
def snap():return {str(x.resolve()):hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs}
before=snap()
getter_text,client_text,bridge_text=[x.read_text(encoding='utf-8-sig') for x in (getter,client,bridge)]
collector=writer.read_text(encoding='utf-8-sig').split('KModErr maplesu::su_auth::detail::replace_uid_profile(',1)[0]
collector=re.sub(r'^#include "[^\n]*\n','',collector,flags=re.M)
(out/'su-collector-production.inc').write_text(collector,encoding='utf-8')
(out/'su-getter-production.inc').write_text(block(getter_text,'KModErr maplesu::su_auth::detail::sdk_get_uid_profiles('),encoding='utf-8')
image=subprocess.check_output(['docker','image','inspect','ghcr.io/ylarod/ddk-min:android15-6.6-20260828','--format','{{.Id}}'],text=True).strip()
script='''set -eu
C=/opt/ddk/clang/clang-r510928
$C/bin/clang++ -stdlib=libc++ -std=c++17 -O1 -ffunction-sections -fdata-sections -Wl,--gc-sections -Wl,-rpath,$C/lib/x86_64-unknown-linux-gnu -I/repo/runtime/maplesu-sdk/include -I/repo/runtime/maplesu-sdk/sdk/include -I/repo/runtime/maplesu-sdk/sdk/src/module/su_redirect_tu -I/out /repo/runtime/tests/ultra_su_decisions_test.cpp -o /out/decisions.test
failed=0
for t in absent_control canonical_0711 valid_delivery root_error load_error invalid_record package_map_missing stale_installation; do
 /out/decisions.test "$t" || failed=1
done
exit $failed
'''
(out/'native.sh').write_text(script,encoding='utf-8',newline='\n')
command=['docker','run','--rm','--pull','never','--network','none','--mount',f'type=bind,source={ROOT},target=/repo,readonly','--mount',f'type=bind,source={out},target=/out','--entrypoint','sh',image,'/out/native.sh']
native=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(out/'native.log').write_text(native.stdout+native.stderr,encoding='utf-8');print('native',native.returncode,native.stdout+native.stderr,flush=True)
method=block(bridge_text,'Java_dev_maplesu_manager_runtime_ultra_bridge_NativeBridge_getSuUidProfiles(')
bridge_decisions='"decisionsVerified", true' in method and '"deliveryVerified", true' not in method
def write(name,text):
 path=out/name;path.write_text(text,encoding='utf-8');return path
sources=[write('Parser.kt','package dev.maplesu.manager.runtime.ultra\nimport java.net.URLDecoder\n'+block(client_text,'internal fun parseSuUidProfiles(')),
 write('NativeReceipt.kt','package dev.maplesu.manager.runtime.ultra\nimport org.json.*\nimport java.net.URLEncoder\n'+block(receipt.read_text(encoding='utf-8-sig'),'data class NativeReceipt(')),
 write('Boundaries.kt','''package dev.maplesu.manager.runtime.ultra
data class SuUidProfile(val uid:Int,val allow:Boolean,val packageName:String)
data class SuExecResult(val exitCode:Int,val output:String,val timedOut:Boolean=false,val failure:String?=null)
class UltraAuthorizationResult { companion object { fun parse(value:org.json.JSONObject?):UltraAuthorizationResult?=null } }
class UltraRuntimeClient {
'''+block(client_text,'data class SuUidProfilesSnapshot(')+'''
 companion object {const val BACKEND="maplesu_ultra"}
}
'''),write('StoredInputs.kt','package dev.maplesu.manager.compat\nobject StoredInputs {fun receiptBackend(x:String)=x;fun error(x:String)=x}\n'),
 write('SuDecisionParserTest.kt','''package dev.maplesu.manager.runtime.ultra
import org.json.*
import org.junit.Assert.*
import org.junit.Test
class SuDecisionParserTest {
 private fun row(uid:Int,allow:Boolean,name:String)=JSONObject().put("uid",uid).put("allow",allow).put("package",name)
 private fun data()=JSONObject().put("profiles",JSONArray().put(row(10123,true,"test.shared_a")).put(row(10125,false,"test.denied")).put(row(110124,true,"test.secondary"))).put("count",3).put("encoding","url").put("decisionsVerified",true)
 private fun receipt(d:JSONObject,ok:Boolean=true,error:String="OK",code:Int=0):NativeReceipt=checkNotNull(NativeReceipt.parse(JSONObject().put("backend","maplesu_ultra").put("operation","get_su_uid_profiles").put("ok",ok).put("sdkError",error).put("sdkErrorCode",code).put("data",d).toString()))
 private fun invalid(d:JSONObject){val result=parseSuUidProfiles(receipt(d));assertFalse(result.ok);assertTrue(result.profiles.isEmpty())}
 @Test fun validStoredFullUidDecisions(){val result=parseSuUidProfiles(receipt(data()));assertTrue(result.ok);assertEquals(listOf(10123,10125,110124),result.profiles.map{it.uid});assertFalse(result.profiles[1].allow)}
 @Test fun brokenDeliveryDoesNotInvalidateStoredDecisions(){val result=parseSuUidProfiles(receipt(data().put("deliveryVerified",false)));assertTrue(result.ok);assertEquals(3,result.profiles.size)}
 @Test fun deliveryOnlyIsNotNewDecisionContract(){invalid(data().apply{remove("decisionsVerified");put("deliveryVerified",true)})}
 @Test fun unverifiedMissingAndWrongTypedDecisionMarkers(){for(value in listOf(false,"true",1,JSONObject.NULL))invalid(data().put("decisionsVerified",value));invalid(data().apply{remove("decisionsVerified")})}
 @Test fun realErrorsRemainErrors(){val result=parseSuUidProfiles(receipt(data(),false,"ERR_MODULE_OPEN_FILE",-19988));assertFalse(result.ok);assertEquals(-19988,result.receipt.sdkErrorCode);assertTrue(result.profiles.isEmpty())}
 @Test fun duplicateFullUidRejected(){invalid(data().put("profiles",JSONArray().put(row(10123,true,"one")).put(row(10123,false,"two"))).put("count",2))}
 @Test fun malformedRowsAreNotPartialSnapshots(){for(value in listOf(-1,0,1,Int.MAX_VALUE.toLong()+1,110124.5))invalid(data().apply{getJSONArray("profiles").getJSONObject(2).put("uid",value)})}
 @Test fun countEncodingAndPackageErrorsRemainErrors(){invalid(data().put("count",2));invalid(data().put("encoding","plain"));invalid(data().apply{getJSONArray("profiles").getJSONObject(2).put("package","broken/name")})}
}
''')]
deps=[cached_jar(*v) for v in [('org.jetbrains.kotlin','kotlin-stdlib','2.4.20'),('org.jetbrains','annotations','13.0'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),('org.json','json','20240303'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]]
compiler=cached_jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.20')
java=jdk_tool()
classes=out/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
commands=[[java,'-Xmx256m','-cp',os.pathsep.join(map(str,[compiler,*deps])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-jvm-target','21','-classpath',cp,'-d',classes,*sources],[java,'-cp',cp,'org.junit.runner.JUnitCore','dev.maplesu.manager.runtime.ultra.SuDecisionParserTest']]
jvm=[]
for index,command in enumerate(commands):
 result=subprocess.run(list(map(str,command)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 log=result.stdout+result.stderr;(out/f'jvm-{index}.log').write_text(log,encoding='utf-8')
 jvm.append(dict(exitCode=result.returncode,log=log));print('jvm',index,result.returncode,log[-3000:],flush=True)
 if result.returncode:break
stable=before==snap();passed=native.returncode==0 and len(jvm)==2 and all(x['exitCode']==0 for x in jvm) and bridge_decisions and stable
(out/'RESULT.json').write_text(json.dumps(dict(passed=passed,utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),nativeExitCode=native.returncode,nativePassed=native.stdout.count('PASS '),jvm=jvm,bridgeEmitsDecisionProofOnly=bridge_decisions,inputsUnchanged=stable,sourceSha256=before,dockerImage=image,scope='Actual extracted SDK getter and durable collector with real inode/shared-member installation validation; root/storage/map lookup/kernel are fixture boundaries. Actual NativeReceipt and UID JSON parser. No device effect.'),indent=2)+'\n',encoding='utf-8')
raise SystemExit(0 if passed else 1)

