// module selinux implementation.
//
#include "kernel_module_kit_umbrella.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <string>
#include <vector>
#include <functional>
#include <new>
#include <fstream>
#include <filesystem>
#include <string_view>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <fcntl.h>

#include "private_module_selinux.h"
#include "maplesu_request_private.h"
#include "maplesu_hook_private.h"
#include "module_base_kernel_request.h"

namespace {

constexpr char kProcSuidDumpable[] = "/proc/sys/fs/suid_dumpable";                 // .str.176
constexpr char kDataDirFormat[] = "/data/%s/";                                    // .str.28
constexpr char kResetpropBinName[] = "resetprop";                                 // .str.27

constexpr char kBootSelinuxProp[] = "ro.boot.selinux";                            // .str.18
constexpr char kDebuggableProp[] = "ro.debuggable";                               // .str.19
constexpr char kSecureProp[] = "ro.secure";                                       // .str.21
constexpr char kAdbSecureProp[] = "ro.adb.secure";                                // .str.23
constexpr char kPropertyOne[] = "1";                                              // .str.20
constexpr char kPropertyZero[] = "0";                                             // .str.22
constexpr char kPropertyEmpty[] = "";                                             // .str.15
constexpr char kSelinuxEnforceText[] = "enforce";                                 // .str.17

constexpr char kSelinuxEnforcePath[] = "/sys/fs/selinux/enforce";                 // .str.155
constexpr char kSelinuxStateSymbol[] = "selinux_state";                           // .str.37
constexpr char kSelinuxStatusPageSymbol[] = "selinux_status_update_setenforce";   // .str.31
constexpr char kSelinuxPolicyloadSymbol[] = "selinux_status_update_policyload";   // .str.32
constexpr char kKernelVersion4_14[] = "4.14.0";                                   // .str.157

constexpr char kFirstPatchBootSessionKey[] =
    "selinux_status_hooks.v2.UbWDhTqZGwowB89iXFLxTPhkS4VXNmvv";
constexpr char kFirstPatchOwnerBootSessionKey[] =
    "selinux_status_hooks.owner.v1.UbWDhTqZGwowB89iXFLxTPhkS4VXNmvv";
constexpr char kFirstPatchLegacyBootSessionKey[] =
    "selinux_status_hooks.legacy.v1.UbWDhTqZGwowB89iXFLxTPhkS4VXNmvv";
constexpr char kFirstCveFixBootSessionKey[] =
    "first_fix_CVE2026_43499_selinux.2EKDWQOMTqXyerfsYlnr4sdqWENgOQwe";            // .str.159

// adbd 关闭 / 属性恢复
constexpr char kInitSelinuxContext[] = "u:r:init:s0";                             // .str.169
constexpr char kRestoreconAdbdConfigProp[] =
    "/system/bin/sh -c \"restorecon /dev/__properties__/u:object_r:adbd_config_prop:s0\"";  // .str.170
constexpr char kRestoreconShellProp[] =
    "/system/bin/sh -c \"restorecon /dev/__properties__/u:object_r:shell_prop:s0\"";        // .str.171
constexpr char kAdbRootProp[] = "service.adb.root";                               // .str.191
constexpr char kAdbTcpPortProp[] = "service.adb.tcp.port";                        // .str.192
constexpr char kAdbEnabledSettingCmd[] =
    "/system/bin/sh -c 'v=$(settings get global adb_enabled 2>/dev/null); "
    "[ \"$v\" = \"1\" ] && settings put global adb_enabled 0'";                    // .str.193
constexpr char kDevelopmentSettingsCmd[] =
    "/system/bin/sh -c 'v=$(settings get global development_settings_enabled 2>/dev/null); "
    "[ \"$v\" = \"1\" ] && settings put global development_settings_enabled 0'";    // .str.194
constexpr char kAdbdInitServiceProp[] = "init.svc.adbd";                          // .str.195
constexpr char kAdbdStopped[] = "stopped";                                        // .str.196
constexpr char kAdbdControlAction[] = "ctl.stop";                                 // .str.197
constexpr char kAdbdServiceName[] = "adbd";                                       // .str.198
constexpr char kAdbForcedDisabledMarker[] = "adb_forced_disabled";                // .str.190

constexpr char kPatchSequencePrintFormat[] =
    "patch_selinux_sequence_kernel_func:%s\n";                                    // .str.16
constexpr char kStatusPageKaddrPrintFormat[] = "selinux_status_page kaddr: %lx\n"; // .str.30
constexpr char kSetenforceKaddrPrintFormat[] =
    "selinux_status_update_setenforce: %lx\n";                                    // .str.33
constexpr char kPolicyloadKaddrPrintFormat[] =
    "selinux_status_update_policyload: %lx\n";                                    // .str.34
constexpr char kStatusPagePointerPrintFormat[] = "selinux_status_page_ptr:%lx\n"; // .str.38
constexpr char kSelinuxStatusPrintFormat[] = "selinux_status:%d\n";               // .str.153
constexpr char kBootSelinuxPrintFormat[] = "ro.boot.selinux=%s\n";                // .str.154
constexpr char kCommitEnforcingFailFormat[] =
    "commit_selinux_enforcing failed: %s\n";                                      // .str.167
constexpr char kSetEnforcingFailText[] = "set selinux enforcing failed.";         // str.205
constexpr char kForkFailFormat[] = "fork failed: %s\n";                           // .str.199
constexpr char kPatchKernelSwitchOpen[] =
    "[module_selinux] patch kernel switch: open";                                 // @str
constexpr char kCveFixStartText[] = "cve-2026-43499 start fix selinux";           // str.200
constexpr char kCveFixDoneText[] = "cve-2026-43499 fix selinux done";             // str.201
constexpr char kCveFixAlreadyDoneText[] =
    "cve-2026-43499 fix selinux already done";                                    // str.202

// SELinux 内核 status 页在内核地址空间的合法性下界：
constexpr uint64_t kKernelAddressFloor = 0xFFFF000000000000ULL;

}  // namespace

