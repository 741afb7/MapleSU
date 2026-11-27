// Compile the actual m025 TU; only OS and transport calls are captured here.
#include "kernel_module_kit_umbrella.h"
#include "module_base_kernel_func_executor.h"
#include "module_base_kernel_request.h"
#include "private_module_base_kernel_func_executor.h"
#include "maplesu_request_private.h"
#include <cstdarg>
#include <fstream>
#include <iterator>
#include <limits>

using namespace kernel_module;
namespace {
uint64_t expected;
uint32_t bits = 0x2468, saved_uid = 1001, saved_gid = 1002;
int allocations, executions, releases, restores;
std::string failure;
std::vector<std::vector<uint8_t>> captured;
KModErr fault() { return KModErr::ERR_MODULE_TEST_CHANNEL; }
std::string hex(const std::vector<uint8_t>& bytes) {
    std::ostringstream out;
    for (const auto byte : bytes) out << std::hex << std::setfill('0') << std::setw(2) << unsigned(byte);
    return out.str();
}
}
extern "C" int getresuid(uid_t* r, uid_t* e, uid_t* s) { *r = *e = 1000; *s = saved_uid; return 0; }
extern "C" int getresgid(gid_t* r, gid_t* e, gid_t* s) { *r = *e = 1000; *s = saved_gid; return 0; }
extern "C" uid_t getuid() { return 1000; }
extern "C" int prctl(int, ...) { return static_cast<int>(bits); }
extern "C" int64_t maplesu_release_staging(uint64_t) { ++releases; return 0; }
namespace kernel_module {
#ifdef MAPLESU_FRAME_TEST_CONSUMERS
KModErr test_frame_base(uint64_t&, bool);
KModErr test_frame_append(uint64_t&, bool);
KModErr test_frame_avc(uint64_t, uint64_t&);
bool test_frame_vector_size(size_t);
void test_frame_context(uint64_t, unsigned);
unsigned test_frame_closes();
unsigned test_frame_begins();
unsigned test_frame_ends();
KModErr test_frame_execution_error();
#endif
KModErr request_kernel_set_suid_sgid(uint32_t uid, uint32_t gid) {
    ++restores; saved_uid = uid; saved_gid = gid; return KModErr::OK;
}
KModErr request_kernel_alloc_mem(uint32_t, uint64_t& address) {
    ++allocations; address = 0x111000; return failure == "alloc" ? fault() : KModErr::OK;
}
KModErr unsafe_request_kernel_write_mem(uint64_t, const void* bytes, uint32_t size) {
    const auto* p = static_cast<const uint8_t*>(bytes);
    captured.emplace_back(p, p + size);
    return failure == "write" ? fault() : KModErr::OK;
}
KModErr unsafe_request_kernel_exec_mem(uint64_t, uint32_t, uint64_t& reply) {
    ++executions;
    if (failure == "exec" || (failure == "restore" && executions == 2)) return fault();
    const auto& code = captured.back();
    uint32_t store = 0;
    if (code.size() == 232) std::memcpy(&store, code.data() + 124, sizeof(store));
    if (store == 0xb9001a2a) {
        uint32_t low = 0, high = 0;
        std::memcpy(&low, code.data() + 116, 4);
        std::memcpy(&high, code.data() + 120, 4);
        bits = ((low >> 5) & 0xffff) | (((high >> 5) & 0xffff) << 16);
        reply = 0;
    } else {
        reply = (expected & UINT64_C(0xffffffffffff)) << 8;
        bits = static_cast<uint32_t>(expected >> 48);
        saved_uid = 77; saved_gid = 88;
    }
    return KModErr::OK;
}
}

