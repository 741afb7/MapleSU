#pragma once
#include "maplesu_storage_private.h"
#include <filesystem>
#include <string>
namespace maplesu::sdk {
static inline std::string get_system_file_dir_name() {
    maplesu::storage::StorageContext context;
    if (is_failed(maplesu::storage::current_context(context))) return {};
    return context.locator.system_component;
}
static inline std::string get_system_file_dir_path() {
    std::string path;
    if (is_failed(maplesu::storage::current_system_directory(path))) return {};
    return path;
}
}
