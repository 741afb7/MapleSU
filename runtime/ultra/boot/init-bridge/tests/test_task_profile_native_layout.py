"""Native TaskProfile layouts come from module BTF, without an Image oracle."""
import importlib.util
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import unittest

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("task_native_layout", BASE / "task_profile_legacy.py")
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)
sys.path.insert(0, str(BASE.parents[3] / "scripts"))
from policy_btf_abi import Btf


def definitions(work=False, iterator=True, wrong_probe=False):
    strings, records = bytearray(b"\0"), []

    def name(value):
        at = len(strings)
        strings.extend(value.encode() + b"\0")
        return at

    def record(label, kind, value, tail=b"", count=0):
        records.append(struct.pack("<III", name(label), kind << 24 | count, value) + tail)
        return len(records)

    def aggregate(label, size, members):
        return record(label, 4, size, b"".join(struct.pack("<III", name(n), t, at * 8)
            for n, t, at in members), len(members))

    record("u64", 1, 8, struct.pack("<I", 64))                         # 1
    record("int", 1, 4, struct.pack("<I", 16777248))                  # 2
    record("", 2, 0)                                                  # 3
    aggregate("list_head", 16, [("next", 5, 0), ("prev", 5, 8)])       # 4
    record("", 2, 4)                                                  # 5
    aggregate("thread_info", 16, [("flags", 1, 0)] +
        ([("syscall_work", 1, 8)] if work else []))                    # 6
    aggregate("task_struct", 512, [("thread_info", 6, 0),
        ("tasks", 4, 64), ("thread_node", 4, 80)])                     # 7
    aggregate("signal_struct", 48, [("thread_head", 4, 16)])          # 8
    record("", 3, 0, struct.pack("<III", 1, 1, 31))                   # 9
    aggregate("", 248, [("regs", 9, 0)])                              # 10
    aggregate("pt_regs", 336, [("", 10, 0), ("syscallno", 2, 280)])   # 11
    aggregate("tracepoint_func", 24, [("func", 3, 0), ("data", 3, 8)]) # 12
    record("", 2, 12)                                                 # 13
    aggregate("tracepoint", 72, [("funcs", 13, 64)] +
        ([("iterator", 3, 40)] if iterator else []))                   # 14
    aggregate("kprobe", 128, [("addr", 3, 40)])                       # 15
    record("", 13, 2, struct.pack("<IIII", 0, 3, 0, 18), 2)          # 16
    record("", 2, 16)                                                 # 17
    record("", 2, 11)                                                 # 18
    aggregate("kretprobe", 176, [("kp", 3 if wrong_probe else 15, 8),
        ("handler", 17, 136), ("entry_handler", 17, 144),
        ("maxactive", 2, 152), ("nmissed", 2, 156)])                   # 19
    types = b"".join(records)
    return Btf(struct.pack("<HBBIIIII", 0xeb9f, 1, 0, 24, 0, len(types),
        len(types), len(strings)) + types + strings)


class NativeLayoutChecks(unittest.TestCase):
    def test_actual_members_anonymous_regs_and_nested_probe_address(self):
        report = legacy.native_layout(definitions())
        self.assertEqual(report["origin"], "module-dwarf-derived-btf")
        self.assertFalse(report["targetImageBtf"])
        fields = report["fields"]
        self.assertNotIn("thread_info.syscall_work", fields)
        self.assertEqual(fields["pt_regs.regs"], dict(offsetBytes=0, widthBytes=248,
            ownerSizeBytes=336, signature="a31:i8:64"))
        self.assertEqual(fields["kretprobe.kp.addr"], dict(offsetBytes=48, widthBytes=8,
            ownerSizeBytes=176, signature="*v"))
        self.assertEqual(fields["kretprobe.handler"]["signature"],
            "*fi4:16777248(*v;*spt_regs;)")
        self.assertEqual(fields["task_struct.__size"]["widthBytes"], 512)
        self.assertNotIn("kretprobe.kp", fields)
        self.assertNotIn("kprobe.addr", fields)

    def test_optional_syscall_work_is_emitted_only_when_present(self):
        field = legacy.native_layout(definitions(work=True))["fields"]["thread_info.syscall_work"]
        self.assertEqual(field, dict(offsetBytes=8, widthBytes=8,
            ownerSizeBytes=16, signature="i8:64"))

    def test_missing_required_tracepoint_member_stays_a_real_error(self):
        with self.assertRaisesRegex(ValueError, "Missing required BTF structure members: tracepoint"):
            legacy.native_layout(definitions(iterator=False))

    def test_verification_recomputes_from_module_and_rejects_changed_field(self):
        actual = definitions()
        seen = []
        def object_btf(path):
            seen.append(path)
            return actual
        def fail(label):
            raise ValueError(label)
        api = SimpleNamespace(
            retained_path=lambda reference, report, bridge: Path(reference["path"]),
            load_api=lambda name, path: SimpleNamespace(object_btf=object_btf), fail=fail)
        report = dict(nativeLayout=legacy.native_layout(actual), provenance=dict(
            claimBuilder=dict(path="scripts/build-ultra-manager-claim.py"),
            derivedObject=dict(path="module-derived.elf"), targetImage=dict(path="target.Image")))
        legacy.verify_native_layout(api, report, Path("bridge.ko"))
        self.assertEqual(seen, [Path("module-derived.elf")])
        report["nativeLayout"]["fields"]["task_struct.tasks"]["offsetBytes"] += 8
        with self.assertRaisesRegex(ValueError, "NATIVE_LAYOUT_FACTS"):
            legacy.verify_native_layout(api, report, Path("bridge.ko"))

    def test_nested_probe_requires_the_actual_embedded_kprobe_type(self):
        with self.assertRaisesRegex(ValueError, "NATIVE_LAYOUT_KPROBE"):
            legacy.native_layout(definitions(wrong_probe=True))


if __name__ == "__main__":
    unittest.main()
