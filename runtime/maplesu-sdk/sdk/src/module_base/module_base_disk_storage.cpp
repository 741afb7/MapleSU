// module base disk storage implementation.
//
#include "kernel_module_kit_umbrella.h"

#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <ios>
#include <string>
#include <system_error>
#include <vector>

#include "file_utils/file_utils.h"
#include "module_base_disk_storage.h"
#include "maplesu_storage_private.h"
#include "../maplesu/compat/storage_legacy.h"
#include "../maplesu/module/path_contract.h"
#include <utility>

namespace maplesu::storage {
namespace {
thread_local StorageScope* selected_scope = nullptr;

bool owner_component(const char* text, size_t length) {
    if (strnlen(text, length + 1) != length) return false;
    for (size_t index = 0; index < length; ++index) {
        const unsigned char c = text[index];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')))
            return false;
    }
    return true;
}

bool owner_module(const char* text) {
    if (!text) return false;
    const size_t length = strnlen(text, 1024);
    return length < 1024 && module_paths::valid_id(std::string_view(text, length));
}
}

StorageScope::StorageScope(const StorageLocator& locator, const char* module_id32) {
    auto* previous = selected_scope;
    if (!owner_component(locator.kernel_component, 16) ||
        !owner_component(locator.system_component, 17) ||
        std::memcmp(locator.kernel_component, locator.system_component, 16) ||
        locator.system_component[16] != locator.kernel_component[0] ||
        (module_id32 && !owner_module(module_id32))) return;
    if (!kernel_module::maplesu_request_owner_lifetime_capture(lifetime_)) {
        error_ = KModErr::ERR_MODULE_ROOT_KEY;
        return;
    }
    context_.locator = locator;
    if (module_id32) context_.module_id = module_id32;
    if (!kernel_module::maplesu_request_owner_lifetime_matches(lifetime_)) {
        error_ = KModErr::ERR_MODULE_ROOT_KEY;
        clear();
        return;
    }
    if (previous && previous->selected_ &&
        kernel_module::maplesu_request_owner_lifetime_matches(previous->lifetime_))
        previous_ = previous;
    selected_scope = this;
    selected_ = true;
    error_ = KModErr::OK;
}

StorageScope::~StorageScope() { clear(); }

KModErr StorageScope::select_module(const char* module_id32) {
    if (!owner_module(module_id32)) return KModErr::ERR_MODULE_PARAM;
    if (!selected_ || selected_scope != this ||
        !kernel_module::maplesu_request_owner_lifetime_matches(lifetime_))
        return KModErr::ERR_MODULE_ROOT_KEY;
    context_.module_id = module_id32;
    return KModErr::OK;
}

void StorageScope::clear() {
    if (selected_ && selected_scope == this) {
        selected_scope = previous_ && previous_->selected_ &&
            kernel_module::maplesu_request_owner_lifetime_matches(previous_->lifetime_) ? previous_ : nullptr;
    }
    selected_ = false;
    previous_ = nullptr;
    lifetime_ = {};
}

KModErr current_context(StorageContext& out) {
    out = {};
    const uint64_t auth = kernel_module::maplesu_request_auth_state();
    if (!kernel_module::maplesu_request_auth_usable(auth)) return KModErr::ERR_MODULE_ROOT_KEY;
    StorageContext candidate;
    if (kernel_module::maplesu_request_auth_legacy(auth)) {
        RETURN_IF_ERROR(compat::current_legacy_storage_context(candidate));
    } else {
        const auto* scope = selected_scope;
        if (!scope || !scope->selected_ || is_failed(scope->error_)) return KModErr::ERR_MODULE_PARAM;
        if (!kernel_module::maplesu_request_owner_lifetime_matches(scope->lifetime_))
            return KModErr::ERR_MODULE_ROOT_KEY;
        candidate = scope->context_;
        if (!kernel_module::maplesu_request_owner_lifetime_matches(scope->lifetime_))
            return KModErr::ERR_MODULE_ROOT_KEY;
    }
    if (auth != kernel_module::maplesu_request_auth_state()) return KModErr::ERR_MODULE_ROOT_KEY;
    out = std::move(candidate);
    return KModErr::OK;
}

KModErr current_kernel_directory(std::string& out) {
    out.clear();
    StorageContext context;
    RETURN_IF_ERROR(current_context(context));
    out = std::string("/data/") + context.locator.kernel_component + "/";
    return KModErr::OK;
}

KModErr current_system_directory(std::string& out) {
    out.clear();
    StorageContext context;
    RETURN_IF_ERROR(current_context(context));
    out = std::string("/data/") + context.locator.system_component + "/";
    return KModErr::OK;
}
} // namespace maplesu::storage

