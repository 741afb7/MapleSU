#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""生成/校验 third_party/kernelsu/manifest.json（03 分册 §1.2.3）。

无 submodule / 无 git 时用三层证据替代：
  1) commit 字段（打包时从 .research 的 git 读一次）；
  2) 逐文件 sha256（LF 归一化后的字节）；
  3) 树聚合哈希 treeSha256（对 `path\\0sha256\\n` 排序拼接再 sha256）。

用法：
  python third_party/kernelsu/scripts/gen_manifest.py            # 生成
  python third_party/kernelsu/scripts/gen_manifest.py --check    # CI 门禁，任何漂移 exit 2
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

import ksu_common as C

MANIFEST_GENERATOR = "third_party/kernelsu/scripts/gen_manifest.py@1"
VOLATILE_KEYS = ("generatedAt", "verifiedAt")


def extract_uapi(text: str) -> dict:
    version = int(re.search(r"KERNEL_SU_UAPI_VERSION\s*=\s*(\d+)\s*;", text).group(1))
    ioctls = {}
    for name, num in re.findall(r"KSU_IOCTL_(\w+)\s*=\s*_IO[RW]*\('K',\s*(\d+)", text):
        ioctls[name] = int(num)
    frozen = {
        "APP_LOCK_STATE": 30, "APP_LOCK_AUTH": 31, "APP_LOCK_SET": 32,
        "STEALTH_GET": 33, "STEALTH_SET": 34, "STEALTH_GET_G": 35, "STEALTH_SET_G": 36,
    }
    missing = [k for k in frozen if k not in ioctls]
    if missing:
        raise C.KsuError(f"applied UAPI 缺冻结 ioctl: {missing}")
    for key, want in frozen.items():
        if ioctls[key] != want:
            raise C.KsuError(f"冻结 ioctl 号漂移: {key}={ioctls[key]} != {want}")
    if ioctls.get("LOOKUP_SYMBOLS") != 22:
        raise C.KsuError("上游 LOOKUP_SYMBOLS=22 被改动")
    if any(n in ioctls.values() for n in (37, 38, 39)):
        raise C.KsuError("37-39 是预留号，被普通功能占用")
    return {
        "version": version,
        "ioctls": ioctls,
        "frozenIoctls": frozen,
        "reservedNumbers": [37, 38, 39],
        "source": "applied/uapi/supercall.h",
    }


