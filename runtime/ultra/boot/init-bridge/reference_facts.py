"""Project verified bridge build inputs without publishing reference images."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import struct
import sys

ROOT = Path(__file__).resolve().parents[4]
SCHEMA = "maplesu-init-bridge-reference-facts/1"
LOCAL_TYPES = {"module", "kprobe", "pt_regs", "file_operations", "kiocb", "iov_iter",
               "mutex", "spinlock_t", "raw_spinlock_t", "work_struct", "callback_head",
               "fsnotify_mark", "fsnotify_ops", "path", "list_head", "hlist_node", "user_arg_ptr"}
PROFILES = ("do_execveat_common", "__arm64_sys_newfstatat", "__arm64_sys_faccessat",
            "__arm64_sys_statx", "__arm64_sys_faccessat2")
HASH = re.compile(r"[0-9a-f]{64}")


def require(value, code):
    if not value:
        raise ValueError("ULTRA_REFERENCE_" + code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def api(name):
    spec = importlib.util.spec_from_file_location("reference_" + name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def unique(table, name, image):
    values = set(table.all_offsets(name))
    require(len(values) == 1, "SYMBOL:" + name)
    at = values.pop()
    require(0 <= at <= len(image) - 4 and at % 4 == 0, "SYMBOL_BOUNDS:" + name)
    return at


def canonical(image, table, name):
    body = unique(table, name, image)
    at = unique(table, name + ".cfi_jt", image)
    word = struct.unpack_from("<I", image, at)[0]
    if word in (0xd503245f, 0xd50324df):
        at += 4
        require(at <= len(image) - 4, "CANONICAL_BOUNDS:" + name)
        word = struct.unpack_from("<I", image, at)[0]
    displacement = word & 0x3ffffff
    if displacement & 0x2000000:
        displacement -= 0x4000000
    require(word & 0xfc000000 == 0x14000000 and at + displacement * 4 == body,
            "CANONICAL_TARGET:" + name)


def generation(selection_path, handoff):
    catalog = api("package_catalog")
    common, _ = catalog.selection_sources(catalog.read_json(Path(selection_path)))
    return digest(catalog.encoded({"moduleSources": common, "handoffSha256": digest(handoff)}))


def generate(data, release, module, source_generation):
    sys.path.insert(0, str(ROOT / "tools/specgen"))
    sys.path.insert(0, str(ROOT / "scripts"))
    from specgen.bootimg import _inspect, decode as decode_kernel
    from specgen.kallsyms import decode
    from policy_btf_abi import Btf
    package = api("package")
    image = data["kernel_image"]
    contract = json.loads(data["target_contract"])
    config = package.config_values(data["target_config"])
    layout = _inspect(data["target_boot"])
    require(layout["kernelSize"] > 0 and decode_kernel(data["target_boot"][layout["kernelOffset"]:
            layout["kernelOffset"] + layout["kernelSize"]]) == image, "BOOT_KERNEL")
    require(digest(image) == contract["inputs"]["kernelImage"]["sha256"] and
            digest(data["target_boot"]) == contract["inputs"]["targetBoot"]["sha256"] and
            digest(data["target_config"]) == contract["inputs"]["targetConfig"]["sha256"], "SOURCE_IDENTITY")
    table = decode(image)
    legacy_api = api("legacy_cfi_contract")
    legacy = legacy_api.mode(image, table, config) == "legacy-lto-cfi"
    require(legacy == (contract.get("cfiMode") == "legacy-lto-cfi"), "CFI_MODE")
    if legacy:
        require(legacy_api.target_facts(image, table) == contract["legacyCfi"], "CFI_SOURCE")
    checked = package.inspect_module(data["bridge"], release, config, data["symvers"], contract)
    versions = package.module_versions(package.ELFFile(io.BytesIO(data["bridge"])))
    direct = checked["legacyDirectImports"]
    direct_targets = sorted({item["target"] for item in direct.get("directImportsV2", [])}
                            or set(direct["imports"]))
    for name in direct_targets:
        unique(table, name, image)
    needed = set(checked["ordinaryImports"]) | (set(versions) - set(direct["imports"]))
    exports = {name: {"crc": f"{crc:08x}", "namespace": namespace}
               for name, crc, _, namespace in api("target_exports").capture(image, table, config) if name in needed}
    require(exports.keys() == needed, "EXPORT_CLOSURE")
    loader = contract.get("abiEvidence", {}).get("method") == "same-kmi-module-loader"
    profiles = sorted(name for name in PROFILES if table.all_offsets(name))
    require(set(PROFILES[:3]) <= set(profiles), "PROFILE_REQUIRED")
    imports, local_types, profile_signatures = {}, {}, {}
    if not loader:
        require("btf" in data, "BTF_REQUIRED")
        btf = Btf(data["btf"])
        for name in sorted(needed):
            if name in btf.functions:
                imports[name] = btf.function(name)
        for name in sorted(LOCAL_TYPES | ({"group_info"} if "session_abi" in data else set())):
            candidates = {btf.signature(index) for index, row in enumerate(btf.types)
                          if row and row[1] in (4, 5, 8) and btf.name(row[0]) == name}
            require(len(candidates) == 1, "LOCAL_TYPE:" + name)
            local_types[name] = candidates.pop()
        profile_signatures = {name: btf.function(name) for name in profiles}
    private = (set(contract.get("privateSymbols", [])) | set(contract.get("optionalPrivateSymbols", []))) if loader else set(contract.get("prototypes", {}))
    optional = set(contract.get("optionalPrivateSymbols", [])) if loader else set()
    presence = {name: bool(table.all_offsets(name)) for name in sorted(private)}
    canonical_names, kcfi = [], {}
    for name in sorted(private - set(direct_targets)):
        if name in optional and not presence[name]:
            continue
        if legacy:
            try:
                canonical(image, table, name)
            except ValueError:
                pass  # An unavailable canonical entry remains absent; rebinding must reject it.
            else:
                canonical_names.append(name)
        elif config.get("CONFIG_CFI_CLANG") == "y" or config.get("CONFIG_CFI") == "y":
            at = unique(table, name, image)
            require(at >= 4, "KCFI_BOUNDS:" + name)
            kcfi[name] = f"{struct.unpack_from('<I', image, at - 4)[0]:08x}"
    if "session_abi" in data:
        session = json.loads(data["session_abi"])
        require(session.get("bundledModuleSha256") == digest(data["bridge"]), "SESSION_MODULE")
        package.verify_session_module(data["bridge"], session)
        if legacy:
            require(session.get("cfiPolicy", {}).get("mode") == "legacy-lto-cfi", "SESSION_MODE")
            legacy_api.verify_module(data["bridge"], contract["legacyCfi"], session["callbacks"])
    if "task_profile_abi" in data:
        task = json.loads(data["task_profile_abi"])
        require(task.get("bundledModuleSha256") == digest(data["bridge"]), "TASK_MODULE")
        api("task_profile_abi").verify_module(data["bridge"], task)
    pages = [size for token, size in (("4K", 4096), ("16K", 16384), ("64K", 65536))
             if config.get("CONFIG_ARM64_" + token + "_PAGES") == "y"]
    require(len(pages) == 1, "PAGE_CONFIG")
    facts = dict(schema=SCHEMA, referenceBootSha256=digest(data["target_boot"]),
                 referenceKernelSha256=digest(image), bridgeSha256=digest(data["bridge"]),
                 contractSha256=digest(data["target_contract"]), sourceGenerationSha256=source_generation,
                 sourceRelease=release, sourceVermagic=module.get("targetVermagic", module.get("vermagic")),
                 sourcePageSize=pages[0], sourceConfigSha256=digest(data["target_config"]), legacyCfi=legacy,
                 exports=exports, importSignatures=imports, localTypeSignatures=local_types,
                 profileSignatures=profile_signatures, profileSymbols=profiles, directTargets=direct_targets,
                 privateSymbolPresence=presence, privateKcfi=kcfi, privateCanonicalSymbols=canonical_names,
                 sourceProofs={"sessionAbiSha256": digest(data["session_abi"]) if "session_abi" in data else None,
                               "taskProfileAbiSha256": digest(data["task_profile_abi"]) if "task_profile_abi" in data else None})
    require(0 < len(encoded(facts)) <= 1048576, "SIZE")
    return facts


def verify(facts, manifest, contract, components):
    require(facts.get("schema") == SCHEMA, "SCHEMA")
    for field, key in (("referenceBootSha256", "targetBootSha256"), ("referenceKernelSha256", "kernelSha256"),
                       ("bridgeSha256", "bridgeSha256"), ("contractSha256", "targetContractSha256"),
                       ("sourceGenerationSha256", "sourceGenerationSha256"), ("sourceRelease", "kernelRelease"),
                       ("sourceVermagic", "targetVermagic"), ("sourceConfigSha256", "configSha256")):
        require(facts.get(field) == manifest.get(key), "IDENTITY:" + field)
    require(type(facts.get("legacyCfi")) is bool and facts["legacyCfi"] == (contract.get("cfiMode") == "legacy-lto-cfi"), "CFI_MODE")
    require(facts["sourcePageSize"] in (4096, 16384, 65536), "PAGE_SIZE")
    for field in ("sessionAbi", "taskProfileAbi"):
        require(facts["sourceProofs"].get(field + "Sha256") ==
                (digest(components[field]) if field in components else None), "PROOF:" + field)


def verify_projection(root, manifest, sources, source_generation):
    fields = {"config": "target_config", "moduleSymvers": "symvers", "targetContract": "target_contract",
              "bridge": "bridge", "handoff": "handoff", "sessionAbi": "session_abi", "taskProfileAbi": "task_profile_abi"}
    data = {key: (root / manifest[field]).read_bytes() for field, key in fields.items() if field in manifest}
    for name in ("target_boot", "kernel_image", "btf"):
        if name not in sources["inputs"]:
            continue
        row = sources["inputs"][name]
        body = Path(row["path"]).read_bytes()
        require(len(body) == row["bytes"] and digest(body) == row["sha256"], "SOURCE_CHANGED:" + name)
        require(row.get("bundledAs") is None, "REFERENCE_IMAGE_PUBLISHED:" + name)
        data[name] = body
    expected = encoded(generate(data, manifest["kernelRelease"], {"targetVermagic": manifest["targetVermagic"]}, source_generation))
    actual = (root / manifest["referenceFacts"]).read_bytes()
    require(expected == actual and digest(actual) == manifest["referenceFactsSha256"], "PROJECTION_CHANGED")


def compact_bundle(bundle, selection, strict_build, output):
    catalog, package = api("package_catalog"), api("package")
    item = catalog.inspect_variant({"bundle": str(bundle), "sourceSelection": str(selection), "strictBuild": str(strict_build)}, bundle.parent)
    manifest = catalog.read_json(bundle / "manifest.json")
    require(manifest.get("schemaVersion") == 1, "COMPACT_SOURCE_SCHEMA")
    sources = catalog.read_json(bundle / manifest["bundleSources"])
    mapping = {"targetBoot": "target_boot", "config": "target_config", "moduleSymvers": "symvers",
               "targetContract": "target_contract", "bridge": "bridge", "handoff": "handoff", "btf": "btf",
               "sessionAbi": "session_abi", "taskProfileAbi": "task_profile_abi"}
    data = {key: (bundle / manifest[field]).read_bytes() for field, key in mapping.items() if field in manifest}
    kernel = sources["inputs"]["kernel_image"]
    data["kernel_image"] = Path(kernel["path"]).read_bytes()
    require(len(data["kernel_image"]) == kernel["bytes"] and digest(data["kernel_image"]) == kernel["sha256"], "KERNEL_SOURCE_CHANGED")
    module = {"targetVermagic": manifest["targetVermagic"]}
    facts = generate(data, manifest["kernelRelease"], module, item["generation"])
    require(not output.exists(), "OUTPUT_NOT_FRESH")
    output.mkdir(parents=True)
    omitted = {manifest[field] for field in ("targetBoot", "btf") if field in manifest}
    for name, identity in item["files"].items():
        if name in omitted or name in ("manifest.json", manifest["bundleSources"]):
            continue
        destination = output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(bundle / name, destination)
        catalog.same(catalog.identity(destination), identity, "ULTRA_REFERENCE_COPY_CHANGED:" + name)
    if (bundle / "MODULE-INPUT-CHECK.json").is_file():
        shutil.copyfile(bundle / "MODULE-INPUT-CHECK.json", output / "MODULE-INPUT-CHECK.json")
    for name in ("target_boot", "btf"):
        if name in sources["inputs"]:
            sources["inputs"][name]["bundledAs"] = None
    source_bytes = encoded(sources)
    (output / manifest["bundleSources"]).write_bytes(source_bytes)
    manifest["bundleSourcesSha256"] = digest(source_bytes)
    for field in ("targetBoot", "btf", "btfSha256"):
        manifest.pop(field, None)
    manifest.update(schemaVersion=3, kind="target-template", sourceGenerationSha256=item["generation"],
                    referenceFacts="reference-facts.json", referenceFactsSha256=digest(encoded(facts)))
    (output / "reference-facts.json").write_bytes(encoded(facts))
    (output / "manifest.json").write_bytes(encoded(manifest))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--source-selection", type=Path, required=True)
    parser.add_argument("--strict-build", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(compact_bundle(args.bundle.resolve(), args.source_selection.resolve(),
                                   args.strict_build.resolve(), args.output.resolve()), sort_keys=True))


if __name__ == "__main__":
    main()
