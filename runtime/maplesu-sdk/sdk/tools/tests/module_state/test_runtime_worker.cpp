// The actual private worker template owns the real fork/pipe/wait and scope.
// The action and authenticated kernel operations are the only injected seams.
#include "../../../src/maplesu/module/runtime.cpp"
#include "fixture_config.h"
#include <cassert>
#include <filesystem>
#include <cstdio>

int main() {
    using namespace maplesu;
    using namespace module_runtime;
    std::filesystem::create_directories("/data/" + fixture::deployment);
    const auto success = [](int, bool&, storage::WorkerScope&) -> Outcome { return {}; };
    fixture::fail_admission = true;
    auto result = worker("fixture", success);
    assert(result.value.loader == KModErr::ERR_MODULE_ROOT_KEY && fixture::fork_calls == 0);
    fixture::fail_admission = false;
    result = worker("fixture", success);
    assert(result.complete && is_ok(result.value.loader) && is_ok(result.state));
    fixture::fail_close = true;
    result = worker("fixture", success);
    assert(result.complete && result.value.loader == KModErr::ERR_MODULE_ROOT_KEY &&
        result.state == KModErr::ERR_MODULE_ROOT_KEY);
    result = worker("fixture", [](int, bool&, storage::WorkerScope&) -> Outcome {
        return {{KModErr::ERR_MODULE_READ_FILE, 7, false}};
    });
    assert(result.complete && result.value.loader == KModErr::ERR_MODULE_READ_FILE && result.value.entry == 7);
    result = worker("fixture", [](int, bool&, storage::WorkerScope&) -> Outcome { throw 1; });
    assert(result.complete && result.value.loader == KModErr::ERR_MODULE_DLOPEN);
    fixture::fail_close = false;
    assert(is_ok(kernel_module::maplesu_request_set_auth_mode(kernel_module::RequestAuthMode::EarlyHelper)));
    result = worker("fixture", success);
    assert(result.complete && is_ok(result.value.loader));
    fixture::fail_close = true;
    result = worker("fixture", success);
    assert(result.complete && result.value.loader == KModErr::ERR_MODULE_ROOT_KEY &&
        result.bootstrap_error == KModErr::ERR_MODULE_ROOT_KEY);
    puts("PASS 7 actual module worker admission/close/error/helper-transition cases");
}
