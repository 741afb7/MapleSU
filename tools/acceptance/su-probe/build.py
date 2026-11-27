"""Build ordinary SU or explicit gateway test APKs; --syntax-only emits no APK/native library."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path("D:/Maple_Env/Android/Sdk"))
    parser.add_argument("--ndk", default="26.1.10909125")
    parser.add_argument("--build-tools", default="37.0.0")
    parser.add_argument("--platform", default="android-37.0")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"))
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--syntax-only", action="store_true")
    parser.add_argument("--gateway", action="store_true", help="build independent explicit gateway identities")
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if args.gateway and any(out.glob("su-probe-*.apk")):
        parser.error("gateway output must be separate from existing passive APKs")
    stem = "gateway-probe" if args.gateway else "su-probe"
    java = HERE / ("GatewayActivity.java" if args.gateway else "ProbeActivity.java")
    source = HERE / ("gateway.c" if args.gateway else "probe.c")
    library = "libgatewayprobe.so" if args.gateway else "libsuprobe.so"
    manifest_template = HERE / ("GatewayAndroidManifest.xml.in" if args.gateway else "AndroidManifest.xml.in")
    tools = args.sdk / "build-tools" / args.build_tools
    android = args.sdk / "platforms" / args.platform / "android.jar"
    ndk = args.sdk / "ndk" / args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    commands = []
    environment = dict(os.environ, JAVA_HOME=str(args.jdk))

    def run(*command):
        result = subprocess.run(list(map(str, command)), env=environment, capture_output=True, text=True, encoding="utf-8", errors="replace")
        commands.append({"argv": list(map(str, command)), "exit": result.returncode, "output": result.stdout + result.stderr})
        (out / "commands.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
        result.check_returncode()
        return result.stdout + result.stderr

    classes = out / ("gateway-classes" if args.gateway else "classes")
    classes.mkdir(exist_ok=True)
    run(args.jdk / "bin/javac.exe", "-source", "8", "-target", "8", "-classpath", android,
        "-d", classes, java)
    variants = [("arm32", "armeabi-v7a", "armv7a-linux-androideabi26"),
                ("arm64", "arm64-v8a", "aarch64-linux-android26")]
    artifacts = []
    keystore = out / (stem + "-test.jks")
    if not args.syntax_only and not keystore.exists():
        run(args.jdk / "bin/keytool.exe", "-genkeypair", "-keystore", keystore, "-storepass", "android",
            "-keypass", "android", "-alias", stem, "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-dname", "CN=MapleSU Acceptance Test")
    for name, abi, target in variants:
        folder = out / name
        folder.mkdir(exist_ok=True)
        native = folder / library
        command = [ndk / "clang.exe", "--target=" + target, "-std=c11", "-Wall", "-Wextra", "-Werror"]
        if args.syntax_only:
            run(*command, "-fsyntax-only", source)
            if args.gateway:
                run(*command, "-fsyntax-only", HERE / "gateway_encoding_test.c")
            continue
        run(*command, "-shared", "-fPIC", "-Wl,-z,max-page-size=16384", source, "-o", native)
        header = native.read_bytes()[:20]
        if header[:4] != b"\x7fELF" or header[4] != (1 if name == "arm32" else 2) or int.from_bytes(header[18:20], "little") != (40 if name == "arm32" else 183):
            raise RuntimeError("native ELF class/machine mismatch: " + name)
        package = ("dev.maplesu.acceptance.gateway." if args.gateway else "dev.maplesu.acceptance.su.") + name
        manifest = folder / "AndroidManifest.xml"
        manifest.write_text(manifest_template.read_text().replace("@PACKAGE@", package), encoding="utf-8")
        resource_apk = folder / "resources.apk"
        run(tools / "aapt2.exe", "link", "-I", android, "--manifest", manifest, "-o", resource_apk)
        java_classes = list(classes.rglob("*.class"))
        run(tools / "d8.bat", "--min-api", "26", "--lib", android, "--output", folder, *java_classes)
        with zipfile.ZipFile(resource_apk, "a") as apk:
            apk.write(folder / "classes.dex", "classes.dex")
            apk.write(native, "lib/" + abi + "/" + library)
        aligned = folder / "aligned.apk"
        run(tools / "zipalign.exe", "-f", "4", resource_apk, aligned)
        signed = out / (stem + "-" + name + ".apk")
        run(tools / "apksigner.bat", "sign", "--ks", keystore, "--ks-key-alias", stem,
            "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, aligned)
        cert = run(tools / "apksigner.bat", "verify", "--verbose", "--print-certs", signed)
        badging = run(tools / "aapt2.exe", "dump", "badging", signed)
        if "native-code: '" + abi + "'" not in badging:
            raise RuntimeError("APK ABI mismatch: " + name)
        elf = run(ndk / "llvm-readelf.exe", "-h", native)
        artifacts.append(dict(package=package, abi=abi, apk=str(signed), sha256=hashlib.sha256(signed.read_bytes()).hexdigest(),
                              certificate=cert, elf=elf))
    (out / "RESULT.json").write_text(json.dumps(dict(syntaxOnly=args.syntax_only, device=False, artifacts=artifacts,
        probeKind="gateway" if args.gateway else "su",
        sourceSha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in HERE.iterdir() if p.is_file()}), indent=2), encoding="utf-8")
    print("syntax passed" if args.syntax_only else "built " + str(len(artifacts)) + " ordinary application APKs")


if __name__ == "__main__":
    main()
