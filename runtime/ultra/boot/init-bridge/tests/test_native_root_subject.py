"""Run the actual native ROOT subject, denial callback and failed-init cleanup."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "native_root.c"
ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT / "jailbreak-providers/dirtyfrag/tests"))
from test_l3_arm import host_compiler

CC = os.environ.get("BRIDGE_FIXTURE_CC") or host_compiler()

FIXTURE = r'''
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
typedef uint32_t u32;
struct group_info { unsigned refs; };
struct cred { unsigned uid, euid; struct group_info *group_info; };
struct pt_regs { uintptr_t regs[31], sp, pc, pstate; };
struct kprobe {
    const char *symbol_name;
    int (*pre_handler)(struct kprobe *, struct pt_regs *);
    void *addr;
};
#define DEFINE_MUTEX(name) int name
#define smp_load_acquire(at) (*(at))
#define smp_store_release(at, value) (*(at) = (value))
#define MSU_DYN_FIELD(value, type, member) ((value)->member)
#define GLOBAL_ROOT_UID 0U
#define uid_eq(a, b) ((a) == (b))
static struct group_info app_groups = {2}, private_groups = {0}, other_groups = {1};
static struct cred parent = {10498, 10498, &app_groups}, prepared = {0, 0, &app_groups};
static struct cred *active = &parent;
static unsigned registrations, removals, allocations, changes, locks;
static int failure, displacement;
static u32 entry_word = 0xd503245fU;
static const struct cred *msu_dynamic_current_cred(void) { return active; }
static const struct cred *msu_dynamic_current_real_cred(void) { return active; }
static void mutex_lock(int *lock) { (void)lock; assert(!locks++); }
static void mutex_unlock(int *lock) { (void)lock; assert(locks-- == 1); }
static uintptr_t msu_init_bridge_lookup(const char *name)
{ assert(!strcmp(name, "avc_denied")); return failure == 1 ? 0 : 0x1000; }
static int register_kprobe(struct kprobe *probe)
{
    assert(!probe->addr);
    ++registrations;
    probe->addr = (void *)(uintptr_t)(0x1000 + displacement);
    if (failure == 2) return -EBUSY;
    return 0;
}
static void unregister_kprobe(struct kprobe *probe)
{ (void)probe; ++removals; }
static int copy_from_kernel_nofault(void *to, const void *from, size_t bytes)
{
    assert((uintptr_t)from == 0x1000 && bytes == sizeof(entry_word));
    if (failure == 3) return -EFAULT;
    memcpy(to, &entry_word, bytes); return 0;
}
static struct group_info *groups_alloc(unsigned count)
{
    assert(!count); ++allocations;
    if (failure == 4) return NULL;
    private_groups.refs = 1; return &private_groups;
}
static void set_groups(struct cred *cred, struct group_info *groups)
{
    assert(cred != active && groups && cred->group_info->refs);
    ++groups->refs; --cred->group_info->refs; cred->group_info = groups; ++changes;
}
@PRODUCTION@
int main(int argc, char **argv)
{
    assert(argc == 2);
    const char *scenario = argv[1];
    int expected_error = 0;
    if (!strcmp(scenario, "missing")) { failure = 1; expected_error = -ENOENT; }
    if (!strcmp(scenario, "register")) { failure = 2; expected_error = -EBUSY; }
    if (!strcmp(scenario, "read")) { failure = 3; displacement = 4; expected_error = -EOPNOTSUPP; }
    if (!strcmp(scenario, "allocation")) { failure = 4; expected_error = -ENOMEM; }
    if (!strcmp(scenario, "bti")) displacement = 4;
    if (!strcmp(scenario, "past-prologue")) { displacement = 8; expected_error = -EOPNOTSUPP; }
    if (!strcmp(scenario, "past-pac")) { displacement = 4; entry_word = 0xd503233fU; expected_error = -EOPNOTSUPP; }
    if (!strcmp(scenario, "published")) {
        assert(msu_native_root_prepare(&parent) == -EINVAL);
        assert(!registrations && !allocations && !changes); return 0;
    }
    if (!strcmp(scenario, "nonroot-prepared")) {
        prepared.uid = 10498;
        assert(msu_native_root_prepare(&prepared) == -EINVAL);
        assert(!registrations && !allocations && !changes); return 0;
    }
    int result = msu_native_root_prepare(&prepared);
    assert(result == expected_error && !locks);
    assert(parent.uid == 10498 && parent.euid == 10498 && parent.group_info == &app_groups);
    if (expected_error) {
        assert(!msu_root_groups && !changes && prepared.group_info == &app_groups);
        assert(removals == (failure == 1 || failure == 2 ? 0U : 1U));
        /* Failed initialization leaves the next authenticated attempt usable. */
        failure = displacement = 0; entry_word = 0xd503245fU;
        assert(!msu_native_root_prepare(&prepared));
    }
    assert(prepared.group_info == &private_groups && private_groups.refs == 2);
    unsigned previous_registrations = registrations, previous_allocations = allocations;
    assert(!msu_native_root_prepare(&prepared));
    assert(registrations == previous_registrations && allocations == previous_allocations);
    assert(private_groups.refs == 2 && app_groups.refs == 1);
    active = &prepared;
    if (!strcmp(scenario, "unmarked-root")) prepared.group_info = &other_groups;
    if (!strcmp(scenario, "app")) prepared.uid = prepared.euid = 10498;
    if (!strcmp(scenario, "uid-only")) prepared.euid = 10498;
    if (!strcmp(scenario, "euid-only")) prepared.uid = 10498;
    if (!strcmp(scenario, "unready")) msu_root_groups = NULL;
    if (!strcmp(scenario, "inherited")) {
        static struct cred forked; forked = prepared; active = &forked;
    }
    struct pt_regs frame, expected;
    memset(&frame, 0x35, sizeof(frame)); frame.regs[30] = 0x2345; expected = frame;
    int allowed = strcmp(scenario, "unmarked-root") && strcmp(scenario, "app") &&
        strcmp(scenario, "uid-only") && strcmp(scenario, "euid-only") &&
        strcmp(scenario, "unready") && strcmp(scenario, "null-regs");
    if (allowed) { expected.regs[0] = 0; expected.pc = expected.regs[30]; }
    assert(msu_root_avc_pre(NULL, !strcmp(scenario, "null-regs") ? NULL : &frame) == allowed);
    assert(!memcmp(&frame, &expected, sizeof(frame)));
    return 0;
}
'''


class NativeRootSubjectTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if CC is None:
            raise AssertionError("host compiler missing")
        cls.temporary = tempfile.TemporaryDirectory(prefix="native-root-subject-")
        cls.addClassCleanup(cls.temporary.cleanup)
        directory = Path(cls.temporary.name)
        production = "\n".join(line for line in SOURCE.read_text(encoding="utf-8").splitlines()
                               if not line.startswith("#include"))
        source = directory / "fixture.c"
        source.write_text(FIXTURE.replace("@PRODUCTION@", production)
                          .replace("unsigned long", "uintptr_t"), encoding="utf-8")
        cls.binary = directory / "fixture.exe"
        subprocess.run([CC, "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                        str(source), "-o", str(cls.binary)], check=True, timeout=20)

    def run_cases(self, *cases):
        for case in cases:
            with self.subTest(case=case):
                subprocess.run([str(self.binary), case], check=True, timeout=5)

    def test_authenticated_prepared_root_and_inheritance(self):
        self.run_cases("root", "inherited", "bti")

    def test_other_tasks_keep_the_original_selinux_decision(self):
        self.run_cases("unmarked-root", "app", "uid-only", "euid-only", "unready", "null-regs")

    def test_failed_initialization_cleans_up_and_allows_retry(self):
        self.run_cases("missing", "register", "read", "allocation", "past-prologue", "past-pac")

    def test_published_and_nonroot_credentials_are_rejected_before_initialization(self):
        self.run_cases("published", "nonroot-prepared")


if __name__ == "__main__":
    unittest.main()
