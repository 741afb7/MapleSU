// webui_loader -- launch contract, recovered from the installed-program shape.
//
// The kernel-module kit starts this program from its web-UI feature path once a
// module declares a WebUI handler.  The kit chooses the TCP port, remembers the
// pid of the process that owns the session, and passes both to this program so
// the loader can watch its owner and report the server normally even before the
// module code runs:
//
//     webui_loader <mod_id32> <port> <caller_pid> <module_log_flag>
//
//   argv[1]  mod_id32        the module identity component the kit parsed
//   argv[2]  port            decimal TCP port, chosen in [20000, 60000]
//   argv[3]  caller_pid      pid of the process that requested the WebUI
//   argv[4]  module_log_flag "1" when module file logging is enabled, else "0"
//
// The program is published in the existing StorageLocator system directory.
// A private pipe carries the nonsecret deployment directory after the worker
// acquired Root. This record selects paths and grants no authority.
//
#pragma once

#include <cstddef>
#include <string>

#include "startup_receipt.h"
#include "../../../maplesu/module/path_contract.h"
#include "../../../maplesu/module/types.h"

namespace maplesu::payload::webui {

inline constexpr int kPortMin = 20000;
inline constexpr int kPortMax = 60000;

struct LaunchArgs {
    std::string mod_id32;
    int port = 0;
    int caller_pid = 0;
    int log_flag = 0;
    int ready_fd = -1;
    int owner_directory_fd = -1;
    bool legacy_credential_record = false;
    bool open_browser = true;
};

// Local copy of the decimal parser: the contract header stays self-contained so
// the same checks are compiled by the device program and by the host tests.
inline bool parse_positive(const char* text, long long& out) {
    if (text == nullptr || *text == '\0') return false;
    long long value = 0;
    for (const char* p = text; *p != '\0'; ++p) {
        if (*p < '0' || *p > '9') return false;
        value = value * 10 + (*p - '0');
        if (value > 0x7fffffffLL) return false;
    }
    out = value;
    return true;
}

// Strict parser for the four positional arguments.  A malformed launch is a
// hard start-up failure: serving a web UI for an unknown module identity or on a
// port outside the kit's allocation range would put an endpoint on the device
// that nothing owns.  `error` receives a one-line diagnostic on failure.
inline bool parse_launch_args(int argc, const char* const* argv, LaunchArgs& out,
                              std::string& error) {
    if (argc != 5 && argc != 8 && argc != 10) {
        error = "expected 4 legacy arguments or the versioned SDK startup arguments";
        return false;
    }
    const std::string mod = argv[1] ? argv[1] : "";
    if (mod.size() >= sizeof(module::Descriptor::id32) || !module_paths::valid_id(mod)) {
        error = "mod_id32 must be a nonempty path component shorter than 1024 bytes";
        return false;
    }
    const std::string port_text = argv[2] ? argv[2] : "";
    long long port = 0;
    if (!parse_positive(port_text.c_str(), port) || port < kPortMin || port > kPortMax) {
        error = "port must be a decimal number in [20000, 60000]";
        return false;
    }
    long long pid = 0;
    if (!parse_positive(argv[3], pid) || pid <= 0) {
        error = "caller_pid must be a positive decimal number";
        return false;
    }
    const std::string flag = argv[4] ? argv[4] : "";
    if (flag != "0" && flag != "1") {
        error = "maplesu_log_flag must be 0 or 1";
        return false;
    }
    int ready_fd = -1;
    bool open_browser = true;
    if (argc == 8 || argc == 10) {
        long long descriptor = 0;
        const std::string mode = argv[7] ? argv[7] : "";
        if (argv[5] == nullptr || std::string(argv[5]) != kReadyArgument ||
            !parse_positive(argv[6], descriptor) || descriptor < 3 ||
            (mode != "browser" && mode != "manager")) {
            error = "invalid versioned SDK startup arguments";
            return false;
        }
        ready_fd = static_cast<int>(descriptor);
        open_browser = mode == "browser";
    }
    int owner_directory_fd = -1;
    bool legacy_credential_record = false;
    if (argc == 10) {
        long long descriptor = 0;
        const std::string handoff = argv[8] ? argv[8] : "";
        if ((handoff != kOwnerDirectoryArgument && handoff != kLegacyDirectoryArgument) ||
            !parse_positive(argv[9], descriptor) || descriptor < 3 || descriptor == ready_fd) {
            error = "invalid Owner directory argument";
            return false;
        }
        owner_directory_fd = static_cast<int>(descriptor);
        legacy_credential_record = handoff == kLegacyDirectoryArgument;
    }
    out.mod_id32 = mod;
    out.port = static_cast<int>(port);
    out.caller_pid = static_cast<int>(pid);
    out.log_flag = (flag == "1") ? 1 : 0;
    out.ready_fd = ready_fd;
    out.owner_directory_fd = owner_directory_fd;
    out.legacy_credential_record = legacy_credential_record;
    out.open_browser = open_browser;
    return true;
}

// --------------------------------------------------------------------------
// path derivation
// --------------------------------------------------------------------------
inline std::string deployment_data_dir(const DeploymentDirectory& directory) {
    if (!valid_directory(directory)) return {};
    std::string name(directory.name, sizeof(directory.name));
    name.push_back(name[0]);
    return "/data/" + name + "/";
}

}  // namespace maplesu::payload::webui