// （init 里由 lambda $_1 产生，只在 init 内使用）
struct SelinuxState {
    int32_t status = 0;
    std::string boot_selinux;
};

//   open("/sys/fs/selinux/enforce", O_RDONLY)；失败 -1；
//   读 1 字节：'1' -> 1，'0' -> 0，其它/短读 -> -1。
static int get_selinux_enforce_status() {
    const int fd = open(kSelinuxEnforcePath, O_RDONLY);
    if (fd < 0) return -1;

    char enforce_char = 0;
    const ssize_t read_len = read(fd, &enforce_char, 1);
    close(fd);
    if (read_len != 1) return -1;

    if (enforce_char == '1') return 1;
    if (enforce_char == '0') return 0;
    return -1;
}

//   92 字节 = PROP_VALUE_MAX 的缓冲区；__system_property_get 返回长度 <1 时
//   返回空串，否则 std::string(buffer, len)。
static std::string get_system_property(const char* name) {
    static_assert(PROP_VALUE_MAX == 92, "layout constraint violated");
    char buffer[PROP_VALUE_MAX] = {0};
    const int value_len = __system_property_get(name, buffer);
    if (value_len < 1) return std::string();
    return std::string(buffer, static_cast<size_t>(value_len));
}

//
// 把一条 shell 命令行按空白切词（支持 "…" / '…' 引用段），
// 用 calloc/realloc 维护 argv（初始 8 槽 + 1 个 NUL 位，倍增扩容），
// 最后 execve(argv[0], argv, environ)，失败也照常释放并返回。
static void execve_cmdline(const char* cmdline, char* const* /*envp*/) {
    if (cmdline == nullptr || *cmdline == '\0') return;

    char* const text = strdup(cmdline);
    if (text == nullptr) return;

    size_t argc = 0;
    size_t capacity = 8;
    char** argv = static_cast<char**>(calloc(capacity + 1, sizeof(char*)));
    if (argv == nullptr) {
        free(text);
        return;
    }

    const auto is_space = [](char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    };

    char* cursor = text;
    for (;;) {
        if (*cursor == '\0') break;
        while (is_space(*cursor)) ++cursor;
        if (*cursor == '\0') break;

        if (argc >= capacity) {
            const size_t new_capacity = capacity * 2;
            char** grown = static_cast<char**>(
                realloc(argv, (new_capacity + 1) * sizeof(char*)));
            if (grown == nullptr) {
                free(argv);
                free(text);
                return;
            }
            memset(grown + capacity, 0, (new_capacity + 1 - capacity) * sizeof(char*));
            argv = grown;
            capacity = new_capacity;
        }

        char* token = cursor;
        if (*cursor == '"' || *cursor == '\'') {
            const char quote = *cursor;
            token = cursor + 1;
            char* scan = token;
            while (*scan != '\0' && *scan != quote) ++scan;
            cursor = (*scan == quote) ? scan + 1 : scan;
        } else {
            char* scan = cursor;
            while (*scan != '\0' && !is_space(*scan)) ++scan;
            cursor = (*scan != '\0') ? scan + 1 : scan;
        }
        argv[argc++] = token;
    }

    if (argc == 0) {
        free(argv);
        free(text);
        return;
    }

    argv[argc] = nullptr;
    execve(argv[0], argv, environ);
    free(argv);
    free(text);
}

//   fork 失败直接返回；子进程 execve_cmdline 后 _exit(127)；
//   父进程 waitpid 回收（返回值实测被丢弃）。
static void fork_execve_cmdline(const char* cmdline, char* const* /*envp*/) {
    const pid_t pid = fork();
    if (pid < 0) return;

    if (pid == 0) {
        execve_cmdline(cmdline);
        _exit(127);
    }

    int status = 0;
    const pid_t waited = waitpid(pid, &status, 0);
    (void)((waited > -1) ? status : -1);
}

