"""Exercise production controller status ownership at the Android/service boundary."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import native_toolchain
sys.path.insert(0, str(ROOT / "tools/specgen"))
from specgen.product import cached_jar

STUBS = {
    "context.kt": """package android.content
import java.io.File
class Context(val filesDir: File) {
    val applicationContext get() = this
    val dataDir get() = filesDir
    fun createDeviceProtectedStorageContext() = this
}
""",
    "os.kt": """package android.os
class Binder
class Looper { companion object {
    fun myLooper(): Looper? = null
    fun getMainLooper(): Looper = Looper()
} }
class Bundle {
    fun getInt(name: String, fallback: Int) = fallback
    fun getBoolean(name: String, fallback: Boolean) = fallback
}
class ParcelFileDescriptor {
    class AutoCloseInputStream(value: ParcelFileDescriptor) : java.io.ByteArrayInputStream(byteArrayOf())
}
""",
    "log.kt": """package android.util
object Log { fun w(tag: String, message: String, error: Throwable) = 0
    fun i(tag: String, message: String) = 0 }
""",
    "boundary.kt": """package dev.maplesu.manager.jailbreak
import android.content.Context
import java.io.File
enum class JailbreakMethod(val wireName:String) { DIRTYFRAG("dirtyfrag") }
object DirtyfragAttempt { fun readBootId() = "current-boot" }
object JailbreakDiagnostics {
    fun begin(files: File, request: JailbreakRequest) {}
    fun recordOutcome(files: File, request: JailbreakRequest, outcome: JailbreakController.Outcome) {}
    fun record(protected: File, files: File, status: JailbreakStatus, text: String) {}
}
""",
    "daemon.kt": """package dev.maplesu.manager.runtime
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.maplesu.manager.jailbreak.*
object NativeSuDaemon {
    var prepare: (Context, JailbreakRequest) -> Unit = { _, _ -> }
    fun reproveHookOutcome(context: Context): JailbreakController.Outcome? = null
    fun prepareHook(context: Context, request: JailbreakRequest): JailbreakStatus? {
        prepare(context, request); return JailbreakStatus.read(context)
    }
    fun prepareKsu(context: Context, request: JailbreakRequest) { prepare(context, request) }
    fun management(context: Context, request: JailbreakRequest? = null): Management {
        if(request != null) prepare(context, request); return Management()
    }
    fun cancel(context: Context) = true
    fun probeStatus(context: Context): Bundle? = null
}
class Management {
    fun status() = Bundle()
    fun start(argv: Array<String>, binder: Binder, tty: Boolean) = Task()
}
class Task {
    fun openStdout(): ParcelFileDescriptor? = null
    fun openStderr(): ParcelFileDescriptor? = null
    fun awaitExit(timeout: Int) = Bundle()
    fun close() {}
}
class HookRootExecutor { fun available() = false
    companion object { fun production(context: Context) = HookRootExecutor() } }
""",
    "backend.kt": """package dev.maplesu.manager.workmode.backend
object KsuBackendRegistry { fun prepareJailbreakManager(context: android.content.Context) {} }
""",
    "detect.kt": """package dev.maplesu.manager.workmode.detect
enum class EnvironmentInvalidation { JAILBREAK_COMPLETED }
""",
    "probes.kt": """package dev.maplesu.manager.workmode.detect.probes
object SuReadinessProbeAdapter { fun invalidateCache() {} }
object EnvironmentCheckProduction {
    fun markDirty(context: android.content.Context,
        event: dev.maplesu.manager.workmode.detect.EnvironmentInvalidation) {}
}
""",
    "boot.kt": """package dev.maplesu.manager.workmode.boot
object ModeBootstrap { fun retryEnvironmentDetection() {} }
""",
    "setup.kt": """package dev.maplesu.manager.runtime.ultra
object UltraEnvironmentAutoSetup { fun ensure(context: android.content.Context) = "fixture" }
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = [ROOT / "manager/src/main/java/dev/maplesu/manager/jailbreak" / name
               for name in ("JailbreakController.kt", "JailbreakPreparationResult.kt",
                            "JailbreakStatus.kt", "JailbreakRequest.kt", "JailbreakErrorCode.kt")]
    sources += [ROOT / "manager/src/test/resources/jailbreak-controller-jvm/JailbreakControllerOutcomeTest.kt",
                ROOT / "manager/src/test/java/dev/maplesu/manager/jailbreak/JailbreakPreparationResultTest.kt"]
    identity = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sources}
    for name, content in STUBS.items():
        path = out / name
        path.write_text(content, encoding="utf-8")
        sources.append(path)
    deps = [cached_jar(*entry) for entry in [
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"),
        ("org.jetbrains", "annotations", "23.0.0"),
        ("org.json", "json", "20240303"), ("junit", "junit", "4.13.2"),
        ("org.hamcrest", "hamcrest-core", "1.3")]]
    compiler = cached_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20")
    classpath = os.pathsep.join(map(str, deps))
    jar = out / "tests.jar"
    commands = [
        [str(native_toolchain.jdk_tool("java")), "-Xmx512m", "-cp", str(compiler) + os.pathsep + classpath,
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
         "-classpath", classpath, "-d", str(jar), *map(str, sources)],
        [str(native_toolchain.jdk_tool("java")), "-cp", str(jar) + os.pathsep + classpath,
         "org.junit.runner.JUnitCore", "dev.maplesu.manager.jailbreak.JailbreakControllerOutcomeTest",
         "dev.maplesu.manager.jailbreak.JailbreakPreparationResultTest"],
    ]
    results = []
    for index, command in enumerate(commands):
        run = subprocess.run(command, cwd=ROOT / "manager", capture_output=True, text=True,
                             encoding="utf-8", errors="replace", timeout=120)
        log = run.stdout + run.stderr
        (out / f"step-{index}.log").write_text(log, encoding="utf-8")
        print(log, end="")
        results.append({"argv": command, "exitCode": run.returncode})
        if run.returncode:
            break
    unchanged = all(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest
                    for path, digest in identity.items())
    (out / "RESULT.json").write_text(json.dumps({"sources": identity, "commands": results,
        "sourceUnchanged": unchanged,
        "deviceValidated": False, "boundary": "Android context and service transport are fixtures"},
        indent=2) + "\n", encoding="utf-8")
    return run.returncode if unchanged else 1


if __name__ == "__main__":
    raise SystemExit(main())
