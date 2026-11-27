// Private declarations for the su redirect module implementation.
//
#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <sys/syscall.h>
#include <unistd.h>
#include "su_auth_result_private.h"

// The kit umbrella header carries header-only helpers that are maintained
// together with the public SDK interface; their warnings are not ours to fix
// from a single translation unit.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-parameter"
#pragma clang diagnostic ignored "-Wunused-variable"
#pragma clang diagnostic ignored "-Wunused-function"
#pragma clang diagnostic ignored "-Woverloaded-virtual"
#include "kernel_module_kit_umbrella.h"
#pragma clang diagnostic pop

// Persists the package list and publishes the matching kernel UID table.
// `required_package` must resolve to a live UID; `refresh_existing` rebuilds
// the UID table from the regenerated packages.list even when the package set
// did not change.
extern "C" KModErr maplesu_write_su_allowlist(const std::vector<std::string>& packages,
                                              const char* required_package,
                                              bool refresh_existing);

namespace kernel_module {

// Resolves the do_execve symbol and reports how this kernel passes the
// filename argument to it:
//   out_kaddr        kernel address of do_execve
//   out_filename_reg index of the register holding the filename pointer
//   out_single_ptr   true when the kernel passes a single char pointer instead
//                    of a user buffer
KModErr get_do_execve_info(uint64_t& out_kaddr, char& out_filename_reg, bool& out_single_ptr);

}  // namespace kernel_module

// Result of resolving the do_execve kernel symbol.
struct ExecveSymInfo {
    uint64_t kaddr = 0;
    char filename_reg = 0;
    bool single_ptr = false;
} __attribute__((packed));

// Kernel symbols the redirect hooks are installed on.
struct KernelSymbolOffset {
    ExecveSymInfo execve;
    uint64_t sys_newfstatat_kaddr = 0;
    uint64_t sys_statx_kaddr = 0;
    uint64_t sys_faccessat_kaddr = 0;
    uint64_t sys_faccessat2_kaddr = 0;
};

namespace syscall_probe {

// Whether this kernel implements statx(2). The probe treats ENOSYS as absent
// and keeps the answer for the lifetime of the process.
inline bool has_statx() {
    static bool ok = []() noexcept {
        errno = 0;
        const long result = syscall(__NR_statx, AT_FDCWD, "/proc/self/exe", 0, 0, nullptr);
        return result >= 0 || errno != ENOSYS;
    }();
    return ok;
}

// Whether this kernel implements faccessat2(2). The probe treats ENOSYS as
// absent and keeps the answer for the lifetime of the process.
inline bool has_faccessat2() {
    static bool ok = []() noexcept {
        errno = 0;
        const long result = syscall(__NR_faccessat2, AT_FDCWD, "/proc/self/exe", 0, 0);
        return result >= 0 || errno != ENOSYS;
    }();
    return ok;
}

}  // namespace syscall_probe

namespace default_modules {
namespace module_su_redirect {

// The persisted su allowlist contains legacy packages and full-UID decisions.
// Defined by the su allowlist unit.
class SuAllowList {
public:
    SuAllowList();

    // Loads the list from disk, creating an empty file when it is missing.
    bool load();

    // Returns the persisted entries, de-duplicated and in list order.
    std::vector<std::string> get_apps();

    // Appends `app` and persists the new list.
    bool add_app(const std::string& app);

    // Removes every occurrence of `app` and persists the new list.
    bool remove_app(const std::string& app);

    // Removes every entry and persists the empty list.
    bool clear_app();

    bool set_app_list(const std::vector<std::string>& apps);

private:
    std::string path_;
    std::vector<std::string> apps_;
};

static_assert(sizeof(SuAllowList) == 48, "SuAllowList layout constraint violated");

// Allowlist shared by the module entry points for the whole process lifetime.
extern SuAllowList g_suAllowList;

// Generates and installs one kernel hook that makes an allowed process see the
// fake su file. Members are filled in by the patch units.
class PatchBase {
public:
    PatchBase(uint32_t cred_offset, uint32_t cred_uid_offset, uint32_t cred_euid_offset,
              const char* fake_su_path);
    PatchBase(const PatchBase& other);
    ~PatchBase();

private:
    uint32_t cred_offset_;
    uint32_t cred_uid_offset_;
    uint32_t cred_euid_offset_;
    std::string fake_su_path_;
};

static_assert(sizeof(PatchBase) == 40, "PatchBase layout constraint violated");

class PatchDoExecve : public PatchBase {
public:
    PatchDoExecve(const PatchBase& base, const ExecveSymInfo& info);
    ~PatchDoExecve();