// 匿名命名空间：SELinux status 页访问（mangling _ZN12_GLOBAL__N_1…）
//
//   1) kallsyms_lookup_name("selinux_state", &state_kaddr)（静音守卫内）；
//   2) 用 asmjit 生成“调用内核 selinux_kernel_status_page(state) 并返回其 X0”
//      的 shellcode：
//        state_kaddr != 0 -> mov x10, state_kaddr;
//                            export_symbol::linux_above_4_17_0::…(a, err, x10)
//        state_kaddr == 0 -> export_symbol::linux_older::…(a, err)
//      成功后把返回寄存器置为 x0；
//   3) arm64_module_asm_func_start / fn / arm64_module_asm_func_end，
//      aarch64_asm_to_bytes 后 execute_kernel_asm_func 内核执行，得到
//      “struct page*”；
//   4) page==0 -> 打印 selinux_status_page_ptr:0 并返回 -19909；
//      否则 page -> pfn -> phys -> virt，四步任一失败原样返回其错误码。
//   初始错误码 -19990（Ultra 私有段 kAsm，见头文件说明）。
namespace {

KModErr get_selinux_kernel_status_page_kaddr(uint64_t& out_kaddr) {
    uint64_t selinux_state_kaddr = 0;
    KModErr lookup_error;
    int lookup_errno;
    {
        SymbolLookupSilentGuard_c67c0e4fed21dd11b34c67e0a5b878c8 silent_guard;
        lookup_error = kernel_module::kallsyms_lookup_name(kSelinuxStateSymbol, selinux_state_kaddr);
        lookup_errno = errno;
    }
    if (lookup_error != KModErr::OK && lookup_error != KModErr::ERR_MODULE_SYMBOL_NOT_EXIST) {
        errno = lookup_errno;
        return lookup_error;
    }

    uint64_t status_page_kaddr = 0;
    std::function<KModErr(asmjit::a64::Assembler*, asmjit::a64::GpX&)> asm_func =
        [selinux_state_kaddr](asmjit::a64::Assembler* assembler,
                              asmjit::a64::GpX& out_reg) -> KModErr {
        KModErr err = static_cast<KModErr>(kUltraErrAsmInit);
        if (selinux_state_kaddr != 0) {
            aarch64_asm_mov_x(assembler, asmjit::a64::x10, selinux_state_kaddr);
            kernel_module::export_symbol::linux_above_4_17_0::selinux_kernel_status_page(
                assembler, err, asmjit::a64::x10);
        } else {
            kernel_module::export_symbol::linux_older::selinux_kernel_status_page(assembler, err);
        }
        if (err == KModErr::OK) {
            out_reg = asmjit::a64::x0;   // 内核函数返回值在 X0
        }
        return err;
    };

    aarch64_asm_ctx asm_ctx = init_aarch64_asm();
    asmjit::a64::Assembler* assembler = asm_ctx.assembler();
    kernel_module::arm64_module_asm_func_start(assembler);

    asmjit::a64::GpX result_reg = asmjit::a64::x0;
    KModErr err = asm_func(assembler, result_reg);
    if (err != KModErr::OK) return err;

    kernel_module::arm64_module_asm_func_end(assembler, result_reg);
    const std::vector<uint8_t> shellcode = aarch64_asm_to_bytes(assembler);
    err = kernel_module::execute_kernel_asm_func(shellcode, status_page_kaddr);
    if (err != KModErr::OK) return err;

    if (status_page_kaddr == 0) {
        printf(kStatusPagePointerPrintFormat, static_cast<unsigned long>(0));
        return static_cast<KModErr>(kUltraErrSelinuxStatusPageNull);
    }

    uint64_t status_page_pfn = 0;
    err = kernel_module::export_symbol::android_arm64_page_to_pfn(status_page_kaddr,
                                                                status_page_pfn);
    if (err != KModErr::OK) return err;

    uint64_t status_page_phys = 0;
    err = kernel_module::export_symbol::pfn_to_phys(status_page_pfn, status_page_phys);
    if (err != KModErr::OK) return err;

    uint64_t status_page_virt = 0;
    err = kernel_module::export_symbol::phys_to_virt(status_page_phys, status_page_virt);
    if (err != KModErr::OK) return err;

    out_kaddr = status_page_virt;
    return err;
}

}  // namespace

namespace {

KModErr read_selinux_kernel_status(selinux_kernel_status& out_status) {
    if (kernel_module::maplesu_request_helper_mode()) {
        RequestEarlySelinuxResult fixed{};
        RETURN_IF_ERROR(maplesu_request_helper_selinux(EarlySelinuxOperation::ReadStatus, 0, 0, 0, fixed));
        selinux_kernel_status status{};
        status.version = static_cast<uint32_t>(fixed.values[0]);
        status.sequence = static_cast<uint32_t>(fixed.values[0] >> 32);
        status.enforcing = static_cast<uint32_t>(fixed.values[1]);
        status.policyload = static_cast<uint32_t>(fixed.values[1] >> 32);
        if (status.version != 1 || status.enforcing > 1 || fixed.values[2] > 1) {
            errno = EPROTO;
            return KModErr::ERR_MODULE_TEST_CHANNEL;
        }
        status.deny_unknown = static_cast<uint32_t>(fixed.values[2]);
        out_status = status;
        return KModErr::OK;
    }
    uint64_t status_page_kaddr = 0;
    KModErr err = get_selinux_kernel_status_page_kaddr(status_page_kaddr);
    if (err == KModErr::OK) {
        err = kernel_module::read_kernel_mem(status_page_kaddr, &out_status,
                                            static_cast<uint32_t>(sizeof(out_status)));
    }
    return err;
}

KModErr write_selinux_kernel_status_sequence(uint32_t sequence) {
    if (kernel_module::maplesu_request_helper_mode()) {
        RequestEarlySelinuxResult fixed{};
        return maplesu_request_helper_selinux(EarlySelinuxOperation::WriteSequence, 0, 0, sequence, fixed);
    }
    uint64_t status_page_kaddr = 0;
    KModErr err = get_selinux_kernel_status_page_kaddr(status_page_kaddr);
    if (err == KModErr::OK) {
        err = kernel_module::write_kernel_mem(status_page_kaddr + 4, &sequence,
                                             static_cast<uint32_t>(sizeof(sequence)),
                                             kernel_module::KernMemProt::KMP_RW);
    }
    return err;
}

struct SelinuxHookReceipt {
    char boot[128];
    uint64_t flag, setenforce, policyload;
    uint32_t setenforce_entry[4], policyload_entry[4];
};

struct SelinuxOwnedHookReceipt {
    SelinuxHookReceipt hooks;
    uint64_t shared_id, host_low, host_high;
    uint32_t epoch, reserved;
};
static_assert(sizeof(SelinuxHookReceipt) == 184, "legacy receipt layout");
static_assert(sizeof(SelinuxOwnedHookReceipt) == 216, "owner receipt layout");

struct SelinuxHookSwitch {
    bool fixed = false;
    bool boot_retained = false;
    uint64_t address = 0;
    kernel_module::RequestSharedBool shared{};
};

class SelinuxSwitchCreator {
public:
    SelinuxSwitchCreator() = default;
    SelinuxSwitchCreator(const SelinuxSwitchCreator&) = delete;
    SelinuxSwitchCreator& operator=(const SelinuxSwitchCreator&) = delete;
    kernel_module::RequestSharedBool object{};
    bool held = false;
    bool release_attempted = false;

