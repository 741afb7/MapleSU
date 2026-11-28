"""Run the actual protected-I/O scope against distinct kernel/init credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[5]
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=ROOT / 'runtime/ultra/identity/kernel/protected_install_kernel.c')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
source = args.source.read_text(encoding='utf-8-sig')
scope = source[source.index('struct msu_io_scope {'):source.index('struct msu_document {')]
start = source.find('#ifdef MSU_BOOT_SDK_NATIVE\nstatic const struct cred *msu_pid1_io_cred(')
if start < 0:
    start = source.index('static int msu_io_enter(')
implementation = source[start:source.index('static int msu_pid1_root(')]
fixture = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define MSU_BOOT_SDK_NATIVE 1
#define TASK_COMM_LEN 16
typedef uint64_t u64;
struct cred { int refs; int domain; };
struct task_struct { const struct cred *cred, *real_cred; char comm[TASK_COMM_LEN]; int refs; };
static struct cred app_cred = {1, 1}, init_cred = {1, 2}, platform_init_cred = {1, 3};
static struct task_struct app, pid1, sibling;
static struct task_struct *current = &app;
static int init_pid_ns;
static bool layout_ready = true, pid1_present = true;
static int gets, put_count, overrides, reverts;
#define MSU_DYN_FIELD(value, type, member) ((value)->member)
#define rcu_access_pointer(value) (value)
#define ERR_PTR(value) ((void *)(intptr_t)(value))
#define PTR_ERR(value) ((int)(intptr_t)(value))
#define IS_ERR(value) ((intptr_t)(value) < 0 && (intptr_t)(value) >= -4095)
static bool msu_runtime_layout_ready(void) { return layout_ready; }
static const struct cred *msu_dynamic_current_cred(void) { return current->cred; }
static void rcu_read_lock(void) {}
static void rcu_read_unlock(void) {}
static struct task_struct *find_task_by_pid_ns(int nr, const void *ns)
{ assert(nr == 1 && ns == &init_pid_ns); return pid1_present ? &pid1 : NULL; }
static void msu_dynamic_get_task_struct(struct task_struct *task) { ++task->refs; }
static void msu_dynamic_put_task_struct(struct task_struct *task) { assert(task->refs > 1); --task->refs; }
static const struct cred *get_task_cred(struct task_struct *task)
{ ++gets; ++((struct cred *)task->real_cred)->refs; return task->real_cred; }
static void msu_dynamic_put_cred(const struct cred *cred)
{ assert(cred->refs > 1); ++put_count; --((struct cred *)cred)->refs; }
static const struct cred *override_creds(const struct cred *next)
{ const struct cred *old = current->cred; ++overrides; ++((struct cred *)next)->refs; current->cred = next; return old; }
static void revert_creds(const struct cred *old)
{ assert(current->cred->refs > 1); ++reverts; --((struct cred *)current->cred)->refs; current->cred = old; }
static void __attribute__((unused)) msu_dynamic_task_lock(struct task_struct *task) { (void)task; }
static void __attribute__((unused)) msu_dynamic_task_unlock(struct task_struct *task) { (void)task; }
@SCOPE@
@IMPLEMENTATION@
static void reset(void)
{
    assert(app_cred.refs == 1 && init_cred.refs == 1 && platform_init_cred.refs == 1);
    memset(&app, 0, sizeof app); memset(&pid1, 0, sizeof pid1); memset(&sibling, 0, sizeof sibling);
    app.cred = app.real_cred = sibling.cred = sibling.real_cred = &app_cred;
    pid1.cred = pid1.real_cred = &platform_init_cred;
    app.refs = pid1.refs = sibling.refs = 1;
    memcpy(app.comm, "manager-thread", 15); memcpy(sibling.comm, "sibling-thread", 15);
    layout_ready = pid1_present = true; gets = put_count = overrides = reverts = 0;
}
int main(void)
{
    struct msu_io_scope scope;
    int passed = 0;
    reset();
    assert(msu_io_enter(&scope) == 0);
    if (current->cred != &platform_init_cred) {
        msu_io_leave(&scope);
        puts("FAIL native I/O selected kernel-domain init_cred rather than platform PID1 credential");
        return 1;
    }
    assert(gets == 1 && platform_init_cred.refs == 3 && pid1.refs == 1);
    assert(current->real_cred == &app_cred && sibling.cred == &app_cred);
    assert(!memcmp(app.comm, "manager-thread", 15) && !memcmp(sibling.comm, "sibling-thread", 15));
    msu_io_leave(&scope);
    assert(current->cred == &app_cred && gets == put_count && overrides == reverts && platform_init_cred.refs == 1);
    ++passed;
    msu_io_leave(&scope);
    assert(put_count == 1 && reverts == 1); ++passed;
    reset(); layout_ready = false;
    assert(msu_io_enter(&scope) == -EPROTO && !gets && !overrides);
    msu_io_leave(&scope); assert(!put_count && !reverts); ++passed;
    reset(); app.cred = &init_cred;
    assert(msu_io_enter(&scope) == -EOPNOTSUPP && !gets && !overrides);
    msu_io_leave(&scope); assert(current->cred == &init_cred && !put_count); ++passed;
    reset(); pid1_present = false;
    assert(msu_io_enter(&scope) == -ENOENT && !gets && !overrides);
    msu_io_leave(&scope); assert(current->cred == &app_cred && !put_count); ++passed;
    reset();
    assert(msu_io_enter(&scope) == 0);
    pid1.real_cred = &init_cred;
    assert(current->cred == &platform_init_cred);
    msu_io_leave(&scope);
    assert(platform_init_cred.refs == 1 && current->cred == &app_cred && pid1.refs == 1); ++passed;
    printf("PASS %d protected I/O credential lifetime scenarios\n", passed);
    return passed == 6 ? 0 : 2;
}
'''.replace('@SCOPE@', scope).replace('@IMPLEMENTATION@', implementation)
(args.output / 'fixture.c').write_text(fixture, encoding='utf-8')
command = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--cap-drop', 'ALL',
    '--security-opt', 'no-new-privileges', '--mount', 'type=bind,source=' + args.output.resolve().as_posix() + ',target=/test',
    '--entrypoint', 'sh', 'sha256:51159bc935c75a282fc65bc87e02f16bd098d1cdf55dc558c4fdd0fcf7a132e2', '-ec',
    'cc -std=c11 -Wall -Wextra -Werror -Wno-unused-function /test/fixture.c -o /test/fixture && /test/fixture']
result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
(args.output / 'stdout.log').write_text(result.stdout)
(args.output / 'stderr.log').write_text(result.stderr)
receipt = dict(source=args.source.resolve().as_posix(), sourceSha256=hashlib.sha256(args.source.read_bytes()).hexdigest(),
    command=command, exitCode=result.returncode, actualScopeSourceExtracted=True, expectedScenarios=6,
    passed=result.returncode == 0 and 'PASS 6 protected I/O' in result.stdout,
    deviceAcceptanceQualified=False)
(args.output / 'RESULT.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(result.stdout + result.stderr, end='')
raise SystemExit(result.returncode)
