// module base kernel mem implementation.
//
#include "kernel_module_kit_umbrella.h"

#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <new>
#include <vector>

#include "module_base_kernel_mem.h"
#include "module_base_kernel_request.h"
#include "module_base_kernel_export_symbol.h"
#include "module_base_kernel_func_executor.h"
#include "private_module_base_kernel_func_executor.h"
#include "maplesu_request_identity.h"
#include "../../../../ultra/authorization/kernel_read_metadata.h"



namespace kernel_module { KModErr icache_invalidate_all_cpus(); }

// 内核地址下界：低于此值一定不是内核线性映射区。
constexpr uint64_t kKernelAddressFloor = 0xffff000000000000ull;

// 只做参数判别，不产生副作用。
constexpr bool is_kernel_address(uint64_t kaddr) { return kaddr >= kKernelAddressFloor; }

// 生成并执行“无返回值”的内核汇编函数：
//   arm64_module_asm_func_start -> body(a) -> arm64_module_asm_func_end
//   -> aarch64_asm_to_bytes -> execute_kernel_asm_func
// body 返回非 OK 时中止（不执行）。
// 冻结 mangling：文件作用域 static，std::function 按值传参。
static KModErr exec_kernel_shellcode_no_result(std::function<KModErr(Assembler*)> body) {
    aarch64_asm_ctx asm_ctx = init_aarch64_asm();
    Assembler* a = asm_ctx.assembler();
    if (!a) return KModErr::ERR_MODULE_ASM;

    kernel_module::arm64_module_asm_func_start(a);
    const KModErr emit_error = body(a);
    if (is_failed(emit_error)) return emit_error;
    kernel_module::arm64_module_asm_func_end(a);

    uint64_t ignored = 0;
    return kernel_module::execute_kernel_asm_func(aarch64_asm_to_bytes(a), ignored);
}

// 生成并执行“把结果放进 X 寄存器”的内核汇编函数。
// body(a, result_reg) 负责把结果写进 result_reg（初值 x0），
// 之后 arm64_module_asm_func_end(a, result_reg) 用它当返回值。
static KModErr get_kernel_shellcode_u64_result(
    std::function<KModErr(Assembler*, GpX&)> body, uint64_t& output) {
    aarch64_asm_ctx asm_ctx = init_aarch64_asm();
    Assembler* a = asm_ctx.assembler();
    if (!a) return KModErr::ERR_MODULE_ASM;

    kernel_module::arm64_module_asm_func_start(a);
    GpX result_reg = x0;
    const KModErr emit_error = body(a, result_reg);
    if (is_failed(emit_error)) return emit_error;
    GpX return_reg = result_reg;
    kernel_module::arm64_module_asm_func_end(a, return_reg);

    return kernel_module::execute_kernel_asm_func(aarch64_asm_to_bytes(a), output);
}

// 冻结成员里这两个 helper 是 C 名字的**本地**符号（t maplesu_...）：
// 本地链接（static）+ 显式 asm 标签固定符号名，行为不变。
static KModErr maplesu_exec_kernel_patch_checked(std::function<KModErr(Assembler*)> body)
    __asm__("maplesu_exec_kernel_patch_checked");
static uint64_t maplesu_memory_protection_pages(uint64_t kaddr, uint64_t size,
                                                uint64_t page_size)
    __asm__("maplesu_memory_protection_pages");

// 执行一次“会改内核代码/页表”的补丁汇编，并校验补丁返回值（r13 修订）。
// 与 exec_kernel_shellcode_no_result 的差别：
//   * 结束固定用 x0 作为返回值；
//   * 传输成功时还要检查 shellcode 自身的 w0 是否为 0，否则判 ERR_MODULE_ASM。
// 冻结证据：C 链接的本地符号 maplesu_exec_kernel_patch_checked（0x1a0..0x230）。
static KModErr maplesu_exec_kernel_patch_checked(std::function<KModErr(Assembler*)> body) {
    aarch64_asm_ctx asm_ctx = init_aarch64_asm();
    Assembler* a = asm_ctx.assembler();
    if (!a) return KModErr::ERR_MODULE_ASM;

    kernel_module::arm64_module_asm_func_start(a);
    const KModErr emit_error = body(a);
    if (is_failed(emit_error)) return emit_error;
    kernel_module::arm64_module_asm_func_end(a, x0);

    uint64_t raw_result = 0;
    const KModErr transport = kernel_module::execute_kernel_asm_func(
        aarch64_asm_to_bytes(a), raw_result);
    // shellcode 用 w0 表达成功/失败；非 0 一律视为补丁失败。
    const KModErr patch_status =
        (static_cast<uint32_t>(raw_result) == 0) ? KModErr::OK : KModErr::ERR_MODULE_ASM;
    return is_ok(transport) ? patch_status : transport;
}