    KModErr patch_do_execve(uint64_t uid_control_kaddr);

private:
    ExecveSymInfo info_;
};

static_assert(sizeof(PatchDoExecve) == 56, "PatchDoExecve layout constraint violated");

class PatchSysNewfstatat : public PatchBase {
public:
    PatchSysNewfstatat(const PatchBase& base, uint64_t sys_newfstatat_kaddr);
    ~PatchSysNewfstatat();

    KModErr patch_sys_newfstatat(uint64_t uid_control_kaddr);

private:
    uint64_t sys_newfstatat_kaddr_;
};

static_assert(sizeof(PatchSysNewfstatat) == 48, "PatchSysNewfstatat layout constraint violated");

class PatchSysStatx : public PatchBase {
public:
    PatchSysStatx(const PatchBase& base, uint64_t sys_statx_kaddr);
    ~PatchSysStatx();

    KModErr patch_sys_statx(uint64_t uid_control_kaddr);

private:
    uint64_t sys_statx_kaddr_;
};

static_assert(sizeof(PatchSysStatx) == 48, "PatchSysStatx layout constraint violated");

class PatchSysFaccessat : public PatchBase {
public:
    PatchSysFaccessat(const PatchBase& base, uint64_t sys_faccessat_kaddr);
    ~PatchSysFaccessat();

    KModErr patch_sys_faccessat(uint64_t uid_control_kaddr);

private:
    uint64_t sys_faccessat_kaddr_;
};

static_assert(sizeof(PatchSysFaccessat) == 48, "PatchSysFaccessat layout constraint violated");

class PatchSysFaccessat2 : public PatchBase {
public:
    PatchSysFaccessat2(const PatchBase& base, uint64_t sys_faccessat2_kaddr);
    ~PatchSysFaccessat2();

    KModErr patch_sys_faccessat2(uint64_t uid_control_kaddr);

private:
    uint64_t sys_faccessat2_kaddr_;
};

static_assert(sizeof(PatchSysFaccessat2) == 48,
              "PatchSysFaccessat2 layout constraint violated");

}  // namespace module_su_redirect
}  // namespace default_modules

namespace android_pkgmap {
namespace detail {

// Drops leading and trailing blanks (' ' or '\t'..'\r') in place.
inline void trim_inplace(std::string& text) {
    const auto blank = [](char c) { return c == ' ' || (c >= '\t' && c <= '\r'); };

    size_t begin = 0;
    while (begin < text.size() && blank(text[begin])) {
        ++begin;
    }
    size_t end = text.size();
    while (end > begin && blank(text[end - 1])) {
        --end;
    }
    if (begin == 0 && end == text.size()) {
        return;
    }
    text.assign(text.begin() + begin, text.begin() + end);
}

// Parses one /data/system/packages.list line:
//   <package name> <uid> [<flags> ...]
// A '#' starts a comment; the package name and uid are stored in `package`
// and `uid` when the line is well formed. When `out_is_system` is not null it
// also reports whether the line carries the "@system" flag.
inline bool parse_line_pkg_uid(const std::string& line, std::string& package, uint32_t& uid,
                               bool* out_is_system) {
    if (out_is_system) {
        *out_is_system = false;
    }

    std::string text = line;
    const size_t comment = text.find('#');
    if (comment != std::string::npos) {
        text.resize(comment);
    }
    trim_inplace(text);
    if (text.empty()) {
        return false;
    }

    std::istringstream input(text);
    std::string parsed_package;
    uint64_t parsed_uid = 0;
    input >> parsed_package >> parsed_uid;
    if (input.fail()) {
        return false;
    }

    package = std::move(parsed_package);
    uid = static_cast<uint32_t>(parsed_uid);

    if (out_is_system) {
        std::string token;
        while (input >> token) {
            if (token == "@system") {
                *out_is_system = true;
                break;
            }
        }
    }
    return true;
}

}  // namespace detail

// Reads /data/system/packages.list and returns the UID of every package in
// `packages`. Packages that are not installed are absent from the result (or
// mapped to 0); the returned map therefore only contains resolved entries.
std::unordered_map<std::string, uint32_t> read_pkg_uids_from_packages_list(
    const std::vector<std::string>& packages, const std::string& packages_list_path);

}  // namespace android_pkgmap