def build_manifest(root: Path) -> dict:
    upstream = root / "upstream"
    applied = root / "applied"
    if not upstream.is_dir():
        raise C.KsuError("缺 upstream/（先跑 scripts/export_snapshot.py --which upstream）")
    snapshot = C.read_json(upstream / "SNAPSHOT.json") if (upstream / "SNAPSHOT.json").is_file() else {}

    upstream_entries = C.tree_file_entries(upstream)
    # source7k：只作为补丁来源，落临时目录算哈希后删除（不入库）
    tmp_root = root / ".tmp"
    tmp_root.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="source7k-", dir=str(tmp_root)))
    try:
        research = C.RESEARCH_ROOT / "7kimisu"
        meta7k = C.export_git_tree(research, C.SOURCE7K_COMMIT, tmp / "tree", clean=True)
        source7k_entries = C.tree_file_entries(tmp / "tree")
        source7k_tree = C.tree_sha256(tmp / "tree")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    patches_meta = C.read_json(root / "patches" / "7k" / "patches.json")
    series = C.series_hashes(root / "patches" / "7k")
    entries_by_name = {e["name"]: e for e in patches_meta["entries"]}
    series_entries = []
    for name in series["order"]:
        meta = entries_by_name.get(name)
        if meta is None:
            raise C.KsuError(f"patches.json 与 series 不一致：缺 {name} 的元数据")
        if meta["sha256"] != series["entries"][series["order"].index(name)]["sha256"]:
            raise C.KsuError(f"{name}: patches.json 记录的 sha256 与文件不一致（重跑 gen_patches.py）")
        series_entries.append(meta)

    if not applied.is_dir():
        raise C.KsuError("缺 applied/（先跑 scripts/apply_patches.py --clean）")
    # 生成物（.applied.json / NOTICE）不进清单/计数：它们每次重建都会变，
    # 不该出现在可复现的证据哈希里（树哈希同样排除，见 ksu_common.TREE_EXCLUDES）。
    applied_entries = [e for e in C.tree_file_entries(applied)
                       if e["path"] not in C.TREE_EXCLUDES]
    applied_tree = C.tree_sha256(applied)
    applied_json = C.read_json(applied / ".applied.json") if (applied / ".applied.json").is_file() else {}
    if applied_json.get("treeSha256") and applied_json["treeSha256"] != applied_tree:
        raise C.KsuError("applied/.applied.json 记录的 treeSha256 与实际不一致（applied 被手改？）")

    uapi = extract_uapi(C.read_text(applied / "uapi" / "supercall.h"))
    if uapi["version"] != C.MAPLESU_UAPI:
        raise C.KsuError(f"applied UAPI={uapi['version']} != {C.MAPLESU_UAPI}")

    cr_offenders = C.crlf_offenders(applied)
    if cr_offenders:
        raise C.KsuError(f"CR 门禁失败: {cr_offenders[:8]}")

    manifest = {
        "schema": 1,
        "product": "MapleSU",
        "branchSpec": "docs/dual-mode/impl/03-ksu-contract.md §1/§7",
        "generatedAt": C.utc_now_iso(),
        "generator": MANIFEST_GENERATOR,
        "upstream": {
            "repo": C.UPSTREAM_REPO,
            "commit": C.UPSTREAM_COMMIT,
            "refNote": C.UPSTREAM_REF_NOTE,
            "ci": C.UPSTREAM_CI,
            "uapiVersion": C.UPSTREAM_UAPI,
            "exportCommand": C.GIT_ARCHIVE_CMD.replace("<repo>", ".research/KernelSU")
            .replace("<rev>", C.UPSTREAM_COMMIT),
            "lineEnding": "LF (git object bytes; -c core.autocrlf=false)",
            "gitTreeEntries": snapshot.get("gitMembers", 0),
            "fileCount": len(upstream_entries),
            "treeSha256": C.tree_sha256(upstream),
            "materializedSymlinks": snapshot.get("materializedSymlinks", []),
            "crBaseline": C.all_cr_files(upstream),
            "files": upstream_entries,
        },
        "source7k": {
            "repo": C.SOURCE7K_REPO,
            "commit": C.SOURCE7K_COMMIT,
            "release": C.SOURCE7K_RELEASE,
            "uapiVersion": C.SOURCE7K_UAPI,
            "exportCommand": C.GIT_ARCHIVE_CMD.replace("<repo>", ".research/7kimisu")
            .replace("<rev>", C.SOURCE7K_COMMIT),
            "lineEnding": "LF (git object bytes; -c core.autocrlf=false)",
            "gitTreeEntries": meta7k["gitMembers"],
            "materializedSymlinks": meta7k["materializedSymlinks"],
            "fileCount": len(source7k_entries),
            "treeSha256": source7k_tree,
            "files": source7k_entries,
        },
        "patchSeries": {
            "order": series["order"],
            "seriesSha256": series["seriesSha256"],
            "seriesFile": "patches/7k/series",
            "aggregateSha256": series["aggregateSha256"],
            "aggregateRule": "sha256(concat(sha256(patch_i) for i in order))",
            "entries": series_entries,
            "superseded": patches_meta.get("superseded", []),
            "todo": patches_meta["todos"],
        },
        "applied": {
            "treeSha256": applied_tree,
            "fileCount": len(applied_entries),
            "builtBy": applied_json.get("generator", "third_party/kernelsu/scripts/apply_patches.py@1"),
            "rebuiltAt": applied_json.get("appliedAt"),
            "uapiVersion": uapi["version"],
            "uapiSyncTargets": applied_json.get("uapiSyncTargets", []),
            "sameTreeNote": "Windows 无 core.symlinks：applied/{kernel/include/uapi, "
                            "manager/app/src/main/cpp/uapi} 是 applied/uapi 的逐字节副本，"
                            "apply_patches.py 每次重建后同步（03 §1.2.4）",
        },
        "uapi": uapi,
        "identity": {
            "packageName": C.MAPLESU_PACKAGE,
            # W18b 三重校验：kernel 认主（KSU_MANAGER_PACKAGE 编译期注入）/ ksud build.rs 默认值
            # / 本 manifest —— 三处必须同值，verify_payload.py 会逐处断言。
            "managerPackage": C.MAPLESU_PACKAGE,
            "managerPackageSources": [
                "kernel/Kbuild: KSU_MANAGER_PACKAGE ?= " + C.MAPLESU_PACKAGE,
                "userspace/ksud/build.rs: cargo:rustc-env=KSU_PACKAGE_NAME=" + C.MAPLESU_PACKAGE,
                "third_party/kernelsu/manifest.json: identity.managerPackage",
            ],
            "uapiVersion": C.MAPLESU_UAPI,
            "managerMinGen": C.MAPLESU_MANAGER_MIN_GEN,
            "managerGenAsset": "assets/ksu_manager_gen2",
            "managerGenAssetStatus": "源码文件位于 manager/src/main/assets/ksu_manager_gen2；最终打包状态以对应产物核验为准",
            "ksuVersionPin": C.MAPLESU_KSU_VERSION_PIN,
            "isPrBuild": 0,
            "expectedSize": 772,
            "expectedHash": "30bafb57e3604afe93da13a1285516e043feb82b56a861a157b53ccaa27c3506",
            "expectedNote": "默认绑定 Maple 发行证书公开 DER（772 字节）；build_lkm.sh 可通过 "
                            "KSU_EXPECTED_SIZE/KSU_EXPECTED_HASH 显式覆盖构建身份",
        },
        "lineEndingPolicy": {
            "rule": "upstream/applied/patches 一律 LF；任何 CR 出现在构建相关范围即失败",
            "gateCommand": "python third_party/kernelsu/scripts/verify_payload.py",
            "buildScopes": list(C.BUILD_SCOPES),
        },
        "sources": {
            "upstreamResearchPath": ".research/KernelSU",
            "source7kResearchPath": ".research/7kimisu",
            "note": ".research 工作树是 CRLF（core.autocrlf=true），只读其 git 对象；"
                    "manifest 里的哈希都是导出后的 LF 字节",
        },
        "verification": {
            "command": "bash third_party/kernelsu/scripts/rebuild.sh --verify-only",
            "scripts": [
                "scripts/export_snapshot.py", "scripts/gen_patches.py",
                "scripts/apply_patches.py", "scripts/gen_manifest.py",
                "scripts/gen_payload.py", "scripts/verify_payload.py",
            ],
        },
    }
    return manifest


