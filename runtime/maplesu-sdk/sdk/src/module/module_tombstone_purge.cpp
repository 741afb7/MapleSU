// module tombstone purge implementation.
//
#include "kernel_module_kit_umbrella.h"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <ctime>

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <functional>
#include <map>
#include <new>
#include <string>
#include <system_error>
#include <thread>
#include <vector>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#include "private_module_tombstone_purge.h"
#include "maplesu_storage_private.h"
#include "maplesu_request_private.h"
#include "maplesu_storage_worker_private.h"
#include "../maplesu/compat/fork_legacy_abi.h"
#include <signal.h>

namespace {

constexpr char kTombstonesDir[] = "/data/tombstones";

constexpr size_t kTombstoneFileMinLength = 12;
constexpr size_t kTombstoneFileIndexOffset = 10;

constexpr const char* kSelinuxXattrName = "security.selinux";
constexpr const char* kSystemFileSelinuxContext = "u:object_r:system_file:s0";
constexpr size_t kSystemFileSelinuxContextLength = 26;
constexpr mode_t kSystemFileMode = 0777;

}  // namespace

// 全局补丁安装函数。
//   (= KModErr::ERR_MODULE_ASM：没有安装任何内核补丁)。
std::function<KModErr()> g_apply_kernel_patch = []() -> KModErr {
    return KModErr::ERR_MODULE_ASM;
};

static const char* const kAnrchorDirs[] = {"/data/unencrypted", "/data/bootchart"};

static KModErr validate_purge_context(const std::string& init_path) {
    maplesu::storage::StorageContext context;
    RETURN_IF_ERROR(maplesu::storage::current_context(context));
    const char* prefix = "/data/";
    if ((std::filesystem::path(std::string(prefix) + context.locator.kernel_component) /
        "init").string() != init_path) {
        errno = ESTALE;
        return KModErr::ERR_MODULE_ROOT_KEY;
    }
    return KModErr::OK;
}

// is_android_startup_completed
//   __system_property_get("sys.boot_completed") 结果非空且等于 "1"。
static bool is_android_startup_completed() {
    char property_value[92] = {0};
    const int length = __system_property_get("sys.boot_completed", property_value);
    if (length < 1) {
        return false;
    }
    return std::string(property_value, static_cast<size_t>(length)) == "1";
}

// exec_kernel_shellcode_no_result
//   初始化 asmjit 上下文 → arm64 函数序言 → 让调用方生成指令 → 函数尾声 →
//   取机器码 → 交给 kernel_module::execute_kernel_asm_func 在内核态执行。
static KModErr exec_kernel_shellcode_no_result(
    std::function<KModErr(asmjit::a64::Assembler*)> emit_shellcode) {
    // aarch64_asm_ctx / init_aarch64_asm / aarch64_asm_to_bytes 来自
    // aarch64_asm_helper.h 的匿名命名空间（mangling _ZN12_GLOBAL__N_1…），
    aarch64_asm_ctx asm_ctx = init_aarch64_asm();
    asmjit::a64::Assembler* assembler = asm_ctx.assembler();

    kernel_module::arm64_module_asm_func_start(assembler);
    const KModErr emit_err = emit_shellcode(assembler);
    if (emit_err != KModErr::OK) {
        return emit_err;
    }
    kernel_module::arm64_module_asm_func_end(assembler);

    uint64_t output_result = 0;
    const std::vector<uint8_t> shellcode = aarch64_asm_to_bytes(assembler);
    if (asm_ctx.has_error() || shellcode.empty()) return KModErr::ERR_MODULE_ASM;
    return kernel_module::execute_kernel_asm_func(shellcode, output_result);
}

// Preserve kern_path's signed runtime status separately from transport success.
// The dispatch boundary is conservative: after entering execute, an error does
// not prove that the generated function did not run.
static KModErr get_kernel_shellcode_u64_result(
    std::function<KModErr(asmjit::a64::Assembler*, asmjit::a64::GpX&)> emitter,
    uint64_t& output_result, bool& execution_attempted) {
    execution_attempted = false;
    aarch64_asm_ctx asm_ctx = init_aarch64_asm();
    asmjit::a64::Assembler* assembler = asm_ctx.assembler();
    kernel_module::arm64_module_asm_func_start(assembler);
    asmjit::a64::GpX result_reg = asmjit::a64::regs::x0;
    RETURN_IF_ERROR(emitter(assembler, result_reg));
    kernel_module::arm64_module_asm_func_end(assembler, result_reg);
    const std::vector<uint8_t> shellcode = aarch64_asm_to_bytes(assembler);
    if (asm_ctx.has_error() || shellcode.empty()) return KModErr::ERR_MODULE_ASM;
    execution_attempted = true;
    return kernel_module::execute_kernel_asm_func(shellcode, output_result);
}