    KModErr release() {
        release_attempted = true;
        const auto error = kernel_module::maplesu_hook_shared_bool_release(object);
        const auto reply = kernel_module::maplesu_hook_reply();
        if (error == KModErr::OK || (reply.completed &&
            (reply.effects & MAPLESU_EFFECT_REFERENCE_DROPPED))) held = false;
        return error;
    }

    ~SelinuxSwitchCreator() noexcept {
        if (!held) return;
        const kernel_module::HookCleanupReceiptScope preserve_receipt;
        try {
            if (!release_attempted) (void)release();
            // CLOSE reconciles an uncertain release; do not replay it or raw-free.
            if (held) (void)kernel_module::maplesu_hook_close();
        } catch (...) {
            try { (void)kernel_module::maplesu_hook_close(); } catch (...) {}
        }
    }
};

// The boot helper owns its process lock. Persist only a completely installed
// pair, and check resident bytes before reusing it on a same-boot retry.
KModErr prepare_selinux_status_hooks(SelinuxHookSwitch& switch_info) {
    const bool owner = kernel_module::maplesu_request_owner_mode();
    bool boot_retained = false;
    if (!owner) RETURN_IF_ERROR(kernel_module::maplesu_hook_legacy_boot_selected(boot_retained));
    const char* storage_key = owner ? kFirstPatchOwnerBootSessionKey : kFirstPatchLegacyBootSessionKey;
    const std::string boot = boot_session_utils::read_boot_session();
    if (boot.empty() || boot.size() >= sizeof(SelinuxHookReceipt::boot))
        return KModErr::ERR_MODULE_READ_FILE;
    std::vector<uint8_t> saved;
    KModErr error = kernel_module::read_blob_disk_storage(storage_key, saved);
    if (error != KModErr::OK && error != KModErr::ERR_MODULE_STORAGE_NOT_FOUND)
        return error;
    SelinuxHookReceipt receipt{};
    SelinuxOwnedHookReceipt owned_receipt{};
    SelinuxSwitchCreator creator;
    const size_t receipt_size = boot_retained ? sizeof(receipt) : sizeof(owned_receipt);
    if (saved.size() == receipt_size) {
        if (boot_retained) memcpy(&receipt, saved.data(), sizeof(receipt));
        else {
            memcpy(&owned_receipt, saved.data(), sizeof(owned_receipt));
            receipt = owned_receipt.hooks;
        }
        if (!memcmp(receipt.boot, boot.c_str(), boot.size() + 1)) {
            if (receipt.flag < kKernelAddressFloor || receipt.setenforce < kKernelAddressFloor ||
                receipt.policyload < kKernelAddressFloor || (receipt.setenforce & 3u) ||
                (receipt.policyload & 3u) || (!boot_retained && (!owned_receipt.shared_id ||
                    !owned_receipt.epoch || owned_receipt.reserved ||
                    !(owned_receipt.host_low || owned_receipt.host_high))))
                return KModErr::ERR_MODULE_STORAGE_READ;
            uint32_t first[4], second[4];
            RETURN_IF_ERROR(kernel_module::read_kernel_mem(receipt.setenforce, first, sizeof(first)));
            RETURN_IF_ERROR(kernel_module::read_kernel_mem(receipt.policyload, second, sizeof(second)));
            if (memcmp(first, receipt.setenforce_entry, sizeof(first)) ||
                memcmp(second, receipt.policyload_entry, sizeof(second)))
                return KModErr::ERR_MODULE_STORAGE_READ;
            switch_info.address = receipt.flag;
            switch_info.boot_retained = boot_retained;
            if (boot_retained) {
                uint8_t value = 0;
                RETURN_IF_ERROR(kernel_module::read_kernel_mem(receipt.flag, &value, 1));
                if (value > 1) return KModErr::ERR_MODULE_STORAGE_READ;
            } else switch_info.shared = {owned_receipt.shared_id, receipt.flag,
                {owned_receipt.host_low, owned_receipt.host_high}, owned_receipt.epoch};
            return KModErr::OK;
        }
    } else if ((saved.size() == sizeof(receipt) || saved.size() == sizeof(owned_receipt)) &&
        !memcmp(saved.data(), boot.c_str(), boot.size() + 1)) {
        // A same-boot pair from the other backend must retain its own lifetime.
        return KModErr::ERR_MODULE_STORAGE_READ;
    }
    if (!owner) {
        // The old 184-byte record names an unowned raw allocation. A same-boot
        // pair cannot acquire publication references retroactively or be
        // overwritten by another pair. Keep the record and its real failure.
        std::vector<uint8_t> legacy_saved;
        error = kernel_module::read_blob_disk_storage(kFirstPatchBootSessionKey, legacy_saved);
        if (error != KModErr::OK && error != KModErr::ERR_MODULE_STORAGE_NOT_FOUND)
            return error;
        if (legacy_saved.size() == sizeof(receipt)) {
            memcpy(&receipt, legacy_saved.data(), sizeof(receipt));
            if (!memcmp(receipt.boot, boot.c_str(), boot.size() + 1))
                return KModErr::ERR_MODULE_STORAGE_READ;
        }
    }
    receipt = {};
    memcpy(receipt.boot, boot.c_str(), boot.size() + 1);
    uint64_t status_page = 0;
    RETURN_IF_ERROR(get_selinux_kernel_status_page_kaddr(status_page));
    if (status_page < kKernelAddressFloor)
        return static_cast<KModErr>(kUltraErrSelinuxStatusPageAddr);
    RETURN_IF_ERROR(kernel_module::kallsyms_lookup_name(kSelinuxStatusPageSymbol, receipt.setenforce));
    RETURN_IF_ERROR(kernel_module::kallsyms_lookup_name(kSelinuxPolicyloadSymbol, receipt.policyload));
    if (boot_retained) {
        // This native backend retains both callbacks and their raw switch for
        // the boot. No creator/reference id or readers retirement is implied.
        RETURN_IF_ERROR(kernel_module::request_kernel_alloc_mem(1, receipt.flag));
        if (receipt.flag < kKernelAddressFloor) return KModErr::ERR_MODULE_NOT_KADDR;
        const uint8_t initial = 0;
        RETURN_IF_ERROR(kernel_module::write_kernel_mem(receipt.flag, &initial, 1,
            kernel_module::KernMemProt::KMP_RW));
        uint8_t actual = 1;
        RETURN_IF_ERROR(kernel_module::read_kernel_mem(receipt.flag, &actual, 1));
        if (actual) return KModErr::ERR_MODULE_ASM;
    } else {
        error = kernel_module::maplesu_hook_shared_bool_create(false, creator.object);
        if (error != KModErr::OK) {
            const auto reply = kernel_module::maplesu_hook_reply();
            const kernel_module::HookCleanupReceiptScope preserve_receipt;
            if (!reply.completed || ((reply.effects & MAPLESU_EFFECT_ALLOCATED) &&
                !(reply.effects & (MAPLESU_EFFECT_REFERENCE_DROPPED |
                    MAPLESU_EFFECT_RELEASED | MAPLESU_EFFECT_QUARANTINED)))) {
                try { (void)kernel_module::maplesu_hook_close(); } catch (...) {}
            }
            return error;
        }
        creator.held = true;
        receipt.flag = creator.object.address;
    }
    kernel_module::HookHandle enforce_handle = nullptr, policy_handle = nullptr;
    PatchBase base;
    PatchSelinuxStatusUpdateSetenforce setenforce(base, receipt.setenforce);
    PatchSelinuxStatusUpdatePolicyload policyload(base, receipt.policyload);
    const auto* dependency = boot_retained ? nullptr : &creator.object;
    const void* receipt_bytes = boot_retained ? static_cast<const void*>(&receipt) : &owned_receipt;
    bool receipt_attempted = false, keep_pair = false;
    try {
        error = setenforce.patch_selinux_status_update_setenforce(status_page, 4, receipt.flag,
            &enforce_handle, dependency);
        if (error == KModErr::OK)
            error = policyload.patch_selinux_status_update_policyload(receipt.flag, &policy_handle, dependency);
        if (error == KModErr::OK && !boot_retained) error = creator.release();
        if (error == KModErr::OK)
            error = kernel_module::read_kernel_mem(receipt.setenforce, receipt.setenforce_entry, sizeof(receipt.setenforce_entry));
        if (error == KModErr::OK)
            error = kernel_module::read_kernel_mem(receipt.policyload, receipt.policyload_entry, sizeof(receipt.policyload_entry));
        owned_receipt = {receipt, creator.object.id, creator.object.host.low,
            creator.object.host.high, creator.object.epoch, 0};
        if (error == KModErr::OK) {
            receipt_attempted = true;
            error = kernel_module::write_blob_disk_storage(storage_key, receipt_bytes,
                static_cast<uint32_t>(receipt_size));
            if (error != KModErr::OK) {
                const kernel_module::HookCleanupReceiptScope preserve_receipt;
                // Storage can commit bytes and then fail while restoring the label.
                // An exact or temporarily unreadable receipt keeps its pair
                // resident and gated off for the existing boot retry contract.
                std::vector<uint8_t> committed;
                const auto reread = kernel_module::read_blob_disk_storage(storage_key, committed);
                if ((reread != KModErr::OK && reread != KModErr::ERR_MODULE_STORAGE_NOT_FOUND) ||
                    (reread == KModErr::OK && committed.size() == receipt_size &&
                     !memcmp(committed.data(), receipt_bytes, receipt_size)))
                    keep_pair = true;
            }
        }
    } catch (const std::bad_alloc&) {
        error = KModErr::ERR_NO_MEM;
        keep_pair = receipt_attempted;
    } catch (...) {
        error = KModErr::ERR_MODULE_ASM;
        keep_pair = receipt_attempted;
    }
    if (keep_pair) return error;
    if (error != KModErr::OK) {
        // Each publication retires its own shared reference; the creator is
        // dropped separately, including after an uncertain installation.
        const auto failure = error;
        const kernel_module::HookCleanupReceiptScope preserve_receipt;
        for (const auto handle : {policy_handle, enforce_handle}) {
            if (!handle) continue;
            try {
                const auto rollback = kernel_module::uninstall_kernel_hook(handle);
                if (rollback != KModErr::OK) error = rollback;
            } catch (...) {
            }
        }
        return failure;
    }
    switch_info.address = receipt.flag;
    switch_info.boot_retained = boot_retained;
    switch_info.shared = creator.object;
    return KModErr::OK;
}

KModErr enable_selinux_status_hooks(const SelinuxHookSwitch& switch_info) {
    if (switch_info.fixed) {
        RequestEarlySelinuxResult fixed{};
        return maplesu_request_helper_selinux(EarlySelinuxOperation::Enable, 0, 0, 0, fixed);
    }
    if (switch_info.address < kKernelAddressFloor) return KModErr::ERR_MODULE_PARAM;
    if (switch_info.boot_retained) {
        const uint8_t enabled = 1;
        RETURN_IF_ERROR(kernel_module::write_kernel_mem(switch_info.address, &enabled, 1,
            kernel_module::KernMemProt::KMP_RW));
        uint8_t observed = 0;
        RETURN_IF_ERROR(kernel_module::read_kernel_mem(switch_info.address, &observed, 1));
        return observed == enabled ? KModErr::OK : KModErr::ERR_MODULE_ASM;
    }
    if (!switch_info.shared.id || switch_info.shared.address != switch_info.address)
        return KModErr::ERR_MODULE_PARAM;
    return kernel_module::maplesu_hook_shared_bool_store(switch_info.shared, true);
}

}  // namespace

