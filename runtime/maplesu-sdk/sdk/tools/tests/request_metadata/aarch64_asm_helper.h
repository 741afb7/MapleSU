#pragma once
// Compile-only legacy assembler seam. New consumers must leave init_count at zero.
#include <atomic>
#include <cstdint>
#include <vector>
namespace metadata_test {
inline std::atomic<unsigned> init_count{0}, patch_calls{0}, execmem_frees{0}, module_frees{0};
inline std::vector<uint8_t>& embedded() { static thread_local std::vector<uint8_t> value; return value; }
}
namespace asmjit {
struct Imm { template<class T> explicit Imm(T) {} };
struct Label {};
namespace a64 {
struct GpX {};
struct GpW {};
namespace regs {
inline GpX x0,x1,x2,x9,x10,x11,x12,x13,x14,x15,x16,x17,x29,x30,xzr,sp;
inline GpW w0,w2,w10,w11,wzr;
}
using namespace regs;
struct Mem { Mem pre(int) { return {}; } Mem post(int) { return {}; } };
inline Mem ptr(GpX, int = 0) { return {}; }
namespace Predicate { enum class SysReg { kPAR_EL1 }; }
struct Assembler {
    void embed(const void* data, size_t size) {
        const auto* bytes = static_cast<const uint8_t*>(data);
        metadata_test::embedded().insert(metadata_test::embedded().end(), bytes, bytes + size);
    }
    Label newLabel() { return {}; }
    size_t offset() { return 0; }
#define METADATA_TEST_INSN(name) template<class... T> void name(T...) {}
    METADATA_TEST_INSN(add) METADATA_TEST_INSN(sub) METADATA_TEST_INSN(mov)
    METADATA_TEST_INSN(ldr) METADATA_TEST_INSN(ldrb) METADATA_TEST_INSN(str)
    METADATA_TEST_INSN(stlr) METADATA_TEST_INSN(ubfm) METADATA_TEST_INSN(lsr)
    METADATA_TEST_INSN(lsl) METADATA_TEST_INSN(orr) METADATA_TEST_INSN(b)
    METADATA_TEST_INSN(bind) METADATA_TEST_INSN(stp)
    METADATA_TEST_INSN(ldp) METADATA_TEST_INSN(blr) METADATA_TEST_INSN(cbnz)
    METADATA_TEST_INSN(tbnz) METADATA_TEST_INSN(msr)
#undef METADATA_TEST_INSN
};
}
}
using namespace asmjit;
using namespace asmjit::a64;
struct aarch64_asm_ctx {
    Assembler value;
    Assembler* assembler() { return &value; }
    bool has_error() { return false; }
};
inline aarch64_asm_ctx init_aarch64_asm() { ++metadata_test::init_count; metadata_test::embedded().clear(); return {}; }
inline std::vector<uint8_t> aarch64_asm_to_bytes(Assembler*) { return {0,0,0,0}; }
struct RegProtectGuard { template<class... T> explicit RegProtectGuard(T...) {} };
#define METADATA_TEST_HELPER(name) template<class... T> inline void name(T...) {}
METADATA_TEST_HELPER(aarch64_asm_mov_x) METADATA_TEST_HELPER(aarch64_asm_mov_w)
METADATA_TEST_HELPER(aarch64_asm_adr_x) METADATA_TEST_HELPER(aarch64_asm_mrs_ctr_el0)
METADATA_TEST_HELPER(aarch64_asm_dc_cvac) METADATA_TEST_HELPER(aarch64_asm_dsb_ish)
METADATA_TEST_HELPER(aarch64_asm_ic_iallu) METADATA_TEST_HELPER(aarch64_asm_isb)
METADATA_TEST_HELPER(aarch64_asm_mrs_daif) METADATA_TEST_HELPER(aarch64_asm_mrs_par_el1)
METADATA_TEST_HELPER(aarch64_asm_msr_daifset) METADATA_TEST_HELPER(aarch64_asm_at_s1e1r)
METADATA_TEST_HELPER(aarch64_asm_msr_daif)
#undef METADATA_TEST_HELPER
