// MapleSU test private implementation.
//
#pragma once

#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <string>
#include <system_error>
#include <thread>
#include <vector>

// 官方伞头：引入 KModErr / module_err_def.h / maplesu::sdk 公开接口 / kernel_module
// 导出、以及 module_descriptor.h 里的 private_desc_parser.h（弱符号 webui 钩子）。
#include "a_kernel_module_kit_umbrella.h"


//      _ZN15default_modules11module_root16set_current_rootEb
//      _ZN15default_modules11module_root19get_module_eng_nameEv
//      _ZN15default_modules11module_root25init_anti_detect_root_envE…b
//      _ZN15default_modules14module_selinux… / module_su_redirect… /
//      _ZN15default_modules22module_tombstone_purge…
namespace default_modules {
namespace module_root {
KModErr set_current_root(bool enable_root);
const char* get_module_eng_name();
KModErr init_anti_detect_root_env(const std::vector<std::string>& dirs, bool enable);
}  // namespace module_root

namespace module_selinux {
const char* get_module_eng_name();
KModErr init(maplesu::sdk::InstallMode mode, maplesu::sdk::ExploitMethod method);
}  // namespace module_selinux

namespace module_su_redirect {
const char* get_module_eng_name();
KModErr init(const char* path, bool enable);
}  // namespace module_su_redirect

namespace module_tombstone_purge {
const char* get_module_eng_name();
KModErr init(bool enable, bool print);
}  // namespace module_tombstone_purge
}  // namespace default_modules

namespace maplesu::sdk {
// 仅声明：实现由 maplesu_fork_helper.o / 其它成员提供。
KModErr safe_restart_zygote64();
}  // namespace maplesu::sdk

namespace kernel_module {
KModErr get_empty_area_start_addr(uint64_t& result);
}  // namespace kernel_module

// 3) MapleSU 子进程 IPC 基础设施
//      fork_base_info = 6 x i32 = 24 字节；方法为 linkonce_odr（私有头 inline）。
//      ctor 先 memset(-1,24) 再 reset()；reset() 关全部 fd、前 4 个字段置 -1、
//      self_pid=getpid()、child_pid=0。close_all 逐个 safe_close。
//      fork_base_info 的方法，故为派生/空派生）。
namespace maplesu::sdk {

struct fork_base_info {
    int fd_parent_to_child_read;   // 字段 0：子进程侧「父->子」读端
    int fd_parent_to_child_write;  // 字段 1：父进程侧「父->子」写端
    int fd_child_to_parent_read;   // 字段 2：父进程侧「子->父」读端
    int fd_child_to_parent_write;  // 字段 3：子进程侧「子->父」写端
    int self_pid;                  // 字段 4
    int child_pid;                 // 字段 5（父侧 waitpid 用）

    fork_base_info() {
        fd_parent_to_child_read = -1;
        fd_parent_to_child_write = -1;
        fd_child_to_parent_read = -1;
        fd_child_to_parent_write = -1;
        self_pid = -1;
        child_pid = -1;
        reset();
    }

    ~fork_base_info() { close_all(); }

    static void safe_close(int& fd) {
        if (fd > -1) {
            ::close(fd);
            fd = -1;
        }
    }

    void close_all() {
        safe_close(fd_parent_to_child_read);
        safe_close(fd_parent_to_child_write);
        safe_close(fd_child_to_parent_read);
        safe_close(fd_child_to_parent_write);
    }

    void reset() {
        close_all();
        fd_parent_to_child_read = -1;
        fd_parent_to_child_write = -1;
        fd_child_to_parent_read = -1;
        fd_child_to_parent_write = -1;
        self_pid = getpid();
        child_pid = 0;
    }
};

struct fork_pipe_info : fork_base_info {};

bool fork_pipe_child_process(fork_pipe_info& pipe);

bool write_errcode_from_child(const fork_pipe_info& pipe, KModErr err);
bool write_string_from_child(const fork_pipe_info& pipe, const std::string& text);
bool write_int_from_child(const fork_pipe_info& pipe, int value);
bool write_bool_from_child(const fork_pipe_info& pipe, bool value);

bool read_errcode_from_child(const fork_pipe_info& pipe, KModErr& out_err);
bool read_string_from_child(fork_pipe_info& pipe, std::string& out_text);
bool read_int_from_child(const fork_pipe_info& pipe, int& out_value);
bool read_bool_from_child(const fork_pipe_info& pipe, bool& out_value);

}  // namespace maplesu::sdk