int main(int argc, char** argv) {
    if (argc < 4) return 2;
    const std::string mode = argv[1];
    expected = std::stoull(argv[2], nullptr, 0);
    const unsigned reg = unsigned(std::stoul(argv[3]));
    failure = argc > 4 ? argv[4] : "";
#ifdef MAPLESU_FRAME_TEST_CONSUMERS
    test_frame_context(mode == "compat-owner" ? 1 : 4,
                       mode == "compat-begin" ? 1 : mode == "compat-update" ? 2 : 0);
    if (mode == "compat-exec") failure = "exec";
#endif
    std::vector<uint8_t> code;
    uint64_t output = UINT64_C(0x8877665544332211);
    KModErr status;
    if (mode == "interleaved") {
        auto first = init_aarch64_asm(); auto second = init_aarch64_asm();
        arm64_module_asm_func_start(first.assembler());
        arm64_module_asm_func_start(second.assembler());
        first.assembler()->mov(x17, Imm(expected));
        second.assembler()->mov(x11, Imm(expected ^ UINT64_C(0x55aa55aa55aa55aa)));
        arm64_module_asm_func_end(second.assembler(), x11);
        arm64_module_asm_func_end(first.assembler(), x17);
        if (allocations || first.has_error() || second.has_error()) return 4;
        code = aarch64_asm_to_bytes(first.assembler());
        status = execute_kernel_asm_func_with_buf(code.data(), uint32_t(code.size()), output);
        if (status != KModErr::OK) return 5;
        expected ^= UINT64_C(0x55aa55aa55aa55aa);
        const auto other = aarch64_asm_to_bytes(second.assembler());
        status = execute_kernel_asm_func_with_buf(other.data(), uint32_t(other.size()), output);
    } else if (mode == "classify") {
        std::ifstream in(argv[5], std::ios::binary);
        code.assign(std::istreambuf_iterator<char>(in), {});
        status = execute_kernel_asm_func_with_buf(code.data(), uint32_t(code.size()), output);
    } else if (mode == "raw-restore") {
        status = maplesu_restore_securebits(uint32_t(expected));
#ifdef MAPLESU_FRAME_TEST_CONSUMERS
    } else if (mode == "locator-base") {
        status = test_frame_base(output, reg != 0);
    } else if (mode == "locator-append") {
        status = test_frame_append(output, reg != 0);
    } else if (mode == "avc") {
        status = test_frame_avc(expected, output);
    } else if (mode == "size") {
        output = test_frame_vector_size(size_t(expected));
        status = KModErr::OK;
#endif
    } else {
        auto context = init_aarch64_asm();
        auto* a = context.assembler();
        bool legacy = mode == "legacy" || mode == "legacy-void" || mode == "legacy-lr";
#ifdef MAPLESU_FRAME_TEST_V1
        if (legacy) maplesu_legacy_asm_func_start(a); else
#endif
        arm64_module_asm_func_start(a);
        if (mode == "nested") {
            a->mov(x19, Imm(0xaabbccdd));
            a->mov(x0, Imm(expected));
            a->mov(x16, Imm(0x700000));
            a->blr(x16);
        } else if (mode == "literal") {
            auto data = a->newLabel();
            a->adr(x11, data); a->ldr(x0, ptr(x11));
            arm64_module_asm_func_end(a, x0);
            a->bind(data); a->embed(&expected, sizeof(expected));
        } else if (mode == "multi") {
            auto alternate = a->newLabel();
            a->cbz(x0, alternate);
            a->mov(x17, Imm(expected)); arm64_module_asm_func_end(a, x17);
            a->bind(alternate);
            a->mov(x11, Imm(expected)); arm64_module_asm_func_end(a, x11);
        } else if (mode == "legacy-lr") {
            a->sub(x0, x30, Imm(4));
        } else {
            if (!legacy) {
                for (unsigned n = 0; n <= 30; ++n) {
                    if (n != 18) a->mov(regs::x(n), Imm(UINT64_C(0xdead000000000000) + n));
                }
            }
            a->mov(regs::x(reg), Imm(expected));
        }
        if (mode != "literal" && mode != "multi") {
#ifdef MAPLESU_FRAME_TEST_V1
            if (legacy) {
                if (mode == "legacy-void") maplesu_legacy_asm_func_end(a);
                else maplesu_legacy_asm_func_end(a, mode == "legacy-lr" ? x0 : regs::x(reg));
            } else
#endif
            if (mode == "void") arm64_module_asm_func_end(a);
            else arm64_module_asm_func_end(a, mode == "nested" ? x0 : regs::x(reg));
        }
        if (context.has_error()) return 3;
        code = aarch64_asm_to_bytes(a);
        if (mode == "compat" || mode == "compat-begin" || mode == "compat-update" ||
            mode == "compat-exec" || mode == "compat-owner")
            status = maplesu_execute_legacy_credential_frame(code.data(), uint32_t(code.size()), output);
        else status = execute_kernel_asm_func_with_buf(code.data(), uint32_t(code.size()), output);
    }
    unsigned closes = 0, begins = 0, ends = 0;
    KModErr execution_error = KModErr::OK;
#ifdef MAPLESU_FRAME_TEST_CONSUMERS
    closes = test_frame_closes();
    begins = test_frame_begins();
    ends = test_frame_ends();
    execution_error = test_frame_execution_error();
#endif
    std::cout << "{\"status\":" << static_cast<int64_t>(status)
              << ",\"result\":" << output << ",\"securebits\":" << bits
              << ",\"uid\":" << saved_uid << ",\"gid\":" << saved_gid
              << ",\"allocations\":" << allocations << ",\"executions\":" << executions
              << ",\"releases\":" << releases << ",\"restores\":" << restores
              << ",\"closes\":" << closes
              << ",\"begins\":" << begins << ",\"ends\":" << ends
              << ",\"execution_error\":" << static_cast<int64_t>(execution_error)
              << ",\"source\":\"" << hex(code) << "\",\"frames\":[";
    for (size_t i = 0; i < captured.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << '\"' << hex(captured[i]) << '\"';
    }
    std::cout << "]}\n";
}
