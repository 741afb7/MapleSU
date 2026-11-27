// Executes the production worker/transaction with fixture process and package data.
#include "maplesu_parasite.h"
#include "module_err_def.h"
#include "maplesu_sdk/maplesu_root.h"
#include "maplesu_sdk/maplesu_su_auth.h"
#include "maplesu_request_private.h"
#include "../maplesu-sdk/sdk/src/module/su_redirect_tu/su_uid_profile_private.h"
#include <cassert>
#include <chrono>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <sys/stat.h>
#include <unistd.h>

namespace fs = std::filesystem;
using namespace kernel_root;
namespace {
const fs::path root = "/tmp/maplesu-parasite-test";
const fs::path app = root / "host";
const fs::path lib = app / "lib/arm64";
const fs::path so = lib / "libfixture.so";
const fs::path other = lib / "libother.so";
std::map<std::string, int> captured;
pid_t parent;
int parent_forks = 0, parent_pipes = 0;
void callback(const char* path, AppDynlibStatus status) {
    assert(getpid() == parent); // Callback must run in caller, not the elevated worker.
    captured[path] = status;
}
void put(const fs::path& path, const std::string& text) {
    std::ofstream file(path, std::ios::binary); file.write(text.data(), text.size());
    assert(file.good());
}
std::string bytes(const fs::path& path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), {}};
}
void expect(ParasiteError result, ParasiteError expected, const char* name) {
    if (result != expected) { std::cerr << name << ": " << result << " != " << expected << '\n'; abort(); }
    std::cout << "PASS " << name << '\n';
}
}
extern "C" pid_t __real_fork();
extern "C" int __real_pipe2(int*, int);
extern "C" pid_t __wrap_fork() {
    if (getpid() == parent) ++parent_forks;
    return __real_fork();
}
extern "C" int __wrap_pipe2(int* pipes, int flags) {
    if (getpid() == parent) ++parent_pipes;
    return __real_pipe2(pipes, flags);
}
namespace maplesu::sdk {
KModErr get_root() {
    if (getenv("FIXTURE_ROOT_FAIL")) return static_cast<KModErr>(-23456);
    if (getenv("FIXTURE_ROOT_CRASH")) raise(SIGSEGV);
    if (getenv("FIXTURE_ROOT_HANG")) sleep(10);
    return KModErr::OK;
}
}
namespace maplesu::su_auth {
KModErr get_uid_profiles_with_cb(void (*callback)(const UidProfileV1*)) {
    if (!getenv("FIXTURE_NO_AUTH")) {
        UidProfileV1 item{}; item.uid = 10489; item.allow = 1;
        strcpy(item.package, "dev.fixture.host"); callback(&item);
    }
    return KModErr::OK;
}
}
namespace kernel_module {
uint64_t maplesu_request_auth_state() { return getenv("FIXTURE_NO_IDENTITY") ? UINT64_MAX : 5; }
KModErr maplesu_request_worker_admission() {
    assert(getpid() == parent);
    return getenv("FIXTURE_NO_ADMISSION") ? static_cast<KModErr>(-EACCES) : KModErr::OK;
}
KModErr maplesu_request_owner_close() {
    return getenv("FIXTURE_CLOSE_FAIL") ? static_cast<KModErr>(-23457) : KModErr::OK;
}
}