// namespace default_modules::module_selinux
namespace default_modules {
namespace module_selinux {

//
//   若当前已经是 enforcing（get_selinux_enforce_status()==1）直接返回；
//   否则读 status 页，再 open("/sys/fs/selinux/enforce", O_RDWR) 写 "1"。
//   写成功时把 status 页 offset 4 的序列号写回“写之前”的值（回滚内核给
//   selinux_status_update_setenforce 递增的 sequence，用户态 libselinux
//   因此感知不到这次 enforce 变更）；任一失败打印
//   "commit_selinux_enforcing failed: <KModErr>"。
//   失败码 -19933（Ultra 私有段，见 private_module_selinux.h）。
static KModErr commit_selinux_enforcing_state() {
    if (get_selinux_enforce_status() == 1) return KModErr::OK;

    selinux_kernel_status status = {};
    KModErr err = read_selinux_kernel_status(status);
    if (err == KModErr::OK) {
        const int fd = open(kSelinuxEnforcePath, O_RDWR);
        if (fd < 0) {
            puts(kSetEnforcingFailText);
            err = static_cast<KModErr>(kUltraErrSetSelinuxEnforce);
        } else {
            const ssize_t write_len = write(fd, kPropertyOne, 1);
            close(fd);
            if (write_len != 1) {
                puts(kSetEnforcingFailText);
                err = static_cast<KModErr>(kUltraErrSetSelinuxEnforce);
            } else {
                err = write_selinux_kernel_status_sequence(status.sequence);
                if (err == KModErr::OK) return KModErr::OK;
            }
        }
    }
    const int saved_errno = errno;
    printf(kCommitEnforcingFailFormat, to_string(err).c_str());
    errno = saved_errno;
    return err;
}

//
//   ① 清空 service.adb.root / service.adb.tcp.port；
//   ② commit_selinux_enforcing_state()；
//   ③ 用 shell 关掉全局 adb_enabled / development_settings_enabled；
//   ④ 读 init.svc.adbd：为 "stopped" 或空则结束；
//   ⑤ fork 子进程：切 u:r:init:s0（setcon+setexeccon）→ 属性 ctl.stop=adbd →
//      _exit(0)；父进程 waitpid。
static void close_adbd() {
    __system_property_set(kAdbRootProp, kPropertyEmpty);
    __system_property_set(kAdbTcpPortProp, kPropertyEmpty);
    commit_selinux_enforcing_state();
    fork_execve_cmdline(kAdbEnabledSettingCmd);
    fork_execve_cmdline(kDevelopmentSettingsCmd);

    const std::string adbd_state = get_system_property(kAdbdInitServiceProp);
    if (adbd_state == kAdbdStopped) return;
    if (adbd_state.empty()) return;

    const pid_t pid = fork();
    if (pid == 0) {
        selinux_procattr::setcon(kInitSelinuxContext);
        selinux_procattr::setexeccon(kInitSelinuxContext);
        __system_property_set(kAdbdControlAction, kAdbdServiceName);
        _exit(0);
    }
    int child_status = 0;
    waitpid(pid, &child_status, 0);
}

//   _ZZN15default_modules14module_selinuxL25CVE2026_43499_fix_selinuxEvE6w1_fix，
//
//   一次性把 selinux_state 起始的 8 字节策略字改成 {00 01 01 01 01 00 00 01}，
//   成功后在 boot_id 记账键里记下本次 boot；同一 boot 内不再重复改写。
static void CVE2026_43499_fix_selinux() {
    static const uint8_t w1_fix[8] = {0x00, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x01};

    puts(kCveFixStartText);

    std::string last_fix_boot_id;
    (void)kernel_module::read_string_disk_storage(kFirstCveFixBootSessionKey,
                                                 last_fix_boot_id);
    if (boot_session_utils::read_boot_session() == last_fix_boot_id) {
        puts(kCveFixAlreadyDoneText);
        return;
    }

    uint64_t selinux_state_kaddr = 0;
    KModErr err = kernel_module::kallsyms_lookup_name(kSelinuxStateSymbol, selinux_state_kaddr);
    if (err != KModErr::OK) return;

    err = kernel_module::write_kernel_mem(selinux_state_kaddr, w1_fix,
                                         static_cast<uint32_t>(sizeof(w1_fix)),
                                         kernel_module::KernMemProt::KMP_RW);
    if (err != KModErr::OK) return;

    const std::string boot_id = boot_session_utils::read_boot_session();
    err = kernel_module::write_string_disk_storage(kFirstCveFixBootSessionKey,
                                                   boot_id.c_str());
    if (err != KModErr::OK) return;

    puts(kCveFixDoneText);
}

//   ro.boot.selinux  模式 EqualsThenSet("", "")         —— 当前空则写空（跳过）
//   ro.debuggable    模式 EqualsThenSet("1", "")        —— 是 1 才清空
//   ro.secure        模式 EqualsThenSet("0", "1")       —— 是 0 才改 1
//   ro.adb.secure    模式 EqualsThenSet("0", "1")       —— 是 0 才改 1
static void enforce_property_baseline() {
    resetprop::check_and_set_property(kBootSelinuxProp, kPropertyEmpty, kPropertyEmpty,
                                      PropertyCheckMode::EqualsThenSet);
    resetprop::check_and_set_property(kDebuggableProp, kPropertyOne, kPropertyEmpty,
                                      PropertyCheckMode::EqualsThenSet);
    resetprop::check_and_set_property(kSecureProp, kPropertyZero, kPropertyOne,
                                      PropertyCheckMode::EqualsThenSet);
    resetprop::check_and_set_property(kAdbSecureProp, kPropertyZero, kPropertyOne,
                                      PropertyCheckMode::EqualsThenSet);
}

//   `ret ptr @.str.24` => 直接返回 "module_selinux" 字面量指针。
const char* get_module_eng_name() {
    return "module_selinux";
}

KModErr init(maplesu::sdk::InstallMode install_mode, maplesu::sdk::ExploitMethod exploit_method) {
    // Reuse the authenticated deployment namespace for the published resetprop tool.
    {
        char dir_path_buf[1024] = {0};
        std::string dir_name;
        RETURN_IF_ERROR(maplesu::sdk::get_kernel_file_dir_name(dir_name));
        snprintf(dir_path_buf, sizeof(dir_path_buf), kDataDirFormat,
                 (dir_name + dir_name[0]).c_str());
        std::string resetprop_path =
            std::filesystem::path(std::string(dir_path_buf)) / kResetpropBinName;
        if (!resetprop_path.empty()) {
            resetprop::detail::resetprop_bin_path_ref() = resetprop_path;
        }
    }

    const bool helper = kernel_module::maplesu_request_helper_mode();
    SelinuxHookSwitch kernel_switch;
    KModErr patch_error;
    if (helper) {
        RequestEarlySelinuxResult fixed{};
        patch_error = maplesu_request_helper_selinux(EarlySelinuxOperation::Prepare,
            static_cast<uint32_t>(install_mode), static_cast<uint32_t>(exploit_method), 0, fixed);
        kernel_switch.fixed = true;
    } else {
        patch_error = prepare_selinux_status_hooks(kernel_switch);
    }
    const int patch_errno = errno;
    printf(kPatchSequencePrintFormat, to_string(patch_error).c_str());
    errno = patch_errno;
    if (patch_error != KModErr::OK) return patch_error;
    // No detached child or five-second exposure window. Reusing a receipt also
    // re-enables the live switch with a confirmed write before succeeding.
    RETURN_IF_ERROR(enable_selinux_status_hooks(kernel_switch));

    auto read_selinux_state = []() -> SelinuxState {
        const int32_t enforce_status = get_selinux_enforce_status();
        const std::string boot_selinux = get_system_property(kBootSelinuxProp);
        printf(kSelinuxStatusPrintFormat, static_cast<int>(enforce_status));
        printf(kBootSelinuxPrintFormat, boot_selinux.c_str());
        return SelinuxState{enforce_status, boot_selinux};
    };

    const SelinuxState selinux_state = read_selinux_state();
    const bool already_expected =
        (selinux_state.status == 1) &&
        (selinux_state.boot_selinux.empty() || selinux_state.boot_selinux == kSelinuxEnforceText);

    if (!already_expected) {
        if (!helper && exploit_method == maplesu::sdk::ExploitMethod::CVE2026_43499) {
            // 内核 >= 4.14.0 才做 selinux_state 策略字改写（is_kernel_version_less 为
            // true 表示内核更老，跳过）
            if (!kernel_module::is_kernel_version_less(kKernelVersion4_14)) {
                CVE2026_43499_fix_selinux();
            }
        }

        enforce_property_baseline();
        RETURN_IF_ERROR(commit_selinux_enforcing_state());

        if (exploit_method != maplesu::sdk::ExploitMethod::CVE2026_43499) {
            const pid_t pid = fork();
            if (pid == 0) {
                // 子进程切到 init 域恢复属性文件 label，避免 adbd 重新读取被隐藏的属性。
                selinux_procattr::setcon(kInitSelinuxContext);
                selinux_procattr::setexeccon(kInitSelinuxContext);
                fork_execve_cmdline(kRestoreconAdbdConfigProp);
                fork_execve_cmdline(kRestoreconShellProp);
                _exit(0);
            }
            int child_status = 0;
            waitpid(pid, &child_status, 0);
        }

        (void)read_selinux_state();
    }

    //   读整个文件；只有“读成功”且内容不是 "0"/"0\n" 时，
    //   用 ofstream(out|trunc) 写回 "0\n"。
    {
        std::string dumpable_content;
        std::vector<uint8_t> dumpable_bytes;
        bool dumpable_read_ok = false;
        {
            std::ifstream dumpable_in(kProcSuidDumpable, std::ios::binary);
            if (dumpable_in) {
                dumpable_in.seekg(0, std::ios::end);
                const std::streamoff file_size =
                    static_cast<std::streamoff>(dumpable_in.tellg());
                if (file_size >= 0) {
                    dumpable_in.seekg(0, std::ios::beg);
                    dumpable_bytes.resize(static_cast<size_t>(file_size));
                    dumpable_in.read(reinterpret_cast<char*>(dumpable_bytes.data()),
                                     file_size);
                    if (static_cast<bool>(dumpable_in)) {
                        dumpable_content.assign(dumpable_bytes.begin(), dumpable_bytes.end());
                        dumpable_read_ok = true;
                    }
                }
            }
        }
        if (dumpable_read_ok && dumpable_content != kPropertyZero &&
            dumpable_content != "0\n") {
            std::ofstream dumpable_out(kProcSuidDumpable,
                                       std::ios::out | std::ios::trunc);
            if (dumpable_out.is_open()) {
                dumpable_out << std::string_view("0\n");
            }
        }
    }

    //   "/data/<dir>/adb_forced_disabled" 不存在时：立刻 close_adbd()，
    //   再 fork 子进程 sleep(10) 后 close_adbd() 一次（_exit(127)）。
    {
        char adb_marker_dir_buf[1024] = {0};
        std::string dir_name;
        RETURN_IF_ERROR(maplesu::sdk::get_kernel_file_dir_name(dir_name));
        snprintf(adb_marker_dir_buf, sizeof(adb_marker_dir_buf), kDataDirFormat,
                 dir_name.c_str());
        std::filesystem::path adb_marker_path =
            std::filesystem::path(std::string(adb_marker_dir_buf)) / kAdbForcedDisabledMarker;
        // inline，实测编译成对 path 内部 basic_string 的拷贝构造），随后
        // path(basic_string const&) 的 inline 定义）。
        const std::string adb_marker_path_text = adb_marker_path;
        std::error_code exists_ec;
        const bool marker_exists = std::filesystem::exists(
            std::filesystem::path(adb_marker_path_text), exists_ec);
        if (!marker_exists) {
            close_adbd();
            const pid_t pid = fork();
            if (pid < 0) {
                printf(kForkFailFormat, strerror(errno));
            } else if (pid == 0) {
                sleep(10);
                close_adbd();
                _exit(127);
            }
        }
    }

    // native module's normalization is required even when enforcing was already correct.
    // Leave policyload and the live access-decision generation untouched.
    selinux_kernel_status status{};
    RETURN_IF_ERROR(read_selinux_kernel_status(status));
    const uint32_t sequence = status.policyload == 1 ? 4 : 0;
    if (status.sequence != sequence)
        RETURN_IF_ERROR(write_selinux_kernel_status_sequence(sequence));
    RETURN_IF_ERROR(read_selinux_kernel_status(status));
    if (status.sequence != sequence || get_selinux_enforce_status() != 1)
        return static_cast<KModErr>(kUltraErrSetSelinuxEnforce);
    RETURN_IF_ERROR(enable_selinux_status_hooks(kernel_switch));

    return KModErr::OK;
}

}  // namespace module_selinux
}  // namespace default_modules

#include "../maplesu/compat/module_selinux_exports_legacy_abi.h"
