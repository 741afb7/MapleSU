#pragma once

#include "maplesu_request_private.h"
#include <string>

namespace maplesu::storage {

struct StorageLocator {
    char kernel_component[17]{};
    char system_component[18]{};
};

struct StorageContext {
    StorageLocator locator;
    std::string module_id;
};

// A locator supplies paths only. The worker owns its management session and
// must leave this scope before owner_close; the scope never grants authority.
class __attribute__((visibility("hidden"))) StorageScope final {
public:
    // A null ID selects only the deployment directories, never module storage.
    StorageScope(const StorageLocator& locator, const char* module_id32);
    ~StorageScope();
    KModErr error() const { return error_; }
    KModErr select_module(const char* module_id32);
    void clear();
    StorageScope(const StorageScope&) = delete;
    StorageScope& operator=(const StorageScope&) = delete;
    StorageScope(StorageScope&&) = delete;
    StorageScope& operator=(StorageScope&&) = delete;
private:
    friend KModErr current_context(StorageContext& out);
    StorageContext context_;
    kernel_module::RequestOwnerLifetimeToken lifetime_{};
    StorageScope* previous_ = nullptr;
    KModErr error_ = KModErr::ERR_MODULE_PARAM;
    bool selected_ = false;
};

// Paths require the explicit current worker scope and its live session.
__attribute__((visibility("hidden")))
KModErr current_context(StorageContext& out);
__attribute__((visibility("hidden")))
KModErr current_kernel_directory(std::string& out);
__attribute__((visibility("hidden")))
KModErr current_system_directory(std::string& out);

} // namespace maplesu::storage
