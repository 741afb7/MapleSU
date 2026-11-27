#include "kernel_module_kit_umbrella.h"
#include "../../../src/maplesu/compat/module_list_legacy_abi.h"
#include "../../../src/maplesu/module/state.h"
#include "fixture_config.h"
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sys/stat.h>
#include <fcntl.h>

namespace fs = std::filesystem;
using namespace maplesu::module_state;
namespace maplesu::sdk {
KModErr safe_install_module(const char*, bool, std::string&);
KModErr safe_uninstall_module(const char*);
KModErr unsafe_uninstall_module(const char*, bool);
}
static unsigned assertions = 0;
static void check(bool ok, const char* message) {
    ++assertions;
    if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static fs::path root16() { return fs::path("/data/" + fixture::deployment); }
static fs::path root17() { const auto head = fixture::deployment; return fs::path("/data/" + head + head[0]); }
static const char* names[] = {
    "running_mods.3cee2062f9eab0e034c55c298c7571b4",
    "abnormal_mods.3cee2062f9eab0e034c55c298c7571b4",
    "removed_pending_reboot_mods.3cee2062f9eab0e034c55c298c7571b4",
    "pending_developer_run_once_mods.3cee2062f9eab0e034c55c298c7571b4",
    "finished_developer_run_once_mods.3cee2062f9eab0e034c55c298c7571b4",
};
static void reset() {
    fixture::deployment = "0123456789abcdef";
    fs::remove_all(root16()); fs::remove_all(root17());
    fs::create_directories(root16()); fs::create_directories(root17() / "modules");
    fixture::root_result = fixture::reset_result = fixture::install_result = KModErr::OK;
    fixture::fail_chmod = fixture::fail_label = fixture::fail_close = false;
    fixture::label_contract_ok = true;
    fixture::chmod_calls = fixture::label_calls = 0;
    fixture::select_deployment();
}
static void put(const fs::path& file, const std::string& text) {
    fs::create_directories(file.parent_path());
    std::ofstream out(file, std::ios::binary); out.write(text.data(), text.size()); out.close();
    check(out.good(), "fixture file write");
}
static std::string bytes(const fs::path& file) {
    std::ifstream input(file, std::ios::binary);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
static void module(const std::string& id, time_t timestamp = 1) {
    const auto file = root17() / "modules" / id / id;
    put(file, "fixture:" + id);
    const timespec stamps[2] = {{timestamp, 0}, {timestamp, 0}};
    check(utimensat(AT_FDCWD, file.c_str(), stamps, 0) == 0, "fixture module timestamp");
}
static std::vector<maplesu::sdk::module_record> list() {
    std::vector<maplesu::sdk::module_record> records;
    check(maplesu::sdk::unsafe_get_all_modules(records) == KModErr::OK, "real list succeeds");
    return records;
}
static maplesu::sdk::ModuleRunState state(const std::vector<maplesu::sdk::module_record>& records, const std::string& id) {
    const auto found = std::find_if(records.begin(), records.end(), [&](const auto& item) { return item.desc.id32 == id; });
    check(found != records.end(), "real list returns module");
    return found->state;
}
static void report(const char* test, const char* status) {
    std::cout << "{\"test\":\"" << test << "\",\"status\":\"" << status
              << "\",\"assertionsSoFar\":" << assertions << "}" << std::endl;
}

static void paths_and_format() {
    for (const auto& deployment : {std::string("0123456789abcdef"), std::string("Q123456789abcdef"),
                                   std::string("Z123456789abcdef"), std::string("a123456789abcdef"),
                                   std::string("b123456789abcdef")}) {
        fixture::deployment = deployment;
        fixture::select_deployment();
        for (unsigned i = 0; i != 5; ++i) {
            check(path(static_cast<List>(i)) == "/data/" + deployment + "/" + names[i], "STATE01 kernel-directory path");
        }
    }
    check(path(static_cast<List>(6)).empty() && path(static_cast<List>(255)).empty(), "STATE01 invalid state");
    reset();
    for (unsigned i = 0; i != 5; ++i) {
        const auto which = static_cast<List>(i);
        std::vector<std::string> out = {"stale"};
        check(read(which, out) == KModErr::OK && out.empty(), "STATE02 missing clears output");
        put(root17() / names[i], "wrong_directory\n");
        check(read(which, out) == KModErr::OK && out.empty(), "STATE01 ignores system-directory five-state files");
        put(path(which), "\nalpha\r\n\nbeta\nalpha\n\r\nlast");
        check(read(which, out) == KModErr::OK && out == std::vector<std::string>({"alpha", "beta", "alpha", "last"}), "STATE02 CRLF blank duplicate ordering");
        check(save(which, {"first", "", "second", "first"}) == KModErr::OK, "STATE02 save");
        check(bytes(path(which)) == "first\nsecond\nfirst\n", "STATE02 save format");
        struct stat st{};
        check(stat(path(which).c_str(), &st) == 0 && (st.st_mode & 0777) == 0777, "STATE02 existing chmod contract");
        const auto labels = fixture::label_calls;
        check(add(which, "first") == KModErr::OK && fixture::label_calls == labels + 1, "STATE02 existing add still saves");
        check(read(which, out) == KModErr::OK && out == std::vector<std::string>({"first", "second", "first"}), "STATE02 add does not duplicate");
        const auto unchanged_labels = fixture::label_calls;
        check(remove(which, "absent") == KModErr::OK && fixture::label_calls == unchanged_labels, "STATE02 missing remove does not write");
        check(remove(which, "first") == KModErr::OK, "STATE02 remove");
        check(read(which, out) == KModErr::OK && out == std::vector<std::string>({"second"}), "STATE02 removes all matches");
        check(save(which, {}) == KModErr::OK && bytes(path(which)).empty(), "STATE02 clear via save");
        check(add(which, "") == KModErr::ERR_MODULE_PARAM && remove(which, "") == KModErr::ERR_MODULE_PARAM, "STATE02 empty id error");
    }
    check(fixture::label_contract_ok, "STATE02 existing label name/value/NUL/flags");
    std::vector<std::string> invalid = {"stale"};
    check(read(static_cast<List>(6), invalid) == KModErr::ERR_MODULE_PARAM && invalid.empty(), "STATE01 invalid read clears");
    check(save(static_cast<List>(255), {}) == KModErr::ERR_MODULE_PARAM, "STATE01 invalid write error");
    report("STATE01-02-path-format-idempotence", "PASS");
}

static void failures() {
    for (unsigned i = 0; i != 5; ++i) {
        reset(); const auto which = static_cast<List>(i);
        fs::create_directory(path(which));
        std::vector<std::string> ids = {"stale"};
        check(read(which, ids) == KModErr::ERR_MODULE_READ_FILE && ids.empty(), "STATE03 real directory read failure");
        check(save(which, {"x"}) == KModErr::ERR_MODULE_WRITE_FILE, "STATE03 real open write failure");
    }
    reset(); fs::remove_all(root16());
    check(save(List::Running, {"x"}) == KModErr::ERR_MODULE_WRITE_FILE && !fs::exists(root16()), "STATE03 no implicit directory creation");
    std::vector<std::string> ids = {"stale"};
    check(read(List::Running, ids) == KModErr::OK && ids.empty(), "STATE03 absent parent retains missing semantics");
    reset(); fixture::fail_chmod = true;
    check(save(List::Running, {"x"}) == KModErr::ERR_SET_FILE_SELINUX && fixture::label_calls == 0, "STATE03 chmod failure propagation");
    fixture::fail_chmod = false; fixture::fail_label = true;
    check(save(List::Running, {"x"}) == KModErr::ERR_SET_FILE_SELINUX, "STATE03 label failure propagation");
    reset(); fs::create_symlink("/dev/full", path(List::Removed));
    check(save(List::Removed, {"small"}) == KModErr::ERR_MODULE_WRITE_FILE && fixture::label_calls == 0, "STATE03 close failure detected before label");
    check(save(List::Removed, {std::string(131072, 'X')}) == KModErr::ERR_MODULE_WRITE_FILE, "STATE03 write failure detected");
    report("STATE03-real-io-and-boundary-errors", "PASS");
}

static void listing() {
    using State = maplesu::sdk::ModuleRunState;
    reset();
    module("run_only", 4); module("abnormal", 3); module("removed", 2); module("plain", 1);
    check(save(List::Running, {"run_only", "abnormal", "removed"}) == KModErr::OK, "STATE04 running fixture");
    check(save(List::Abnormal, {"abnormal", "removed"}) == KModErr::OK, "STATE04 abnormal fixture");
    check(save(List::Removed, {"removed"}) == KModErr::OK, "STATE04 removed fixture");
    put(root17() / names[0], "plain\n");
    auto records = list();
    check(state(records, "run_only") == State::Running, "STATE04 kernel-directory Running");
    check(state(records, "abnormal") == State::Abnormal, "STATE04 abnormal priority");
    check(state(records, "removed") == State::RemovedPendingReboot, "STATE04 removed priority");
    check(state(records, "plain") == State::NotRunning, "STATE04 no system-directory dual read");
    check(std::string(records[0].desc.id32) == "plain" && std::string(records[3].desc.id32) == "run_only", "STATE04 mtime order retained");
    auto override = State::Abnormal;
    put(root17() / "modules_new_state" / "plain", std::string(reinterpret_cast<const char*>(&override), sizeof(override)));
    override = State::NotRunning;
    put(root17() / "modules_new_state" / "run_only", std::string(reinterpret_cast<const char*>(&override), sizeof(override)));
    put(root17() / "modules_new_state" / "abnormal", "123");
    put(root17() / "modules_new_desc" / "plain", "overridden description");
    records = list();
    check(state(records, "plain") == State::Abnormal && state(records, "run_only") == State::NotRunning, "STATE04 system-directory four-byte state override retained");
    check(state(records, "abnormal") == State::Abnormal, "STATE04 wrong-sized override ignored");
    check(std::string(records[0].desc.desc) == "overridden description", "STATE04 system-directory description override retained");
    std::vector<maplesu::sdk::module_record> safe;
    check(maplesu::sdk::safe_get_all_modules(safe) == KModErr::OK && safe.size() == records.size(), "STATE04 real safe list fork transfer");
    fs::remove(path(List::Running)); fs::create_directory(path(List::Running));
    safe.clear();
    check(maplesu::sdk::unsafe_get_all_modules(safe) == KModErr::ERR_MODULE_READ_FILE, "STATE04 real list read error");
    check(maplesu::sdk::safe_get_all_modules(safe) == KModErr::ERR_MODULE_READ_FILE, "STATE04 real safe list read error propagation");
    fixture::root_result = KModErr::ERR_MODULE_NO_ROOT;
    check(maplesu::sdk::unsafe_get_all_modules(safe) == KModErr::ERR_MODULE_NO_ROOT, "STATE04 root error retained");
    reset(); fs::remove_all(root17() / "modules");
    check(maplesu::sdk::unsafe_get_all_modules(safe) == KModErr::ERR_MODULE_OPEN_DIR, "STATE04 module dir remains system-directory");
    report("STATE04-real-list-priority-overrides-errors", "PASS");
}

static void installation() {
    reset(); put(root16() / "install.mode", "boot");
    check(save(List::FinishedRunOnce, {"fixture_mod", "keep_finished"}) == KModErr::OK, "STATE04 finished setup");
    check(save(List::PendingRunOnce, {"keep_pending"}) == KModErr::OK, "STATE04 pending setup");
    std::string reason;
    check(maplesu::sdk::safe_install_module("fixture.zip", true, reason) == KModErr::OK, "STATE04 real run-once installer fork succeeds");
    check(bytes(path(List::FinishedRunOnce)) == "keep_finished\n" && bytes(path(List::PendingRunOnce)) == "keep_pending\nfixture_mod\n", "STATE04 install removes finished then adds pending");
    check(fs::exists(root17() / "modules" / "fixture_mod" / "fixture_mod"), "STATE04 installer preserves system-directory module ELF");
    check(!fs::exists(root17() / names[3]) && !fs::exists(root17() / names[4]), "STATE04 installer only writes kernel-directory state");
    check(maplesu::sdk::safe_install_module("fixture.zip", false, reason) == KModErr::OK, "STATE04 real normal reinstall");
    check(bytes(path(List::PendingRunOnce)) == "keep_pending\n", "STATE04 normal reinstall removes pending");
    check(add(List::Running, "fixture_mod") == KModErr::OK && state(list(), "fixture_mod") == maplesu::sdk::ModuleRunState::Running, "STATE04 installer output readable by real list");
    check(add(List::PendingRunOnce, "fixture_mod") == KModErr::OK && add(List::FinishedRunOnce, "fixture_mod") == KModErr::OK, "STATE04 uninstall states setup");
    put(root17() / "storage" / "fixture_mod" / "value", "storage");
    check(maplesu::sdk::safe_uninstall_module("fixture_mod") == KModErr::OK, "STATE04 real uninstall fork succeeds");
    check(!fs::exists(root17() / "modules" / "fixture_mod") && !fs::exists(root17() / "storage" / "fixture_mod"), "STATE04 uninstall physical deletion");
    check(bytes(path(List::Removed)) == "fixture_mod\n" && bytes(path(List::PendingRunOnce)) == "keep_pending\n" && bytes(path(List::FinishedRunOnce)) == "keep_finished\n", "STATE04 uninstall Removed Pending Finished order and data");

    reset(); module("fixture_mod");
    put(path(List::PendingRunOnce), "fixture_mod\n"); put(path(List::FinishedRunOnce), "fixture_mod\n");
    fs::create_directory(path(List::Removed));
    check(maplesu::sdk::safe_uninstall_module("fixture_mod") == KModErr::ERR_MODULE_READ_FILE, "STATE04 uninstall state read error reaches parent");
    check(bytes(path(List::PendingRunOnce)) == "fixture_mod\n" && bytes(path(List::FinishedRunOnce)) == "fixture_mod\n", "STATE04 error stops later state updates");
    reset(); module("fixture_mod");
    fixture::reset_result = KModErr::ERR_MODULE_BOOT_SESSION;
    check(maplesu::sdk::safe_uninstall_module("fixture_mod") == KModErr::ERR_MODULE_BOOT_SESSION && fs::exists(path(List::Removed)) && fs::exists(path(List::PendingRemoval)), "STATE04 reset failure retains committed removal intent");
    reset(); put(root16() / "install.mode", "boot");
    put(path(List::PendingRunOnce), "keep_pending\n"); fs::create_directory(path(List::FinishedRunOnce));
    check(maplesu::sdk::safe_install_module("fixture.zip", true, reason) == KModErr::ERR_MODULE_READ_FILE && bytes(path(List::PendingRunOnce)) == "keep_pending\n", "STATE04 install finished failure stops pending");
    reset(); put(root16() / "install.mode", "boot");
    put(path(List::FinishedRunOnce), "fixture_mod\nkeep_finished\n"); fs::create_directory(path(List::PendingRunOnce));
    check(maplesu::sdk::safe_install_module("fixture.zip", true, reason) == KModErr::ERR_MODULE_READ_FILE && bytes(path(List::FinishedRunOnce)) == "fixture_mod\nkeep_finished\n", "STATE04 uncommitted install failure restores prior finished state");
    reset(); put(root16() / "install.mode", "boot");
    fixture::fail_close = true;
    check(maplesu::sdk::safe_install_module("fixture.zip", false, reason) == KModErr::ERR_MODULE_ROOT_KEY,
        "STATE04 committed install close failure reaches parent");
    fixture::fail_close = false;
    check(fs::exists(root17() / "modules" / "fixture_mod" / "fixture_mod"), "STATE04 close failure retains committed image");
    fixture::fail_close = true;
    check(maplesu::sdk::safe_uninstall_module("fixture_mod") == KModErr::ERR_MODULE_ROOT_KEY,
        "STATE04 uninstall close failure reaches parent");
    fixture::fail_close = false;
    report("STATE04-real-install-uninstall-fork-errors", "PASS");
}

int main() {
    paths_and_format(); failures(); listing(); installation();
    fixture::fail_admission = true;
    const auto before = fixture::fork_calls;
    std::vector<maplesu::sdk::module_record> records;
    std::string reason;
    check(maplesu::sdk::safe_get_all_modules(records) == KModErr::ERR_MODULE_ROOT_KEY, "admission rejects list");
    check(maplesu::sdk::safe_install_module("fixture.zip", false, reason) == KModErr::ERR_MODULE_ROOT_KEY, "admission rejects install");
    check(maplesu::sdk::safe_uninstall_module("fixture_mod") == KModErr::ERR_MODULE_ROOT_KEY, "admission rejects uninstall");
    check(fixture::fork_calls == before, "admission rejects before any child is created");
    report("NEW-ALL", "PASS");
}