namespace {
constexpr char kStorageDirName[] = "storage";
constexpr size_t kSelinuxLabelLen = 26;                // strlen 含 NUL

// getxattr 两次（先问长度，再读内容）；读到的标签去掉结尾 NUL 后与目标比较。
bool is_selinux_label_expected(const char* path) {
    if (path == nullptr) {
        return false;
    }
    const ssize_t len = ::getxattr(path, "security.selinux", nullptr, 0);
    if (len < 1) {
        return false;
    }
    std::vector<char> label(static_cast<size_t>(len));
    const ssize_t got = ::getxattr(path, "security.selinux", label.data(),
                                   static_cast<size_t>(len));
    if (got < 0) {
        return false;
    }
    std::string text;
    text.assign(label.data(), static_cast<size_t>(got));
    if (!text.empty() && text.back() == '\0') {
        text.pop_back();
    }
    return text == "u:object_r:system_file:s0";
}

}  // namespace

namespace kernel_module {

static KModErr get_key_file_path(const char* key, std::string& out_path, bool for_write) {
    maplesu::storage::StorageContext context;
    RETURN_IF_ERROR(maplesu::storage::current_context(context));
    if (context.module_id.empty()) return KModErr::ERR_MODULE_PARAM;
    const std::filesystem::path storage_dir =
        std::filesystem::path(std::string("/data/") + context.locator.system_component + "/") /
        std::filesystem::path(kStorageDirName) / std::filesystem::path(context.module_id) /
        std::filesystem::path("");
    const std::string storage_dir_text = storage_dir.string();

    if (for_write) {
        if (!file_utils::file_exists(std::filesystem::path(storage_dir_text))) {
            std::error_code ec;
            std::filesystem::create_directories(std::filesystem::path(storage_dir_text), ec);
        }
        if (!file_utils::file_exists(std::filesystem::path(storage_dir_text))) {
            return KModErr::ERR_MODULE_CREATE_DIR;
        }
        if (!file_utils::set_file_selinux_access_mode(storage_dir_text.c_str())) {
            return KModErr::ERR_SET_FILE_SELINUX;
        }
    }

    const std::filesystem::path file_path =
        std::filesystem::path(storage_dir_text) / std::filesystem::path(key);
    out_path = file_path.string();
    return KModErr::OK;
}

//   打开 ofstream(out|trunc|binary) → 写 → 校验 rdstate → 补 SELinux 标签。
//   任何写路径失败都是 ERR_MODULE_STORAGE_WRITE；标签失败是 ERR_SET_FILE_SELINUX。
static KModErr __write_disk_blob(const char* key, const void* buf, uint64_t size) {
    std::string path;
    const KModErr path_error = get_key_file_path(key, path, true);
    if (is_failed(path_error)) {
        return path_error;
    }
    if (path.c_str() == nullptr) {
        return KModErr::ERR_MODULE_STORAGE_WRITE;
    }

    constexpr auto kOpenMode =
        std::ios::out | std::ios::trunc | std::ios::binary;
    {
        std::ofstream ofs(path.c_str(), kOpenMode);
        if ((ofs.rdstate() & (std::ios::badbit | std::ios::failbit)) != 0) {
            return KModErr::ERR_MODULE_STORAGE_WRITE;
        }
        if (buf != nullptr && size != 0) {
            ofs.write(static_cast<const char*>(buf),
                      static_cast<std::streamsize>(size));
        }
        if (ofs.rdstate() != 0) {
            return KModErr::ERR_MODULE_STORAGE_WRITE;
        }
    }

    if (is_selinux_label_expected(path.c_str())) {
        return maplesu_private_chmod(path.c_str(), 0) == 0
            ? KModErr::OK : KModErr::ERR_SET_FILE_SELINUX;
    }
    if (!file_utils::set_file_selinux_access_mode(path.c_str())) {
        return KModErr::ERR_SET_FILE_SELINUX;
    }
    return KModErr::OK;
}

//   路径 → 存在性 → 打开 → seekg(end)/tellg 求长度 → seekg(begin) → resize →
//   read 整块 → 校验 rdstate。
static KModErr __read_disk_blob(const char* key, std::vector<uint8_t>& out) {
    std::string path;
    const KModErr path_error = get_key_file_path(key, path, false);
    if (is_failed(path_error)) {
        return path_error;
    }
    std::error_code status_error;
    const bool present = std::filesystem::exists(std::filesystem::path(path), status_error);
    if (status_error) {
        return KModErr::ERR_MODULE_STORAGE_READ;
    }
    if (!present) {
        return KModErr::ERR_MODULE_STORAGE_NOT_FOUND;
    }

    std::ifstream ifs(path.c_str(), std::ios::binary);
    if ((ifs.rdstate() & (std::ios::badbit | std::ios::failbit)) != 0) {
        return KModErr::ERR_MODULE_STORAGE_READ;
    }
    ifs.seekg(0, std::ios::end);
    const std::streamoff size = static_cast<std::streamoff>(ifs.tellg());
    if (size < 0) {
        return KModErr::ERR_MODULE_STORAGE_READ;
    }
    ifs.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(size));
    ifs.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(size));
    if (ifs.rdstate() != 0) {
        return KModErr::ERR_MODULE_STORAGE_READ;
    }
    return KModErr::OK;
}

