// Test the real C entry without linking any SDK OEM/root initializer.
#include "kernel_policy_gate.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <sys/types.h>

extern "C" int maplesu_environment_policy_start();
namespace {
uid_t real_uid = 0, effective_uid = 0;
unsigned opened = 0, prepared = 0;
unsigned failure = 0;
const std::string boot = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
void reset(unsigned error = 0) {
    real_uid = effective_uid = 0;
    opened = prepared = 0;
    failure = error;
}
}
extern "C" uid_t __wrap_getuid() { return real_uid; }
extern "C" uid_t __wrap_geteuid() { return effective_uid; }
namespace maplesu::module_policy {
Store::Store(int directory) : directory_(directory) {}
Store::~Store() = default;
Store Store::open(bool create) {
    if (!create) return Store(-1);
    ++opened;
    if (failure == 1) throw std::runtime_error("FIXTURE_OPEN");
    return Store(-1);
}
void Store::record_compatibility(const std::string& report) const noexcept {
    assert(report.find("result=failed reason=FIXTURE_") != std::string::npos);
}
std::string current_boot_id() {
    if (failure == 2) throw std::runtime_error("FIXTURE_BOOT_ID");
    return boot;
}
unsigned prepare_kernel_policy(Mode mode, bool allow_unverified) {
    assert(mode == Mode::Block);
    assert(!allow_unverified);
    ++prepared;
    if (failure == 3) throw std::runtime_error("FIXTURE_BACKEND");
    return 1;
}
BootState Store::prepare_boot(const std::string& id, unsigned (*prepare)(Mode, bool), bool supports_unverified) {
    assert(id == boot);
    assert(!supports_unverified);
    return {Mode::Block, prepare(Mode::Block, false), id};
}
}
int main() {
    unsigned tests = 0;
    reset();
    assert(maplesu_environment_policy_start() == 0 && opened == 1 && prepared == 1); ++tests;
    reset(); real_uid = 2000;
    assert(maplesu_environment_policy_start() == 1 && opened == 0 && prepared == 0); ++tests;
    reset(); effective_uid = 2000;
    assert(maplesu_environment_policy_start() == 1 && opened == 0 && prepared == 0); ++tests;
    for (unsigned stage : {1U, 2U, 3U}) {
        reset(stage);
        assert(maplesu_environment_policy_start() == 3);
        assert(opened == 1 && prepared == unsigned(stage == 3));
        ++tests;
    }
    printf("PASS %u environment policy root/entry cases\n", tests);
}
