#pragma once
#include <string>
#include "module_err_def.h"
namespace fixture {
extern std::string deployment;
void select_deployment();
extern KModErr root_result, reset_result, install_result;
extern bool fail_chmod, fail_label, fail_close, fail_admission;
extern unsigned chmod_calls, label_calls, fork_calls;
extern bool label_contract_ok;
}
