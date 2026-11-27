// MapleSU su auth implementation.
//
#include "kernel_module_kit_umbrella.h"
#include "../maplesu/compat/su_auth_legacy_abi.h"
#include "closure_fork_pipe.h"
#include "maplesu_request_private.h"
#include "maplesu_storage_worker_private.h"
#include "../module/su_redirect_tu/su_auth_result_private.h"
#include "../module/su_redirect_tu/su_uid_profile_private.h"
#include <cerrno>
#include <sys/wait.h>
#include <utility>

namespace default_modules::module_root {
KModErr set_current_root(bool);
}
namespace maplesu::sdk {
KModErr get_su_auth_list_with_cb(void (*)(const su_auth_item*));
}

namespace skroot_env {
namespace {
thread_local void (*legacy_su_callback)(const su_auth_item*) = nullptr;

void forward_legacy_su_item(const maplesu::sdk::su_auth_item* item) {
    if (!item || !legacy_su_callback) return;
    su_auth_item legacy{};
    std::memcpy(&legacy, item, sizeof(legacy));
    legacy_su_callback(&legacy);
}
}  // namespace

KModErr get_su_auth_list_with_cb(void (*callback)(const su_auth_item*)) {
    const auto previous = legacy_su_callback;
    legacy_su_callback = callback;
    const KModErr error = maplesu::sdk::get_su_auth_list_with_cb(forward_legacy_su_item);
    legacy_su_callback = previous;
    return error;
}

KModErr add_su_auth_list(const char* app_package_name) {
    return maplesu::sdk::add_su_auth_list(app_package_name);
}

KModErr remove_su_auth_list(const char* app_package_name) {
    return maplesu::sdk::remove_su_auth_list(app_package_name);
}

KModErr clear_su_auth_list() {
    return maplesu::sdk::clear_su_auth_list();
}
}  // namespace skroot_env
namespace default_modules::module_su_redirect {
KModErr get_su_allowlist(void (*)(const maplesu::sdk::su_auth_item*));
}

