"""Compile the actual Owner credential transaction against native constructors."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[5]
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=ROOT / 'runtime/ultra/identity/kernel/manager_management.c')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
source = args.source.read_text(encoding='utf-8-sig')
start = source.find('static struct cred *msu_credential_prepare(')
if start < 0:
    start = source.index('static int msu_credential_replace_profile(')
transaction = source[start:source.index('#if defined(CONFIG_PROC_FS) && defined(CONFIG_SECURITY)', start)]
profile = (ROOT / 'runtime/ultra/identity/kernel/manager_credential_profile.h').read_text(encoding='utf-8')
profile = '\n'.join(line for line in profile.splitlines() if not line.startswith('#include'))
fixture = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define MSU_BOOT_SDK_NATIVE 1
#define CONFIG_MULTIUSER 1
#define MSU_MANAGEMENT_CRED_UCOUNTS 0
#define CAP_LAST_CAP 40
#define CAP_EMPTY_SET 0ULL
#define MAPLESU_EFFECT_COMMITTED 1U
#define READ_ONCE(x) (x)
#define MSU_DYN_FIELD(value, type, member) ((value)->member)
#define cap_issubset(a, b) (((a) & ~(b)) == 0)
#define cap_intersect(a, b) ((a) & (b))
#define ERR_PTR(value) ((void *)(intptr_t)(value))
#define PTR_ERR(value) ((int)(intptr_t)(value))
#define IS_ERR(value) ((intptr_t)(value) < 0 && (intptr_t)(value) >= -4095)
typedef uint32_t u32;
typedef uint64_t u64, kernel_cap_t;
typedef unsigned int kuid_t, kgid_t;
typedef struct { int refs; } refcount_t;
typedef struct { int refs; } atomic_t;
typedef struct { u64 inheritable, permitted, effective, bounding, ambient; } maplesu_caps_v3;
@PROFILE@
struct mm_struct { int refs; };
struct group_info { refcount_t usage; unsigned int gid[32]; };
struct user_namespace { int id; };
struct user_struct { int id; };
struct security_blob { int sid, exec_sid, landlock; };
struct cred {
    int refs; bool allocated;
    kuid_t uid, suid, euid, fsuid;
    kgid_t gid, sgid, egid, fsgid;
    struct user_namespace *user_ns;
    struct user_struct *user;
    struct group_info *group_info;
    u32 securebits;
    kernel_cap_t cap_inheritable, cap_permitted, cap_effective, cap_bset, cap_ambient;
    struct security_blob *security;
};
struct task_struct { int refs; struct cred *cred, *real_cred; struct mm_struct *mm; u64 self_exec_id; };
struct msu_management_session { const struct cred *active; bool retired; };
typedef struct { u64 data_user; } maplesu_management_v3;
typedef struct { u32 metadata_length; } maplesu_response_owner_v3;
static struct user_namespace init_user_ns;
static struct user_struct root_user;
static struct group_info groups;
static struct group_info root_marker;
static struct mm_struct app_mm;
static struct security_blob app_security, pid1_security;
static struct cred app_cred, pid1_cred;
static struct task_struct app, pid1;
static struct task_struct *current = &app;
static int init_pid_ns;
static bool pid1_present, constructor_fail;
static int normal_prepares, kernel_prepares, aborts, commits, validations, fail_validation;
static int private_blobs, adopts, task_gets, task_puts;
static int root_prepares;
static bool root_prepare_fail;
static bool sleepable = true;
static bool msu_management_sleepable(void) { return sleepable; }
static void rcu_read_lock(void) {}
static void rcu_read_unlock(void) {}
static struct task_struct *find_task_by_pid_ns(int number, const void *ns)
{ assert(number == 1 && ns == &init_pid_ns); return pid1_present ? &pid1 : NULL; }
static void msu_dynamic_get_task_struct(struct task_struct *task) { ++task_gets; ++task->refs; }
static void msu_dynamic_put_task_struct(struct task_struct *task)
{ assert(task->refs > 1); ++task_puts; --task->refs; }
static const struct cred *msu_dynamic_current_cred(void) { return current->cred; }
static const struct cred *msu_dynamic_current_real_cred(void) { return current->real_cred; }
static const struct cred *msu_dynamic_get_cred(const struct cred *cred)
{ if (cred) ++((struct cred *)cred)->refs; return cred; }
static const struct cred *msu_dynamic_get_current_cred(void)
{ return msu_dynamic_get_cred(current->cred); }
static void msu_dynamic_put_cred(const struct cred *cred)
{
    if (!cred) return;
    assert(cred->refs > 0);
    if (!--((struct cred *)cred)->refs && cred->allocated) {
        assert(cred->security != &app_security && cred->security != &pid1_security);
        free(cred->security); --private_blobs; free((void *)cred);
    }
}
static struct cred *clone_cred(const struct cred *old)
{
    struct cred *next = malloc(sizeof(*next)); assert(next);
    *next = *old; next->refs = 1; next->allocated = true;
    next->security = malloc(sizeof(*next->security)); assert(next->security);
    *next->security = *old->security; ++private_blobs; return next;
}
static struct cred *prepare_creds(void) { ++normal_prepares; return clone_cred(current->cred); }
static struct cred *prepare_kernel_cred(struct task_struct *task)
{
    assert(task == &pid1 && task->refs == 2); ++kernel_prepares;
    if (constructor_fail) return NULL;
    return clone_cred(task->real_cred);
}
static void abort_creds(struct cred *cred) { ++aborts; msu_dynamic_put_cred(cred); }
static int commit_creds(struct cred *cred)
{
    assert(current->cred == current->real_cred); assert(cred != current->cred);
    ++commits; msu_dynamic_put_cred(current->cred); current->cred = current->real_cred = cred; return 0;
}
static void msu_dynamic_mmgrab(struct mm_struct *mm) { ++mm->refs; }
static void msu_dynamic_mmdrop(struct mm_struct *mm) { assert(mm->refs > 1); --mm->refs; }
static kuid_t make_kuid(const void *ns, u32 value) { assert(ns == &init_user_ns); return value; }
static kgid_t make_kgid(const void *ns, u32 value) { assert(ns == &init_user_ns); return value; }
static struct user_struct *alloc_uid(kuid_t uid) { assert(!uid); return &root_user; }
static void free_uid(struct user_struct *user) { (void)user; }
static struct group_info *groups_alloc(u32 count) { assert(count <= 32); groups.usage.refs = 2; return &groups; }
static void groups_sort(struct group_info *value) { (void)value; }
static void set_groups(struct cred *cred, struct group_info *value) { cred->group_info = value; }
int msu_native_root_prepare(struct cred *cred)
{
    assert(cred != current->cred && cred->refs == 1 && !cred->uid && !cred->euid);
    ++root_prepares;
    if (root_prepare_fail) return -EOPNOTSUPP;
    set_groups(cred, &root_marker);
    return 0;
}
static void refcount_dec(refcount_t *value) { assert(value->refs > 1); --value->refs; }
static void atomic_dec(atomic_t *value) { assert(value->refs > 1); --value->refs; }
static kernel_cap_t msu_management_cap(u64 value) { return value; }
static int msu_credential_domain_prepare(struct cred *cred, const char *domain, u32 length)
{ (void)cred; (void)domain; (void)length; return -EOPNOTSUPP; }
int msu_credential_profile_validate(const struct msu_credential_profile *profile)
{ return profile && !profile->selinux_domain[0] ? 0 : -EINVAL; }
static int msu_management_check(struct msu_management_session *session)
{
    ++validations;
    if (validations == fail_validation) return -EACCES;
    return session->retired || session->active != current->cred ? -ESTALE : 0;
}
static void msu_management_retire(struct msu_management_session *session) { session->retired = true; }
static const void *msu_management_user_pointer(u64 value) { return (void *)(uintptr_t)value; }
static int copy_from_user_nofault(void *dst, const void *src, size_t size)
{ memcpy(dst, src, size); return 0; }
@TRANSACTION@
static void reset(struct msu_management_session *session)
{
    assert(!private_blobs);
    memset(&app_cred, 0, sizeof(app_cred)); memset(&pid1_cred, 0, sizeof(pid1_cred));
    app_security = (struct security_blob){.sid = 11, .exec_sid = 0, .landlock = 7};
    pid1_security = (struct security_blob){.sid = 42, .exec_sid = 3, .landlock = 0};
    app_cred.refs = 2; app_cred.uid = app_cred.euid = app_cred.gid = app_cred.egid = 10498;
    app_cred.security = &app_security; app_cred.user_ns = &init_user_ns;
    pid1_cred.refs = 1; pid1_cred.security = &pid1_security; pid1_cred.user_ns = &init_user_ns;
    app_mm.refs = 1;
    app = (struct task_struct){.refs = 1, .cred = &app_cred, .real_cred = &app_cred, .mm = &app_mm, .self_exec_id = 9};
    pid1 = (struct task_struct){.refs = 1, .cred = &pid1_cred, .real_cred = &pid1_cred};
    *session = (struct msu_management_session){.active = &app_cred};
    pid1_present = sleepable = true; constructor_fail = false;
    normal_prepares = kernel_prepares = aborts = commits = validations = fail_validation = 0;
    adopts = task_gets = task_puts = 0;
    root_prepares = 0; root_prepare_fail = false;
}
static void finish(struct msu_management_session *session)
{
    assert(app_mm.refs == 1 && pid1.refs == 1 && pid1_cred.refs == 1);
    assert(task_gets == task_puts);
    msu_dynamic_put_cred(session->active); msu_dynamic_put_cred(current->cred);
    assert(!private_blobs);
}
static int adopt(void *context, const struct cred *before, const struct cred *after)
{
    struct msu_management_session *session = context; ++adopts;
    assert(before == session->active && after == current->cred);
    msu_dynamic_put_cred(session->active); session->active = msu_dynamic_get_cred(after); return 0;
}
int main(void)
{
    struct msu_management_session session;
    maplesu_management_v3 operation = {0}; maplesu_response_owner_v3 response = {0};
    maplesu_caps_v3 caps = {.permitted = 3, .effective = 3};
    struct msu_credential_profile profile = {0}; u32 effects = 0; int result, passed = 0;
    reset(&session);
    result = msu_management_credentials(&session, &operation, true, &response);
    assert(!result && response.metadata_length == MAPLESU_EFFECT_COMMITTED && commits == 1);
    if (current->cred->security->sid != pid1_security.sid) {
        puts("FAIL Owner ROOT retained application SID rather than native PID1 constructor state");
        finish(&session); return 1;
    }
    assert(current->cred->security != &pid1_security && current->cred->security != &app_security);
    assert(!current->cred->security->landlock && current->cred->security->exec_sid == pid1_security.exec_sid);
    assert(!current->cred->uid && !current->cred->gid && !normal_prepares && kernel_prepares == 1 && !aborts);
    assert(app_security.sid == 11 && app_security.landlock == 7 && pid1_security.sid == 42);
    if (current->cred->group_info != &root_marker || root_prepares != 1) {
        puts("FAIL authenticated Owner ROOT omitted its private SELinux execution subject");
        finish(&session); return 1;
    }
    finish(&session); ++passed;
    reset(&session); root_prepare_fail = true; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -EOPNOTSUPP);
    assert(root_prepares == 1 && !commits && aborts == 1 && !response.metadata_length);
    assert(current->cred == &app_cred && session.active == &app_cred);
    finish(&session); ++passed;
    reset(&session); fail_validation = 1; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -EACCES);
    assert(!commits && !aborts && !kernel_prepares && !normal_prepares && !response.metadata_length);
    finish(&session); ++passed;
    reset(&session); fail_validation = 2; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -EACCES);
    assert(!commits && aborts == 1 && kernel_prepares == 1 && current->cred == &app_cred && !response.metadata_length);
    finish(&session); ++passed;
    reset(&session); constructor_fail = true; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -ENOMEM);
    assert(!commits && !aborts && kernel_prepares == 1 && !normal_prepares && !response.metadata_length);
    finish(&session); ++passed;
    reset(&session); pid1_present = false; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -ESRCH);
    assert(!commits && !aborts && !kernel_prepares && !normal_prepares && !response.metadata_length);
    finish(&session); ++passed;
    reset(&session); operation.data_user = (uintptr_t)&caps; response.metadata_length = 0;
    assert(!msu_management_credentials(&session, &operation, false, &response));
    assert(current->cred->security->sid == 11 && current->cred->security->landlock == 7);
    assert(normal_prepares == 1 && !kernel_prepares && commits == 1);
    assert(!root_prepares && current->cred->group_info != &root_marker);
    finish(&session); ++passed;
    reset(&session);
    assert(!msu_credential_replace_profile_current(&profile, msu_management_validate_credentials, adopt, &session, &effects));
    assert(current->cred->security->sid == 11 && normal_prepares == 1 && !kernel_prepares && commits == 1 && adopts == 1);
    finish(&session); ++passed;
    reset(&session);
    assert(!msu_credential_replace_current(NULL, msu_management_validate_credentials, &session, &effects));
    assert(current->cred->security->sid == 11 && normal_prepares == 1 && !kernel_prepares && commits == 1);
    msu_dynamic_put_cred(session.active); session.active = msu_dynamic_get_current_cred();
    finish(&session); ++passed;
    reset(&session); current->real_cred = &pid1_cred; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -EPERM);
    assert(!commits && !aborts && !kernel_prepares && !normal_prepares);
    current->real_cred = &app_cred; finish(&session); ++passed;
    reset(&session); sleepable = false; response.metadata_length = 0;
    assert(msu_management_credentials(&session, &operation, true, &response) == -EWOULDBLOCK);
    assert(!commits && !aborts && !kernel_prepares && !normal_prepares);
    finish(&session); ++passed;
    printf("PASS %d Owner bootstrap credential transaction scenarios\n", passed);
    return 0;
}
'''.replace('@PROFILE@', profile).replace('@TRANSACTION@', transaction)
(args.output / 'fixture.c').write_text(fixture, encoding='utf-8')
(args.output / 'transaction.c').write_text(transaction, encoding='utf-8')
command = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--cap-drop', 'ALL',
           '--security-opt', 'no-new-privileges', '--mount',
           'type=bind,source=' + args.output.resolve().as_posix() + ',target=/test',
           '--entrypoint', 'sh', 'sha256:51159bc935c75a282fc65bc87e02f16bd098d1cdf55dc558c4fdd0fcf7a132e2',
           '-ec', 'cc -std=gnu11 -Wall -Wextra -Werror -Wno-unused-function /test/fixture.c -o /test/fixture && /test/fixture']
result = subprocess.run(command, capture_output=True)
(args.output / 'stdout.log').write_bytes(result.stdout)
(args.output / 'stderr.log').write_bytes(result.stderr)
receipt = dict(source=args.source.resolve().as_posix(),
               sourceSha256=hashlib.sha256(args.source.read_bytes()).hexdigest(),
               command=command, exitCode=result.returncode, actualTransactionExtracted=True,
               expectedScenarios=11, passed=result.returncode == 0, deviceAcceptanceQualified=False)
(args.output / 'RESULT.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(result.stdout.decode(), end='')
print(result.stderr.decode(), end='')
raise SystemExit(result.returncode)