// 页数计算（冻结 C 链接本地符号 maplesu_memory_protection_pages，0x17c4..0x1848）：
//   * 页大小只接受 4096 / 16384 / 65536，其它值返回 0（不产生页）;
//   * [kaddr, kaddr+size) 首尾必须落在同一可表示区间内，溢出/越界返回 0;
//   * 页数 = size/page_size + (size%page_size + kaddr%page_size)/page_size，
//     余数非 0 再进一页；
//   * 页数超过 31 位或 pages*page_size 越过 ~page_addr 时返回 0。
static uint64_t maplesu_memory_protection_pages(uint64_t kaddr, uint64_t size,
                                                uint64_t page_size) {
    const bool supported = (page_size == 4096) || (page_size == 16384) ||
                           (page_size == 65536);
    if ((size - 1) > ~kaddr) {
        return 0;
    }
    if (!supported) {
        return 0;
    }

    uint64_t pages = size / page_size;
    const uint64_t offset_in_page = kaddr & (page_size - 1);
    const uint64_t tail = (size % page_size) + offset_in_page;
    pages += tail / page_size;
    if ((tail % page_size) != 0) {
        pages += 1;
    }

    const uint64_t page_addr = kaddr & ~(page_size - 1);
    if ((pages >> 31) != 0) {
        return 0;
    }
    if ((pages * page_size) > ~page_addr) {
        return 0;
    }
    return pages;
}