// m093-local owner. A missing EXEC completion is a retained resource, not a
// reason to replay kern_path/path_put or free a buffer they may still use.
namespace {

struct kpath {
    uint64_t mnt = 0;
    uint64_t dentry = 0;
};

struct scoped_kpath {
    enum class State : uint8_t { Empty, PageOnly, PathOwned, Retained };
    kpath path_{};
    uint64_t kaddr_ = 0;
    KModErr cleanup_error_ = KModErr::OK;
    int cleanup_errno_ = 0;
    State state_ = State::Empty;

    scoped_kpath() = default;
    scoped_kpath(const scoped_kpath&) = delete;
    scoped_kpath& operator=(const scoped_kpath&) = delete;
    ~scoped_kpath() {
        kernel_module::CleanupReceiptScope preserve_failure;
        (void)reset();
    }

    uint64_t dentry_kaddr() const { return path_.dentry; }

    void retain(const char* stage, KModErr error) noexcept {
        cleanup_error_ = error;
        cleanup_errno_ = errno;
        state_ = State::Retained;
        std::fprintf(stderr, "MapleSU tombstone path: retained page=%llx stage=%s status=%lld errno=%d; no retry\n",
            static_cast<unsigned long long>(kaddr_), stage,
            static_cast<long long>(error), cleanup_errno_);
        errno = cleanup_errno_;
    }