int main(int argc, char** argv) {
    assert(argc == 2);
    assert(!fs::exists(root));
    parent = getpid();
    auto precheck_export = reinterpret_cast<decltype(&parasite_precheck_app_with_cb)>(dlsym(
        RTLD_DEFAULT, "_ZN11kernel_root29parasite_precheck_app_with_cbEPKcPFvS1_NS_15AppDynlibStatusEE"));
    auto app_export = reinterpret_cast<decltype(&parasite_implant_app)>(dlsym(
        RTLD_DEFAULT, "_ZN11kernel_root20parasite_implant_appEPKcS1_"));
    auto su_export = reinterpret_cast<decltype(&parasite_implant_su_env)>(dlsym(
        RTLD_DEFAULT, "_ZN11kernel_root23parasite_implant_su_envEPKcS1_S1_"));
    assert(precheck_export == &parasite_precheck_app_with_cb && app_export == &parasite_implant_app &&
           su_export == &parasite_implant_su_env);
    expect(precheck_export("dev.fixture;host", callback), PARASITE_PARAM, "runtime precheck ABI lookup");
    expect(app_export("dev.fixture;host", "/fixture.so"), PARASITE_PARAM, "runtime app ABI lookup");
    expect(su_export("dev.fixture;host", "/fixture.so", "/fixture/su"), PARASITE_PARAM, "runtime su ABI lookup");
    fs::create_directories(lib);
    fs::create_directories(root / "proc/101");
    assert(chown((root / "proc/101").c_str(), 10489, 10489) == 0);
    fs::copy_file(argv[1], so);
    fs::copy_file(argv[1], other);
    const std::string original = bytes(so);
    const std::string process("dev.fixture.host\0ignored", 24);
    put(root / "proc/101/cmdline", process);
    put(root / "proc/101/maps", "1000-2000 r-xp 0 00:00 1 " + so.string() + "\n");
    put(root / "pm", "#!/bin/sh\nprintf 'package:" + (app / "base.apk").string() + "\\n'\n");
    chmod((root / "pm").c_str(), 0700);
    auto check = [&] { captured.clear(); return parasite_precheck_app_with_cb("dev.fixture.host", callback); };
    auto implant_app = [&] { return parasite_implant_app("dev.fixture.host", so.c_str()); };
    expect(parasite_precheck_app_with_cb("dev.fixture.host", nullptr), PARASITE_PARAM, "null callback");
    expect(parasite_precheck_app_with_cb("dev.fixture;touch", callback), PARASITE_PARAM, "invalid identifier");
    setenv("FIXTURE_NO_IDENTITY", "1", 1);
    expect(check(), PARASITE_PARAM, "unusable request identity rejected");
    unsetenv("FIXTURE_NO_IDENTITY");
    setenv("FIXTURE_NO_ADMISSION", "1", 1);
    const int forks_before = parent_forks, pipes_before = parent_pipes;
    expect(check(), -EACCES, "ordinary precheck rejected before worker");
    expect(implant_app(), -EACCES, "ordinary app implant rejected before worker");
    expect(su_export("dev.fixture.host", so.c_str(), "/fixture/su"), -EACCES,
        "ordinary su implant rejected before worker");
    assert(parent_forks == forks_before && parent_pipes == pipes_before && captured.empty());
    unsetenv("FIXTURE_NO_ADMISSION");
    setenv("FIXTURE_CLOSE_FAIL", "1", 1);
    expect(check(), -23457, "close failure retained");
    setenv("FIXTURE_ROOT_FAIL", "1", 1);
    expect(check(), -23456, "root error retained"); assert(captured.empty());
    unsetenv("FIXTURE_ROOT_FAIL");
    unsetenv("FIXTURE_CLOSE_FAIL");
    setenv("FIXTURE_ROOT_CRASH", "1", 1);
    expect(check(), PARASITE_CHILD, "worker crash isolated");
    unsetenv("FIXTURE_ROOT_CRASH");
    setenv("FIXTURE_ROOT_HANG", "1", 1);
    auto start = std::chrono::steady_clock::now();
    expect(check(), PARASITE_CHILD, "worker timeout bounded");
    assert(std::chrono::steady_clock::now() - start < std::chrono::seconds(3));
    unsetenv("FIXTURE_ROOT_HANG");
    expect(parasite_precheck_app_with_cb("dev.fixture.missing", callback), PARASITE_PROCESS, "missing process");
    expect(check(), 0, "precheck actual ELF inventory");
    assert(captured.size() == 2 && captured[so.string()] == Running && captured[other.string()] == NotRunning);
    put(root / "proc/101/maps", "1000-2000 r-xp 0 00:00 1 " + (app / "lib/arm/libbad.so").string() + "\n");
    expect(check(), PARASITE_32BIT, "32-bit host rejected");
    put(root / "proc/101/maps", "1000-2000 r-xp 0 00:00 1 " + so.string() + "\n");
    put(lib / "bad.so", "not ELF");
    expect(check(), 0, "non-ELF excluded"); assert(captured.size() == 2);
    expect(implant_app(), PARASITE_RECOVERY_MISSING, "missing recovery blocks mutation");
    assert(bytes(so) == original);
    put(root / "start.sh", "#!/bin/sh\nexit 0\n"); chmod((root / "start.sh").c_str(), 0600);
    setenv("FIXTURE_NO_AUTH", "1", 1);
    expect(implant_app(), -31003, "existing host grant required");
    unsetenv("FIXTURE_NO_AUTH");
    assert(chown((root / "proc/101").c_str(), 110489, 110489) == 0);
    expect(implant_app(), -31003, "same appId in another fullUID is not authorized");
    assert(bytes(so) == original);
    assert(chown((root / "proc/101").c_str(), 10489, 10489) == 0);
    fs::copy_file(argv[1], root / "outside.so");
    expect(parasite_implant_app("dev.fixture.host", (root / "outside.so").c_str()), PARASITE_ORIGINAL, "out-of-host path rejected");
    fs::create_symlink(so, lib / "libsymlink.so");
    expect(parasite_implant_app("dev.fixture.host", (lib / "libsymlink.so").c_str()), PARASITE_ORIGINAL, "symlink original rejected");
    fs::remove(lib / "libsymlink.so");
    const auto malformed = lib / "libmalformed.so";
    put(malformed, original.substr(0, sizeof(Elf64_Ehdr)));
    expect(parasite_implant_app("dev.fixture.host", malformed.c_str()), PARASITE_VERIFY,
           "patch failure preserves original");
    assert(bytes(malformed) == original.substr(0, sizeof(Elf64_Ehdr)));
    assert(!fs::exists(malformed.string() + ".maplesu-parasite.orig"));
    fs::remove(malformed);
    put(so.string() + ".maplesu-parasite.orig", "invalid backup");
    expect(implant_app(), PARASITE_CONFLICT, "invalid backup never overwritten");
    assert(bytes(so) == original && bytes(so.string() + ".maplesu-parasite.orig") == "invalid backup");
    fs::remove(so.string() + ".maplesu-parasite.orig");
    expect(implant_app(), 0, "app implant actual patch engine");
    const auto once = bytes(so);
    assert(once != original && bytes(so.string() + ".maplesu-parasite.orig") == original);
    assert(fs::exists(lib / "libmaplesu_parasite.so"));
    expect(implant_app(), 0, "app implant idempotent"); assert(bytes(so) == once);
    expect(parasite_implant_app("dev.fixture.host", other.c_str()), 0, "second library unaffected by patch globals");
    assert(bytes(other.string() + ".maplesu-parasite.orig") == original);
    fs::create_directories(root / "su");
    const auto su_dir = (root / "su").string();
    auto su_implant = [&] { return parasite_implant_su_env("dev.fixture.host", so.c_str(), su_dir.c_str()); };
    expect(su_implant(), PARASITE_PAYLOAD, "su executable required");
    put(root / "su/su", "fixture"); chmod((root / "su/su").c_str(), 0755);
    expect(su_implant(), 0, "distinct su environment implant");
    assert(bytes(so.string() + ".maplesu-parasite.orig") == original);
    const auto both = bytes(so);
    expect(su_implant(), 0, "su environment idempotent"); assert(bytes(so) == both);
    assert(bytes(lib / "libmaplesu_su_env.so") != bytes(lib / "libmaplesu_parasite.so"));
    assert(bytes(lib / "libmaplesu_su_env.so").find(su_dir) != std::string::npos);
    auto alternate = (root / "alternate").string(); fs::create_directories(alternate);
    fs::copy_file(root / "su/su", fs::path(alternate) / "su");
    expect(parasite_implant_su_env("dev.fixture.host", so.c_str(), alternate.c_str()), PARASITE_CONFLICT, "conflicting su configuration preserved");
    assert(bytes(so) == both);
    put(lib / "libmaplesu_parasite.so", "damaged payload");
    expect(implant_app(), PARASITE_CONFLICT, "damaged existing payload rejected"); assert(bytes(so) == both);
    std::cout << "ALL PARASITE FIXTURE CHECKS PASSED\n";
}
