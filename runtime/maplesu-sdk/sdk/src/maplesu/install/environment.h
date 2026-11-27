
//
#pragma once

#include <string>

#include "core.h"
#include "private_maple_runtime.h"
#include "maplesu_storage_private.h"

namespace maplesu::install::environment {

inline std::string kernel_file_dir_path() {
    std::string directory;
    if (storage::current_kernel_directory(directory) != KModErr::OK) return {};
    return directory;
}

inline std::string kernel_file_dir_name() {
    const std::string directory = kernel_file_dir_path();
    if (directory.empty()) return {};
    return directory.substr(6, directory.size() - 7);
}

inline std::string kernel_file_path(const char* component, const char* subdir = nullptr) {
    std::string path = kernel_file_dir_path();
    if (path.empty() || component == nullptr) return {};
    if (subdir != nullptr) path.append(subdir).push_back('/');
    return path + component;
}

}  // namespace maplesu::install::environment