    KModErr reset() noexcept {
        if (state_ == State::Retained) {
            errno = cleanup_errno_;
            return cleanup_error_;
        }
        if (!kaddr_) return KModErr::OK;
        const char* stage = "path_put";
        try {
            if (state_ == State::PathOwned) {
                const KModErr error = exec_kernel_shellcode_no_result(
                    [page = kaddr_](asmjit::a64::Assembler* a) -> KModErr {
                        KModErr emit_error = KModErr::ERR_MODULE_PARAM;
                        kernel_module::export_symbol::path_put(a, emit_error, page);
                        return emit_error;
                    });
                if (is_failed(error)) { retain(stage, error); return error; }
                state_ = State::PageOnly;
                path_ = kpath{};
            }
            stage = "free_kernel_mem";
            const KModErr error = kernel_module::free_kernel_mem(kaddr_);
            if (is_failed(error)) { retain(stage, error); return error; }
        } catch (const std::bad_alloc&) {
            errno = ENOMEM;
            retain(stage, KModErr::ERR_NO_MEM);
            return cleanup_error_;
        } catch (...) {
            errno = EIO;
            retain(stage, KModErr::ERR_MODULE_ASM);
            return cleanup_error_;
        }
        kaddr_ = 0;
        state_ = State::Empty;
        return KModErr::OK;
    }
};

static_assert(sizeof(kpath) == 16, "kernel struct path is two arm64 pointers");
static_assert(offsetof(scoped_kpath, kaddr_) == 16, "path owner field layout");

static KModErr acquire_kpath(const std::string& path_name, scoped_kpath& out_path) {
    scoped_kpath candidate;
    uint64_t page = 0;
    KModErr error = kernel_module::alloc_kernel_mem(
        kernel_module::get_page_size(), page);
    if (is_failed(error)) return error;
    candidate.kaddr_ = page;
    candidate.state_ = scoped_kpath::State::PageOnly;
    error = kernel_module::fill00_kernel_mem(candidate.kaddr_,
        kernel_module::get_page_size(), kernel_module::KernMemProt::KMP_RW);
    if (is_failed(error)) return error;

    bool execution_attempted = false;
    uint64_t result = 0;
    try {
        error = get_kernel_shellcode_u64_result(
            [&path_name, page = candidate.kaddr_](asmjit::a64::Assembler* a,
                    asmjit::a64::GpX& result_reg) -> KModErr {
                KModErr emit_error = KModErr::ERR_MODULE_PARAM;
                aarch64_asm_set_x_cstr_ptr(a, asmjit::a64::regs::x10, path_name);
                kernel_module::export_symbol::kern_path(a, emit_error, asmjit::a64::regs::x10,
                    kernel_module::export_symbol::LookupFlags::LOOKUP_FOLLOW, page);
                if (is_ok(emit_error)) result_reg = asmjit::a64::regs::x0;
                return emit_error;
            }, result, execution_attempted);
    } catch (const std::bad_alloc&) {
        errno = ENOMEM;
        error = KModErr::ERR_NO_MEM;
    } catch (...) {
        errno = EIO;
        error = KModErr::ERR_MODULE_ASM;
    }
    if (is_failed(error)) {
        if (execution_attempted) candidate.retain("kern_path EXEC completion", error);
        return error;
    }
    const int32_t runtime_status = static_cast<int32_t>(static_cast<uint32_t>(result));
    if (runtime_status != 0) {
        std::fprintf(stderr, "MapleSU tombstone path: kern_path runtime status=%d\n", runtime_status);
        errno = runtime_status < 0 && runtime_status >= -4095 ? -runtime_status : EIO;
        return KModErr::ERR_MODULE_OPEN_FILE;
    }
    candidate.state_ = scoped_kpath::State::PathOwned;
    error = kernel_module::read_kernel_mem(candidate.kaddr_, &candidate.path_, sizeof(candidate.path_));
    if (is_failed(error)) return error;

    error = out_path.reset();
    if (is_failed(error)) return error;
    out_path.path_ = candidate.path_;
    out_path.kaddr_ = candidate.kaddr_;
    out_path.state_ = candidate.state_;
    candidate.path_ = kpath{};
    candidate.kaddr_ = 0;
    candidate.state_ = scoped_kpath::State::Empty;
    return KModErr::OK;
}

// get_tombstone_times：收集目录下所有 "tombstone_<digits>" 文件的最后写时间。
static std::map<std::string, std::filesystem::file_time_type> get_tombstone_times(
    const std::string& dir_path) {
    std::map<std::string, std::filesystem::file_time_type> times;
    const std::filesystem::path directory(dir_path);
    if (!std::filesystem::exists(directory)) {
        return times;
    }
    for (const auto& entry : std::filesystem::directory_iterator(directory)) {
        const std::string file_name = entry.path().filename().string();
        if (file_name.size() < kTombstoneFileMinLength) {
            continue;
        }
        if (file_name.find("tombstone_", 0) != 0) {
            continue;
        }
        bool all_digits = true;
        for (size_t index = kTombstoneFileIndexOffset; index < file_name.size(); ++index) {
            const char ch = file_name[index];
            if (ch < '0' || ch > '9') {
                all_digits = false;
                break;
            }
        }
        if (!all_digits) {
            continue;
        }
        std::error_code ec;
        const auto write_time = entry.last_write_time(ec);
        if (ec) {
            continue;
        }
        times[file_name] = write_time;
    }
    return times;
}

// trigger_crash_once_and_get_index：fork+exec 一次 init --suicide 制造 tombstone，
//   轮询目录，返回新出现的 "tombstone_<n>" 的 n；30 次 × 100ms 内没出现返回 -1。
static KModErr trigger_crash_once_and_get_index(const std::string& dir_path,
    const std::string& init_path, int32_t& out_index) {
    RETURN_IF_ERROR(validate_purge_context(init_path));
    std::map<std::string, std::filesystem::file_time_type> before =
        get_tombstone_times(dir_path);

    const uint32_t random_length = random_utils::random_u32_from_i32() % 10 + 3;
    const std::string random_process_name =
        random_utils::generate_random_str_with_dot(random_length);

    const pid_t child_pid = fork();
    if (child_pid < 0) {
        return KModErr::ERR_READ_CHILD_ERRCODE;
    }
    if (child_pid == 0) {
        execl(init_path.c_str(), random_process_name.c_str(), "--suicide",
              static_cast<char*>(nullptr));
        printf("execl failed: %d\n", errno);
        _exit(1);
    }

    int status = 0;
    pid_t waited;
    do { waited = waitpid(child_pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != child_pid || !WIFSIGNALED(status)) return KModErr::ERR_READ_CHILD_ERRCODE;

    for (int attempt = 0; attempt < 30; ++attempt) {
        std::map<std::string, std::filesystem::file_time_type> after =
            get_tombstone_times(dir_path);
        for (const auto& entry : after) {
            const auto known = before.find(entry.first);
            if (known != before.end() && known->second == entry.second) {
                continue;  // 老条目且时间未变
            }
            // 新 tombstone 或已有文件被改写：文件名 "tombstone_<n>" 的 n
            out_index = atoi(entry.first.c_str() + kTombstoneFileIndexOffset);
            return KModErr::OK;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    errno = ETIMEDOUT;
    return KModErr::ERR_READ_CHILD_DATA;
}

// reset_tombstone_slot_index：探测并记账当前 tombstone slot 的最大下标。
//   1) 若磁盘记账里已有正数，直接沿用；
//   2) 否则反复触发 crash，直到下标回退（说明绕了一圈），把最大值写盘；
//   3) 再触发到下标达到该最大值，让 slot 轮转对齐。
static KModErr reset_tombstone_slot_index(const std::string& dir_path,
    const std::string& init_path) {
    // Standard 32-slot rotation needs 64 triggers. Keep larger valid rings,
    // but never create crash children forever if the observed slot stalls.
    constexpr int kTriggerBudget = 1024;
    int triggers_remaining = kTriggerBudget;
    const auto trigger_once = [&](int32_t& index) -> KModErr {
        if (triggers_remaining == 0) {
            errno = ETIMEDOUT;
            return KModErr::ERR_READ_CHILD_DATA;
        }
        --triggers_remaining;
        return trigger_crash_once_and_get_index(dir_path, init_path, index);
    };
    RETURN_IF_ERROR(validate_purge_context(init_path));
    int32_t max_slot_index = 0;
    const KModErr read_err =
        kernel_module::read_int32_disk_storage(
            "tombstones_max_slot.ENq6WiPzSN7NwMf0oIeAI6e4u9Ab8VmN", max_slot_index);
    if (read_err != KModErr::OK &&
        read_err != KModErr::ERR_MODULE_STORAGE_NOT_FOUND) {
        return read_err;
    }
    if (read_err != KModErr::OK || max_slot_index <= 0) {
        int32_t previous_index = -1;
        int32_t detected_max_index = -1;
        for (;;) {
            int32_t index = -1;
            const KModErr trigger_err = trigger_once(index);
            if (is_failed(trigger_err)) {
                puts("[module_tombstone_purge] unable to get current slot");
                puts("[module_tombstone_purge] get tombstones max slot failed");
                return trigger_err;
            }
            detected_max_index = std::max(index, detected_max_index);
            if (previous_index != -1 && index < previous_index) {
                // 下标回退 => 已经绕到最大 slot，记账后继续。
                printf("[module_tombstone_purge] max slot: %d\n", detected_max_index);
                RETURN_IF_ERROR(kernel_module::write_int32_disk_storage(
                    "tombstones_max_slot.ENq6WiPzSN7NwMf0oIeAI6e4u9Ab8VmN",
                    detected_max_index));
                if (detected_max_index == -1) {
                    puts("[module_tombstone_purge] get tombstones max slot failed");
                    return KModErr::ERR_READ_CHILD_DATA;
                }
                max_slot_index = detected_max_index;
                break;
            }
            previous_index = index;
        }
    }
    // 触发到下标达到 max_slot_index（对齐 slot 轮转位置）。
    for (;;) {
        int32_t index = -1;
        const KModErr trigger_err = trigger_once(index);
        if (is_failed(trigger_err)) {
            puts("[module_tombstone_purge] tigger crash error!");
            return trigger_err;
        }
        if (index < max_slot_index) {
            continue;
        }
        return KModErr::OK;
    }
}

}  // namespace

// namespace default_modules::module_tombstone_purge
//   _ZN15default_modules22module_tombstone_purgeL…（namespace + 内部链接），
//   所以这些函数写成 namespace 内的 static。
namespace default_modules {
namespace module_tombstone_purge {

// 16 字节 inode 时间字段补丁：Linux < 6.11.0 的 i_atime/i_mtime/i_ctime
// 是 16 字节的 struct timespec64，整块写回即可。
struct InodeTimePatch16 {
    uint32_t offset = 0;
    uint8_t bytes[16] = {};
};

// >= 6.11.0 把 sec/nsec 拆成独立字段：sec 是 8 字节、nsec 是 4 字节。
struct InodeTimePatch8 {
    uint32_t offset = 0;
    uint64_t value = 0;
};
struct InodeTimePatch4 {
    uint32_t offset = 0;
    uint32_t value = 0;
};

static_assert(sizeof(InodeTimePatch16) == 20, "16 字节时间字段补丁 = offset + 16B");
static_assert(sizeof(InodeTimePatch8) == 16, "sec 补丁 = offset + 8B");
static_assert(sizeof(InodeTimePatch4) == 8, "nsec 补丁 = offset + 4B");

// Legacy retains its relative argument; Owner uses its authenticated deployment.
static KModErr get_runtime_paths(std::string& duplicate_path, std::string& init_path,
    maplesu::storage::StorageContext& context) {
    duplicate_path.clear();
    init_path.clear();
    RETURN_IF_ERROR(maplesu::storage::current_context(context));
    const char* prefix = "/data/";
    duplicate_path = (std::filesystem::path(std::string(prefix) +
        context.locator.system_component) /
        "duplicate_qMEOYbxeWgEZAhAEY2Bg8rDn1rcLrvn4").string();
    init_path = (std::filesystem::path(std::string(prefix) +
        context.locator.kernel_component) / "init").string();
    return KModErr::OK;
}

// print_time：把 timespec 打印成 "标签  : YYYY-MM-DD HH:MM:SS.nnnnnnnnn"
static void print_time(const char* label, const timespec& time_value) {
    char time_buffer[64] = {0};
    struct tm broken_down = {};
    localtime_r(&time_value.tv_sec, &broken_down);
    strftime(time_buffer, sizeof(time_buffer), "%Y-%m-%d %H:%M:%S", &broken_down);
    printf("%-7s: %s.%09ld\n", label, time_buffer, time_value.tv_nsec);
}

// make_kernel_patch_apply_fn（< 6.11.0 内核）
//   返回一个 std::function：把 anchor 目录 inode 的三组 16 字节时间字段原值
//   写回 /data/tombstones 的 inode（kaddr + offset，KMP_RW）。
//   写之前先比较两者时间是否已经一致（dir_time_utils::is_same_dir_times_minute），
//   一致就什么都不做直接返回 OK。
static std::function<KModErr()> make_kernel_patch_apply_fn(
    const char* anchor_path, uint64_t inode_kaddr, const InodeTimePatch16 (&patches)[3]) {
    return [anchor_path, inode_kaddr, patches]() -> KModErr {
        if (dir_time_utils::is_same_dir_times_minute(kTombstonesDir, anchor_path)) {
            return KModErr::OK;
        }
        for (const InodeTimePatch16& patch : patches) {
            const KModErr err = kernel_module::write_kernel_mem(
                inode_kaddr + patch.offset, patch.bytes, sizeof(patch.bytes),
                kernel_module::KernMemProt::KMP_RW);
            if (err != KModErr::OK) {
                return err;
            }
        }
        return KModErr::OK;
    };
}

// make_kernel_patch_apply_fn（>= 6.11.0 内核）
//   同上，但 sec/nsec 字段分开写：3 个 8 字节 sec + 3 个 4 字节 nsec。
static std::function<KModErr()> make_kernel_patch_apply_fn(
    const char* anchor_path, uint64_t inode_kaddr, const InodeTimePatch8 (&patches_sec)[3],
    const InodeTimePatch4 (&patches_nsec)[3]) {
    return [anchor_path, inode_kaddr, patches_sec, patches_nsec]() -> KModErr {
        if (dir_time_utils::is_same_dir_times_minute(kTombstonesDir, anchor_path)) {
            return KModErr::OK;
        }
        for (const InodeTimePatch8& patch : patches_sec) {
            const KModErr err = kernel_module::write_kernel_mem(
                inode_kaddr + patch.offset, &patch.value, sizeof(patch.value),
                kernel_module::KernMemProt::KMP_RW);
            if (err != KModErr::OK) {
                return err;
            }
        }
        for (const InodeTimePatch4& patch : patches_nsec) {
            const KModErr err = kernel_module::write_kernel_mem(
                inode_kaddr + patch.offset, &patch.value, sizeof(patch.value),
                kernel_module::KernMemProt::KMP_RW);
            if (err != KModErr::OK) {
                return err;
            }
        }
        return KModErr::OK;
    };
}

// reset_tombstone_dir
//   目录为空时直接返回 g_apply_kernel_patch() 的结果（不改任何东西）；
//   否则（skip_slot_reset 为 false 时先重置 slot 记账）递归删除目录里所有条目，
//   最后调用 g_apply_kernel_patch() 把 inode 时间“伪装”回去。
static KModErr reset_tombstone_dir(bool skip_slot_reset, const std::string& init_path) {
    RETURN_IF_ERROR(validate_purge_context(init_path));
    if (!file_utils::is_dir_empty_noatime(kTombstonesDir)) {
        if (!skip_slot_reset) {
            RETURN_IF_ERROR(reset_tombstone_slot_index(std::string(kTombstonesDir), init_path));
        }
        const std::string tombstone_dir(kTombstonesDir);
        const std::filesystem::path tombstone_path(tombstone_dir);
        std::error_code ec;
        const bool exists = std::filesystem::exists(tombstone_path, ec);
        if (ec) {
            errno = ec.value();
            return KModErr::ERR_MODULE_OPEN_DIR;
        }
        if (exists) {
            if (!std::filesystem::is_directory(tombstone_path, ec) || ec) {
                errno = ec ? ec.value() : ENOTDIR;
                return KModErr::ERR_MODULE_OPEN_DIR;
            }
            std::filesystem::directory_iterator entries(tombstone_path, ec), end;
            if (ec) {
                errno = ec.value();
                return KModErr::ERR_MODULE_OPEN_DIR;
            }
            while (entries != end) {
                RETURN_IF_ERROR(validate_purge_context(init_path));
                std::error_code remove_ec;
                (void)std::filesystem::remove_all(entries->path(), remove_ec);
                if (remove_ec) {
                    printf("clear failed at %s : %s\n", entries->path().c_str(),
                           remove_ec.message().c_str());
                    errno = remove_ec.value();
                    return KModErr::ERR_MODULE_DEL_FILE;
                }
                entries.increment(ec);
                if (ec) {
                    errno = ec.value();
                    return KModErr::ERR_MODULE_OPEN_DIR;
                }
            }
        }
    }
    return g_apply_kernel_patch();
}

// get_module_eng_name  —— T 导出之一
const char* get_module_eng_name() {
    return "module_tombstone_purge";
}

// init  —— T 导出之一（消费者 link 依赖的入口）
KModErr init(bool enable, bool purge_all) {
    std::string duplicate_file_path, init_path;
    maplesu::storage::StorageContext storage_context;
    RETURN_IF_ERROR(get_runtime_paths(duplicate_file_path, init_path, storage_context));
    if (enable) {
        const KModErr hide_dir_err = maplesu::sdk::unsafe_create_hide_dir();
        if (hide_dir_err != KModErr::OK) {
            return hide_dir_err;
        }
    }

    {
        std::error_code exists_ec;
        const bool marker_exists = std::filesystem::exists(
            std::filesystem::path(duplicate_file_path), exists_ec);
        if (exists_ec) {
            errno = exists_ec.value();
            return KModErr::ERR_MODULE_OPEN_FILE;
        }
        if (!marker_exists) {
            std::ofstream marker_file(duplicate_file_path.c_str(),
                                      std::ios::out | std::ios::trunc | std::ios::binary);
            if (!marker_file.is_open()) {
                puts("create duplicate file failed.");
                return KModErr::ERR_MODULE_OPEN_FILE;
            }
            marker_file.close();
            if (!marker_file) {
                if (!errno) errno = EIO;
                return KModErr::ERR_MODULE_WRITE_FILE;
            }
            if (chmod(duplicate_file_path.c_str(), kSystemFileMode) == 0) {
                setxattr(duplicate_file_path.c_str(), kSelinuxXattrName,
                         kSystemFileSelinuxContext, kSystemFileSelinuxContextLength, 0);
            }
        }
    }

    KModErr err = kernel_module::write_int32_disk_storage(
        "tombstones_max_slot.ENq6WiPzSN7NwMf0oIeAI6e4u9Ab8VmN", -1);
    if (err != KModErr::OK) {
        return err;
    }

    const bool tombstones_dir_empty = file_utils::is_dir_empty_noatime(kTombstonesDir);
    const bool android_startup_completed = is_android_startup_completed();
    printf("first_tombstones_dir_empty:%d, first_android_startup:%d\n",
           tombstones_dir_empty ? 1 : 0, android_startup_completed ? 1 : 0);

    fd_guard tombstones_dir_fd(open(kTombstonesDir, O_RDONLY));
    if (tombstones_dir_fd.get() < 0) {
        puts("open all anrchor dirs failed");
        err = KModErr::ERR_MODULE_OPEN_DIR;
    } else {
        int anchor_dir_fd = -1;
        const char* anchor_path = nullptr;
        for (const char* candidate : kAnrchorDirs) {
            anchor_dir_fd = open(candidate, O_RDONLY | O_CLOEXEC);
            if (anchor_dir_fd >= 0) {
                anchor_path = candidate;
                break;
            }
            printf("open failed: %s, errno=%d\n", candidate, errno);
        }
        fd_guard anchor_dir_guard(anchor_dir_fd);
        if (anchor_dir_fd <= -1 || anchor_path == nullptr) {
            puts("open all anrchor dirs failed");
            err = KModErr::ERR_MODULE_OPEN_DIR;
        } else {
            scoped_kpath tombstones_kpath;
            scoped_kpath anchor_kpath;
            uint32_t d_inode_offset = 0;
            uint64_t inode_kaddr = 0;
            uint64_t anchor_inode_kaddr = 0;

            if (err == KModErr::OK) {
                err = acquire_kpath(std::string(kTombstonesDir), tombstones_kpath);
            }
            if (err == KModErr::OK) {
                err = acquire_kpath(std::string(anchor_path), anchor_kpath);
            }
            if (err == KModErr::OK) {
                err = kernel_module::get_dentry_d_inode_offset(d_inode_offset);
            }
            if (err == KModErr::OK) {
                err = kernel_module::read_kernel_mem(
                    tombstones_kpath.dentry_kaddr() + d_inode_offset, &inode_kaddr,
                    sizeof(inode_kaddr));
            }
            if (err == KModErr::OK) {
                err = kernel_module::read_kernel_mem(
                    anchor_kpath.dentry_kaddr() + d_inode_offset, &anchor_inode_kaddr,
                    sizeof(anchor_inode_kaddr));
            }
            if (err == KModErr::OK) {
                printf("inode:%lx\n", inode_kaddr);
                printf("anrchor_inode:%lx\n", anchor_inode_kaddr);

                if (kernel_module::is_kernel_version_less("6.11.0")) {
                    uint32_t atime_offset = 0;
                    uint32_t mtime_offset = 0;
                    uint32_t ctime_offset = 0;
                    err = kernel_module::get_inode_time_offset(atime_offset, mtime_offset,
                                                               ctime_offset);
                    if (err == KModErr::OK) {
                        printf("offset: i_atime=0x%x, i_mtime=0x%x, i_ctime=0x%x\n",
                               atime_offset, mtime_offset, ctime_offset);
                        if (!enable) {
                            InodeTimePatch16 patches[3] = {};
                            patches[0].offset = atime_offset;
                            patches[1].offset = mtime_offset;
                            patches[2].offset = ctime_offset;
                            err = kernel_module::read_kernel_mem(
                                anchor_inode_kaddr + atime_offset, patches[0].bytes,
                                sizeof(patches[0].bytes));
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + mtime_offset, patches[1].bytes,
                                    sizeof(patches[1].bytes));
                            }
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + ctime_offset, patches[2].bytes,
                                    sizeof(patches[2].bytes));
                            }
                            if (err == KModErr::OK) {
                                std::function<KModErr()> apply_fn = make_kernel_patch_apply_fn(
                                    anchor_path, inode_kaddr, patches);
                                g_apply_kernel_patch.swap(apply_fn);
                            }
                        }
                    }
                } else {
                    uint32_t atime_sec_offset = 0;
                    uint32_t atime_nsec_offset = 0;
                    uint32_t mtime_sec_offset = 0;
                    uint32_t mtime_nsec_offset = 0;
                    uint32_t ctime_sec_offset = 0;
                    uint32_t ctime_nsec_offset = 0;
                    err = kernel_module::get_inode_time_offset(
                        atime_sec_offset, atime_nsec_offset, mtime_sec_offset,
                        mtime_nsec_offset, ctime_sec_offset, ctime_nsec_offset);
                    if (err == KModErr::OK) {
                        printf("offset: i_atime=0x%x+0x%x, i_mtime=0x%x+0x%x, "
                               "i_ctime=0x%x+0x%x\n",
                               atime_sec_offset, atime_nsec_offset, mtime_sec_offset,
                               mtime_nsec_offset, ctime_sec_offset,
                               ctime_nsec_offset);
                        if (!enable) {
                            InodeTimePatch8 patches_sec[3] = {};
                            InodeTimePatch4 patches_nsec[3] = {};
                            patches_sec[0].offset = atime_sec_offset;
                            patches_sec[1].offset = mtime_sec_offset;
                            patches_sec[2].offset = ctime_sec_offset;
                            patches_nsec[0].offset = atime_nsec_offset;
                            patches_nsec[1].offset = mtime_nsec_offset;
                            patches_nsec[2].offset = ctime_nsec_offset;
                            err = kernel_module::read_kernel_mem(
                                anchor_inode_kaddr + atime_sec_offset,
                                &patches_sec[0].value,
                                sizeof(patches_sec[0].value));
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + mtime_sec_offset,
                                    &patches_sec[1].value,
                                    sizeof(patches_sec[1].value));
                            }
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + ctime_sec_offset,
                                    &patches_sec[2].value,
                                    sizeof(patches_sec[2].value));
                            }
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + atime_nsec_offset,
                                    &patches_nsec[0].value,
                                    sizeof(patches_nsec[0].value));
                            }
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + mtime_nsec_offset,
                                    &patches_nsec[1].value,
                                    sizeof(patches_nsec[1].value));
                            }
                            if (err == KModErr::OK) {
                                err = kernel_module::read_kernel_mem(
                                    anchor_inode_kaddr + ctime_nsec_offset,
                                    &patches_nsec[2].value,
                                    sizeof(patches_nsec[2].value));
                            }
                            if (err == KModErr::OK) {
                                std::function<KModErr()> apply_fn = make_kernel_patch_apply_fn(
                                    anchor_path, inode_kaddr, patches_sec,
                                    patches_nsec);
                                g_apply_kernel_patch.swap(apply_fn);
                            }
                        }
                    }
                }
            }
            if (is_failed(err)) {
                kernel_module::CleanupReceiptScope preserve_failure;
                (void)tombstones_kpath.reset();
                (void)anchor_kpath.reset();
            } else {
                err = tombstones_kpath.reset();
                if (is_failed(err)) {
                    kernel_module::CleanupReceiptScope preserve_failure;
                    (void)anchor_kpath.reset();
                } else {
                    err = anchor_kpath.reset();
                }
            }
        }
    }

    printf("make_kernel_patch_apply_fn: %s\n", to_string(err).c_str());
    if (err != KModErr::OK || enable) {
        return err;
    }

    err = reset_tombstone_dir(true, init_path);
    printf("reset_tombstone_dir: %s\n", to_string(err).c_str());
    if (err != KModErr::OK) {
        return err;
    }

    {
        struct stat dir_stat = {};
        if (stat(kTombstonesDir, &dir_stat) != 0) {
            printf("stat failed: %s, errno=%d, %s\n", kTombstonesDir, errno,
                   strerror(errno));
        } else {
            print_time("Access", dir_stat.st_atim);
            print_time("Modify", dir_stat.st_mtim);
            print_time("Change", dir_stat.st_ctim);
        }
    }

    maplesu::sdk::fork_pipe_info pipe;
    const bool child = maplesu::sdk::fork_pipe_child_process(pipe);
    if (!child) {
        if (pipe.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;
        int system_error = 0;
        if (!maplesu::sdk::read_errcode_from_child(pipe, err) ||
            !maplesu::sdk::read_int_from_child(pipe, system_error)) {
            err = KModErr::ERR_READ_CHILD_ERRCODE;
            system_error = errno;
        }
        pipe.close_all();
        if (is_failed(err)) {
            (void)kill(pipe.child_pid, SIGTERM);
            while (waitpid(pipe.child_pid, nullptr, 0) < 0 && errno == EINTR) {}
        } else {
            try {
                std::thread([pid = pipe.child_pid] {
                    while (waitpid(pid, nullptr, 0) < 0 && errno == EINTR) {}
                }).detach();
            } catch (const std::system_error& failure) {
                system_error = failure.code().value();
                (void)kill(pipe.child_pid, SIGTERM);
                while (waitpid(pipe.child_pid, nullptr, 0) < 0 && errno == EINTR) {}
                err = KModErr::ERR_READ_CHILD_ERRCODE;
            }
        }
        errno = system_error;
        return err;
    }

    // The child opens its own session; inherited paths grant no authority.
    maplesu::storage::WorkerScope worker(storage_context.module_id.c_str());
    err = worker.owner_mode() ? worker.root() : KModErr::OK;
    if (is_ok(err)) {
        maplesu::storage::StorageContext child_context;
        std::string child_duplicate_path, child_init_path;
        err = get_runtime_paths(child_duplicate_path, child_init_path, child_context);
        if (is_ok(err) && (child_duplicate_path != duplicate_file_path ||
            child_init_path != init_path || child_context.module_id != storage_context.module_id)) {
            errno = ESTALE;
            err = KModErr::ERR_MODULE_ROOT_KEY;
        }
    }
    const int startup_errno = errno;
    if (is_failed(err)) worker.close();
    const bool announced = maplesu::sdk::write_errcode_from_child(pipe, err) &&
        maplesu::sdk::write_int_from_child(pipe, startup_errno);
    pipe.close_all();
    if (is_failed(err) || !announced) {
        worker.close();
        _exit(1);
    }

    // ===== 子进程 =====
    sleep(0);
    if (purge_all) {
        sleep(10);
    }
    puts("[module_tombstone_purge] waiting android start");
    for (int waited = 0; waited < 60; ++waited) {
        if (is_android_startup_completed()) {
            break;
        }
        sleep(1);
    }
    puts("[module_tombstone_purge] keep running");
    sleep(5);
    if (!tombstones_dir_empty) {
        err = reset_tombstone_slot_index(std::string(kTombstonesDir), init_path);
    }
    if (is_ok(err)) err = reset_tombstone_dir(false, init_path);
    printf("[module_tombstone_purge] reset_tombstone_dir: %s\n",
           to_string(err).c_str());
    if (err == KModErr::OK) {
        for (int round = 0; round < 40; ++round) {
            sleep(3);
            err = validate_purge_context(init_path);
            if (is_failed(err)) break;
            file_lock_utils::RunningLockFile running_lock;
            const bool locked = running_lock.init(duplicate_file_path.c_str(), 0666);
            if (locked && !file_utils::is_dir_empty_noatime(kTombstonesDir)) {
                const KModErr clear_err = reset_tombstone_dir(false, init_path);
                if (clear_err != KModErr::OK) {
                    err = clear_err;
                    printf("[module_tombstone_purge] reset_tombstone_dir failed: %s\n",
                           to_string(clear_err).c_str());
                    break;
                } else {
                    puts("[module_tombstone_purge] triggered once!");
                }
            }
        }
        puts("[module_tombstone_purge] finished");
    }
    const KModErr close_err = worker.close_checked();
    if (is_failed(close_err)) {
        printf("[module_tombstone_purge] worker-close: %s\n", to_string(close_err).c_str());
        if (is_ok(err)) err = close_err;
    }
    _exit(is_ok(err) ? 0 : 1);
}

}  // namespace module_tombstone_purge
}  // namespace default_modules
