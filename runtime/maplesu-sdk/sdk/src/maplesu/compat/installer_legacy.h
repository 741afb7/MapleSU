#pragma once

#include "maplesu/compat/persisted_inputs.h"
#include "kernel_module_kit_umbrella.h"
#include "fork_legacy_abi.h"
#include "../install/bootstrap_install.h"
#include "../install/lifecycle.h"

namespace maplesu::sdk {
KModErr unsafe_clean_older_hide_dir();
KModErr unsafe_create_hide_dir();
KModErr unsafe_del_hide_dir();
KModErr unsafe_install_su();
KModErr unsafe_install_webui_loader();
KModErr unsafe_install_resetprop();
KModErr unsafe_reset_autorun_bootstrap_safe_flag();
KModErr unsafe_reset_autorun_bootstrap_duplicate_boot_session_flag();
KModErr unsafe_restart_zygote64();
KModErr unsafe_get_all_modules(std::vector<module_record>&);
KModErr safe_uninstall_module(const char*);
}
namespace default_modules::module_root { KModErr set_current_root(bool); }
namespace default_modules::module_su_redirect { KModErr add_su_allowlist(const char*); }
namespace default_modules::module_bypass_oplus_secure_guard { bool first_install_init(); }

#pragma GCC visibility push(hidden)
namespace maplesu::compat {

inline constexpr install::core::err_t kInstallerOk = static_cast<install::core::err_t>(KModErr::OK);
inline constexpr install::core::err_t kInstallerOpenDirectory = static_cast<install::core::err_t>(KModErr::ERR_MODULE_OPEN_DIR);
inline constexpr install::core::err_t kInstallerDeleteFile = static_cast<install::core::err_t>(KModErr::ERR_MODULE_DEL_FILE);
inline constexpr install::core::err_t kInstallerWriteFile = static_cast<install::core::err_t>(KModErr::ERR_MODULE_WRITE_FILE);
inline constexpr install::core::err_t kInstallerEnvironmentAbsent = static_cast<install::core::err_t>(KModErr::ERR_MAPLESU_ENV_NOT_INSTALL);
inline constexpr install::core::err_t kInstallerFileAbsent = static_cast<install::core::err_t>(KModErr::ERR_MODULE_FILE_NOT_EXIST);
inline constexpr install::core::err_t kInstallerReadFile = static_cast<install::core::err_t>(KModErr::ERR_MODULE_READ_FILE);
inline constexpr install::core::err_t kInstallerIdentity = static_cast<install::core::err_t>(KModErr::ERR_MODULE_ROOT_KEY);
inline constexpr install::core::err_t kInstallerBootSession = static_cast<install::core::err_t>(KModErr::ERR_MODULE_BOOT_SESSION);
inline constexpr install::core::err_t kInstallerCreateFile = static_cast<install::core::err_t>(KModErr::ERR_MODULE_CREATE_EMPTY_FILE);
inline constexpr install::core::err_t kInstallerSetLabel = static_cast<install::core::err_t>(KModErr::ERR_SET_FILE_SELINUX);
inline constexpr install::core::err_t kInstallerReadError = static_cast<install::core::err_t>(KModErr::ERR_READ_CHILD_ERRCODE);
inline constexpr install::core::err_t kInstallerReadText = static_cast<install::core::err_t>(KModErr::ERR_READ_CHILD_STRING);
inline constexpr install::core::err_t kInstallerParseVersion = static_cast<install::core::err_t>(KModErr::ERR_MODULE_PARSE_VERSION);
inline constexpr const char* kInstallerVersionFile = maplesu::compat::kEnvironmentVersionFile;

inline install::core::err_t installer_root(bool enabled) {
    return static_cast<install::core::err_t>(default_modules::module_root::set_current_root(enabled));
}
inline bool installer_first_configuration() {
    return default_modules::module_bypass_oplus_secure_guard::first_install_init();
}
inline install::core::err_t installer_clean_older() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_clean_older_hide_dir()); }
inline install::core::err_t installer_create_directories() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_create_hide_dir()); }
inline install::core::err_t installer_delete_directories() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_del_hide_dir()); }
inline install::core::err_t installer_su() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_install_su()); }
inline install::core::err_t installer_webui() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_install_webui_loader()); }
inline install::core::err_t installer_resetprop() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_install_resetprop()); }
inline install::core::err_t installer_prepare_bootstrap() { return static_cast<install::core::err_t>(install::prepare_environment_bootstrap()); }
inline install::core::err_t installer_prepare_bootstrap_state() { return static_cast<install::core::err_t>(install::prepare_environment_bootstrap_state()); }
inline install::core::err_t installer_refresh_bootstrap() { return static_cast<install::core::err_t>(install::refresh_environment_bootstrap()); }
inline install::core::err_t installer_refresh_authorization() {
    return static_cast<install::core::err_t>(default_modules::module_su_redirect::add_su_allowlist(nullptr));
}
inline install::core::err_t installer_commit_version() { return static_cast<install::core::err_t>(install::commit_environment_version()); }
inline install::core::err_t installer_commit_version(install::state::Mutation& mutation) {
    return static_cast<install::core::err_t>(install::commit_environment_version(mutation));
}
inline install::core::err_t installer_reset_safe() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_reset_autorun_bootstrap_safe_flag()); }
inline install::core::err_t installer_reset_duplicate() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_reset_autorun_bootstrap_duplicate_boot_session_flag()); }
inline install::core::err_t installer_restart_zygote() { return static_cast<install::core::err_t>(maplesu::sdk::unsafe_restart_zygote64()); }

class InstallerModules {
    std::vector<maplesu::sdk::module_record> records_;
public:
    install::core::err_t load() {
        return static_cast<install::core::err_t>(maplesu::sdk::unsafe_get_all_modules(records_));
    }
    std::size_t size() const { return records_.size(); }
    install::core::err_t remove(std::size_t index) const {
        return static_cast<install::core::err_t>(maplesu::sdk::safe_uninstall_module(records_[index].desc.id32));
    }
};

// The existing wire carrier is confined to this legacy transport adapter.
class InstallerChannel {
    maplesu::sdk::fork_pipe_info pipe_;
public:
    bool start() { return maplesu::sdk::fork_pipe_child_process(pipe_); }
    int child_pid() const { return pipe_.child_pid; }
    void close() { pipe_.close_all(); }
    bool write_error(install::core::err_t error) const {
        return maplesu::sdk::write_errcode_from_child(pipe_, static_cast<KModErr>(error));
    }
    bool read_error(install::core::err_t& error) const {
        KModErr value = static_cast<KModErr>(error);
        const bool read = maplesu::sdk::read_errcode_from_child(pipe_, value);
        error = static_cast<install::core::err_t>(value);
        return read;
    }
    bool write_integer(int value) const { return maplesu::sdk::write_int_from_child(pipe_, value); }
    bool read_integer(int& value) const { return maplesu::sdk::read_int_from_child(pipe_, value); }
    bool write_text(const std::string& value) const { return maplesu::sdk::write_string_from_child(pipe_, value); }
    bool read_text(std::string& value) { return maplesu::sdk::read_string_from_child(pipe_, value); }
};

}  // namespace maplesu::compat
#pragma GCC visibility pop
