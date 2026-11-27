// MapleSU test implementation.
//
#define SymbolLookupSilentGuard_8dfccc5cf454087c7314725d3487e703 \
    SymbolLookupSilentGuard_c67c0e4fed21dd11b34c67e0a5b878c8

#include "gap45/maplesu_test_private.h"

#include <sys/syscall.h>
#include <cerrno>
#include <new>
#include <stdexcept>
#include <type_traits>
#include "maplesu_root_status_private.h"
#include "maplesu_root_command_private.h"
#include "maplesu_caps_private.h"
#include "maplesu_storage_worker_private.h"
#include "maplesu_status_query_private.h"
#include "../maplesu/compat/runtime_config_exports_legacy_abi.h"

namespace maplesu::sdk {
bool write_transfer_data_from_child(const fork_pipe_info&, const std::vector<uint8_t>&);
bool read_transfer_data_from_child(fork_pipe_info&, std::vector<uint8_t>&);
}

// A. root_status_report 私有工具实现
namespace root_status_report {
using namespace maplesu::sdk;

static thread_local RootStatusResult last_result;

static int read_cap_limit(RootProcessFacts& facts) {
    const int fd = open("/proc/sys/kernel/cap_last_cap", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return errno;
    char text[16] = {};
    size_t used = 0;
    int error = 0;
    while (used < sizeof(text) - 1) {
        const ssize_t count = read(fd, text + used, sizeof(text) - 1 - used);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { error = errno; break; }
        if (count == 0) break;
        used += static_cast<size_t>(count);
    }
    if (close(fd) < 0 && error == 0) error = errno;
    if (error != 0) return error;
    if (used == 0 || used == sizeof(text) - 1) return EPROTO;
    char* end = nullptr;
    errno = 0;
    const long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || value < 0 || value > 63) return EPROTO;
    while (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n') ++end;
    if (*end != '\0') return EPROTO;
    facts.cap_last_cap = static_cast<int>(value);
    facts.capability_mask = value == 63 ? UINT64_MAX : (UINT64_C(1) << (value + 1)) - 1;
    return 0;
}

static std::string build_current_process_status(RootProcessFacts& facts, bool include_report) {
    std::ostringstream os;
    uid_t ruid = 0, euid = 0, suid = 0;
    errno = 0;
    if (getresuid(&ruid, &euid, &suid) == 0) {
        facts.ruid = ruid; facts.euid = euid; facts.suid = suid;
        facts.uid_observed = true;
    } else { facts.uid_error = errno; os << "getresuid failed\n"; }
    gid_t rgid = 0, egid = 0, sgid = 0;
    errno = 0;
    if (getresgid(&rgid, &egid, &sgid) == 0) {
        facts.rgid = rgid; facts.egid = egid; facts.sgid = sgid;
        facts.gid_observed = true;
    } else { facts.gid_error = errno; os << "getresgid failed\n"; }
    errno = 0;
    const long fsuid = syscall(SYS_setfsuid, -1);
    if (fsuid < 0) facts.fsuid_error = errno;
    else facts.fsuid = static_cast<uint32_t>(fsuid);
    errno = 0;
    const long fsgid = syscall(SYS_setfsgid, -1);
    if (fsgid < 0) facts.fsgid_error = errno;
    else facts.fsgid = static_cast<uint32_t>(fsgid);
    facts.fsids_observed = fsuid >= 0 && fsgid >= 0;
    os << "current process status:\n";
    os << "ruid: " << ruid << "\n" << "rgid: " << rgid << "\n";
    os << "suid: " << suid << "\n" << "sgid: " << sgid << "\n";
    os << "euid: " << euid << "\n" << "egid: " << egid << "\n";
    os << "fsuid: " << facts.fsuid << "\n" << "fsgid: " << facts.fsgid << "\n";
    kernel_module::caps_info caps{};
    errno = 0;
    facts.caps_error = kernel_module::get_current_caps_snapshot(caps, facts.ambient_present);
    facts.caps_errno = errno;
    facts.caps_observed = is_ok(facts.caps_error);
    facts.inheritable = caps.inheritable; facts.permitted = caps.permitted;
    facts.effective = caps.effective; facts.bounding = caps.bounding; facts.ambient = caps.ambient;
    os << "capInh: 0x" << std::hex << facts.inheritable << std::dec << "\n";
    os << "capPrm: 0x" << std::hex << facts.permitted << std::dec << "\n";
    os << "capEff: 0x" << std::hex << facts.effective << std::dec << "\n";
    os << "capBnd: 0x" << std::hex << facts.bounding << std::dec << "\n";
    os << "capAmb: 0x" << std::hex << facts.ambient << std::dec << "\n";
    facts.cap_limit_error = read_cap_limit(facts);
    facts.cap_limit_observed = facts.cap_limit_error == 0;
    const int seccomp_state = prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
    errno = 0;
    const int secure_bits = prctl(PR_GET_SECUREBITS, 0, 0, 0, 0);
    if (secure_bits >= 0) {
        facts.securebits = secure_bits;
        facts.securebits_observed = true;
    } else facts.securebits_error = errno;
    os << "seccomp: " << seccomp_state << "\n";
    os << "securebits: " << secure_bits << "\n";
    facts.matches_root = root_process_facts_match(facts);
    if (!include_report) return {};
    FILE* enforce_pipe = popen("getenforce", "r");
    if (enforce_pipe != nullptr) {
        char enforce_text[512] = {};
        const size_t got = fread(enforce_text, 1, sizeof(enforce_text) - 1, enforce_pipe);
        pclose(enforce_pipe);
        if (got != 0 && enforce_text[got - 1] == '\n') enforce_text[got - 1] = '\0';
        os << "selinux: " << enforce_text << "\n";
    }
    return os.str();
}

static void snapshot_root_command(RootStatusCredentialFacts& facts) {
    const auto command = maplesu::sdk::current_root_command_result();
    const auto& root = command.root_transition;
    facts.command_observed = command.phase != RootCommandPhase::None;
    facts.command_phase = static_cast<int>(command.phase);
    facts.command_error = command.error; facts.command_errno = command.system_error;
    facts.process = command.root_process;
    facts.root_attempted = command.root_attempted; facts.root_returned = command.root_returned;
    facts.root_report_received = command.root_report_received;
    facts.credentials_attempted = root.credentials_attempted;
    facts.credentials_error = root.credentials_error;
    facts.transition_error = root.error; facts.transition_errno = root.system_error;
    facts.transition_phase = static_cast<int>(default_modules::module_root::root_transition_phase_code(root.phase));
    facts.owner_mode = root.owner_mode; facts.environment_completed = root.environment_completed;
    facts.credentials = root.credentials;
    facts.task_state_error = root.task_state_error; facts.task_state_attempted = root.task_state_attempted;
    facts.task_state = root.task_state;
}

struct RootStatusWire {
    uint32_t magic = 0x3153524d;
    uint32_t version = 1;
    RootStatusResult result{};
};
static_assert(std::is_trivially_copyable_v<RootStatusWire>);

static void failure(RootStatusResult& result, KModErr error, RootStatusPhase phase, int system_error) {
    if (is_failed(result.error)) return;
    result.error = error;
    result.phase = phase;
    result.system_error = system_error;
}

const char* get_root_status_report() {
    static thread_local std::string report;
    report.clear();
    last_result = RootStatusResult{};
    last_result.error = KModErr::OK;
    last_result.process = getpid();
    errno = 0;
    const KModErr admitted = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admitted)) {
        failure(last_result, admitted, RootStatusPhase::None, errno);
        report = "get_root: FAILED\n\n\n";
        return report.c_str();
    }
    last_result.phase = RootStatusPhase::Fork;
    maplesu::sdk::fork_pipe_info pipe;
    errno = 0;
    if (maplesu::sdk::fork_pipe_child_process(pipe)) {
        RootStatusWire wire{};
        auto& result = wire.result;
        result.error = KModErr::OK;
        result.process = getpid();
        std::string child_report;
        bool sent = false;
        try {
            result.phase = RootStatusPhase::Root;
            result.root_attempted = true;
            errno = 0;
            result.root_error = maplesu::sdk::get_root();
            result.root_errno = errno;
            result.root_returned = true;
            result.error = result.root_error;
            result.system_error = is_failed(result.root_error) ? result.root_errno : 0;
            snapshot_root_command(result.root_credentials);
            const std::string status = build_current_process_status(result.facts, is_ok(result.root_error));
            if (is_ok(result.root_error)) {
                child_report = status;
                child_report.append("===================\n");
                child_report.append(print_proc_self_cgroup(nullptr));
            }
            if (is_ok(result.error)) result.phase = result.facts.matches_root ? RootStatusPhase::Complete : RootStatusPhase::Credentials;
        } catch (const std::bad_alloc&) {
            failure(result, KModErr::ERR_NO_MEM, result.phase, 0);
        } catch (const std::length_error&) {
            failure(result, KModErr::ERR_NO_MEM, result.phase, 0);
        } catch (...) {
            failure(result, KModErr::ERR_MODULE_TEST_CHANNEL, result.phase, 0);
        }
        if (result.root_attempted && !result.root_returned) snapshot_root_command(result.root_credentials);
        try {
            const auto* first = reinterpret_cast<const uint8_t*>(&wire);
            sent = maplesu::sdk::write_transfer_data_from_child(pipe, std::vector<uint8_t>(first, first + sizeof(wire))) &&
                maplesu::sdk::write_string_from_child(pipe, child_report);
        } catch (...) {}
        pipe.close_all();
        _exit(sent ? 0 : 1);
    }
    const int fork_error = errno;
    const pid_t child = pipe.child_pid;
    std::string child_report;
    if (child <= 0) {
        failure(last_result, KModErr::ERR_MODULE_TEST_CHANNEL, RootStatusPhase::Fork, fork_error);
    } else {
        last_result.child_pid = child;
        std::vector<uint8_t> bytes;
        bool received = false;
        try {
            errno = 0;
            received = maplesu::sdk::read_transfer_data_from_child(pipe, bytes);
            const int transfer_error = errno == EINTR || errno == EAGAIN ? 0 : errno;
            if (received && bytes.size() == sizeof(RootStatusWire)) {
                RootStatusWire wire{};
                std::memcpy(&wire, bytes.data(), sizeof(wire));
                if (wire.magic == 0x3153524d && wire.version == 1 && wire.result.process == child) {
                    last_result = wire.result;
                    last_result.process = getpid();
                    last_result.child_pid = child;
                    last_result.child_status_valid = false;
                    last_result.root_verified = false;
                    errno = 0;
                    last_result.receipt_complete = maplesu::sdk::read_string_from_child(pipe, child_report);
                    if (!last_result.receipt_complete) {
                        last_result.transport_error = errno == EINTR || errno == EAGAIN ? 0 : errno;
                        failure(last_result, KModErr::ERR_READ_CHILD_STRING, RootStatusPhase::Receipt, last_result.transport_error);
                    }
                } else received = false;
            } else received = false;
            if (!received) {
                last_result.transport_error = transfer_error;
                failure(last_result, KModErr::ERR_READ_CHILD_DATA, RootStatusPhase::Receipt, transfer_error);
            }
        } catch (const std::bad_alloc&) {
            failure(last_result, KModErr::ERR_NO_MEM, RootStatusPhase::Receipt, 0);
        } catch (const std::length_error&) {
            failure(last_result, KModErr::ERR_NO_MEM, RootStatusPhase::Receipt, 0);
        }
        pipe.close_all();
        int status = 0;
        pid_t waited;
        do {
            errno = 0;
            waited = waitpid(child, &status, 0);
        } while (waited < 0 && errno == EINTR);
        last_result.wait_error = waited < 0 ? errno : 0;
        if (waited == child) {
            last_result.child_status = status;
            last_result.child_status_valid = true;
        }
        if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
            failure(last_result, KModErr::ERR_READ_CHILD_DATA, RootStatusPhase::Wait, last_result.wait_error);
        }
        last_result.facts.matches_root = root_process_facts_match(last_result.facts);
        last_result.root_verified = last_result.receipt_complete && last_result.child_status_valid &&
            WIFEXITED(last_result.child_status) && WEXITSTATUS(last_result.child_status) == 0 &&
            last_result.root_attempted && last_result.root_returned && is_ok(last_result.root_error) &&
            is_ok(last_result.error) && last_result.facts.matches_root;
    }
    report.assign("get_root: ");
    report.append(last_result.root_verified ? "OK" : "FAILED");
    report.append("\n\n" + child_report + "\n");
    return report.c_str();
}
}  // namespace root_status_report