// 标量读取的公共骨架：长度必须严格等于 sizeof(T)，否则 ERR_MODULE_STORAGE_TYPE。
template <typename T>
static KModErr read_scalar(const char* key, T& out) {
    std::vector<uint8_t> blob;
    const KModErr read_error = __read_disk_blob(key, blob);
    if (is_failed(read_error)) {
        return read_error;
    }
    if (blob.size() != sizeof(T)) {
        return KModErr::ERR_MODULE_STORAGE_TYPE;
    }
    std::memcpy(&out, blob.data(), sizeof(T));
    return KModErr::OK;
}

// 写：标量都是"取地址 + sizeof"，字符串带结尾 NUL，blob 原样。
KModErr write_bool_disk_storage(const char* key, bool value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_int8_disk_storage(const char* key, int8_t value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_uint8_disk_storage(const char* key, uint8_t value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_int32_disk_storage(const char* key, int32_t value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_uint32_disk_storage(const char* key, uint32_t value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_int64_disk_storage(const char* key, int64_t value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_uint64_disk_storage(const char* key, uint64_t value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_float_disk_storage(const char* key, float value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_double_disk_storage(const char* key, double value) {
    return __write_disk_blob(key, &value, sizeof(value));
}

KModErr write_string_disk_storage(const char* key, const char* value) {
    return __write_disk_blob(key, value, std::strlen(value) + 1);
}

KModErr write_blob_disk_storage(const char* key, const void* data, uint64_t size) {
    return __write_disk_blob(key, data, size);
}

// 读：标量长度必须精确匹配，否则 ERR_MODULE_STORAGE_TYPE。
KModErr read_bool_disk_storage(const char* key, bool& out) {
    std::vector<uint8_t> blob;
    const KModErr read_error = __read_disk_blob(key, blob);
    if (is_failed(read_error)) {
        return read_error;
    }
    if (blob.size() != sizeof(out)) {
        return KModErr::ERR_MODULE_STORAGE_TYPE;
    }
    std::memcpy(&out, blob.data(), sizeof(out));
    return KModErr::OK;
}

KModErr read_int8_disk_storage(const char* key, int8_t& out) { return read_scalar(key, out); }
KModErr read_uint8_disk_storage(const char* key, uint8_t& out) { return read_scalar(key, out); }
KModErr read_int32_disk_storage(const char* key, int32_t& out) { return read_scalar(key, out); }
KModErr read_uint32_disk_storage(const char* key, uint32_t& out) { return read_scalar(key, out); }
KModErr read_int64_disk_storage(const char* key, int64_t& out) { return read_scalar(key, out); }
KModErr read_uint64_disk_storage(const char* key, uint64_t& out) { return read_scalar(key, out); }
KModErr read_float_disk_storage(const char* key, float& out) { return read_scalar(key, out); }
KModErr read_double_disk_storage(const char* key, double& out) { return read_scalar(key, out); }

KModErr read_string_disk_storage_with_cb(const char* key, void (*cb)(const char*)) {
    std::vector<uint8_t> blob;
    const KModErr read_error = __read_disk_blob(key, blob);
    if (is_failed(read_error)) {
        return read_error;
    }
    const std::string text(blob.begin(), blob.end());
    cb(text.c_str());
    return KModErr::OK;
}

KModErr read_blob_disk_storage_with_cb(const char* key, void (*cb)(const void*, uint64_t)) {
    std::vector<uint8_t> blob;
    const KModErr read_error = __read_disk_blob(key, blob);
    if (is_failed(read_error)) {
        return read_error;
    }
    cb(blob.empty() ? nullptr : blob.data(), static_cast<uint64_t>(blob.size()));
    return KModErr::OK;
}

}  // namespace kernel_module