namespace maplesu::sdk {
static_assert(sizeof(su_auth_item) == 256 && alignof(su_auth_item) == 1);

// The pipe payload is the raw record array; the conversion helpers keep the
// record stride and the byte view in one place.
template<class Item> static std::vector<uint8_t> to_bytes_memcpy(
    const std::vector<Item>& items __attribute__((noescape))) {
    std::vector<uint8_t> bytes(items.size() * sizeof(Item));
    if (!items.empty())
        std::memcpy(bytes.data(), items.data(), bytes.size());
    return bytes;
}

template<class Item> static bool from_bytes_memcpy(
    const std::vector<uint8_t>& bytes __attribute__((noescape)), std::vector<Item>& items) {
    if (bytes.size() % sizeof(Item) != 0)
        return false;
    items.resize(bytes.size() / sizeof(Item));
    if (!items.empty())
        std::memcpy(items.data(), bytes.data(), bytes.size());
    return true;
}

// The allowlist visitor runs only in the forked child; the callback records the
// items into the vector whose address is published for the lifetime of the
// kernel query.
static thread_local std::vector<su_auth_item>* tls_out = nullptr;

static KModErr unsafe_get_su_auth_list(std::vector<su_auth_item>& out) {
    tls_out = &out;
    KModErr err = default_modules::module_su_redirect::get_su_allowlist(
        [](const su_auth_item* item) {
            if (tls_out != nullptr && item != nullptr) {
                su_auth_item copy;
                std::memcpy(&copy, item, sizeof(copy));
                tls_out->push_back(static_cast<su_auth_item&&>(copy));
            }
        });
    tls_out = nullptr;
    return err;
}

// Runs the fork/pipe exchange and fills the record vector.
template<class Item, class Reader> static KModErr read_su_auth_over_pipe(
    std::vector<Item>& items, Reader read_items) {
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) return admission;
    fork_pipe_info pipe;
    KModErr err = KModErr::OK;
    if (fork_pipe_child_process(pipe)) {
        // Preserve the explicitly selected protocol. WorkerScope opens only this child's session.
        maplesu::storage::WorkerScope worker;
        KModErr child_err = worker.root();
        if (child_err == KModErr::OK)
            child_err = read_items(items);
        std::vector<uint8_t> bytes = to_bytes_memcpy(items);
        const auto closed = worker.close_checked();
        if (is_ok(child_err) && is_failed(closed)) child_err = closed;
        const bool sent = write_errcode_from_child(pipe, child_err) &&
            write_transfer_data_from_child(pipe, bytes);
        pipe.close_all();
        _exit(sent ? 0 : 1);
    }
    if (pipe.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (!read_errcode_from_child(pipe, err)) err = KModErr::ERR_READ_CHILD_ERRCODE;
    std::vector<uint8_t> bytes;
    if ((!read_transfer_data_from_child(pipe, bytes) || !from_bytes_memcpy(bytes, items)) &&
        err == KModErr::OK)
        err = KModErr::ERR_READ_CHILD_DATA;
    pipe.close_all();
    int status = 0;
    pid_t reaped;
    do { reaped = waitpid(pipe.child_pid, &status, 0); } while (reaped < 0 && errno == EINTR);
    if (err == KModErr::OK &&
        (reaped != pipe.child_pid || !WIFEXITED(status) || WEXITSTATUS(status) != 0))
        err = KModErr::ERR_READ_CHILD_ERRCODE;
    return err;
}

// Reads the whole list in a forked child, transfers the records through the
// pipe and reports every item to the caller after the child has been reaped.
KModErr get_su_auth_list_with_cb(void (*cb)(const su_auth_item*)) {
    if (!cb) return KModErr::ERR_MODULE_PARAM;
    std::vector<su_auth_item> items;
    KModErr err = read_su_auth_over_pipe(items, unsafe_get_su_auth_list);
    if (err != KModErr::OK)
        return err;
    for (const su_auth_item& item : items)
        cb(&item);
    return KModErr::OK;
}

KModErr add_su_auth_list(const char* app_package_name) {
    maplesu::su_auth::ResultV1 result(app_package_name ? maplesu::su_auth::Operation::Add : maplesu::su_auth::Operation::Refresh);
    return maplesu::su_auth::add(app_package_name, result);
}
KModErr remove_su_auth_list(const char* app_package_name) {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Remove);
    return maplesu::su_auth::remove(app_package_name, result);
}
KModErr clear_su_auth_list() {
    maplesu::su_auth::ResultV1 result(maplesu::su_auth::Operation::Clear);
    return maplesu::su_auth::clear(result);
}
}  // namespace maplesu::sdk

