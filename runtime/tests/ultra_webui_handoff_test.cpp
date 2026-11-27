#include "../maplesu-sdk/sdk/src/payload/webui_loader/src/contract.h"
#include "../maplesu-sdk/sdk/src/maplesu/webui/loader_compatibility.h"
#include "module_err_def.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace ui = maplesu::payload::webui;
namespace {
unsigned checks;
volatile sig_atomic_t interrupted;
void interrupt_read(int) { interrupted = 1; }
const std::string key(48, 'K');
void check(bool value, const char* name) {
    ++checks;
    std::printf("%s %s\n", value ? "PASS" : "FAIL", name);
    assert(value);
}
std::vector<std::string> arguments(const char* marker = ui::kOwnerDirectoryArgument) {
    return {"loader", "module name.中文\\part", "23456", "1", "0", ui::kReadyArgument,
            "3", "manager", marker, "4"};
}
bool parse(const std::vector<std::string>& values, ui::LaunchArgs& result) {
    std::vector<const char*> argv;
    for (const auto& value : values) argv.push_back(value.c_str());
    std::string error;
    return ui::parse_launch_args(static_cast<int>(argv.size()), argv.data(), result, error);
}
ui::DeploymentDirectory deployment() {
    ui::DeploymentDirectory value;
    std::memcpy(value.name, "abcdefghijklmnop", 16);
    return value;
}
ui::LegacyCredential credential() {
    ui::LegacyCredential value;
    std::memcpy(value.value, key.c_str(), sizeof(value.value));
    return value;
}
bool empty(const ui::LegacyCredential& value) {
    for (char byte : value.value) if (byte) return false;
    return true;
}
bool read_invalid(ui::LegacyCredential value, size_t size = sizeof(ui::LegacyCredential)) {
    int pipes[2];
    assert(pipe(pipes) == 0);
    assert(write(pipes[1], &value, size) == static_cast<ssize_t>(size));
    close(pipes[1]);
    ui::LegacyCredential received;
    bool rejected = !ui::read_legacy_credential(pipes[0], received) && empty(received);
    close(pipes[0]);
    ui::clear_legacy_credential(value);
    return rejected;
}

// Run the production main/ModuleHost/root-grant on a temporary Linux /data tree.
// The fixture substitutes only the HTTP listener and owner wait, then exits.
bool launch(const char* image, bool extended, bool include_key, bool success,
            const char* module_image, long long expected_error = 0, bool owner = true) {
    const std::string module_id = "flow_module";
    const auto directory = deployment();
    const std::string base = owner ? ui::deployment_data_dir(directory)
        : "/data/" + key.substr(0, 16) + key[0] + "/";
    const std::string private_dir = base + "modules/" + module_id + "/";
    std::filesystem::create_directories(private_dir + "webroot");
    std::filesystem::copy_file(module_image, private_dir + module_id,
                              std::filesystem::copy_options::overwrite_existing);
    int handoff[2], ready[2];
    assert(pipe(handoff) == 0 && pipe(ready) == 0);
    const pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        close(handoff[1]); close(ready[0]);
        auto values = arguments(extended ? ui::kLegacyDirectoryArgument : ui::kOwnerDirectoryArgument);
        values[0] = image;
        values[1] = module_id;
        values[3] = std::to_string(getppid());
        values[6] = std::to_string(ready[1]);
        values[9] = std::to_string(handoff[0]);
        if (!owner) { values.resize(8); close(handoff[0]); }
        std::vector<char*> argv;
        for (auto& value : values) {
            if (value.find(key) != std::string::npos) _exit(99);
            argv.push_back(value.data());
        }
        argv.push_back(nullptr);
        execv(image, argv.data());
        _exit(98);
    }
    close(handoff[0]); close(ready[1]);
    if (owner) assert(ui::write_deployment_directory(handoff[1], directory));
    if (include_key) {
        auto value = credential();
        assert(ui::write_legacy_credential(handoff[1], value));
        ui::clear_legacy_credential(value);
    }
    close(handoff[1]);
    ui::StartupReceipt receipt;
    size_t offset = 0;
    while (offset < sizeof(receipt)) {
        const ssize_t count = read(ready[0], reinterpret_cast<char*>(&receipt) + offset, sizeof(receipt) - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += static_cast<size_t>(count);
    }
    bool passed = offset == sizeof(receipt) && ui::valid_receipt(receipt) &&
        receipt.error == expected_error && (success ? receipt.port == 34567 : receipt.port == 0);
    if (success && owner) {
        ui::DeploymentDirectory reply;
        passed = ui::read_deployment_directory(ready[0], reply) &&
            std::memcmp(reply.name, directory.name, 16) == 0 && passed;
    }
    char trailing;
    passed = read(ready[0], &trailing, 1) == 0 && passed;
    close(ready[0]);
    int status = -1;
    assert(waitpid(child, &status, 0) == child);
    return passed && WIFEXITED(status) && WEXITSTATUS(status) == (success ? 0 : 1);
}
}

