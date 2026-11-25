#!/usr/bin/env python3
"""校验自编译 Ultra 内核的 .config 是否满足 MapleSU 运行前提。

用法：
    python scripts/check-ultra-kernel-config.py path/to/.config

背景（#15）：自编译内核若打开 CONFIG_DEBUG_INFO_BTF 却关闭
CONFIG_MODULE_ALLOW_BTF_MISMATCH，厂商模块的 BTF 校验（pahole 版本/字符串表
编码与官方不同）会被 kernel/bpf/btf.c 当成致命错误，init 随之 panic，
对外表现为"刷进去就落回 fastboot"。这个检查把该失败提前到构建阶段。
"""
from __future__ import annotations

import sys

REQUIRED = {
    "CONFIG_MODVERSIONS": "模块符号版本校验；关闭会与厂商模块的 __versions 段不匹配",
    "CONFIG_DEBUG_INFO_BTF": "Ultra 侧按 BTF 解析结构布局（cred/task_struct 偏移）",
}

# 自编译内核必须显式打开：否则厂商模块 BTF 校验失败 → init panic（#15）
MUST_ENABLE_WITH_BTF = {
    "CONFIG_MODULE_ALLOW_BTF_MISMATCH": (
        "自编译内核的 vmlinux BTF 与官方 pahole 版本不同，厂商模块 BTF 必然"
        "校验失败；未打开时 init 会 panic 并落回 fastboot"
    ),
}


def parse(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                if line.startswith("# ") and " is not set" in line:
                    values[line.split(" ")[1]] = "n"
                continue
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value.strip().strip('"')
    return values


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    values = parse(argv[1])
    problems: list[str] = []
    for key, why in REQUIRED.items():
        if values.get(key) != "y":
            problems.append(f"{key} 未启用：{why}")
    for key, why in MUST_ENABLE_WITH_BTF.items():
        if values.get("CONFIG_DEBUG_INFO_BTF") == "y" and values.get(key) != "y":
            problems.append(f"{key} 未启用：{why}")
    if problems:
        print("ULTRA_KERNEL_CONFIG_UNSAFE")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("ULTRA_KERNEL_CONFIG_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))