#pragma once

#include "maplesu_storage_private.h"
#include <cstring>
#include <optional>
#include <unistd.h>

namespace default_modules::module_root { KModErr set_current_root(bool); }

namespace maplesu::storage {

// Each real worker owns its session; nesting may borrow that same worker's session.
class __attribute__((visibility("hidden"))) WorkerScope final {
public:
    explicit WorkerScope(const char* module_id32 = nullptr)
        : process_(getpid()), auth_(kernel_module::maplesu_request_auth_state()),
          module_id_(module_id32 ? module_id32 : "") {}
    ~WorkerScope() { close(); }
    WorkerScope(const WorkerScope&) = delete;
    WorkerScope& operator=(const WorkerScope&) = delete;
    bool owner_mode() const { return kernel_module::maplesu_request_auth_authenticated(auth_); }

    KModErr root() {
        scope_.reset();
        if (process_ != getpid() || !kernel_module::maplesu_request_auth_usable(auth_) ||
            auth_ != kernel_module::maplesu_request_auth_state())
            return KModErr::ERR_MODULE_ROOT_KEY;
        if (kernel_module::maplesu_request_auth_authenticated(auth_)) {
            kernel_module::RequestOwnerLifetimeToken existing;
            if (!kernel_module::maplesu_request_owner_lifetime_capture(existing)) {
                owns_session_ = true;
                // Retire stale credentials before opening this worker's fresh session.
                RETURN_IF_ERROR(kernel_module::maplesu_request_owner_close());
                if (kernel_module::maplesu_request_auth_helper(auth_))
                    RETURN_IF_ERROR(kernel_module::maplesu_request_helper_open());
            }
        }
        RETURN_IF_ERROR(default_modules::module_root::set_current_root(
            !kernel_module::maplesu_request_auth_helper(auth_)));
        if (kernel_module::maplesu_request_auth_legacy(auth_)) return KModErr::OK;

        kernel_module::RequestDeploymentNamespace deployment;
        RETURN_IF_ERROR(kernel_module::maplesu_request_owner_deployment(deployment));
        if (getuid() != 0 || deployment.binding.actual_uid != 0 ||
            !kernel_module::maplesu_request_owner_binding_matches(deployment.binding))
            return KModErr::ERR_MODULE_ROOT_KEY;
        StorageLocator locator;
        static_assert(sizeof(deployment.record.name) == 16);
        std::memcpy(locator.kernel_component, deployment.record.name, 16);
        std::memcpy(locator.system_component, deployment.record.name, 16);
        locator.system_component[16] = locator.kernel_component[0];
        scope_.emplace(locator, module_id_.empty() ? nullptr : module_id_.c_str());
        return scope_->error();
    }

    KModErr select_module(const char* module_id32) {
        if (process_ != getpid() || auth_ != kernel_module::maplesu_request_auth_state())
            return KModErr::ERR_MODULE_ROOT_KEY;
        if (kernel_module::maplesu_request_auth_legacy(auth_)) return KModErr::OK;
        if (!scope_) return KModErr::ERR_MODULE_ROOT_KEY;
        if (!module_id32 || strnlen(module_id32, 1024) >= 1024) return KModErr::ERR_MODULE_PARAM;
        std::string selected(module_id32);
        RETURN_IF_ERROR(scope_->select_module(selected.c_str()));
        module_id_.swap(selected);
        return KModErr::OK;
    }

    KModErr close_checked() {
        if (process_ != getpid()) return KModErr::ERR_MODULE_ROOT_KEY;
        scope_.reset();
        if (owns_session_) {
            owns_session_ = false;
            return kernel_module::maplesu_request_owner_close();
        }
        return KModErr::OK;
    }

    void close() {
        if (process_ != getpid()) return;
        kernel_module::CleanupReceiptScope diagnostic;
        (void)close_checked();
    }

private:
    pid_t process_;
    uint64_t auth_;
    std::string module_id_;
    std::optional<StorageScope> scope_;
    bool owns_session_ = false;
};

} // namespace maplesu::storage