//    RedirectStdToFile: {int saved_stdout; int saved_stderr; int file_fd; bool
//                        redirecting;}  = 13 字节，字段初值全 0（无 NSDMI，
//    RedirectStdToString: {int, int, int, bool, std::function<void(const char*,
//                        size_t)>, std::thread} = 72 字节（NSDMI 为 -1/-1/-1/false）。
//    OpenMode: 0 = Reset（截断/清空），1 = Append。
class RedirectStdToFile {
public:
    enum class OpenMode : int {
        Reset = 0,
        Append = 1,
    };

    static RedirectStdToFile& instance() {
        static RedirectStdToFile inst;
        return inst;
    }

    bool redirect(const std::string& file_path, OpenMode mode) {
        if (redirecting_) return true;
        fflush(nullptr);
        saved_stdout_ = dup(1);
        saved_stderr_ = dup(2);
        if (saved_stdout_ < 0 || saved_stderr_ < 0) {
            if (saved_stdout_ > -1) {
                close(saved_stdout_);
                saved_stdout_ = -1;
            }
            if (saved_stderr_ > -1) {
                close(saved_stderr_);
                saved_stderr_ = -1;
            }
            return false;
        }
        // 0644 = 420；O_WRONLY=1, O_CREAT=0x40, O_TRUNC=0x200, O_APPEND=0x400, O_CLOEXEC=0x80000
        const int flags = (mode == OpenMode::Reset) ? 525889 : 525377;
        file_fd_ = open(file_path.c_str(), flags, 0644);
        if (file_fd_ < 0) {
            dup2(saved_stdout_, 1);
            dup2(saved_stderr_, 2);
            close(saved_stdout_);
            saved_stdout_ = -1;
            close(saved_stderr_);
            saved_stderr_ = -1;
            return false;
        }
        if (dup2(file_fd_, 1) < 0 || dup2(file_fd_, 2) < 0) {
            close(file_fd_);
            file_fd_ = -1;
            dup2(saved_stdout_, 1);
            dup2(saved_stderr_, 2);
            close(saved_stdout_);
            saved_stdout_ = -1;
            close(saved_stderr_);
            saved_stderr_ = -1;
            return false;
        }
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        redirecting_ = true;
        return true;
    }

    bool restore() {
        if (!redirecting_) return true;
        fflush(nullptr);
        bool ok = true;
        if (saved_stdout_ > -1) {
            ok = dup2(saved_stdout_, 1) > -1;
            close(saved_stdout_);
            saved_stdout_ = -1;
        }
        if (saved_stderr_ > -1) {
            ok = (dup2(saved_stderr_, 2) > -1) && ok;
            close(saved_stderr_);
            saved_stderr_ = -1;
        }
        if (file_fd_ > -1) {
            close(file_fd_);
            file_fd_ = -1;
        }
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        redirecting_ = false;
        return ok;
    }

    ~RedirectStdToFile() { restore(); }

    RedirectStdToFile(const RedirectStdToFile&) = delete;
    RedirectStdToFile& operator=(const RedirectStdToFile&) = delete;

private:
    RedirectStdToFile() = default;

    int saved_stdout_ = 0;
    int saved_stderr_ = 0;
    int file_fd_ = 0;
    bool redirecting_ = false;
};

class RedirectStdToString {
public:
    enum class OpenMode : int {
        Reset = 0,
        Append = 1,
    };

    static RedirectStdToString& instance() {
        static RedirectStdToString inst;
        return inst;
    }

    bool redirect(std::string& out_text, OpenMode mode) {
        if (redirecting_) return true;
        if (mode == OpenMode::Reset) out_text.clear();
        consumer_ = [&out_text](const char* data, size_t size) { out_text.append(data, size); };
        return start_redirect_impl();
    }