namespace maplesu::su_auth {
namespace {
KModErr mutate(const char* package, Operation operation, ResultV1& result,
               const uint32_t* full_uid = nullptr) {
    result = ResultV1(operation);
    if (operation == Operation::Remove && !package)
        return fail(result, KModErr::ERR_MODULE_PARAM, Phase::EntryValidate);
    if (full_uid && (!package || !*package))
        return fail(result, KModErr::ERR_MODULE_PARAM, Phase::EntryValidate);
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) return fail(result, admission, Phase::EntryRoot);
    maplesu::sdk::fork_pipe_info pipe;
    if (maplesu::sdk::fork_pipe_child_process(pipe)) {
        maplesu::storage::WorkerScope worker;
        auto error = worker.root();
        if (is_failed(error)) fail(result, error, Phase::EntryRoot);
        else if (full_uid)
            error = detail::transact_set_uid_profile(*full_uid, package, operation == Operation::Add, result);
        else if (operation == Operation::Add || operation == Operation::Refresh)
            error = detail::transact_add(package, result);
        else if (operation == Operation::Remove) error = detail::transact_remove(package, result);
        else error = detail::transact_clear(result);
        const auto closed = worker.close_checked();
        if (is_ok(error) && is_failed(closed)) error = fail(result, closed, Phase::EntryRoot);
        finish(result, error);
        const bool sent = maplesu::sdk::write_transfer_data_from_child(pipe, wire::encode(result));
        pipe.close_all();
        _exit(sent ? 0 : 1);
    }
    if (pipe.child_pid <= 0) {
        const int error_number = errno;
        result = unavailable(operation, KModErr::ERR_READ_CHILD_ERRCODE, Transport::ForkFailed, error_number);
        return KModErr::ERR_READ_CHILD_ERRCODE;
    }
    std::array<uint8_t, kResultBytes> bytes{};
    const auto read = maplesu::sdk::read_fixed_transfer_data_from_child(pipe, bytes.data(), bytes.size());
    bool available = false;
    if (read.status == maplesu::sdk::FixedTransferStatus::Complete)
        available = wire::decode(bytes.data(), bytes.size(), operation, result);
    if (available) result.transportStatus = static_cast<uint32_t>(Transport::Complete);
    else {
        Transport status = Transport::FrameInvalid;
        if (read.status == maplesu::sdk::FixedTransferStatus::Truncated) status = Transport::Truncated;
        if (read.status == maplesu::sdk::FixedTransferStatus::IoError) status = Transport::ReadError;
        result = unavailable(operation, KModErr::ERR_READ_CHILD_ERRCODE, status, read.systemErrno);
    }
    // Close before waiting: malformed/short frames must not leave this child
    // blocked on a pipe that the parent has stopped reading.
    pipe.close_all();
    int status = 0;
    pid_t reaped;
    do { reaped = waitpid(pipe.child_pid, &status, 0); } while (reaped < 0 && errno == EINTR);
    const int wait_errno = reaped < 0 ? errno : 0;
    const bool completed = reaped == pipe.child_pid;
    if (completed) {
        result.childWaitStatus = status;
        result.childWaitStatusValid = 1;
    }
    const bool child_ok = completed && WIFEXITED(status) && WEXITSTATUS(status) == 0;
    if (available && !child_ok) {
        result.transportStatus = static_cast<uint32_t>(completed ? Transport::ChildFailed : Transport::WaitFailed);
        result.transportErrno = wait_errno;
    }
    KModErr error = KModErr::ERR_READ_CHILD_ERRCODE;
    if (available) (void)legacy_code(result, error); // decode already checked the local KModErr range.
    if (!child_ok && error == KModErr::OK) error = KModErr::ERR_READ_CHILD_ERRCODE;
    return finish(result, error);
}
}
KModErr add(const char* package, ResultV1& result) {
    return mutate(package, package ? Operation::Add : Operation::Refresh, result);
}
KModErr remove(const char* package, ResultV1& result) {
    return mutate(package, Operation::Remove, result);
}
KModErr clear(ResultV1& result) {
    return mutate(nullptr, Operation::Clear, result);
}
KModErr set_uid_profile(uint32_t uid, const char* package,
                       bool allow, ResultV1& result) {
    return mutate(package, allow ? Operation::Add : Operation::Remove, result, &uid);
}

namespace {
thread_local std::vector<UidProfileV1>* uid_profiles_out = nullptr;
KModErr read_uid_profiles(std::vector<UidProfileV1>& profiles) {
    uid_profiles_out = &profiles;
    const auto error = detail::transact_get_uid_profiles([](const UidProfileV1* profile) {
        if (uid_profiles_out && profile) uid_profiles_out->push_back(*profile);
    });
    uid_profiles_out = nullptr;
    return error;
}
}
KModErr get_uid_profiles_with_cb(void (*callback)(const UidProfileV1*)) {
    if (!callback) return KModErr::ERR_MODULE_PARAM;
    std::vector<UidProfileV1> profiles;
    const auto error = maplesu::sdk::read_su_auth_over_pipe(profiles, read_uid_profiles);
    if (is_failed(error)) return error;
    // Validate the complete fixed-record array before publishing any callback.
    for (const auto& profile : profiles) {
        if (!profile.uid || profile.uid > uint32_t(INT32_MAX) || profile.allow > 1 || !profile.package[0] ||
            strnlen(profile.package, sizeof(profile.package)) == sizeof(profile.package))
            return KModErr::ERR_READ_CHILD_DATA;
    }
    for (const auto& profile : profiles) callback(&profile);
    return KModErr::OK;
}
}
