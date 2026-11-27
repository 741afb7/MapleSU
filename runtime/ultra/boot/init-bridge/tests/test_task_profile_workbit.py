"""Execute the native work-bit producer against captured A64 and synthetic CFGs."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[5]
SOURCE = Path(os.environ.get("MAPLESU_TASK_PROFILE_SOURCE_ROOT", ROOT / "runtime/ultra/identity/kernel"))
sys.path.insert(0, str(ROOT / "tools/specgen"))
sys.path.insert(0, str(ROOT / "scripts"))
from specgen.bootimg import kernel
from specgen.kallsyms import decode
from policy_btf_abi import Btf


def function(text, signature):
    start = text.index(signature)
    begin = text.index("{", start)
    depth, end = 1, begin + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end] + "\n"


def captured(label, path):
    raw = path.read_bytes()
    if raw[:8] == b"ANDROID!":
        raw = kernel(path)
    symbols = decode(raw)
    start, end = symbols.offset_of("__start_BTF"), symbols.offset_of("__stop_BTF")
    btf = Btf(raw[start:end])
    trace = btf.named_structure_selected("tracepoint", {"funcs"})["members"]["funcs"]["offset"]
    task = btf.named_structure_selected("task_struct", {"thread_info"})["members"]["thread_info"]["offset"]
    work = btf.named_structure_selected("thread_info", {"flags"})["members"]["flags"]["offset"]
    jump = btf.named_structure_selected("jump_entry", {"code", "target"})
    text, members = [], {}
    for name, suffix in (("syscall_trace_enter", "code"), ("trace_sys_enter", "helper")):
        at = symbols.offset_of(name)
        if at is None:
            members.update({suffix: "NULL", suffix + "_words": "0", suffix + "_jumps": "NULL", suffix + "_address": "0"})
            continue
        stop = symbols.next_offset(at)
        words = list(struct.unpack("<" + "I" * ((stop-at)//4), raw[at:stop]))
        targets = [-1] * len(words)
        for entry in range(symbols.offset_of("__start___jump_table"), symbols.offset_of("__stop___jump_table"), jump["bytes"]):
            code = entry + jump["members"]["code"]["offset"]
            code += struct.unpack_from("<i", raw, code)[0]
            if not at <= code < stop:
                continue
            target = entry + jump["members"]["target"]["offset"]
            target += struct.unpack_from("<i", raw, target)[0]
            assert at <= target < stop and code % 4 == target % 4 == 0
            targets[(code-at)//4] = (target-at)//4
        text += [f'static u32 {label}_{suffix}[]={{' + ','.join(hex(x) for x in words) + '};',
                 f'static s32 {label}_{suffix}_jumps[]={{' + ','.join(map(str, targets)) + '};']
        members.update({suffix: f"{label}_{suffix}", suffix + "_words": str(len(words)),
                        suffix + "_jumps": f"{label}_{suffix}_jumps", suffix + "_address": hex(at + 0x10000000) + "UL"})
    text.append(f'static struct captured {label}={{.address={members["code_address"]},.code={members["code"]},.words={members["code_words"]},.jumps={members["code_jumps"]},'
                f'.helper_address={members["helper_address"]},.helper={members["helper"]},.helper_words={members["helper_words"]},.helper_jumps={members["helper_jumps"]},'
                f'.tracepoint={symbols.offset_of("__tracepoint_sys_enter") + 0x10000000}UL,.funcs={trace},.work_offset={task+work}' + '};')
    return '\n'.join(text)


class TaskProfileWorkbitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="maplesu-workbit-")
        cls.out = Path(cls.temporary.name)
        (cls.out / "workbit.inc").write_bytes((SOURCE / "manager_task_profile_workbit.h").read_bytes())
        source = (SOURCE / "manager_task_profile_runtime.c").read_text(encoding="utf-8-sig")
        body = function(source, "static int msu_profile_workbit_function(") if "static int msu_profile_workbit_function(" in source else ""
        body += function(source, "static int msu_profile_workbit(")
        (cls.out / "producer.inc").write_text(body)
        (cls.out / "fixture.c").write_bytes((Path(__file__).with_name("fixtures") / "task_profile_workbit.c").read_bytes())
        (cls.out / "captured.inc").write_text(captured("k70u", Path(os.environ.get("MAPLESU_WORKBIT_K70U_IMAGE",
            ROOT / "Boot_Research/._boot/k70uboot.img"))) + '\n' +
            captured("v618", ROOT / "native-inputs/kernels/android17-6.18-4f47635b/Image"))
        cls.prefix = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
            "--tmpfs", "/tmp:rw,exec,size=32m", "--mount", f"type=bind,source={cls.out.as_posix()},target=/out",
            "-w", "/out", "--entrypoint", "/bin/sh", "sha256:51159bc935c75a282fc65bc87e02f16bd098d1cdf55dc558c4fdd0fcf7a132e2", "-ec"]
        result = subprocess.run(cls.prefix + ["cc -std=gnu11 -O1 -g -Wall -Wextra -Werror -Wno-unused-function -Wno-sign-compare "
            "-fsanitize=address,undefined -fno-omit-frame-pointer fixture.c -o fixture"], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def check(self, case):
        result = subprocess.run(self.prefix + ["./fixture " + case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


for case in ("nonadjacent", "clobber", "helper", "helper-name-only", "helper-wrong-object", "helper-both-edges", "helper-unreachable", "actual-k70u", "actual-618"):
    setattr(TaskProfileWorkbitTest, "test_" + case.replace("-", "_"), lambda self, case=case: self.check(case))

if __name__ == "__main__":
    unittest.main()
