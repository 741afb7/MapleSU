// Selected production m023; syscall replies model the existing kernel boundary.
#include "../maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp"
#include <cstdarg>
#include <cstdio>
#include <cstdlib>

namespace {
constexpr uint64_t descriptor = UINT64_C(0xffff000020000000);
constexpr maplesu_host_instance host{0x1122334455667788, 0x8877665544332211};
struct Fixture {
    uint32_t uid = 0, epoch = 1, fail_command = 0;
    int status = 0;
    bool missing = false, init_user_ns = true, exact_helper = true;
    std::vector<uint32_t> commands;
} fixture;
unsigned checks = 0;
void check(bool value, const char* label) {
    ++checks;
    if (!value) { std::fprintf(stderr, "FAIL %s errno=%d\n", label, errno); std::abort(); }
}
void reset(uint32_t uid = 0) {
    fixture = {};
    fixture.uid = uid;
    kernel_module::owner_management = {};
    kernel_module::owner_channel = {};
    kernel_module::helper_channel = {};
    kernel_module::management_reply = {};
    kernel_module::management_process = getpid();
    kernel_module::request_auth_state.store(0);
    errno = 0;
}
maplesu_metadata_owner_v3 metadata() {
    maplesu_metadata_owner_v3 value{};
    value.common.magic = MAPLESU_METADATA_MAGIC;
    value.common.version = MAPLESU_OWNER_METADATA_VERSION;
    value.common.length = sizeof(value);
    value.common.capabilities = MAPLESU_OWNER_CAPABILITIES | MAPLESU_CAP_APPEND64;
    value.common.kernel_base_displacement = -0x10000000;
    value.common.legacy_metadata_displacement = -0x1000;
    value.owner_schema = MAPLESU_OWNER_SCHEMA;
    value.owner_state = MAPLESU_OWNER_STATE_BOUND;
    value.owner_epoch = fixture.epoch;
    value.owner_full_uid = fixture.uid;
    value.host = host;
    return value;
}
}

namespace kernel_module { uint32_t get_page_size() { return 4096; } }
extern "C" uid_t __wrap_getuid() { return fixture.uid; }
extern "C" uid_t __wrap_geteuid() { return fixture.uid; }
extern "C" long __wrap_syscall(long number, ...) {
    va_list args;
    va_start(args, number);
    const auto* packet = va_arg(args, const uint8_t*);
    va_end(args);
    constexpr size_t prefix = sizeof(MAPLESU_OWNER_ENVELOPE_PREFIX) - 1;
    check(number == 221 && !std::memcmp(packet, MAPLESU_OWNER_ENVELOPE_PREFIX, prefix), "existing envelope");
    std::array<uint8_t, 18> frame{};
    for (size_t i = 0; i < frame.size(); ++i) frame[i] = packet[prefix + i] + packet[prefix + 18 + i];
    uint64_t pointer = 0;
    std::memcpy(&pointer, frame.data() + 10, sizeof(pointer));
    const auto& request = *reinterpret_cast<const maplesu_request_owner_v3*>(pointer);
    fixture.commands.push_back(request.command);
    check(request.command == MAPLESU_REQUEST_QUERY || request.command == MAPLESU_REQUEST_READ ||
        request.command == MAPLESU_REQUEST_VENDOR_STATE, "no OPEN ROOT or other mutation command");
    if (fixture.missing && request.command == fixture.fail_command) return -1;
    maplesu_response_owner_v3 reply{};
    reply.completion_magic = MAPLESU_RESPONSE_MAGIC;
    reply.version = MAPLESU_OWNER_PROTOCOL_VERSION;
    reply.length = sizeof(reply);
    reply.command = request.command;
    reply.request_id = request.request_id;
    if (request.command == fixture.fail_command) reply.status = fixture.status;
    if (!reply.status && request.command == MAPLESU_REQUEST_QUERY) {
        reply.result = metadata().common.capabilities;
        reply.metadata_address = descriptor;
        reply.metadata_length = sizeof(maplesu_metadata_owner_v3);
        reply.metadata_version = MAPLESU_OWNER_METADATA_VERSION;
    } else if (!reply.status && request.command == MAPLESU_REQUEST_READ) {
        const auto& read = *reinterpret_cast<const maplesu_read_owner_v3*>(request.arg1);
        check(request.arg0 == descriptor && read.length == sizeof(maplesu_metadata_owner_v3), "descriptor-only READ");
        const auto value = metadata();
        std::memcpy(reinterpret_cast<void*>(read.destination_user), &value, sizeof(value));
        reply.result = sizeof(value);
    } else if (!reply.status && request.command == MAPLESU_REQUEST_VENDOR_STATE) {
        const auto& operation = *reinterpret_cast<const maplesu_management_v3*>(request.arg1);
        check(operation.address == MAPLESU_INSTALLATION_READ && !operation.session && !operation.handle,
            "existing helper installation READ only");
        if (!fixture.init_user_ns) reply.status = -EPERM;
        else if (!fixture.exact_helper) reply.status = -ESTALE;
        else {
            maplesu_installation_state_v3 value{};
            value.magic = MAPLESU_INSTALLATION_STATE_MAGIC;
            value.schema = MAPLESU_INSTALLATION_STATE_SCHEMA;
            value.bytes = sizeof(value);
            value.host = host;
            std::memset(value.name, 'A', sizeof(value.name));
            value.revision = 7;
            value.record.magic = 0x5349534dU;
            value.record.schema = 1;
            value.record.bytes = sizeof(value.record);
            std::memcpy(reinterpret_cast<void*>(operation.data_user), &value, sizeof(value));
            reply.result = sizeof(value);
        }
    }
    std::memcpy(reinterpret_cast<void*>(request.response_user), &reply, sizeof(reply));
    return -1; // Transport ignores the native syscall return and reads its receipt.
}

