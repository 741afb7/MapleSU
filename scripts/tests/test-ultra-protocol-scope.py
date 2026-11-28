"""Exercise the actual pair entrypoint and authorization scope capture with Android/native boundaries."""
import argparse,datetime,hashlib,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools/specgen'))
from specgen.product import cached_jar
sys.path.insert(0,str(ROOT/'tools'))
from native_toolchain import jdk_tool
p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
base=ROOT/'manager/src/main/java/dev/maplesu/manager/runtime/ultra';files=[base/'UltraAutoPair.kt',base/'UltraAuthorization.kt',ROOT/'runtime/tests/ultra_protocol_scope_test.kt',Path(__file__)]
files.append(base/'UltraPairingMaintenance.kt')
initial={str(f.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(f.read_bytes()).hexdigest() for f in files}
auth=files[1].read_text(encoding='utf-8-sig');scope=auth[auth.index('    internal class Scope('):auth.index('    /** Select the SDK owner')]
(out/'UltraAuthorization.kt').write_text('package dev.maplesu.manager.runtime.ultra\nimport android.content.Context\nimport dev.maplesu.manager.workmode.ModeRuntime\nobject UltraAuthorization {\n'+scope+'\n}\n',encoding='utf-8')
(out/'UltraAutoPair.kt').write_bytes(files[0].read_bytes());(out/'UltraProtocolScopeTest.kt').write_bytes(files[2].read_bytes())
maintenance=(base/'UltraPairingMaintenance.kt').read_text(encoding='utf-8-sig')
request=maintenance[maintenance.index('    fun request(context: Context) {'):maintenance.rfind('\n}')]
(out/'Maintenance.kt').write_text('package dev.maplesu.manager.runtime.ultra\nimport android.content.Context\nobject UltraPairingMaintenanceProduction { private val queue=FixtureQueue()\n'+request+'\n}\n',encoding='utf-8')
fixtures={
'Android.kt':"""package android.content
open class Context { val applicationContext get()=this }
""",
'Process.kt':"""package android.os
object Process { fun myUid()=10123 }
object Looper { fun myLooper():Any?=null;fun getMainLooper():Any=Any() }
""",
'Mode.kt':"""package dev.maplesu.manager.workmode
object ModeRuntime { var activation=Any() }
""",
'Installer.kt':"""package dev.maplesu.manager.installer
import android.content.Context
object InstallerServiceClient {class Service {fun refreshUltraAuthorization()="OK"};fun <T> withService(c:Context,persistent:Boolean,body:(Service)->T)=body(Service())}
""",
'Fixtures.kt':"""package dev.maplesu.manager.runtime.ultra
import android.content.Context
object Fixture {
 var boot:String?="boot";var record:String?=null;var version=0
 var snapshot=UltraEnvironmentProbe.Snapshot();var blocked=false;var su=true;var refresh=true
 var onQueue:(()->Unit)?=null;var suCalls=0;var refreshCalls=0;var maintained=0;var selected:String?="unset";var onSu:(()->Unit)?=null
 fun reset(){boot="boot";record=null;version=0;snapshot=UltraEnvironmentProbe.Snapshot();blocked=false;su=true;refresh=true;suCalls=0;refreshCalls=0;maintained=0;selected="unset";onSu=null;onQueue=null}
}
class UltraRuntimeClient(val legacyCredentialFingerprint:String?=null,val current:()->Boolean={true}) {
 data class ManagerOwnerIdentity(val fullUid:Int,val epoch:Long,val hostInstance:String)
 fun scoped(next:()->Boolean)=UltraRuntimeClient(legacyCredentialFingerprint){current()&&next()}
}
class UltraBootSdkSession(val bootId:String)
object UltraSdkSession {
 fun captureOwnerMarker(c:Context):()->Boolean {val boot=Fixture.boot;return {Fixture.boot==boot}}
 fun client(c:Context,s:UltraBootSdkSession)=UltraRuntimeClient()
}
object UltraSdkCredentials {
 fun captureOwnerMarker(c:Context):()->Boolean {val v=Fixture.version;return {v==Fixture.version}}
 fun client(c:Context)=UltraRuntimeClient(Fixture.record)
 fun client(c:Context,current:()->Boolean)=client(c).scoped(current)
}
object UltraIdentityRecovery {fun blocked(c:Context)=Fixture.blocked}
object UltraEnvironmentAutoSetup {fun currentBootId()=Fixture.boot}
object UltraEnvironmentProbe {
 enum class State {Unknown,Running}
 data class Snapshot(val state:State=State.Unknown,val kernelRootVerified:Boolean=false,val managerOwnerIdentity:UltraRuntimeClient.ManagerOwnerIdentity?=null,val legacyCredentialFingerprint:String?=null){val rootAvailable get()=kernelRootVerified}
 fun refresh(c:Context,force:Boolean)=Fixture.snapshot.state
 fun currentSnapshot(c:Context)=Fixture.snapshot
 fun hasCurrentRootProof(c:Context)=Fixture.snapshot.kernelRootVerified
}
class FixtureQueue {fun request(identity:String,eligible:()->Boolean,work:()->Boolean):Boolean{Fixture.onQueue?.invoke();return eligible()&&work()}}
object UltraSdkLogging {fun apply(client:UltraRuntimeClient){}}
object UltraPairingMaintenance {fun request(c:Context){Fixture.maintained++}}
class UltraSuChannel(timeoutMillis:Long) {fun rootUid():Int {Fixture.suCalls++;Fixture.onSu?.invoke();return if(Fixture.su)0 else -1}}
object UltraSelfWhitelist {
 class Result(val succeeded:Boolean)
 fun ensureOwner(c:Context,owner:UltraRuntimeClient.ManagerOwnerIdentity,current:()->Boolean):Result {Fixture.refreshCalls++;Fixture.selected=null;return Result(current()&&Fixture.refresh)}
 fun ensureCurrentAuthority(c:Context,client:UltraRuntimeClient,current:()->Boolean):Result {Fixture.refreshCalls++;Fixture.selected=client.legacyCredentialFingerprint;return Result(current()&&Fixture.refresh)}
 fun verifyExec(current:()->Boolean,probe:()->Boolean,rootVerified:()->Boolean,refresh:()->Boolean):Boolean {
  if(!current())return false;if(probe())return current();if(!current()||!rootVerified()||!current())return false
  if(!refresh()||!current())return false;return probe()&&current()
 }
}
"""}
for n,s in fixtures.items():(out/n).write_text(s,encoding='utf-8')
deps=[cached_jar(*v) for v in [('org.jetbrains.kotlin','kotlin-stdlib','2.4.20'),('org.jetbrains','annotations','13.0'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]]
compiler=cached_jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.20');java=str(jdk_tool());classes=out/'classes';classes.mkdir(exist_ok=True);cp=os.pathsep.join(map(str,[classes,*deps]));commands=[]
for label,argv in [('compile',[java,'-Xmx256m','-cp',os.pathsep.join(map(str,[compiler,*deps])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-jvm-target','21','-classpath',cp,'-d',str(classes),*map(str,out.glob('*.kt'))]),('run',[java,'-cp',cp,'org.junit.runner.JUnitCore','dev.maplesu.manager.runtime.ultra.UltraProtocolScopeTest'])]:
 q=subprocess.run(argv,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=55);log=q.stdout+q.stderr;(out/(label+'.log')).write_text(log,encoding='utf-8');commands.append(dict(label=label,argv=argv,exitCode=q.returncode));print(label,q.returncode,log,flush=True)
 if q.returncode:break
stable=all(hashlib.sha256((ROOT/n).read_bytes()).hexdigest()==h for n,h in initial.items());passed=len(commands)==2 and all(c['exitCode']==0 for c in commands) and stable
(out/'RESULT.json').write_text(json.dumps(dict(passed=passed,utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),sourceSha256=initial,inputsUnchanged=stable,commands=commands,scope='Complete actual AutoPair entry/complete and exact production Authorization scope bodies. Android, persisted credential client, standard su and authorization synchronization are boundary fixtures; no device result.'),indent=2)+'\n',encoding='utf-8');raise SystemExit(0 if passed else 1)
