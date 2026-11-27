#include "module_err_def.h"
#include "module_base_disk_storage.h"
#include "maplesu_storage_worker_private.h"
#include <cstdint>
#include <string>
#include <vector>
#include <cstring>
#include <unistd.h>

extern "C" KModErr maplesu_write_su_allowlist(const std::vector<std::string>&, const char*, bool);
extern "C" KModErr maplesu_prepare_su_exec();
extern "C" KModErr maplesu_freeze_su_copies();
extern "C" KModErr maplesu_open_su_copies_from_control(uint64_t);
extern "C" void maplesu_thaw_su_copies();

namespace default_modules { namespace module_su_redirect {
class PatchDoExecve {
public:
    KModErr patch_do_execve(uint64_t);
};
}}

namespace {
bool context(const char* module) {
    return getuid() == 0 && geteuid() == 0 && module && strnlen(module, 33) == 32;
}
struct BootDelivery {
    bool opened = false;
    bool finished = false;
    KModErr finish(KModErr primary) {
        finished = true;
        const auto cleanup = opened ? KModErr::OK : maplesu_freeze_su_copies();
        maplesu_thaw_su_copies();
        return is_failed(primary) ? primary : cleanup;
    }
    ~BootDelivery() {
        if (!finished) try { (void)finish(KModErr::ERR_MODULE_OPEN_FILE); } catch (...) { }
    }
};
}

extern "C" __attribute__((visibility("default")))
int64_t maplesu_helper_su_write(const void* packages, const char* module) {
    if (!packages || !context(module)) return static_cast<int64_t>(KModErr::ERR_MODULE_PARAM);
    maplesu::storage::WorkerScope worker(module);
    auto result = worker.root();
    if (is_ok(result)) {
        BootDelivery delivery;
        try {
            result = [&]() {
                auto error = maplesu_prepare_su_exec();
                if (is_failed(error)) return error;
                error = maplesu_freeze_su_copies();
                if (is_failed(error)) return error;
                error = maplesu_write_su_allowlist(*static_cast<const std::vector<std::string>*>(packages), nullptr, false);
                if (is_failed(error)) return error;
                uint64_t control = 0;
                error = kernel_module::read_uint64_disk_storage(
                    "uid_control_kaddr.e842572c094b847aed9d11840a807b46", control);
                if (is_failed(error)) return error;
                error = maplesu_open_su_copies_from_control(control);
                delivery.opened = !is_failed(error);
                return error;
            }();
        } catch (...) {
            result = KModErr::ERR_MODULE_OPEN_FILE;
        }
        result = delivery.finish(result);
    }
    const auto closed = worker.close_checked();
    return static_cast<int64_t>(is_failed(result) ? result : closed);
}

extern "C" __attribute__((visibility("default")))
int64_t maplesu_helper_su_exec(void* object, uint64_t control, const char* module) {
    if (!object || !control || !context(module)) return static_cast<int64_t>(KModErr::ERR_MODULE_PARAM);
    maplesu::storage::WorkerScope worker(module);
    auto result = worker.root();
    if (is_ok(result)) {
        try {
            result = static_cast<default_modules::module_su_redirect::PatchDoExecve*>(object)->patch_do_execve(control);
        } catch (...) {
            result = KModErr::ERR_MODULE_OPEN_FILE;
        }
    }
    const auto closed = worker.close_checked();
    return static_cast<int64_t>(is_failed(result) ? result : closed);
}