int main() {
    using namespace kernel_module;
    reset(10123);
    fixture.fail_command = MAPLESU_REQUEST_QUERY; fixture.status = -EPERM;
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_ROOT_KEY && errno == EPERM &&
        fixture.commands == std::vector<uint32_t>{MAPLESU_REQUEST_QUERY}, "ordinary caller rejected by one QUERY");
    reset(2010123);
    check(maplesu_request_worker_admission() == KModErr::OK && fixture.commands ==
        std::vector<uint32_t>{MAPLESU_REQUEST_QUERY, MAPLESU_REQUEST_READ}, "cold Manager fullUID QUERY READ");
    const auto generation = maplesu_request_auth_state();
    fixture.commands.clear();
    check(maplesu_request_worker_admission() == KModErr::OK &&
        fixture.commands == std::vector<uint32_t>{MAPLESU_REQUEST_READ} &&
        maplesu_request_auth_state() == generation, "warm descriptor READ preserves generation");
    fixture.commands.clear(); fixture.fail_command = MAPLESU_REQUEST_READ; fixture.status = -ESTALE;
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_ROOT_KEY && errno == ESTALE &&
        fixture.commands == std::vector<uint32_t>{MAPLESU_REQUEST_READ}, "warm revocation has no replay");
    reset();
    check(maplesu_request_worker_admission() == KModErr::OK && !owner_management.session,
        "Root mode1 read admission creates no session");
    reset(); fixture.fail_command = MAPLESU_REQUEST_READ; fixture.missing = true;
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_TEST_CHANNEL && errno == EPROTO &&
        fixture.commands.size() == 2, "missing READ receipt preserves protocol failure without retry");
    reset(10123); maplesu_request_set_auth_mode(RequestAuthMode::EarlyHelper);
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_ROOT_KEY && errno == EPERM &&
        fixture.commands.empty(), "mode3 ordinary UID rejected locally");
    reset(); maplesu_request_set_auth_mode(RequestAuthMode::EarlyHelper); fixture.init_user_ns = false;
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_ROOT_KEY && errno == EPERM &&
        fixture.commands == std::vector<uint32_t>{MAPLESU_REQUEST_VENDOR_STATE}, "user namespace fake Root rejected natively");
    reset(); maplesu_request_set_auth_mode(RequestAuthMode::EarlyHelper); fixture.exact_helper = false;
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_ROOT_KEY && errno == ESTALE &&
        fixture.commands.size() == 1, "wrong executable retains native failure without fallback");
    reset(); maplesu_request_set_auth_mode(RequestAuthMode::EarlyHelper);
    const auto helper_generation = maplesu_request_auth_state();
    owner_management.session = 0x91; // Existing parent session seam, never granted by admission.
    check(maplesu_request_worker_admission() == KModErr::OK && owner_management.session == 0x91 &&
        maplesu_request_auth_state() == helper_generation &&
        fixture.commands == std::vector<uint32_t>{MAPLESU_REQUEST_VENDOR_STATE}, "helper READ preserves existing session and mode");
    reset(); request_auth_state.store(UINT64_MAX);
    check(maplesu_request_worker_admission() == KModErr::ERR_MODULE_ROOT_KEY && errno == EPERM &&
        fixture.commands.empty(), "invalid authentication tag rejects locally");
    std::printf("worker admission: %u checks passed; no session OPEN or ROOT\n", checks);
}
