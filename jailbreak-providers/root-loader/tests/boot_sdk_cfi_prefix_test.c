/* Host seams for the actual native-start admission body; not target CFI proof. */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#ifndef EOPNOTSUPP
#define EOPNOTSUPP 95
#endif
#define MSU_INIT_BRIDGE_CFI_KCFI 1
#define MSU_INIT_BRIDGE_CFI_NONE 0
#define MSU_BOOT_SDK_EMPTY 0
typedef uint32_t u32;
typedef uintptr_t uptr;
struct msu_boot_sdk_layout { int unused; };
static u32 prefix;
static uptr entry;
static int state, sdk_dynamic_ready, task_ok, atomic_context, irq_off;
static int read_error, reads, initialized, configured, bootstrapped, bad_read;
static int init_error, configure_error, bootstrap_error;
#define MSU_SDK_CODE_START (&prefix)
#define MSU_SDK_CODE_ENTRY entry
static int in_task(void) { return task_ok; }
static int in_atomic(void) { return atomic_context; }
static int irqs_disabled(void) { return irq_off; }
static long copy_from_kernel_nofault(void *to, const void *from, size_t bytes)
{
    ++reads;
    if (from != &prefix || bytes != sizeof(prefix)) bad_read = 1;
    if (read_error) return read_error;
    memcpy(to, from, bytes);
    return 0;
}
static uptr msu_init_bridge_checked_lookup(const char *name)
{
    (void)name;
    return 0;
}
static int msu_boot_sdk_init(uptr (*lookup)(const char *), int (*subject)(void),
    int (*handover)(void))
{
    ++initialized;
    if (lookup != msu_init_bridge_checked_lookup || subject || handover) return -EINVAL;
    return init_error;
}
static int configure(const struct msu_boot_sdk_layout *layout)
{
    (void)layout;
    ++configured;
    return configure_error;
}
static int bootstrap(void) { ++bootstrapped; return bootstrap_error; }
#include "boot_sdk_native_start.inc"
#define CHECK(value) do { if (!(value)) return __LINE__; } while (0)
static void reset(void)
{
    prefix = 0x12345678U;
    entry = 4;
    task_ok = 1;
    state = sdk_dynamic_ready = atomic_context = irq_off = 0;
    read_error = reads = initialized = configured = bootstrapped = bad_read = 0;
    init_error = configure_error = bootstrap_error = 0;
}
int main(void)
{
    struct msu_boot_sdk_layout layout = {0};
    unsigned int offset;
    reset();
    CHECK(!msu_boot_sdk_native_start(&layout));
    CHECK(reads == 1 && initialized == 1 && configured == 1 && bootstrapped == 1 && !bad_read);
    reset(); prefix = 0;
    CHECK(msu_boot_sdk_native_start(&layout) == -EOPNOTSUPP && reads == 1 && !initialized);
    reset(); read_error = -EFAULT;
    CHECK(msu_boot_sdk_native_start(&layout) == -EOPNOTSUPP && reads == 1 && !initialized);
    for (offset = 0; offset <= 8; offset += 8) {
        reset(); entry = offset;
        CHECK(msu_boot_sdk_native_start(&layout) == -EOPNOTSUPP && !reads && !initialized);
    }
    reset(); task_ok = 0;
    CHECK(msu_boot_sdk_native_start(&layout) == -EWOULDBLOCK && !reads);
    reset(); atomic_context = 1;
    CHECK(msu_boot_sdk_native_start(&layout) == -EWOULDBLOCK && !reads);
    reset(); irq_off = 1;
    CHECK(msu_boot_sdk_native_start(&layout) == -EWOULDBLOCK && !reads);
    reset(); state = 1;
    CHECK(msu_boot_sdk_native_start(&layout) == -EBUSY && !reads);
    reset(); sdk_dynamic_ready = 1;
    CHECK(msu_boot_sdk_native_start(&layout) == -EBUSY && !reads);
    reset();
    CHECK(msu_boot_sdk_native_start(NULL) == -EINVAL && !reads);
    reset(); init_error = -EIO;
    CHECK(msu_boot_sdk_native_start(&layout) == -EIO && !configured && !bootstrapped);
    reset(); configure_error = -ERANGE;
    CHECK(msu_boot_sdk_native_start(&layout) == -ERANGE && !bootstrapped);
    reset(); bootstrap_error = -EIO;
    CHECK(msu_boot_sdk_native_start(&layout) == -EIO && bootstrapped == 1);
    puts("native prefix and lifecycle guards passed");
    return 0;
}
