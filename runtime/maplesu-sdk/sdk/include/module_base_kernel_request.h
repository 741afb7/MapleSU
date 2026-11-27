// module base kernel request implementation.
//
#pragma once
#include <stdint.h>
#include <sys/types.h>
#include <unistd.h>

#include "module_err_def.h"

namespace kernel_module {

// 内核请求通道的命令字。
enum class KernelCmdFlag : uint8_t {
    ALLOC_MEM       = 48,   // 申请内核内存
    WRITE_MEM       = 49,   // 写内核内存（sub 字段携带待写字节）
    READ_MEM        = 50,   // 读内核内存
    EXEC_MEM        = 51,   // 执行内核内存（stage 成可执行页）
    SET_SUID_SGID   = 52,   // 恢复通道写坏的 suid/sgid
    RELEASE_STAGING = 53,   // 释放 staging 暂存页
};

// 恢复通道被内核改写的 suid/sgid（成员 m023 导出符号）。
KModErr request_kernel_set_suid_sgid(uint32_t suid, uint32_t sgid);

// 旧通道经 saved ids 返回结果；实际调用显式检查 snapshot/restore 的首错。
struct SuidSgidGuard {
    SuidSgidGuard() {
        error_ = snapshot(suid_, sgid_);
        armed_ = error_ == KModErr::OK;
    }
    ~SuidSgidGuard() { if (armed_) (void)restore(); }

    static KModErr snapshot(uint32_t& suid, uint32_t& sgid) {
        uid_t uid = 0, euid = 0, saved_uid = 0;
        gid_t gid = 0, egid = 0, saved_gid = 0;
        if (getresuid(&uid, &euid, &saved_uid) || getresgid(&gid, &egid, &saved_gid))
            return KModErr::ERR_MODULE_CRED_DANGER;
        suid = static_cast<uint32_t>(saved_uid);
        sgid = static_cast<uint32_t>(saved_gid);
        return KModErr::OK;
    }

    KModErr status() const { return error_; }
    KModErr restore() {
        if (!armed_) return error_;
        armed_ = false;
        error_ = request_kernel_set_suid_sgid(suid_, sgid_);
        return error_;
    }

    // 仅保留旧源码形状；实际请求调用上面的 checked snapshot。
    static uint32_t get_suid() {
        uid_t real_id = 0, effective_id = 0, saved_id = 0;
        getresuid(&real_id, &effective_id, &saved_id);
        return static_cast<uint32_t>(saved_id);
    }
    // 真实 gid：getresgid() 的第三个输出（saved gid）。
    static uint32_t get_sgid() {
        gid_t real_id = 0, effective_id = 0, saved_id = 0;
        getresgid(&real_id, &effective_id, &saved_id);
        return static_cast<uint32_t>(saved_id);
    }

private:
    uint32_t suid_ = 0;
    uint32_t sgid_ = 0;
    KModErr error_ = KModErr::ERR_MODULE_CRED_DANGER;
    bool armed_ = false;
};

// 恢复通道被内核改写的 suid/sgid（成员 m023 导出符号）。

// ---- 不保护 cred 的原始通道入口（调用者自行保证 suid/sgid guard）-----------
KModErr unsafe_request_kernel_alloc_mem(uint32_t size, uint64_t& out_kaddr);
KModErr unsafe_request_kernel_write_mem(uint64_t kaddr, const void* buf, uint32_t size);
KModErr unsafe_request_kernel_read_mem(uint64_t kaddr, void* buf, uint32_t size);
KModErr unsafe_request_kernel_exec_mem(uint64_t kaddr, uint32_t size, uint64_t& out_reply);

// ---- 带 cred guard 的通道入口 ---------------------------------------------
KModErr request_kernel_alloc_mem(uint32_t size, uint64_t& out_kaddr);
KModErr request_kernel_write_mem(uint64_t kaddr, const void* buf, uint32_t size);
KModErr request_kernel_read_mem(uint64_t kaddr, void* buf, uint32_t size);

}  // namespace kernel_module

// 私有运行时 API：取得当前模块的 ROOT 密钥文本。 该符号由安装期注入的
// 私有 helper 提供，名字带 hash 后缀（不可改名，否则链接失败）。
extern "C" const char*
__get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW(void);

namespace kernel_module {
inline const char* current_module_root_key() {
    return __get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW();
}
}  // namespace kernel_module

// 由 runtime/ultra/authorization/ 下已存在的上游源码提供（同一成员内合并）：
//   request_context.cpp  -> maplesu_prepare_kernel_request
//   kernel_read.cpp      -> request_kernel_read_mem（canonical nofault 实现）
extern "C" int64_t maplesu_prepare_kernel_request() noexcept;