int main() {
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    ui::LaunchArgs parsed;
    auto values = arguments();
    check(parse(values, parsed) && parsed.owner_directory_fd == 4 && !parsed.legacy_credential_record,
          "existing owner argv and 32-byte deployment contract retained");
    check(parsed.mod_id32 == values[1], "native ID preserves UTF-8 spaces and backslash");
    values[1] = "a..b";
    check(parse(values, parsed), "native ID accepts embedded double dot");
    values[1].assign(1023, 'a');
    check(parse(values, parsed), "native ID permits 1023 UTF-8 bytes");
    values[1].push_back('a');
    check(!parse(values, parsed), "native ID rejects 1024 UTF-8 bytes");
    for (const char* bad : {"", ".", "..", "a/b"}) {
        values[1] = bad;
        check(!parse(values, parsed), "invalid native path component rejected");
    }
    values = arguments(ui::kLegacyDirectoryArgument);
    check(parse(values, parsed) && parsed.legacy_credential_record, "credential extension requires explicit argv version");
    for (const char* fd : {"0", "2", "-1", "3", "2147483648", "x"}) {
        values[9] = fd;
        check(!parse(values, parsed), "invalid or duplicate anonymous FD rejected");
    }
    values = arguments(); values[8] = "--unknown";
    check(!parse(values, parsed), "unknown directory protocol rejected");
    values = arguments(); values.resize(8);
    check(parse(values, parsed) && parsed.owner_directory_fd == -1, "historical receipt caller still parses");
    values.resize(5);
    check(parse(values, parsed) && parsed.ready_fd == -1, "historical positional caller still parses");
    auto value = credential();
    auto invalid = value; invalid.magic = 0;
    check(read_invalid(invalid), "credential magic rejected and buffer cleared");
    invalid = value; invalid.version = 2;
    check(read_invalid(invalid), "credential version rejected and buffer cleared");
    invalid = value; invalid.size = 63;
    check(read_invalid(invalid), "credential length rejected and buffer cleared");
    invalid = value; invalid.reserved[0] = 1;
    check(read_invalid(invalid), "credential reserved bytes rejected");
    invalid = value; invalid.value[0] = 0;
    check(read_invalid(invalid), "empty credential rejected");
    invalid = value; invalid.value[48] = 'K';
    check(read_invalid(invalid), "unterminated credential rejected");
    check(read_invalid(value, 0), "missing credential EOF remains failure");
    check(read_invalid(value, sizeof(value) - 1), "truncated credential remains failure");
    {
        int pipes[2]; assert(pipe(pipes) == 0);
        struct sigaction action{}; action.sa_handler = interrupt_read; sigemptyset(&action.sa_mask);
        assert(sigaction(SIGALRM, &action, nullptr) == 0);
        const pid_t writer = fork(); assert(writer >= 0);
        if (writer == 0) {
            close(pipes[0]); usleep(20000);
            const auto* bytes = reinterpret_cast<const char*>(&value);
            for (size_t i = 0; i < sizeof(value); ++i) {
                if (write(pipes[1], bytes + i, 1) != 1) _exit(1);
            }
            close(pipes[1]); _exit(0);
        }
        close(pipes[1]); ualarm(1000, 0);
        ui::LegacyCredential received;
        const bool complete = ui::read_legacy_credential(pipes[0], received);
        close(pipes[0]); int status;
        assert(waitpid(writer, &status, 0) == writer);
        check(complete && interrupted && std::strcmp(received.value, key.c_str()) == 0 &&
              WIFEXITED(status) && WEXITSTATUS(status) == 0, "credential read retries real EINTR and fragmented writes");
        ui::clear_legacy_credential(received);
    }
    {
        int pipes[2]; assert(pipe(pipes) == 0);
        assert(ui::write_deployment_directory(pipes[1], deployment()));
        close(pipes[1]);
        ui::DeploymentDirectory received;
        char byte;
        check(ui::read_deployment_directory(pipes[0], received) && read(pipes[0], &byte, 1) == 0,
              "old directory record retains exact EOF");
        close(pipes[0]);
    }
    {
        int pipes[2]; assert(pipe(pipes) == 0);
        assert(ui::write_deployment_directory(pipes[1], deployment()));
        assert(ui::write_legacy_credential(pipes[1], value));
        close(pipes[1]);
        ui::DeploymentDirectory received;
        ui::LegacyCredential received_key;
        char byte;
        check(ui::read_deployment_directory(pipes[0], received) && ui::read_legacy_credential(pipes[0], received_key) &&
              std::strcmp(received_key.value, key.c_str()) == 0 && read(pipes[0], &byte, 1) == 0,
              "directory and actual credential arrive as separate complete records");
        ui::clear_legacy_credential(received_key);
        check(empty(received_key), "successful credential consumption can erase buffer");
        close(pipes[0]);
    }
    bool send = true;
    check(maplesu::webui::select_legacy_handoff({}, nullptr, send) && !send, "unconfigured caller stays on owner v1");
    check(maplesu::webui::select_legacy_handoff(ui::kLegacyDirectoryArgument, key.c_str(), send) && send,
          "new installed payload explicitly selects FD extension");
    std::string old_image = std::string(ui::kReadyArgument) + ui::kOwnerDirectoryArgument;
    std::string slot(4096, '\0'); slot.replace(0, 48, key);
    check(maplesu::webui::select_legacy_handoff(old_image + slot, key.c_str(), send) && !send,
          "older payload with matching installed credential retains original startup");
    constexpr char marker[] =
        "8ba9d457344d22b85d3e7f7263c3dd04d9effeb1becb41a806eaaa9926087fae"
        "49c43c1c3d64c98333e8017195c6ab7bfe7e72a99c4530b267705824e5d6e700";
    slot.replace(49, sizeof(marker) - 1 - 49, marker + 49);
    check(maplesu::webui::select_legacy_handoff(old_image + slot, key.c_str(), send) && !send,
          "fixed baseline partial-slot replacement is recognized");
    slot[0] = 'Q';
    check(!maplesu::webui::select_legacy_handoff(old_image + slot, key.c_str(), send), "different installed credential preserves startup failure");
    check(!maplesu::webui::select_legacy_handoff(key, key.c_str(), send), "arbitrary key occurrence is not payload compatibility");
    check(!maplesu::webui::select_legacy_handoff(ui::kLegacyDirectoryArgument, "short", send), "invalid configured key never reaches extension");
    for (const char* file : {"/work/payload", "/work/baseline-payload-patched", "/work/baseline-payload"}) {
        std::ifstream input(file, std::ios::binary);
        const std::string image((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
        const bool compatible = maplesu::webui::select_legacy_handoff(image, key.c_str(), send);
        const bool current = std::strcmp(file, "/work/payload") == 0;
        const bool patched = std::strcmp(file, "/work/baseline-payload-patched") == 0;
        check(compatible == (current || patched) && send == current,
              "actual installed ELF selects extension or exact old patched credential");
    }
    check(launch("/work/payload", false, false, true, "/work/flow-keyless.so"),
          "production main owner v1 to keyless module succeeds without key");
    check(launch("/work/payload", true, true, true, "/work/flow-legacy.so"),
          "production main FD credential reaches actual legacy WebUI");
    check(launch("/work/payload", true, true, true, "/work/flow-keyless.so"),
          "production main keyless ABI wins even with configured credential");
    check(launch("/work/payload", false, false, false, "/work/flow-legacy.so",
                 static_cast<long long>(KModErr::ERR_MODULE_ROOT_KEY)),
          "missing legacy key is first startup error rather than static fallback success");
    check(launch("/work/payload", true, false, false, "/work/flow-legacy.so",
                 static_cast<long long>(KModErr::ERR_READ_CHILD_DATA)),
          "production main reports missing credential EOF and closes startup pipe");
    check(launch("/work/baseline-payload-patched", false, false, true, "/work/flow-legacy.so"),
          "new owner caller uses old payload with its exact installed credential");
    check(launch("/work/payload-patched", false, false, true, "/work/flow-legacy.so", 0, false),
          "historical caller uses new payload installed credential slot under optimization");
    check(launch("/work/payload", false, false, false, "/work/flow-legacy.so",
                 static_cast<long long>(KModErr::ERR_MODULE_ROOT_KEY), false),
          "unpatched new payload never treats feature marker as a legacy key");
    ui::clear_legacy_credential(value);
    ui::clear_legacy_credential(invalid);
    std::printf("checks=%u\n", checks);
}
