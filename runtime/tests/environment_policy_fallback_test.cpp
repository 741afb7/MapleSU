// Real environment entry -> shared boot state -> Zygisk permission gate.
// Only device paths and kernel preparation are fixtures.
#include "module_policy_config.h"
#include "module_policy_mode.h"
#include <cassert>
#include <cstdarg>
#include <cstdio>
#include <fcntl.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

extern "C" int maplesu_environment_policy_start();
extern "C" int __real_open(const char*, int, ...);
namespace {
unsigned hides = 0;
bool fail_hide = true, active = false, fail_cancel = false, partial_failure = false;
unsigned cancellations = 0;
const char* BOOT[] = {"00000000-0000-0000-0000-000000000000",
    "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
    "00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004",
    "00000000-0000-0000-0000-000000000005"};
void boot(unsigned id) {
    active = false;
    int fd = __real_open("/output/current-boot", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    const auto value = std::string(BOOT[id]) + "\n";
    assert(fd >= 0 && write(fd, value.data(), value.size()) == ssize_t(value.size()));
    close(fd);
}
int start() { return maplesu_environment_policy_start(); }
}
extern "C" int __wrap_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    if (std::string(path) == "/data") path = "/output/fallback-data";
    else if (std::string(path) == "/proc/sys/kernel/random/boot_id") path = "/output/current-boot";
    return __real_open(path, flags, mode);
}
namespace maplesu::module_policy {
bool hidden_kernel_policy_supported() { return true; }
unsigned prepare_hidden_kernel_policy(bool) {
    ++hides;
    if (fail_hide) {
        if (partial_failure) active = true;
        throw std::runtime_error("MODULE_POLICY_LOADER_HOOK_CONFLICT:sel_write_access");
    }
    active = true;
    return 3;
}
void cancel_hidden_kernel_policy() {
    ++cancellations;
    if (fail_cancel) throw std::runtime_error("CANCEL_TRANSPORT");
    active = false;
}
}
int main() {
    using namespace maplesu::module_policy;
    assert(mkdir("/output/fallback-data", 0700) == 0);
    assert(mkdir("/output/fallback-data/adb", 0700) == 0);
    boot(0);
    assert(start() == 0 && !maple_module_policy_allowed() && !active);
    auto store = Store::open(false);
    store.request(Mode::Load, BOOT[0]);
    assert(start() == 0 && !maple_module_policy_allowed());
    boot(1);
    assert(start() == 0 && maple_module_policy_allowed() && hides == 0 && !active);
    store.request(Mode::Hide, BOOT[1]);
    boot(2);
    assert(start() == 0 && maple_module_policy_allowed() && hides == 1 && !active);
    auto state = store.read(BOOT[2]);
    assert(state.hide_failed && state.requested == Mode::Load && state.effective->mode == Mode::Load);
    assert(start() == 0 && hides == 1 && maple_module_policy_allowed());
    store.request(Mode::Hide, BOOT[2], true);
    fail_hide = false;
    boot(3);
    assert(start() == 0 && maple_module_policy_allowed() && hides == 2 && active);
    state = store.read(BOOT[3]);
    assert(!state.hide_failed && state.effective->mode == Mode::Hide);
    store.request(Mode::Block, BOOT[3]);
    assert(start() == 0 && maple_module_policy_allowed() && active);
    boot(4);
    assert(start() == 0 && !maple_module_policy_allowed() && !active && hides == 2);
    store.request(Mode::Hide, BOOT[4]);
    boot(5);
    fail_hide = partial_failure = fail_cancel = true;
    assert(start() == 3 && active && !maple_module_policy_allowed());
    state = store.read(BOOT[5]);
    assert(!state.effective && state.requested == Mode::Hide && store.blocks_transactions(BOOT[5], true));
    auto attempts = hides, cancelled = cancellations;
    assert(start() == 3 && active && hides == attempts && cancellations == cancelled + 1);
    assert(!store.read(BOOT[5]).effective && !maple_module_policy_allowed());
    fail_cancel = false;
    assert(start() == 0 && !active && hides == attempts && cancellations == cancelled + 2);
    state = store.read(BOOT[5]);
    assert(state.hide_failed && state.requested == Mode::Load && state.effective->mode == Mode::Load);
    assert(maple_module_policy_allowed() && !store.blocks_transactions(BOOT[5], true));
    puts("PASS 11 environment/boot-transition/Zygisk fallback integration cases");
}