    bool restore() {
        if (!redirecting_) return true;
        fflush(nullptr);
        bool ok = true;
        if (saved_stdout_ > -1) {
            ok = dup2(saved_stdout_, 1) > -1;
            close(saved_stdout_);
            saved_stdout_ = -1;
        }
        if (saved_stderr_ > -1) {
            ok = (dup2(saved_stderr_, 2) > -1) && ok;
            close(saved_stderr_);
            saved_stderr_ = -1;
        }
        if (thread_.joinable()) thread_.join();
        consumer_ = nullptr;
        redirecting_ = false;
        return ok;
    }

    ~RedirectStdToString() {
        if (redirecting_) {
            restore();
        }
    }

    RedirectStdToString(const RedirectStdToString&) = delete;
    RedirectStdToString& operator=(const RedirectStdToString&) = delete;

private:
    RedirectStdToString() = default;

    bool start_redirect_impl() {
        int pipe_fds[2];
        if (pipe(pipe_fds) != 0) {
            consumer_ = nullptr;
            return false;
        }
        read_fd_ = pipe_fds[0];
        const int write_fd = pipe_fds[1];
        saved_stdout_ = dup(1);
        saved_stderr_ = dup(2);
        if (saved_stdout_ < 0 || saved_stderr_ < 0) {
            if (saved_stdout_ > -1) {
                close(saved_stdout_);
                saved_stdout_ = -1;
            }
            if (saved_stderr_ > -1) {
                close(saved_stderr_);
                saved_stderr_ = -1;
            }
            close(read_fd_);
            read_fd_ = -1;
            close(write_fd);
            consumer_ = nullptr;
            return false;
        }
        if (dup2(write_fd, 1) < 0 || dup2(write_fd, 2) < 0) {
            close(write_fd);
            dup2(saved_stdout_, 1);
            dup2(saved_stderr_, 2);
            close(saved_stdout_);
            saved_stdout_ = -1;
            close(saved_stderr_);
            saved_stderr_ = -1;
            close(read_fd_);
            read_fd_ = -1;
            consumer_ = nullptr;
            return false;
        }
        close(write_fd);
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        redirecting_ = true;
        std::thread reader([this]() {
            char buffer[4096];
            for (;;) {
                const ssize_t got = read(read_fd_, buffer, sizeof(buffer));
                if (got > 0) {
                    if (consumer_) consumer_(buffer, static_cast<size_t>(got));
                    continue;
                }
                if (got == 0) break;
                if (errno == EINTR) continue;
                break;
            }
            if (read_fd_ > -1) {
                close(read_fd_);
                read_fd_ = -1;
            }
        });
        thread_ = std::move(reader);
        return true;
    }

    int saved_stdout_ = -1;  // 字段 0
    int saved_stderr_ = -1;  // 字段 1
    int read_fd_ = -1;       // 字段 2
    bool redirecting_ = false;  // 字段 3
    std::function<void(const char*, size_t)> consumer_;  // 字段 4（40 字节）
    std::thread thread_;                                 // 字段 5（8 字节）
};

namespace file_utils {

enum class SelinuxFileFlag : int {
    Default = 0,
};

static bool create_empty_file(const char* file_path) {
    std::ofstream ofs(file_path, std::ios::out | std::ios::trunc | std::ios::binary);
    const bool opened = ofs.is_open();
    if (opened) {
        ofs.close();
    }
    return opened;
}

static bool set_file_selinux_access_mode(const char* file_path, SelinuxFileFlag flag) {
    (void)flag;
    if (chmod(file_path, 0777) != 0) {
        return false;
    }
    constexpr const char kKernelContext[] = "u:object_r:kernel:s0";
    return setxattr(file_path, "security.selinux", kKernelContext,
                    sizeof(kKernelContext), 0) == 0;
}

static bool file_exists(const std::filesystem::path& path) {
    std::error_code err;
    return std::filesystem::exists(path, err);
}


static bool read_text_file(const char* file_path, std::string& out_text) {
    std::vector<uint8_t> buffer;
    std::ifstream ifs(file_path, std::ios::binary);
    if ((ifs.rdstate() & (std::ios::badbit | std::ios::failbit)) != 0) {
        return false;
    }
    ifs.seekg(0, std::ios::end);
    const std::streamoff size = ifs.tellg();
    if (size < 0) {
        return false;
    }
    ifs.seekg(0, std::ios::beg);
    buffer.resize(static_cast<size_t>(size));
    ifs.read(reinterpret_cast<char*>(buffer.data()), static_cast<std::streamsize>(buffer.size()));
    const bool ok =
        (ifs.rdstate() & (std::ios::badbit | std::ios::failbit)) == 0;
    if (ok) {
        out_text.assign(buffer.begin(), buffer.end());
    }
    return ok;
}

}  // namespace file_utils

