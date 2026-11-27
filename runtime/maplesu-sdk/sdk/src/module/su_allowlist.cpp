// su allowlist implementation.
//
#include "kernel_module_kit_umbrella.h"
#include "maplesu_storage_private.h"

#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

// 私有运行时文件接口（由本 SDK 的私有 DAC / 白名单成员提供）：
//   * chmod 只接受运行时校验通过的文件，并把权限收紧到受限默认值；
//   * allowlist_create 以独占方式创建空白名单文件；
//   * allowlist_save 原子落盘（临时文件 + rename + 目录同步）。
extern "C" int maplesu_private_chmod(const char* path, unsigned int mode);
extern "C" int maplesu_private_allowlist_create(const char* path);
extern "C" bool maplesu_private_allowlist_save(const std::string* path,
                                               const std::vector<std::string>* apps);

namespace {

// 文件名常量

// 去掉首尾空白后拷贝。
//
// `_ZN12_GLOBAL__N_1...` = 文件作用域匿名 namespace 里的函数（不是 static）。
//   `%22 = icmp ne i8 %c, 32`  `%23 = add nsw i32 %c, -14`  `%24 = icmp ult i32 %23, -5`
//   (c-14) < (unsigned)-5  <=>  (c-14) 不属于 {0xFFFFFFFB..0xFFFFFFFF} <=> c 不属于 {9..13}
std::string trim_copy(const std::string& s) {
    std::size_t begin = 0;
    while (begin < s.size()) {
        const char c = s[begin];
        const bool is_space = (c == ' ') || (c >= '\t' && c <= '\r');
        if (!is_space) break;
        ++begin;
    }

    std::size_t end = s.size();
    while (end > begin) {
        const char c = s[end - 1];
        const bool is_space = (c == ' ') || (c >= '\t' && c <= '\r');
        if (!is_space) break;
        --end;
    }
    return s.substr(begin, end - begin);
}

}  // namespace

#ifndef XATTR_NAME_SELINUX
#define XATTR_NAME_SELINUX "security.selinux"
#endif
#ifndef SELINUX_SYSTEM_FILE_FLAG
#define SELINUX_SYSTEM_FILE_FLAG "u:object_r:system_file:s0"
#endif

namespace {

// 收紧白名单文件权限并打上 system_file 标签：权限由运行时私有校验器设置
// （它拒绝非运行时拥有的 inode），成功后补 SELinux 标签。
bool set_allowlist_selinux_access_mode(const char* path) {
    if (maplesu_private_chmod(path, 0600) != 0) {
        return false;
    }
    return ::setxattr(path, XATTR_NAME_SELINUX, SELINUX_SYSTEM_FILE_FLAG,
                      sizeof(SELINUX_SYSTEM_FILE_FLAG), 0) == 0;
}

}  // namespace

namespace default_modules {
namespace module_su_redirect {

// SU 白名单：一条私有文件路径 + 一份已排序去重的包名表。
class SuAllowList {
public:
    SuAllowList();

    // 从磁盘装载（不存在则建空文件）。
    bool load();

    // Current deployment system directory plus the private authorization file.
    std::string get_su_allowlist_path();

    // 返回去重后的当前表（不改动成员）。
    std::vector<std::string> get_apps();

    // 去重（保序，保留首次出现）。
    std::vector<std::string> filter_duplicates(const std::vector<std::string>& apps);

    // 落盘：写 .tmp 再 rename，成功后 sync()。
    bool save_all() const;

    bool add_app(const std::string& app);
    bool remove_app(const std::string& app);
    bool set_app_list(const std::vector<std::string>& apps);
    bool clear_app();

private:
    std::string path_;
    std::vector<std::string> apps_;
};

// 定义放在 .cpp 里（不是类内 inline），保证该成员在归档里有独立符号 C1/C2。
SuAllowList::SuAllowList() = default;

std::string SuAllowList::get_su_allowlist_path() {
    std::string directory;
    if (is_failed(maplesu::storage::current_system_directory(directory))) return {};
    return directory + "su_allowlist.txt";
}

std::vector<std::string> SuAllowList::filter_duplicates(const std::vector<std::string>& apps) {
    std::vector<std::string> out;
    out.reserve(apps.size());

    std::unordered_set<std::string> seen;
    seen.reserve(apps.size() * 2 + 1);
    for (const std::string& app : apps) {
        // insert 的 second == true 表示首次出现。
        if (seen.insert(app).second) {
            out.push_back(app);
        }
    }
    out.shrink_to_fit();
    return out;
}

std::vector<std::string> SuAllowList::get_apps() {
    return filter_duplicates(apps_);
}

bool SuAllowList::load() {
    path_ = get_su_allowlist_path();
    if (path_.empty()) return false;
    apps_.clear();

    std::error_code ec;
    if (!std::filesystem::exists(path_, ec)) {
        // 白名单文件不存在时以独占方式创建一个空文件；创建即完成初始化。
        return maplesu_private_allowlist_create(path_.c_str()) != 0;
    }

    if (!set_allowlist_selinux_access_mode(path_.c_str())) {
        return false;
    }

    std::ifstream ifs(path_, std::ios::in);
    if (!ifs) {
        return false;
    }
    std::string line;
    while (std::getline(ifs, line)) {
        const std::string trimmed = trim_copy(line);
        if (!trimmed.empty()) {
            apps_.push_back(trimmed);
        }
    }

    apps_ = filter_duplicates(apps_);
    return true;
}

bool SuAllowList::save_all() const {
    return maplesu_private_allowlist_save(&path_, &apps_);
}

bool SuAllowList::add_app(const std::string& app) {
    std::string trimmed = trim_copy(app);
    if (trimmed.empty()) {
        return false;
    }
    if (std::find(apps_.begin(), apps_.end(), trimmed) != apps_.end()) {
        return true;  // 已存在，不重复落盘
    }
    apps_.push_back(std::move(trimmed));
    return save_all();
}

bool SuAllowList::remove_app(const std::string& app) {
    const std::string trimmed = trim_copy(app);
    if (trimmed.empty()) {
        return false;
    }

    const std::size_t count_before = apps_.size();
    apps_.erase(std::remove(apps_.begin(), apps_.end(), trimmed), apps_.end());
    if (apps_.size() == count_before) {
        return true;  // 本来就没有这一条
    }
    return save_all();
}

bool SuAllowList::set_app_list(const std::vector<std::string>& apps) {
    apps_ = apps;
    return save_all();
}

bool SuAllowList::clear_app() {
    apps_.clear();
    return save_all();
}

}  // namespace module_su_redirect
}  // namespace default_modules