def normalize(manifest: dict) -> dict:
    import copy
    norm = copy.deepcopy(manifest)
    norm.pop("generatedAt", None)
    if isinstance(norm.get("applied"), dict):
        norm["applied"].pop("rebuiltAt", None)
    return norm


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="生成/校验 manifest.json")
    ap.add_argument("--root", default=str(C.KSU_ROOT))
    ap.add_argument("--out", default=None)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    root = Path(args.root)
    out = Path(args.out) if args.out else root / "manifest.json"
    manifest = build_manifest(root)
    if args.check:
        if not out.is_file():
            print(f"ERROR: {out} 不存在，--check 无意义", file=sys.stderr)
            return 2
        current = C.read_json(out)
        a, b = normalize(manifest), normalize(current)
        if a != b:
            diffs = []
            for key in sorted(set(a) | set(b)):
                if a.get(key) != b.get(key):
                    diffs.append(key)
            print(f"ERROR: manifest 漂移（重跑 gen_manifest.py）：{diffs}", file=sys.stderr)
            return 2
        print(f"OK manifest --check: {out}")
        return 0
    C.write_json(out, manifest)
    print(f"OK manifest: {out} upstreamFiles={len(manifest['upstream']['files'])} "
          f"source7kFiles={len(manifest['source7k']['files'])} "
          f"patchEntries={len(manifest['patchSeries']['entries'])} "
          f"todos={len(manifest['patchSeries']['todo'])} "
          f"appliedTree={manifest['applied']['treeSha256']}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except C.KsuError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
