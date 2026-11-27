#pragma once
#define module_desc public_packed_module_desc
#define module_record public_packed_module_record
#include "maplesu_sdk/module_callback.h"
#include "kernel_module_kit_umbrella.h"
#undef module_desc
#undef module_record
#include <cstddef>
#include <sys/wait.h>

namespace default_modules::module_root { KModErr set_current_root(bool); }

namespace maplesu::sdk {
struct module_desc {
    char name[1024] = {}, version[1024] = {}, desc[4096] = {}, author[1024] = {};
    char id32[1024] = {}, update_json[1024] = {};
    bool web_ui = false;
    MapleSUSdkVersion min_sdk_ver = {};
};
struct module_record { module_desc desc; ModuleRunState state = ModuleRunState::NotRunning; };
static_assert(sizeof(module_desc) == 9232 && alignof(module_desc) == 4);
static_assert(offsetof(module_desc, min_sdk_ver) == 9220 && sizeof(module_record) == 9236);

class fork_base_info {
public:
    int fd_parent_to_child_read, fd_parent_to_child_write;
    int fd_child_to_parent_read, fd_child_to_parent_write;
    int self_pid, child_pid;
    fork_base_info() : fd_parent_to_child_read(-1), fd_parent_to_child_write(-1),
        fd_child_to_parent_read(-1), fd_child_to_parent_write(-1), self_pid(-1), child_pid(-1) { reset(); }
    ~fork_base_info() { close_all(); }
    static void safe_close(int& fd) { if (fd >= 0) { close(fd); fd = -1; } }
    void close_all() {
        safe_close(fd_parent_to_child_read); safe_close(fd_parent_to_child_write);
        safe_close(fd_child_to_parent_read); safe_close(fd_child_to_parent_write);
    }
    void reset() {
        close_all();
        fd_parent_to_child_read = fd_parent_to_child_write = -1;
        fd_child_to_parent_read = fd_child_to_parent_write = -1;
        self_pid = getpid(); child_pid = 0;
    }
};
class fork_pipe_info : public fork_base_info {};
static_assert(sizeof(fork_base_info) == 24 && sizeof(fork_pipe_info) == 24);
static_assert(offsetof(fork_base_info, child_pid) == 20);
bool fork_pipe_child_process(fork_pipe_info&);
bool write_errcode_from_child(const fork_pipe_info&, KModErr);
bool write_string_from_child(const fork_pipe_info&, const std::string&);
bool read_errcode_from_child(const fork_pipe_info&, KModErr&);
bool read_string_from_child(fork_pipe_info&, std::string&);
}

namespace maplesu::sdk {
KModErr find_module_elf_in_zip(const char*, module_desc&, std::string&);
KModErr unsafe_reset_autorun_bootstrap_safe_flag();
KModErr unsafe_uninstall_module(const char*, bool);
KModErr safe_uninstall_module(const char*);
KModErr safe_install_module(const char*, bool, std::string&);
}

namespace maplesu::compat {
using InstallPipe = maplesu::sdk::fork_pipe_info;
using maplesu::sdk::unsafe_reset_autorun_bootstrap_safe_flag;
using maplesu::sdk::fork_pipe_child_process;
using maplesu::sdk::write_errcode_from_child;
using maplesu::sdk::write_string_from_child;
using maplesu::sdk::read_errcode_from_child;
using maplesu::sdk::read_string_from_child;
using maplesu::sdk::features::callback::invoke_module_on_install;
using maplesu::sdk::features::callback::invoke_module_on_uninstall;
}