//      +0  std::string name_              (24 字节)
//      +24 int module_ret_                (maplesu_module_main 的返回码)
//      +32 KModErr err_                   (ssize_t)
//      +40 std::chrono::steady_clock::time_point start_
//    ctor(desc, is_default_module) 打印 banner；dtor 打印 [Module End]/[Module Error]。
namespace maplesu::sdk {

class TestModuleBannerGuard {
public:
    TestModuleBannerGuard(const module_desc& desc, bool is_default_module)
        : name_(desc.name),
          start_(std::chrono::steady_clock::now()) {
        print_sep('*');
        printf(is_default_module ? "MapleSU built-in mods\n" : "MapleSU mod\n");
        printf("Name: %s\n", name_.c_str());
        if (!is_default_module) {
            printf("Version: %s\n", desc.version);
            printf("ID32: %s\n", desc.id32);
            printf("MinSDK: %u.%u.%u\n", desc.min_sdk_ver.major, desc.min_sdk_ver.minor,
                   desc.min_sdk_ver.patch);
        }
        const bool has_update_json = strlen(desc.update_json) != 0;
        if (has_update_json || desc.web_ui) {
            puts("Features:");
            print_feature("Web UI", desc.web_ui);
            print_feature("Online Update", has_update_json);
        }
        print_sep('*');
        fflush(stdout);
    }

    ~TestModuleBannerGuard() {
        const int64_t elapsed_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start_)
                .count();
        if (err_ == KModErr::OK) {
            printf("[Module End] name=%s, module_entry=%s, elapsed=%lld ms\n",
                   name_.c_str(), std::to_string(module_ret_).c_str(),
                   static_cast<long long>(elapsed_ms));
        } else {
            printf("[Module Error] name=%s, error=%s, elapsed=%lld ms\n", name_.c_str(),
                   to_string(err_).c_str(), static_cast<long long>(elapsed_ms));
        }
        fflush(stdout);
    }

    static void print_sep(char ch) {
        for (int i = 0; i < 30; ++i) {
            putchar(ch);
        }
        putchar('\n');
    }

    static void print_feature(const char* name, bool enabled) {
        if (enabled) {
            printf("   [+] %s\n", name);
        }
    }

    int module_ret_ = 0;
    KModErr err_ = KModErr::OK;

private:
    std::string name_;
    std::chrono::steady_clock::time_point start_;
};

}  // namespace maplesu::sdk

//    print_proc_self_cgroup(const char*) -> "cgroup:\n" + /proc/self/cgroup 内容，
//    打不开时追加 "(unreadable)\n"；形参未使用，调用点传 nullptr。
namespace root_status_report {

inline std::string print_proc_self_cgroup(const char* unused_tag) {
    (void)unused_tag;
    std::string cgroup_text("cgroup:\n");
    const int fd = open("/proc/self/cgroup", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        cgroup_text.append("(unreadable)\n");
        return cgroup_text;
    }
    for (;;) {
        char buffer[1024] = {0};
        const ssize_t got = read(fd, buffer, sizeof(buffer));
        if (got > 0) {
            cgroup_text.append(buffer, buffer + got);
            continue;
        }
        if (got == -1 && errno == EINTR) continue;
        break;
    }
    close(fd);
    return cgroup_text;
}

}  // namespace root_status_report
