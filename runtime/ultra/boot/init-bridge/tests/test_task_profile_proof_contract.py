"""Run production TaskProfile Image/runtime binding and publication contracts."""
import ast
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[5]
SOURCE = Path(os.environ.get("MAPLESU_TASK_PROFILE_SOURCE_ROOT", ROOT / "runtime/ultra/identity/kernel"))
FIXTURE = Path(__file__).with_name("fixtures") / "task_profile_proof_contract.c"
IMAGE = "sha256:51159bc935c75a282fc65bc87e02f16bd098d1cdf55dc558c4fdd0fcf7a132e2"


def function(text, signature):
    start = text.index(signature)
    begin = text.index("{", start)
    depth, end = 1, begin + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end] + "\n"


class TaskProfileProofContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="maplesu-profile-proof-")
        cls.out = Path(cls.temporary.name)
        header = (SOURCE / "manager_task_state.h").read_text(encoding="utf-8-sig")
        state = (SOURCE / "manager_task_state.c").read_text(encoding="utf-8-sig")
        runtime = (SOURCE / "manager_task_profile_runtime.c").read_text(encoding="utf-8-sig")
        start = header.index("#define MSU_TASK_PROFILE_DISABLE_ROOT ")
        end = header.index("};", header.index("struct msu_task_profile_marker_binding {")) + 2
        (cls.out / "binding.inc").write_text(header[start:end], encoding="utf-8")
        bodies = function(state, "static bool msu_task_profile_hash_present(")
        bodies += function(state, "static bool msu_task_profile_target_ready(")
        (cls.out / "consumer.inc").write_text(bodies, encoding="utf-8")
        bodies = function(runtime, "bool msu_task_profile_runtime_ready(")
        bodies += function(runtime, "noinline __used __visible int msu_task_profile_runtime_produce(")
        (cls.out / "producer.inc").write_text(bodies, encoding="utf-8")
        (cls.out / "fixture.c").write_bytes(FIXTURE.read_bytes())
        cls.prefix = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
            "--tmpfs", "/tmp:rw,exec,size=32m", "--mount", f"type=bind,source={cls.out.as_posix()},target=/out",
            "-w", "/out", "--entrypoint", "/bin/sh", IMAGE, "-ec"]
        result = subprocess.run(cls.prefix + ["cc -std=gnu11 -O1 -g -Wall -Wextra -Werror "
            "-Wno-unused-function -fsanitize=address,undefined -fno-omit-frame-pointer fixture.c -o fixture"],
            capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def check_case(self, case):
        result = subprocess.run(self.prefix + [f"ulimit -c 0; ./fixture {case}"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_claim_metadata_requires_five_current_proofs(self):
        tree = ast.parse((ROOT / "scripts/build-ultra-manager-claim.py").read_text(encoding="utf-8-sig"))
        missing = next(node.value for node in tree.body if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == "TASK_PROFILE_MISSING" for target in node.targets))
        self.assertEqual(ast.literal_eval(missing), ("slot63", "rcu", "bitops", "cfi", "syscallTracepoint"))
        collector = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
            and node.name == "collect_task_profile_implementation")
        schema = next(node.value for node in ast.walk(collector) if isinstance(node, ast.keyword)
            and node.arg == "targetProofSchema")
        mask = next(node.value for node in ast.walk(collector) if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Subscript) and isinstance(target.slice, ast.Constant)
                and target.slice.value == "requiredProofMask" for target in node.targets))
        self.assertEqual(ast.literal_eval(schema), "maplesu-task-profile-target-proof/3")
        self.assertEqual(ast.literal_eval(mask), 31)


for _case in ("image-five", "image-legacy-six", "image-missing", "image-extra", "image-hashes",
              "runtime-publication", "runtime-false-ready", "runtime-legacy-mask", "runtime-failure",
              "unbound", "layout", "abi"):
    setattr(TaskProfileProofContractTest, "test_" + _case.replace("-", "_"),
            lambda self, case=_case: self.check_case(case))


if __name__ == "__main__":
    unittest.main()
