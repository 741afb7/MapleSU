// MapleSU unsafe hide dir implementation.
//
// 目录隐藏：在当前受验证部署 namespace 定位的私有目录下铺开安装目录树，
// 删除整棵私有目录，以及清理旧版本 SDK 遗留的同类目录。
//
#include "../maplesu/install/installation_state.h"
#include "maplesu/compat/persisted_inputs.h"
#include "kernel_module_kit_umbrella.h"

#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#include <dirent.h>
#include <sys/stat.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

#include "file_utils/file_utils.h"
#include "private_maple_runtime.h"

namespace file_utils {

// 删除整棵路径；删除后仍可见即视为失败。
static bool delete_path(const std::filesystem::path& path) {
    std::error_code error;
    (void)std::filesystem::remove_all(path, error);
    return !file_utils::file_exists(path);
}

}  // namespace file_utils

namespace {

// 目录项：路径 + 该目录是否使用内核侧 SELinux 上下文。
struct DirSpec {
    std::string path;
    int kernel_context;
};

}  // namespace

namespace maplesu::sdk {

static std::string get_kernel_file_dir_name(const storage::StorageContext& context) {
    return context.locator.kernel_component;
}

static std::string get_system_file_dir_name(const storage::StorageContext& context) {
    return context.locator.system_component;
}

static std::string get_kernel_file_dir_path(const storage::StorageContext& context) {
    return "/data/" + get_kernel_file_dir_name(context) + "/";
}

static std::string get_system_file_dir_path(const storage::StorageContext& context) {
    return "/data/" + get_system_file_dir_name(context) + "/";
}

// 末尾的空 path 追加一个目录分隔符，得到 ".../modules/" 这类带斜杠前缀。
static std::string get_module_list_dir_path(const storage::StorageContext& context) {
    const std::filesystem::path base(get_system_file_dir_path(context));
    return (base / std::filesystem::path("modules") / std::filesystem::path("")).string();
}

static std::string get_disk_storage_dir_path(const storage::StorageContext& context) {
    const std::filesystem::path base(get_system_file_dir_path(context));
    return (base / std::filesystem::path("storage") / std::filesystem::path("")).string();
}

static std::string get_modules_new_state_dir_path(const storage::StorageContext& context) {
    const std::filesystem::path base(get_system_file_dir_path(context));
    return (base / std::filesystem::path("modules_new_state")).string();
}

static std::string get_modules_new_desc_dir_path(const storage::StorageContext& context) {
    const std::filesystem::path base(get_system_file_dir_path(context));
    return (base / std::filesystem::path("modules_new_desc")).string();
}

static std::string get_offset_cache_dir_path(const storage::StorageContext& context) {
    const std::filesystem::path base(get_system_file_dir_path(context));
    return (base / std::filesystem::path("offset_cache")).string();
}

// 收紧目录权限：优先走运行时私有校验器，失败时退回 POSIX 权限调用，
// 保证目录不会被留在创建时的宽松权限上。
static bool set_private_mode(const char* path, unsigned int mode) {
    if (maplesu_private_chmod(path, mode) == 0) {
        return true;
    }
    return ::chmod(path, static_cast<mode_t>(mode)) == 0;
}

// 设置文件/目录的 SELinux 标签；标签值同时决定字符串长度，含结尾 NUL。
static bool set_file_context(const char* path, int kernel_context) {
    const char* context = kernel_context ? "u:object_r:kernel:s0" : "u:object_r:system_file:s0";
    return ::setxattr(path, "security.selinux", context, std::strlen(context) + 1, 0) == 0;
}

KModErr unsafe_create_hide_dir() {
    storage::StorageContext context;
    const KModErr storage_error = storage::current_context(context);
    if (storage_error != KModErr::OK) return storage_error;

    const DirSpec dirs[] = {
        {get_kernel_file_dir_path(context), 1},
        {get_system_file_dir_path(context), 0},
        {get_module_list_dir_path(context), 0},
        {get_modules_new_state_dir_path(context), 0},
        {get_modules_new_desc_dir_path(context), 0},
        {get_disk_storage_dir_path(context), 0},
        {get_offset_cache_dir_path(context), 0},
    };
    constexpr std::size_t kDirCount = sizeof(dirs) / sizeof(dirs[0]);

    for (std::size_t index = 0; index < kDirCount; ++index) {
        const std::string& path = dirs[index].path;
        const char* path_str = path.c_str();
        if (!file_utils::file_exists(std::filesystem::path(path_str))) {
            std::error_code error;
            (void)std::filesystem::create_directories(std::filesystem::path(path_str), error);
        }
        if (!file_utils::file_exists(std::filesystem::path(path_str))) {
            return KModErr::ERR_MODULE_CREATE_DIR;
        }
        // 系统工具根目录保留遍历位，其余目录保持宿主专用权限。
        const unsigned int mode = index == 1 ? 0711u : 0700u;
        if (!set_private_mode(path_str, mode)) {
            return KModErr::ERR_SET_FILE_SELINUX;
        }
        if (!set_file_context(path_str, dirs[index].kernel_context)) {
            return KModErr::ERR_SET_FILE_SELINUX;
        }
    }
    return KModErr::OK;
}

KModErr unsafe_del_hide_dir() {
    storage::StorageContext context;
    const KModErr storage_error = storage::current_context(context);
    if (storage_error != KModErr::OK) return storage_error;

    const KModErr set_root_err = default_modules::module_root::set_current_root(true);
    if (set_root_err != KModErr::OK) {
        return set_root_err;
    }

    const std::string kernel_file_dir = get_kernel_file_dir_path(context);
    const std::string system_file_dir = get_system_file_dir_path(context);
    const std::string module_list_dir = get_module_list_dir_path(context);
    const std::string disk_storage_dir = get_disk_storage_dir_path(context);

    install::state::Mutation installation;
    auto observed = installation.open();
    install::state::diagnose("open-delete", observed);
    if (install::state::mutation_open_failed(observed)) return KModErr::ERR_MODULE_WRITE_FILE;
    if (installation.ready()) {
        const bool required = installation.revocation_required();
        observed = installation.revoke();
        install::state::diagnose("revoke-delete", observed);
        if (required && observed.code) return KModErr::ERR_MODULE_WRITE_FILE;
    }

    // 由叶子到根删除，保证父目录删除时不再有子项。
    if (!file_utils::delete_path(std::filesystem::path(disk_storage_dir))) {
        return KModErr::ERR_MODULE_DEL_DIR;
    }
    if (!file_utils::delete_path(std::filesystem::path(module_list_dir))) {
        return KModErr::ERR_MODULE_DEL_DIR;
    }
    if (!file_utils::delete_path(std::filesystem::path(system_file_dir))) {
        return KModErr::ERR_MODULE_DEL_DIR;
    }
    if (!file_utils::delete_path(std::filesystem::path(kernel_file_dir))) {
        return KModErr::ERR_MODULE_DEL_DIR;
    }
    return KModErr::OK;
}

KModErr unsafe_clean_older_hide_dir() {
    storage::StorageContext context;
    const KModErr storage_error = storage::current_context(context);
    if (storage_error != KModErr::OK) return storage_error;

    const KModErr set_root_err = default_modules::module_root::set_current_root(true);
    if (set_root_err != KModErr::OK) {
        return set_root_err;
    }

    const std::string system_file_dir_name = get_system_file_dir_name(context);
    const std::string kernel_file_dir_name = get_kernel_file_dir_name(context);
    const std::filesystem::path data_dir("/data");

    DIR* dir = ::opendir(data_dir.c_str());
    if (dir == nullptr) {
        return KModErr::ERR_MODULE_OPEN_DIR;
    }

    // /data 下的历史私有目录以 su / MapleSU.sdk.ver 作为自身标记；
    // 名字不是本代根密钥派生的目录才属于旧版本，收集后统一删除。
    std::vector<std::filesystem::path> older_dirs;
    for (;;) {
        const struct dirent* entry = ::readdir(dir);
        if (entry == nullptr) {
            break;
        }
        const char* name = entry->d_name;
        if (name[0] == '.' && (name[1] == '\0' || (name[1] == '.' && name[2] == '\0'))) {
            continue;
        }
        if (entry->d_type != DT_DIR) {
            continue;
        }
        if (std::strlen(name) < kernel_file_dir_name.size()) {
            continue;
        }
        if (system_file_dir_name == name || kernel_file_dir_name == name) {
            continue;
        }
        std::filesystem::path candidate = data_dir / std::filesystem::path(name);
        if (file_utils::file_exists(std::filesystem::path(
                    (candidate / std::filesystem::path("su")).string())) ||
            file_utils::file_exists(std::filesystem::path(
                    (candidate / std::filesystem::path(maplesu::compat::kEnvironmentVersionFile)).string()))) {
            older_dirs.emplace_back(std::move(candidate));
        }
    }
    (void)::closedir(dir);

    for (const std::filesystem::path& older : older_dirs) {
        install::state::Mutation retirement;
        auto observed = retirement.open_retired(older.string());
        if (!observed.code) observed = retirement.revoke();
        install::state::diagnose("revoke-older", observed);
        // A live recorded generation is retired before its backing directory.
        const bool tracking_unsupported = observed.phase == install::state::Phase::Read &&
            observed.code == EOPNOTSUPP && !retirement.ready();
        if (observed.code && observed.code != ENOENT && !tracking_unsupported)
            return KModErr::ERR_MODULE_WRITE_FILE;
        (void)file_utils::delete_path(std::filesystem::path(older.string()));
    }
    return KModErr::OK;
}

}  // namespace maplesu::sdk

#include "../maplesu/compat/install_hide_dir_exports_legacy_abi.h"
