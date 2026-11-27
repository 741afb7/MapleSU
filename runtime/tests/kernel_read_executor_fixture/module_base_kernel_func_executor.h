#pragma once
// Production declarations without the unrelated Android umbrella.
#include "aarch64_asm_helper.h"
#include "module_err_def.h"
namespace kernel_module {
void arm64_module_asm_func_start(asmjit::a64::Assembler*);
void arm64_module_asm_func_end(asmjit::a64::Assembler*);
void arm64_module_asm_func_end(asmjit::a64::Assembler*,asmjit::a64::GpX);
KModErr execute_kernel_asm_func(const std::vector<uint8_t>&,uint64_t&);
}
