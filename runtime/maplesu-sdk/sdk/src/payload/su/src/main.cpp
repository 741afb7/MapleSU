// su -- switch to another user identity and run a shell.
//
// This is the userland half of the SDK's su delivery: the installer publishes it
// inside the deployment directory, the kernel execution gate applies the authorized
// Profile, and the program consumes its one-shot session receipt before running
// the requested shell. The CLI and exec shape retain the reference su contract.
//
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <pwd.h>
#include <unistd.h>

#include "cli_contract.h"
#include "payload_support.h"
#include "root_grant.h"

namespace {

using maplesu::payload::su::SuParseKind;
using maplesu::payload::su::SuParseResult;
using maplesu::payload::su::SuRequest;

// Base-10 conversion with the reference client's behaviour: a non-digit makes the
// conversion fail and yields -1.
int parse_int(const char* text) {
    if (text == nullptr || *text == '\0') return -1;
    int value = 0;
    for (const char* cursor = text; *cursor != '\0'; ++cursor) {
        if (*cursor < '0' || *cursor > '9') return -1;
        const int digit = *cursor - '0';
        if (value > (INT_MAX - digit) / 10) return -1;
        value = value * 10 + digit;
    }
    return value;
}

void set_env_pair(const char* name, const char* value) {
    if (value != nullptr) ::setenv(name, value, 1);
}

}  // namespace

int main(int argc, char* argv[]) {
    const SuParseResult parsed = maplesu::payload::su::parse_su_args(argc, argv);
    if (parsed.kind == SuParseKind::Help) {
        maplesu::payload::su::print_usage(stdout);
        return 0;
    }
    if (parsed.kind == SuParseKind::Version) {
        std::printf("%s\n", maplesu::payload::su::kRootVersion);
        return 0;
    }
    if (parsed.kind == SuParseKind::VersionCode) {
        std::printf("%d\n", maplesu::payload::su::kRootVerCode);
        return 0;
    }
    if (parsed.failed) return 2;

    SuRequest request = parsed.request;

    char start_directory[PATH_MAX] = {};
    if (::getcwd(start_directory, sizeof(start_directory)) == nullptr) {
        std::strcpy(start_directory, "/");
    }

    // Optional target user: a name resolves through the password database, an
    // all-digit argument is taken as the uid itself.
    const bool user_requested = parsed.first_positional < argc;
    if (user_requested) {
        const char* user = argv[parsed.first_positional];
        struct passwd* entry = ::getpwnam(user);
        if (entry != nullptr) {
            request.uid = static_cast<int>(entry->pw_uid);
        } else {
            request.uid = parse_int(user);
        }
        if (request.uid < 0) {
            maplesu::payload::err_printf("su: invalid user: %s\n", user);
            return 1;
        }
    }

    if (!maplesu::payload::su::acquire_root()) {
        const int error = errno;
        maplesu::payload::err_printf("su: root handshake failed (errno=%d): %s\n",
                                    error, std::strerror(error));
        return 1;
    }

    const uid_t target_uid = user_requested ? static_cast<uid_t>(request.uid) : ::getuid();
    struct passwd* target = ::getpwuid(target_uid);
    if (target != nullptr && !request.keepenv) {
        set_env_pair("HOME", target->pw_dir);
        set_env_pair("USER", target->pw_name);
        set_env_pair("LOGNAME", target->pw_name);
        set_env_pair("SHELL", request.shell);
    }

    if (request.mount_master) {
        if (!maplesu::payload::su::join_global_mount_namespace()) {
            const int error = errno;
            maplesu::payload::err_printf("su: mount namespace failed (errno=%d): %s\n",
                                        error, std::strerror(error));
            return 1;
        }
    }

    const char* shell_name = std::strrchr(request.shell, '/');
    const std::string login_name = std::string("-") + (shell_name ? shell_name + 1 : request.shell);
    std::vector<char*> new_argv{request.login ? const_cast<char*>(login_name.c_str()) : request.shell};
    if (request.has_command) {
        new_argv.push_back(const_cast<char*>("-c"));
        new_argv.push_back(request.command);
    } else {
        for (int index = parsed.first_shell_argument; index < argc; ++index)
            new_argv.push_back(argv[index]);
    }
    new_argv.push_back(nullptr);

    if (!request.login) {
        if (::chdir(start_directory) == 0) set_env_pair("PWD", start_directory);
    } else if (target != nullptr) {
        if (::chdir(target->pw_dir) == 0) set_env_pair("PWD", target->pw_dir);
    }

    const bool identity_requested = user_requested ||
                                    request.has_gid || !request.groups.empty();
    if (identity_requested) {
        const unsigned int target_gid = request.has_gid ? request.gid :
            (!request.groups.empty() ? request.groups.front() :
             (target != nullptr ? static_cast<unsigned int>(target->pw_gid) :
                                  static_cast<unsigned int>(target_uid)));
        if (!maplesu::payload::su::set_identity(static_cast<unsigned int>(target_uid),
                                               target_gid, request.groups)) {
            const int error = errno;
            maplesu::payload::err_printf("su: target identity failed (errno=%d): %s\n",
                                        error, std::strerror(error));
            return 1;
        }
    }

    // Keep the caller's PTY, process group, streams and cancellation signals.
    ::execvp(request.shell, new_argv.data());
    const int error = errno;
    maplesu::payload::err_printf("su: exec %s failed (errno=%d): %s\n",
                                request.shell, error, std::strerror(error));
    return error == ENOENT ? 127 : 126;
}
