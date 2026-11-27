#include "private/bootstrap_runtime.h"
#include "worker_boundary.h"
#include <cstdlib>
#include <cstring>
#include <iostream>

namespace {
int checks;
void check(bool result, const char* name) {
    ++checks;
    if (!result) { std::cerr << "FAIL " << name << '\n'; std::exit(1); }
}
}

int main() {
    using kernel_module::RequestAuthMode;
    helper_context context{};
    std::strcpy(context.env_dir, "/data/fedcba9876543210");
    std::optional<maplesu::storage::WorkerScope> worker;
    boot_fixture::reset();
    for (const auto mode : {RequestAuthMode::EarlyHelper, RequestAuthMode::ManagerOwner,
                           RequestAuthMode::EarlyHelper, RequestAuthMode::ManagerOwner,
                           RequestAuthMode::EarlyHelper}) {
        const auto previous = kernel_module::maplesu_request_auth_state();
        check(is_ok(maplesu::bootstrap::select_worker(context, worker, mode)), "fresh stage scope roots");
        check(worker && kernel_module::maplesu_request_auth_mode(kernel_module::maplesu_request_auth_state()) == mode,
              "selected stage identity matches fresh scope");
        check(previous != kernel_module::maplesu_request_auth_state(), "old generation is never rebound");
        std::string directory;
        check(is_ok(maplesu::storage::current_kernel_directory(directory)) && directory == "/data/fedcba9876543210/",
              "each new stage validates the same deployment namespace");
    }
    check(is_ok(worker->close_checked()), "last stage session is explicitly closed");
    worker.reset();
    const auto flow = boot_fixture::events();
    check(flow.find("storage-end:") < flow.find("select-root:"), "old storage ends before changing identity");
    check(flow.find("close:") < flow.find("select-root:"), "old helper session closes before root identity selection");

    boot_fixture::reset("boot-close");
    check(is_ok(maplesu::bootstrap::select_worker(context, worker, RequestAuthMode::EarlyHelper)), "close-failure helper opens");
    const auto generation = kernel_module::maplesu_request_auth_state();
    check(is_failed(maplesu::bootstrap::select_worker(context, worker, RequestAuthMode::ManagerOwner)), "uncertain close stops transition");
    check(!worker && generation == kernel_module::maplesu_request_auth_state(), "failed close never selects a new identity");

    boot_fixture::reset();
    std::strcpy(context.env_dir, "/data/different_install");
    check(is_failed(maplesu::bootstrap::select_worker(context, worker, RequestAuthMode::EarlyHelper)), "different installation is rejected");
    check(is_ok(worker->close_checked()), "rejected namespace still drains its session");
    worker.reset();
    std::cout << "PASS helper worker modes " << checks << " assertions; no process creation\n";
}