namespace kernel_module {


// set_memory_ro/rw/x 三条路径：先按当前页大小算出 [kaddr, kaddr+size) 覆盖的
// 页数（maplesu_memory_protection_pages），地址按页大小对齐后交给补丁汇编。
// 冻结证据：三条路径都先调用 get_page_size，再调用页数 helper（outliner 片段
// OUTLINED_FUNCTION_7 = 取页大小后尾调用 maplesu_memory_protection_pages）。
static KModErr set_memory_ro_protection(uint64_t kaddr, uint64_t size) {
    if (size == 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t page_size = get_page_size();
    const uint64_t page_count = maplesu_memory_protection_pages(kaddr, size, page_size);
    if (page_count == 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t page_addr = kaddr & ~(page_size - 1);
    return maplesu_exec_kernel_patch_checked([page_addr, page_count](Assembler* a) {
        KModErr error = KModErr::OK;
        export_symbol::set_memory_ro(a, error, page_addr, static_cast<uint32_t>(page_count));
        return error;
    });
}

static KModErr set_memory_rw_protection(uint64_t kaddr, uint64_t size) {
    if (size == 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t page_size = get_page_size();
    const uint64_t page_count = maplesu_memory_protection_pages(kaddr, size, page_size);
    if (page_count == 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t page_addr = kaddr & ~(page_size - 1);
    return maplesu_exec_kernel_patch_checked([page_addr, page_count](Assembler* a) {
        KModErr error = KModErr::OK;
        export_symbol::set_memory_rw(a, error, page_addr, static_cast<uint32_t>(page_count));
        return error;
    });
}

static KModErr set_memory_x_protection(uint64_t kaddr, uint64_t size) {
    if (size == 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t page_size = get_page_size();
    const uint64_t page_count = maplesu_memory_protection_pages(kaddr, size, page_size);
    if (page_count == 0) return KModErr::ERR_MODULE_PARAM;
    const uint64_t page_addr = kaddr & ~(page_size - 1);
    return maplesu_exec_kernel_patch_checked([page_addr, page_count](Assembler* a) {
        KModErr error = KModErr::OK;
        export_symbol::set_memory_x(a, error, page_addr, static_cast<uint32_t>(page_count));
        return error;
    });
}




static KModErr patch_kernel_static_code(uint64_t kaddr, const void* buf, uint32_t size) {
    if (kaddr & 3) {
        return KModErr::ERR_MODULE_ADDR_NOT_ALGIN4;
    }

    uint64_t cpus_lock = 0, cpus_unlock = 0;
    auto lock_error = kallsyms_lookup_name("cpus_read_lock", cpus_lock);
    if (is_failed(lock_error)) return lock_error;
    lock_error = kallsyms_lookup_name("cpus_read_unlock", cpus_unlock);
    if (is_failed(lock_error)) return lock_error;
    if (cpus_lock < kKernelAddressFloor || (cpus_lock & 3u) ||
        cpus_unlock < kKernelAddressFloor || (cpus_unlock & 3u))
        return KModErr::ERR_MODULE_NOT_KADDR;

    std::vector<uint8_t> staged;
    if (size > staged.max_size()) return KModErr::ERR_MODULE_PARAM;
    staged.resize(size);
    std::memcpy(staged.data(), buf, size);

    //
    // 证据
    //                L3308–L3595（288 行），符号
    //     _ZNSt6__ndk110__function6__funcIZN13kernel_moduleL24patch_kernel_static_codeEm
    //     PKvjE3$_0NS_9allocatorIS5_EEF7KModErrPN6asmjit5v1_163a649AssemblerEEEclEOSD_
    //   上述为历史恢复证据；R08仅捕获kaddr/size/staged，不再改写system_state。
    //
    //
    //       = Predicate::IC::encode(0,7,5,0) = (0<<11)|(7<<7)|(5<<3)|0 = 936  (IALLU)
    //   aarch64_asm_set_x_data_ptr 的写法同构：
    //       int off_addr_start = a->offset();
    //       …emit 数据…
    //       const int off = off_addr_start - a->offset();   // 负数 = 回到数据首
    //   third_party/asmjit2/core/assembler.h:34-46），故负偏移 = 回到表首。
    const KModErr patch_error = maplesu_exec_kernel_patch_checked(
        [kaddr, size, &staged, cpus_lock, cpus_unlock](Assembler* a) -> KModErr {
            // aarch64_insn_patch_text uses stop_machine_cpuslocked. Keep its
            // hotplug exclusion entirely inside this authenticated EXEC.
            aarch64_asm_mov_x(a, x16, cpus_lock);
            a->blr(x16);
            const Label insn_tables = a->newLabel();

            KModErr patch_err = KModErr::OK;
            {
                RegProtectGuard guard(a, x10, x11);

                a->b(insn_tables);

                //    aarch64_insn_patch_text(addrs[], ...) 使用。
                const size_t addr_table_offset = a->offset();
                for (uint32_t off = 0; off < size; off += 4) {
                    const uint64_t addr = kaddr + off;
                    a->embed(&addr, sizeof(addr));  // .quad kaddr+off（8 字节）
                }

                const size_t insn_table_offset = a->offset();
                for (uint32_t off = 0; off < size; off += 4) {
                    uint32_t insn = 0;
                    std::memcpy(&insn, staged.data() + off, sizeof(insn));
                    a->embed(&insn, sizeof(insn));  // .word insn（4 字节）
                }

                a->bind(insn_tables);

                aarch64_asm_adr_x(
                    a, x0, static_cast<int32_t>(addr_table_offset - a->offset()));
                aarch64_asm_adr_x(
                    a, x1, static_cast<int32_t>(insn_table_offset - a->offset()));

                aarch64_asm_mov_w(a, w2, size >> 2);

                export_symbol::aarch64_insn_patch_text(a, patch_err, x0, x1, w2);
            }

            if (is_ok(patch_err)) {
                {
                    RegProtectGuard result(a, x0);
                    aarch64_asm_mov_x(a, x16, cpus_unlock);
                    a->blr(x16);
                }
                aarch64_asm_ic_iallu(a);
                aarch64_asm_dsb_ish(a);
                aarch64_asm_isb(a);
            }
            return patch_err;
        });
    if (is_failed(patch_error)) {
        return patch_error;
    }
    return static_cast<KModErr>(icache_invalidate_all_cpus());
}

// 公开 ABI（module_base_kernel_mem.h）
KModErr alloc_kernel_mem(uint32_t size, uint64_t& out_kaddr) {
    if (size == 0) {
        return KModErr::ERR_MODULE_PARAM;
    }
    if (!maplesu_request_owner_mode()) {
        bool supported = false;
        const auto error = maplesu_request_key_alloc(size, out_kaddr, supported);
        if (is_failed(error) || supported) return error;
    }
    return request_kernel_alloc_mem(size, out_kaddr);
}

KModErr free_kernel_mem(uint64_t kaddr) {
    if (kaddr == 0) {
        return KModErr::ERR_MODULE_PARAM;
    }
    if (!is_kernel_address(kaddr)) {
        return KModErr::ERR_MODULE_NOT_KADDR;
    }
    if (maplesu_request_owner_mode() || maplesu_request_managed_allocation(kaddr))
        return static_cast<KModErr>(maplesu_release_staging(kaddr));
    // 6.12 起模块内存改由 execmem 分配器管理，释放符号因此不同。
    const bool use_execmem = !is_kernel_version_less("6.12.0");
    return exec_kernel_shellcode_no_result([kaddr, use_execmem](Assembler* a) {
        KModErr error = KModErr::OK;
        aarch64_asm_mov_x(a, x11, kaddr);
        if (use_execmem) {
            export_symbol::linux_above_6_12_0::execmem_free(a, error, x11);
        } else {
            export_symbol::linux_older::module_memfree(a, error, x11);
        }
        return error;
    });
}

KModErr read_kernel_mem(uint64_t kaddr, void* buf, uint32_t size) {
    if (kaddr == 0) {
        return KModErr::ERR_MODULE_PARAM;
    }
    if (!is_kernel_address(kaddr)) {
        return KModErr::ERR_MODULE_NOT_KADDR;
    }
    return request_kernel_read_mem(kaddr, buf, size);
}

static KModErr validate_kernel_write_range(uint64_t kaddr, uint32_t size, KernMemProt prot) {
    if (kaddr == 0) {
        return KModErr::ERR_MODULE_PARAM;
    }
    if (!is_kernel_address(kaddr)) {
        return KModErr::ERR_MODULE_NOT_KADDR;
    }
    if (size == 0 || size - 1 > std::numeric_limits<uint64_t>::max() - kaddr ||
        (prot != KernMemProt::KMP_RW && prot != KernMemProt::KMP_RO && prot != KernMemProt::KMP_X) ||
        (maplesu_request_owner_mode() && size > MAPLESU_MANAGEMENT_MAX_BYTES)) {
        return KModErr::ERR_MODULE_PARAM;
    }
    if (prot != KernMemProt::KMP_RW) {
        if (kaddr & 3) return KModErr::ERR_MODULE_ADDR_NOT_ALGIN4;
        const uint64_t patch_size = (static_cast<uint64_t>(size) + 3) & ~uint64_t{3};
        if (patch_size > std::numeric_limits<uint32_t>::max() ||
            patch_size - 1 > std::numeric_limits<uint64_t>::max() - kaddr ||
            (maplesu_request_owner_mode() && patch_size > MAPLESU_MANAGEMENT_MAX_BYTES))
            return KModErr::ERR_MODULE_PARAM;
    }
    return KModErr::OK;
}

KModErr write_kernel_mem(uint64_t kaddr, const void* buf, uint32_t size, KernMemProt prot) {
    const KModErr range_error = validate_kernel_write_range(kaddr, size, prot);
    if (is_failed(range_error)) return range_error;
    if (!buf || reinterpret_cast<uintptr_t>(buf) > std::numeric_limits<uintptr_t>::max() - (size - 1))
        return KModErr::ERR_MODULE_PARAM;
    try {
        if (prot == KernMemProt::KMP_RW) return request_kernel_write_mem(kaddr, buf, size);
        if (maplesu_request_owner_mode()) return maplesu_request_owner_write_text(kaddr, buf, size);
        // Legacy callers also write protected data, including the 18-byte
        // bootstrap path. Preserve the last word's neighboring bytes and submit
        // complete words once; never truncate the payload or its trailing NUL.
        if (size & 3) {
            const uint32_t patch_size = (size + 3u) & ~3u;
            std::vector<uint8_t> staged(patch_size), actual(patch_size);
            const uint32_t tail = patch_size - 4;
            const KModErr read_error = read_kernel_mem(kaddr + tail, staged.data() + tail, 4);
            if (is_failed(read_error)) return read_error;
            std::memcpy(staged.data(), buf, size);
            const KModErr patch_error = patch_kernel_static_code(kaddr, staged.data(), patch_size);
            if (is_failed(patch_error)) return patch_error;
            const KModErr verify_error = read_kernel_mem(kaddr, actual.data(), patch_size);
            if (is_failed(verify_error)) return verify_error;
            return std::memcmp(actual.data(), staged.data(), patch_size) == 0
                ? KModErr::OK : KModErr::ERR_MODULE_ASM;
        }
        return patch_kernel_static_code(kaddr, buf, size);
    } catch (const std::bad_alloc&) {
        return KModErr::ERR_NO_MEM;
    }
}

KModErr fill00_kernel_mem(uint64_t kaddr, uint32_t size, KernMemProt prot) {
    const KModErr range_error = validate_kernel_write_range(kaddr, size, prot);
    if (is_failed(range_error)) return range_error;
    try {
        std::vector<uint8_t> zeros;
        if (size > zeros.max_size()) return KModErr::ERR_MODULE_PARAM;
        zeros.resize(size, 0);
        return write_kernel_mem(kaddr, zeros.data(), size, prot);
    } catch (const std::bad_alloc&) {
        return KModErr::ERR_NO_MEM;
    }
}

KModErr read_kernel_mem_atomic32(uint64_t kaddr, uint32_t& out_val) {
    if (kaddr == 0) return KModErr::ERR_MODULE_PARAM;
    if (!is_kernel_address(kaddr)) return KModErr::ERR_MODULE_NOT_KADDR;

    uint64_t raw = 0;
    const KModErr error = get_kernel_shellcode_u64_result(
        [kaddr](Assembler* a, GpX& result_reg) {
            aarch64_asm_mov_x(a, x10, kaddr);
            a->ldr(w11, ptr(x10));   // LDR W11,[X10]
            result_reg = x11;
            return KModErr::OK;
        },
        raw);
    if (is_ok(error)) {
        out_val = static_cast<uint32_t>(raw);
    }
    return error;
}

KModErr read_kernel_mem_atomic64(uint64_t kaddr, uint64_t& out_val) {
    if (kaddr == 0) return KModErr::ERR_MODULE_PARAM;
    if (!is_kernel_address(kaddr)) return KModErr::ERR_MODULE_NOT_KADDR;

    return get_kernel_shellcode_u64_result(
        [kaddr](Assembler* a, GpX& result_reg) {
            aarch64_asm_mov_x(a, x10, kaddr);
            a->ldr(x11, ptr(x10));   // LDR X11,[X10]
            result_reg = x11;
            return KModErr::OK;
        },
        out_val);
}

KModErr write_kernel_rw_mem_atomic32(uint64_t kaddr, uint32_t val) {
    if (kaddr == 0) return KModErr::ERR_MODULE_PARAM;
    if (!is_kernel_address(kaddr)) return KModErr::ERR_MODULE_NOT_KADDR;

    return exec_kernel_shellcode_no_result([kaddr, val](Assembler* a) {
        aarch64_asm_mov_x(a, x10, kaddr);
        aarch64_asm_mov_w(a, w11, val);
        a->stlr(w11, ptr(x10));            // STLR W11,[X10]（release store）
        aarch64_asm_mrs_ctr_el0(a, x14);   // MRS X14, CTR_EL0
        // 取 CTR_EL0.CWG[19:16]，+2 得到 cache line 字节数的 log2。
        a->ubfm(x14, x14, Imm(16), Imm(19));
        a->add(x14, x14, Imm(2));
        // 地址按 cache line 对齐：x10 = (x10 >> sh) << sh
        a->lsr(x10, x10, x14);
        a->lsl(x10, x10, x14);
        aarch64_asm_dc_cvac(a, x10);       // DC CVAC,X10
        aarch64_asm_dsb_ish(a);            // DSB ISH
        return KModErr::OK;
    });
}

KModErr write_kernel_rw_mem_atomic64(uint64_t kaddr, uint64_t val) {
    if (kaddr == 0) return KModErr::ERR_MODULE_PARAM;
    if (!is_kernel_address(kaddr)) return KModErr::ERR_MODULE_NOT_KADDR;

    return exec_kernel_shellcode_no_result([kaddr, val](Assembler* a) {
        aarch64_asm_mov_x(a, x10, kaddr);
        aarch64_asm_mov_x(a, x11, val);
        a->stlr(x11, ptr(x10));            // STLR X11,[X10]（release store）
        aarch64_asm_mrs_ctr_el0(a, x14);
        a->ubfm(x14, x14, Imm(16), Imm(19));
        a->add(x14, x14, Imm(2));
        a->lsr(x10, x10, x14);
        a->lsl(x10, x10, x14);
        aarch64_asm_dc_cvac(a, x10);
        aarch64_asm_dsb_ish(a);
        return KModErr::OK;
    });
}

KModErr set_kernel_mem_protection(uint64_t kaddr, uint64_t size, KernMemProt prot) {
    if (kaddr == 0) {
        return KModErr::ERR_MODULE_PARAM;
    }
    if (!is_kernel_address(kaddr)) {
        return KModErr::ERR_MODULE_NOT_KADDR;
    }
    switch (prot) {
        case KernMemProt::KMP_RO:
            return set_memory_ro_protection(kaddr, size);
        case KernMemProt::KMP_RW:
            return set_memory_rw_protection(kaddr, size);
        case KernMemProt::KMP_X: {
            // 改成可执行前必须先恢复为只读页表项，否则 W^X 检查会失败。
            const KModErr ro_error = set_memory_ro_protection(kaddr, size);
            if (is_failed(ro_error)) {
                return ro_error;
            }
            return set_memory_x_protection(kaddr, size);
        }
        default:
            return KModErr::ERR_MODULE_PARAM;
    }
}

KModErr get_kernel_base_vaddr(uint64_t& result) {
    void parser_root_key_info(const char*, int&);
    KModErr get_append_package_data_offset(uint32_t&);
    RequestIdentity operation;
    if (!operation.capture()) return KModErr::ERR_MODULE_ROOT_KEY;
    RequestMetadataFacts facts{};
    bool supported = false;
    const auto error = maplesu_kernel_metadata_facts(facts, supported);
    if (is_failed(error)) return error;
    if (!operation.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (supported) {
        result = facts.kernel_base;
        return KModErr::OK;
    }
    if (!maplesu_request_auth_legacy(operation.auth_state)) return KModErr::ERR_MODULE_TEST_CHANNEL;

    int32_t base_delta = 0;
    parser_root_key_info(operation.key.data(), base_delta);
    if (!operation.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    auto context = init_aarch64_asm();
    auto a = context.assembler();
    maplesu_legacy_asm_func_start(a);
    a->mov(x11, x30);
    a->sub(x11, x11, Imm(4));
    aarch64_asm_mov_x(a, x12, static_cast<uint64_t>(static_cast<int64_t>(base_delta)));
    a->add(x11, x11, x12);
    maplesu_legacy_asm_func_end(a, x11);
    const auto code = aarch64_asm_to_bytes(a);
    if (context.has_error() || code.empty()) return KModErr::ERR_MODULE_ASM;
    uint64_t probed = 0;
    if (!operation.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    const auto execution_error = execute_kernel_asm_func(code, probed);
    if (is_failed(execution_error)) return execution_error;
    if (!operation.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    uint64_t candidate = 0;
    if (!maplesu_kernel_read::metadata_address(probed, 0, 1, candidate))
        return KModErr::ERR_MODULE_INTERNAL_APPEND;
    uint32_t append_offset = 0;
    const auto offset_error = get_append_package_data_offset(append_offset);
    if (is_failed(offset_error)) return offset_error;
    if (!operation.matches()) return KModErr::ERR_MODULE_ROOT_KEY;
    if (!maplesu_kernel_read::metadata_address(probed,
            -static_cast<int64_t>(append_offset), 1, candidate))
        return KModErr::ERR_MODULE_INTERNAL_APPEND;
    result = candidate;
    return KModErr::OK;
}

uint32_t get_page_size() {
    static const uint32_t ps = static_cast<uint32_t>(sysconf(_SC_PAGESIZE));
    return ps;
}

}  // namespace kernel_module
