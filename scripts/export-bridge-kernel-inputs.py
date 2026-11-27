"""Export only the fixed-image kernel bytes read by the bridge source/target generators."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def identity(path, data):
    return {"path": path.as_posix(), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("kernel-source", "kernel-build", "out"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--image-sha256", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.image_sha256):
        parser.error("an immutable Docker image ID is required")
    output = args.out.resolve()
    if any((output / name).exists() for name in ("source", "kdir", "EXPORT.json")):
        raise FileExistsError("preserve existing kernel inputs: " + str(output))
    config = (args.kernel_build / ".config").read_text(encoding="utf-8")
    source_required = ["Makefile", "fs/mount.h", "fs/internal.h", "include/linux/fs_struct.h", "include/linux/cred.h"]
    source_optional = ["include/linux/execmem.h"]
    seccomp = ["kernel/seccomp.c", "include/linux/seccomp.h"]
    (source_required if "CONFIG_SECCOMP_FILTER=y" in config.splitlines() else source_optional).extend(seccomp)
    rows, absent, contents = [], [], []
    for role, base, required, optional in (
            ("source", args.kernel_source, source_required, source_optional),
            ("kdir", args.kernel_build, [".config", "Module.symvers", "include/generated/utsrelease.h"], [])):
        for relative in required + optional:
            source = base / relative
            if not source.is_file():
                if relative in required:
                    raise FileNotFoundError(source)
                absent.append({"role": role, "relative": relative, "source": source.as_posix()})
                continue
            data = source.read_bytes()
            destination = output / role / relative
            contents.append((source, destination, data))
            rows.append({"role": role, "relative": relative, "source": identity(source, data),
                         "exported": identity(destination, data)})
    # Resolve and validate the complete narrow read set before publishing any selected byte.
    for source, destination, data in contents:
        if source.read_bytes() != data:
            raise ValueError("kernel input changed while exporting: " + str(source))
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(data)
        if destination.read_bytes() != data:
            raise ValueError("kernel input copy mismatch: " + str(destination))
    receipt = {"schema": "maplesu-bridge-kernel-input-export/1", "dockerImage": args.image_sha256,
               "sourceDirectory": args.kernel_source.as_posix(), "buildDirectory": args.kernel_build.as_posix(),
               "files": rows, "absentOptionalInputs": absent, "compiled": False, "deviceExecuted": False}
    with (output / "EXPORT.json").open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"exported": len(rows), "absentOptionalInputs": absent, "compiled": False}))


if __name__ == "__main__":
    main()