namespace maplesu::sdk {
RootStatusResult current_root_status_result() {
    return root_status_report::last_result.process == getpid()
        ? root_status_report::last_result : RootStatusResult{};
}
}

// B. maplesu::sdk 私有辅助实现
namespace maplesu::sdk {
namespace default_modules_helper {

static KModErr init_module_tombstone_purge(bool enable, bool purge_all) {
    module_desc desc = {};
    strncpy(desc.name, default_modules::module_tombstone_purge::get_module_eng_name(),
            sizeof(desc.name) - 1);
    TestModuleBannerGuard banner(desc, true);
    const KModErr err = default_modules::module_tombstone_purge::init(enable, purge_all);
    banner.err_ = err;
    return err;
}

}  // namespace default_modules_helper

// 回调在 x0 上生成 "movz/movk 立即数 0x12345"，并把所用寄存器作为第二
// 个出参回给调用方（供 arm64_module_asm_func_end 使用）。
static std::function<KModErr(asmjit::a64::Assembler*, asmjit::a64::GpX&)> unsafe_test_channel() {
    return [](asmjit::a64::Assembler* a, asmjit::a64::GpX& out_reg) -> KModErr {
        asmjit::a64::GpX reg = asmjit::a64::regs::x0;
        bool emitted = false;
        for (int index = 0; index < 4; ++index) {
            const uint16_t part = static_cast<uint16_t>((UINT64_C(0x12345) >> (index * 16)) & 0xFFFFu);
            if (part == 0) continue;
            if (!emitted) {
                a->movz(reg, part, index * 16);
                emitted = true;
            } else {
                a->movk(reg, part, index * 16);
            }
        }
        if (!emitted) {
            a->movz(reg, 0, 0);
        }
        out_reg = reg;
        return KModErr::OK;
    };
}

//   子进程：set_current_root(true) -> 写/删 ".boot_fail_protect" 标志文件
//           （enabled=true 建文件并打 SELinux kernel 标签，false 删除）-> sync
//   父进程：读回 errcode -> waitpid。当前安装身份失效返回 ERR_MODULE_ROOT_KEY，
//           子进程无法读取错误码时 ERR_READ_CHILD_ERRCODE。
namespace {
KModErr select_test_identity() {
    return kernel_module::maplesu_request_worker_admission();
}

KModErr finish_test_child(fork_pipe_info& pipe, KModErr error) {
    pipe.close_all();
    if (pipe.child_pid <= 0) return is_failed(error) ? error : KModErr::ERR_READ_CHILD_ERRCODE;
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pipe.child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (is_ok(error) && (waited != pipe.child_pid || !WIFEXITED(status) || WEXITSTATUS(status)))
        error = KModErr::ERR_READ_CHILD_ERRCODE;
    return error;
}

template<class Operation>
KModErr flag_worker(const char* suffix, Operation operation) {
    RETURN_IF_ERROR(select_test_identity());
    fork_pipe_info pipe;
    KModErr error = KModErr::ERR_READ_CHILD_ERRCODE;
    if (fork_pipe_child_process(pipe)) {
        try {
            storage::WorkerScope worker;
            error = worker.root();
            std::string path;
            if (is_ok(error)) error = storage::current_kernel_directory(path);
            if (is_ok(error) && path.empty()) error = KModErr::ERR_MODULE_ROOT_KEY;
            if (is_ok(error)) error = operation(path + suffix);
            const auto closed = worker.close_checked();
            if (is_ok(error)) error = closed;
        } catch (const std::bad_alloc&) { error = KModErr::ERR_NO_MEM; }
        catch (const std::length_error&) { error = KModErr::ERR_NO_MEM; }
        const bool sent = write_errcode_from_child(pipe, error);
        pipe.close_all();
        _exit(sent ? 0 : 1);
    }
    if (pipe.child_pid <= 0 || !read_errcode_from_child(pipe, error)) error = KModErr::ERR_READ_CHILD_ERRCODE;
    return finish_test_child(pipe, error);
}

KModErr set_flag(const char* suffix, bool present) {
    return flag_worker(suffix, [present](const std::string& path) {
        if (!present) {
            if (unlink(path.c_str()) != 0 && errno != ENOENT) return KModErr::ERR_MODULE_DEL_FILE;
        } else {
            if (!file_utils::create_empty_file(path.c_str())) return KModErr::ERR_MODULE_CREATE_EMPTY_FILE;
            if (!file_utils::set_file_selinux_access_mode(path.c_str(), file_utils::SelinuxFileFlag::Default))
                return KModErr::ERR_SET_FILE_SELINUX;
        }
        sync();
        return KModErr::OK;
    });
}

KModErr query_flag(const char* suffix, bool& value) {
    RETURN_IF_ERROR(select_test_identity());
    fork_pipe_info pipe;
    KModErr error = KModErr::ERR_READ_CHILD_ERRCODE;
    if (fork_pipe_child_process(pipe)) {
        int present = 0;
        try {
            storage::WorkerScope worker;
            error = worker.root();
            std::string path;
            if (is_ok(error)) error = storage::current_kernel_directory(path);
            if (is_ok(error) && path.empty()) error = KModErr::ERR_MODULE_ROOT_KEY;
            if (is_ok(error)) {
                path += suffix;
                struct stat state{};
                if (stat(path.c_str(), &state) == 0) present = 1;
                else if (errno != ENOENT) error = KModErr::ERR_MODULE_OPEN_FILE;
            }
            const auto closed = worker.close_checked();
            if (is_ok(error)) error = closed;
        } catch (const std::bad_alloc&) { error = KModErr::ERR_NO_MEM; }
        catch (const std::length_error&) { error = KModErr::ERR_NO_MEM; }
        const bool sent_error = write_errcode_from_child(pipe, error);
        const bool sent_value = write_int_from_child(pipe, present);
        pipe.close_all();
        _exit(sent_error && sent_value ? 0 : 1);
    }
    int present = 0;
    if (pipe.child_pid <= 0 || !read_errcode_from_child(pipe, error)) error = KModErr::ERR_READ_CHILD_ERRCODE;
    else if (!read_int_from_child(pipe, present) && is_ok(error)) error = KModErr::ERR_READ_CHILD_INT;
    error = finish_test_child(pipe, error);
    if (is_ok(error)) value = present == 1;
    return error;
}
} // namespace

KModErr set_boot_fail_protect_enabled(bool enabled) {
    return set_flag("init.boot_fail_protect", enabled);
}
bool is_boot_fail_protect_enabled() {
    bool enabled = false;
    (void)query_flag("init.boot_fail_protect", enabled);
    return enabled;
}
KModErr set_adb_forced_disabled(bool disabled) {
    // Preserve the SDK's existing inverted file contract.
    return set_flag("adb_forced_disabled", !disabled);
}
bool is_adb_forced_disabled() {
    bool disabled = false;
    (void)query_flag("adb_forced_disabled", disabled);
    return disabled;
}

const char* get_root_status_report() {
    return root_status_report::get_root_status_report();
}

//   与其它接口一样在 fork 子进程里执行；stdout/stderr 被重定向进字符串，
//   结果与错误码通过 fork_pipe_info 回传，父进程调用回调把文本交给调用方。
KModErr test_maplesu_basics_with_cb(BasicItem item,
                                   void (*cb)(const char*)) {
    RETURN_IF_ERROR(select_test_identity());

    // （环境 Running 时返回 ERR_MODULE_MUST_UNINSTALL）。
    switch (item) {
        case BasicItem::Channel:
        case BasicItem::KernelBase:
        case BasicItem::PhysAddrCalc:
            break;
        default:
            if (maplesu::sdk::get_maplesu_environment_state() ==
                MapleSUEnvState::Running) {
                return KModErr::ERR_MODULE_MUST_UNINSTALL;
            }
            break;
    }

    std::string report;
    fork_pipe_info pipe;
    KModErr err = KModErr::ERR_MODULE_TEST_CHANNEL;
    if (fork_pipe_child_process(pipe)) {
        storage::WorkerScope worker;
        RedirectStdToString& redirector = RedirectStdToString::instance();
        redirector.redirect(report, RedirectStdToString::OpenMode::Reset);

        bool run_test = true;
        if (worker.owner_mode()) {
            err = worker.root();
            run_test = is_ok(err);
        }
        switch (item) {
            case BasicItem::Channel:
            case BasicItem::KernelBase:
            case BasicItem::PhysAddrCalc:
                break;
            default: {
                const KModErr root_err = worker.owner_mode() ? err : worker.root();
                if (is_failed(root_err)) {
                    err = root_err;
                    run_test = false;
                }
                break;
            }
        }
        if (run_test) {
            printf("-----Test Basics Item [%d]-----\n", static_cast<int>(item));
            switch (item) {
                case BasicItem::Channel: {
                    uint64_t result = 0;
                    std::function<KModErr(asmjit::a64::Assembler*, asmjit::a64::GpX&)> emit =
                        unsafe_test_channel();
                    auto asm_ctx = init_aarch64_asm();
                    asmjit::a64::Assembler* assembler = asm_ctx.assembler();
                    kernel_module::arm64_module_asm_func_start(assembler);
                    asmjit::a64::GpX return_reg = asmjit::a64::regs::x0;
                    KModErr test_err = emit(assembler, return_reg);
                    if (is_ok(test_err)) {
                        kernel_module::arm64_module_asm_func_end(assembler, return_reg);
                        test_err = kernel_module::execute_kernel_asm_func(
                            aarch64_asm_to_bytes(assembler), result);
                    }
                    err = test_err;
                    if (is_ok(err) && result != 0x12345) {
                        err = KModErr::ERR_MODULE_TEST_CHANNEL;
                    }
                    break;
                }
                case BasicItem::KernelBase: {
                    uint64_t kernel_base = 0;
                    err = kernel_module::get_kernel_base_vaddr(kernel_base);
                    if (is_ok(err)) {
                        printf("Output addr: %p\n", reinterpret_cast<void*>(kernel_base));
                    }
                    break;
                }
                case BasicItem::WriteTest: {
                    uint64_t kernel_halt = 0;
                    err = kernel_module::kallsyms_lookup_name("kernel_halt", kernel_halt);
                    if (is_ok(err)) {
                        printf("Output addr: %p\n", reinterpret_cast<void*>(kernel_halt));
                        uint8_t buffer[16] = {0};
                        err = kernel_module::read_kernel_mem(kernel_halt, buffer,
                                                             sizeof(buffer));
                        if (is_ok(err)) {
                            printf("read_kernel_mem (before) Buffer before:");
                            for (size_t i = 0; i < sizeof(buffer); ++i) {
                                printf(" %02hhx", buffer[i]);
                            }
                            putchar('\n');
                            const uint64_t write_value = 0x5566778811223344ULL;
                            err = kernel_module::write_kernel_mem(
                                kernel_halt, &write_value, sizeof(write_value),
                                static_cast<kernel_module::KernMemProt>(2));
                            if (is_ok(err)) {
                                memset(buffer, 0, sizeof(buffer));
                                err = kernel_module::read_kernel_mem(kernel_halt, buffer,
                                                                     sizeof(buffer));
                                if (is_ok(err)) {
                                    printf("read_kernel_mem (after) Buffer after:");
                                    for (size_t i = 0; i < sizeof(buffer); ++i) {
                                        printf(" %02hhx", buffer[i]);
                                    }
                                    putchar('\n');
                                }
                            }
                        }
                    }
                    break;
                }
                case BasicItem::ReadTrampoline: {
                    uint64_t trampoline = 0;
                    err = kernel_module::get_empty_area_start_addr(trampoline);
                    if (is_ok(err)) {
                        printf("Output addr: %p\n", reinterpret_cast<void*>(trampoline));
                        uint8_t buffer[32] = {0};
                        err = kernel_module::read_kernel_mem(trampoline, buffer,
                                                             sizeof(buffer));
                        if (is_ok(err)) {
                            printf("read_kernel_mem buffer:");
                            for (size_t i = 0; i < sizeof(buffer); ++i) {
                                printf(" %02hhx", buffer[i]);
                            }
                            putchar('\n');
                        }
                    }
                    break;
                }
                case BasicItem::WriteTrampoline: {
                    uint64_t trampoline = 0;
                    err = kernel_module::get_empty_area_start_addr(trampoline);
                    if (is_ok(err)) {
                        printf("Output addr: %p\n", reinterpret_cast<void*>(trampoline));
                        const uint64_t write_value = 0x7788990033445566ULL;
                        err = kernel_module::write_kernel_mem(
                            trampoline, &write_value, sizeof(write_value),
                            static_cast<kernel_module::KernMemProt>(2));
                    }
                    break;
                }
                case BasicItem::PhysAddrCalc: {
                    uint64_t vmemmap = 0;
                    err = kernel_module::export_symbol::get_vmemmap(vmemmap);
                    if (is_ok(err)) {
                        printf("vmemmap:%lx\n", static_cast<unsigned long>(vmemmap));
                        uint32_t struct_page_size = 0;
                        err = kernel_module::get_struct_page_size(struct_page_size);
                        if (is_ok(err)) {
                            printf("struct_page_size:%x\n", struct_page_size);
                            uint64_t physvirt_offset = 0;
                            {
                                // SymbolLookupSilentGuard_c67c0e4f…；随包头里该类型的
                                // hash 后缀是 8dfccc5c…（版本差异），行为相同：
                                // 查找期间静音符号解析打印。
                                SymbolLookupSilentGuard_8dfccc5cf454087c7314725d3487e703 guard;
                                (void)kernel_module::kallsyms_lookup_name(
                                    "physvirt_offset", physvirt_offset);
                            }
                            printf("physvirt_offset:%lx\n",
                                   static_cast<unsigned long>(physvirt_offset));
                            uint64_t phys_offset = 0;
                            err = kernel_module::export_symbol::get_PHYS_OFFSET(
                                phys_offset);
                            if (is_ok(err)) {
                                printf("PHYS_OFFSET:%lx\n",
                                       static_cast<unsigned long>(phys_offset));
                                uint64_t page_offset = 0;
                                err = kernel_module::export_symbol::get_PAGE_OFFSET(
                                    page_offset);
                                if (is_ok(err)) {
                                    printf("PAGE_OFFSET:%lx\n",
                                           static_cast<unsigned long>(page_offset));
                                }
                            }
                        }
                    }
                    break;
                }
                default:
                    err = KModErr::ERR_MODULE_PARAM;
                    break;
            }
        }
        redirector.restore();
        const auto closed = worker.close_checked();
        if (is_ok(err)) err = closed;
        write_errcode_from_child(pipe, err);
        write_string_from_child(pipe, report);
        pipe.close_all();
        _exit(0);
    }

    if (!read_errcode_from_child(pipe, err)) {
        err = KModErr::ERR_READ_CHILD_ERRCODE;
    }
    if (!read_string_from_child(pipe, report) && is_ok(err)) {
        err = KModErr::ERR_READ_CHILD_STRING;
    }
    err = finish_test_child(pipe, err);
    if (cb) cb(report.c_str());
    return err;
}

//   环境 Running 时返回 ERR_MODULE_MUST_UNINSTALL（与 test_maplesu_basics 不同，
//   这里对所有模块名都先做该检查）。五个模块名分别走 module_root /
//   module_su_redirect / module_tombstone_purge 三个内置模块。
KModErr test_maplesu_deafult_module_with_cb(DeafultModuleName name,
                                           void (*cb)(const char*)) {
    RETURN_IF_ERROR(select_test_identity());
    if (maplesu::sdk::get_maplesu_environment_state() == MapleSUEnvState::Running) {
        return KModErr::ERR_MODULE_MUST_UNINSTALL;
    }

    std::string report;
    fork_pipe_info pipe;
    KModErr err = KModErr::ERR_MODULE_TEST_CHANNEL;
    if (fork_pipe_child_process(pipe)) {
        storage::WorkerScope worker;
        RedirectStdToString& redirector = RedirectStdToString::instance();
        redirector.redirect(report, RedirectStdToString::OpenMode::Reset);

        // “打印版”内置模块；其余先切根。
        const bool print_variant = (name == DeafultModuleName::RootBridgePrint);
        bool root_ok = true;
        if (worker.owner_mode()) {
            err = worker.root();
            root_ok = is_ok(err);
        }
        if (print_variant) {
            if (root_ok) puts("-----Test Default Module-----");
        } else {
            const KModErr root_err = worker.owner_mode() ? err : worker.root();
            if (is_failed(root_err)) {
                err = root_err;
                root_ok = false;
            } else {
                puts("-----Test Default Module-----");
            }
        }

        if (root_ok) {
            switch (name) {
                case DeafultModuleName::RootBridgePrint:
                case DeafultModuleName::RootBridgeExec: {
                    module_desc desc = {};
                    strncpy(desc.name,
                            default_modules::module_root::get_module_eng_name(),
                            sizeof(desc.name) - 1);
                    TestModuleBannerGuard banner(desc, true);
                    storage::StorageContext context;
                    const auto located = storage::current_context(context);
                    if (is_failed(located)) { err = located; banner.err_ = err; break; }
                    std::string kernel_dir_name = context.locator.kernel_component;
                    std::string system_dir_name = context.locator.system_component;
                    const std::vector<std::string> dirs = {kernel_dir_name,
                                                           system_dir_name};
                    const KModErr init_err =
                        default_modules::module_root::init_anti_detect_root_env(
                            dirs, print_variant);
                    banner.err_ = init_err;
                    err = init_err;
                    break;
                }
                case DeafultModuleName::SuRedirectPrint:
                case DeafultModuleName::SuRedirectExec: {
                    module_desc desc = {};
                    strncpy(desc.name,
                            default_modules::module_su_redirect::get_module_eng_name(),
                            sizeof(desc.name) - 1);
                    TestModuleBannerGuard banner(desc, true);
                    char data_dir_buffer[1024] = {0};
                    storage::StorageContext context;
                    const auto located = storage::current_context(context);
                    if (is_failed(located)) { err = located; banner.err_ = err; break; }
                    std::string system_dir_name = context.locator.system_component;
                    snprintf(data_dir_buffer, sizeof(data_dir_buffer), "/data/%s/",
                             system_dir_name.c_str());
                    const std::string su_path =
                        (std::filesystem::path(std::string(data_dir_buffer)) / "su")
                            .string();
                    const KModErr init_err = default_modules::module_su_redirect::init(
                        su_path.c_str(),
                        name == DeafultModuleName::SuRedirectPrint);
                    banner.err_ = init_err;
                    err = init_err;
                    break;
                }
                case DeafultModuleName::TombstonesPurgePrint: {
                    err = default_modules_helper::init_module_tombstone_purge(true, false);
                    break;
                }
                default:
                    err = KModErr::ERR_MODULE_PARAM;
                    break;
            }
        }
        redirector.restore();
        const auto closed = worker.close_checked();
        if (is_ok(err)) err = closed;
        write_errcode_from_child(pipe, err);
        write_string_from_child(pipe, report);
        pipe.close_all();
        _exit(0);
    }

    if (!read_errcode_from_child(pipe, err)) {
        err = KModErr::ERR_READ_CHILD_ERRCODE;
    }
    if (!read_string_from_child(pipe, report) && is_ok(err)) {
        err = KModErr::ERR_READ_CHILD_STRING;
    }
    err = finish_test_child(pipe, err);
    if (cb) cb(report.c_str());
    return err;
}

//   子进程：初始化日志文件重定向 -> 读 install.mode / install_config.exploit ->
//   调用内置 module_selinux::init(InstallMode, ExploitMethod) -> 可选 tombstone 净化；
//   父进程：读回 errcode -> waitpid；成功时再调用 safe_restart_zygote64()。
KModErr restart_zygote64() {
    RETURN_IF_ERROR(select_test_identity());

    fork_pipe_info pipe;
    KModErr err = KModErr::ERR_MODULE_TEST_CHANNEL;
    if (fork_pipe_child_process(pipe)) {
        storage::WorkerScope worker;
        KModErr child_err = worker.root();
        if (is_ok(child_err)) {
            std::string kernel_directory;
            child_err = storage::current_kernel_directory(kernel_directory);
            if (is_ok(child_err) && kernel_directory.empty()) child_err = KModErr::ERR_MODULE_ROOT_KEY;
            if (is_failed(child_err)) {
                const auto closed = worker.close_checked();
                (void)closed;
                const bool sent = write_errcode_from_child(pipe, child_err);
                pipe.close_all();
                _exit(sent ? 0 : 1);
            }
            const std::string log_path = kernel_directory + "init.log";
            RedirectStdToFile::instance().redirect(log_path,
                                                  RedirectStdToFile::OpenMode::Append);
            puts("start executing reset env module");
            {
                module_desc desc = {};
                strncpy(desc.name,
                        default_modules::module_selinux::get_module_eng_name(),
                        sizeof(desc.name) - 1);
                TestModuleBannerGuard banner(desc, true);
                child_err = worker.root();
                if (is_ok(child_err)) {
                    // 读 install.mode（内容 "hotload" => HotLoad，否则 Boot）
                    int install_mode = 0;
                    const std::string mode_path =
                        (std::filesystem::path(kernel_directory) /
                         "install.mode")
                            .string();
                    std::string mode_text;
                    if (!file_utils::file_exists(std::filesystem::path(mode_path))) {
                        child_err = KModErr::ERR_MODULE_FILE_NOT_EXIST;
                    } else if (!file_utils::read_text_file(mode_path.c_str(), mode_text)) {
                        child_err = KModErr::ERR_MODULE_READ_FILE;
                    } else {
                        install_mode = (mode_text == "hotload") ? 1 : 0;
                    }
                    if (is_ok(child_err)) {
                        // 读 install_config.exploit（"cve2026_43499" => CVE2026_43499）
                        int exploit_method = 0;
                        const std::string exploit_path =
                            (std::filesystem::path(kernel_directory) /
                             "install_config.exploit")
                                .string();
                        std::string exploit_text;
                        if (!file_utils::file_exists(
                                std::filesystem::path(exploit_path))) {
                            child_err = KModErr::ERR_MODULE_FILE_NOT_EXIST;
                        } else if (!file_utils::read_text_file(exploit_path.c_str(),
                                                               exploit_text)) {
                            child_err = KModErr::ERR_MODULE_READ_FILE;
                        } else {
                            // 只有 "cve2026_43499" 会映射到 ExploitMethod 2。
                            const bool is_magica = (exploit_text == "magica");
                            (void)is_magica;
                            exploit_method =
                                (exploit_text == "cve2026_43499") ? 2 : 0;
                        }
                        if (is_ok(child_err)) {
                            child_err = worker.root();
                            if (is_ok(child_err)) {
                                child_err = default_modules::module_selinux::init(
                                    static_cast<maplesu::sdk::InstallMode>(install_mode),
                                    static_cast<maplesu::sdk::ExploitMethod>(
                                        exploit_method));
                            }
                        }
                    }
                }
                banner.err_ = child_err;
            }
            if (is_ok(child_err)) {
                (void)default_modules_helper::init_module_tombstone_purge(false, true);
            }
        }
        err = child_err;
        const auto closed = worker.close_checked();
        if (is_ok(err)) err = closed;
        write_errcode_from_child(pipe, err);
        pipe.close_all();
        _exit(0);
    }
    if (!read_errcode_from_child(pipe, err)) {
        err = KModErr::ERR_READ_CHILD_ERRCODE;
    }
    err = finish_test_child(pipe, err);
    if (is_ok(err)) {
        err = safe_restart_zygote64();
    }
    return err;
}

namespace runtime_settings {
KModErr query_boot_fail_protect(bool& value) {
    return query_flag("init.boot_fail_protect", value);
}
KModErr query_adb_forced_disabled(bool& value) {
    return query_flag("adb_forced_disabled", value);
}
}

}  // namespace maplesu::sdk
