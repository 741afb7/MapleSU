#include "private/bootstrap_runtime.h"
#include "../../../src/maplesu/module/runtime.h"
#include "maplesu_request_private.h"
#include "worker_boundary.h"
#include <algorithm>
#include <iostream>
#include <string>
#include <vector>

namespace {
maplesu::module_runtime::Result reply;
unsigned runs;
std::vector<std::string> logs;
void check(bool result, const char* name) {
    if (!result) { std::cerr << "FAIL " << name << '\n'; std::exit(1); }
}
unsigned occurrences(const std::string& text, const char* prefix) {
    unsigned result = 0;
    size_t at = 0;
    while ((at = text.find(prefix, at)) != std::string::npos) { ++result; at += std::char_traits<char>::length(prefix); }
    return result;
}
}
extern "C" void __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(const char*) {}
void helper_log(helper_context*, const char* message) { logs.emplace_back(message); }
void helper_log_error(helper_context*, const char* message, helper_i64) { logs.emplace_back(message); }
namespace maplesu::module_runtime {
Result run_single(const char*, const maplesu::module::SdkVersion&) { ++runs; return reply; }
}
int main() {
    helper_context context{};
    auto run = [&](const char* failure, bool helper = true) {
        boot_fixture::reset(failure);
        runs = 0;
        logs.clear();
        if (helper) check(is_ok(kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::EarlyHelper)), "helper selected");
        return maplesu::bootstrap::load_single(context, "single_fixture");
    };
    reply = {};
    check(run("") == HELPER_EXIT_OK && runs == 1, "successful worker reports success");
    check(occurrences(boot_fixture::events(), "close:") == 1, "session closes exactly once");
    check(run("boot-close") == HELPER_EXIT_ENVIRONMENT && runs == 1, "close failure is returned after successful callback");
    check(logs.back() == "single-worker-close rc=", "close failure is logged");
    check(occurrences(boot_fixture::events(), "close:") == 1, "failed close is not retried by destructor");
    reply = {KModErr::ERR_MODULE_STORAGE_READ, 0, false};
    check(run("boot-close") == HELPER_EXIT_ENVIRONMENT && runs == 1, "loader failure remains a failure when cleanup also fails");
    check(logs.front() == "single-loader rc=" && logs.back() == "single-worker-close rc=", "original loader error precedes cleanup diagnostic");
    reply = {KModErr::OK, 42, false};
    check(run("") == HELPER_EXIT_ENVIRONMENT && runs == 1, "module callback failure retained");
    reply = {KModErr::OK, 0, true};
    check(run("boot-close") == HELPER_EXIT_ENVIRONMENT, "skipped callback still requires worker cleanup");
    reply = {};
    check(run("boot-root") == HELPER_EXIT_ENVIRONMENT && runs == 0, "failed root does not run module");
    check(run("", false) == HELPER_EXIT_ENVIRONMENT && runs == 0, "unbound worker never falls back to a credential");
    std::cout << "PASS single helper close: 7 causal groups\n";
}
