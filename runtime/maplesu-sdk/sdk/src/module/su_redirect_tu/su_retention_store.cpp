#include "su_retention_store.h"
#include "maplesu_storage_private.h"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>


namespace {
constexpr const char* record = "uid_retention_budget.20261105";
struct Descriptor {
    int value;
    explicit Descriptor(int fd) : value(fd) {}
    ~Descriptor() { if (value >= 0) close(value); }
};

bool identifier(const char* text, size_t size) {
    if (!text || strnlen(text, size + 1) != size) return false;
    for (size_t index = 0; index < size; ++index) {
        const char c = text[index];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')))
            return false;
    }
    return true;
}

bool directory(int fd) {
    struct stat st{};
    return fd >= 0 && fstat(fd, &st) == 0 && S_ISDIR(st.st_mode) &&
        st.st_uid == 0 && st.st_gid == 0 && (st.st_mode & 0022) == 0;
}

int open_child(int parent, const char* name, bool private_mode) {
    const int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (!directory(fd) || (private_mode && fchmod(fd, 0700) != 0)) {
        if (fd >= 0) close(fd);
        return -1;
    }
    return fd;
}

bool synchronize(int fd) {
    int result;
    do { result = fsync(fd); } while (result < 0 && errno == EINTR);
    return result == 0;
}
}

KModErr maplesu_store_su_retention(const void* ledger, uint64_t size) {
    if (!ledger || size != 64 || static_cast<const unsigned char*>(ledger)[0] != 1)
        return KModErr::ERR_MODULE_STORAGE_TYPE;
    maplesu::storage::StorageContext storage_context;
    const auto located = maplesu::storage::current_context(storage_context);
    if (is_failed(located)) return located;
    const char* module = storage_context.module_id.c_str();
    if (!identifier(module, 32)) return KModErr::ERR_MODULE_PARAM;
    const char* owner = storage_context.locator.system_component;
    Descriptor data(open("/data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    // /data is Android-owned and may be group-writable; validate the private
    // component and every descendant before using descriptor-relative paths.
    if (data.value < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    Descriptor root(open_child(data.value, owner, false));
    Descriptor storage(root.value < 0 ? -1 : open_child(root.value, "storage", true));
    Descriptor target(storage.value < 0 ? -1 : open_child(storage.value, module, true));
    if (target.value < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    struct stat old{};
    if (fstatat(target.value, record, &old, AT_SYMLINK_NOFOLLOW) == 0) {
        if (!S_ISREG(old.st_mode) || old.st_uid != 0 || old.st_gid != 0 || old.st_nlink != 1)
            return KModErr::ERR_MODULE_OPEN_FILE;
    } else if (errno != ENOENT) return KModErr::ERR_MODULE_OPEN_FILE;
    char temporary[96]{};
    int fd = -1;
    for (unsigned attempt = 0; attempt < 32; ++attempt) {
        snprintf(temporary, sizeof(temporary), "%s.tmp.%ld.%u", record, static_cast<long>(getpid()), attempt);
        fd = openat(target.value, temporary, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    if (fd < 0) return KModErr::ERR_MODULE_OPEN_FILE;
    bool valid = fstat(fd, &old) == 0 && S_ISREG(old.st_mode) && old.st_uid == 0 &&
        old.st_gid == 0 && old.st_nlink == 1 && fchmod(fd, 0600) == 0;
    static const char context[] = "u:object_r:system_file:s0";
    if (valid) valid = fsetxattr(fd, "security.selinux", context, sizeof(context), 0) == 0;
    size_t written = 0;
    while (valid && written < size) {
        const auto count = write(fd, static_cast<const char*>(ledger) + written, size - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { valid = false; break; }
        written += static_cast<size_t>(count);
    }
    unsigned char observed[64]{};
    size_t read = 0;
    while (valid && read < sizeof(observed)) {
        const auto count = pread(fd, observed + read, sizeof(observed) - read, read);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { valid = false; break; }
        read += static_cast<size_t>(count);
    }
    if (valid) valid = fstat(fd, &old) == 0 && old.st_size == 64 &&
        memcmp(observed, ledger, sizeof(observed)) == 0 && synchronize(fd);
    if (close(fd) != 0) valid = false;
    if (valid) valid = renameat(target.value, temporary, target.value, record) == 0;
    if (valid) valid = synchronize(target.value);
    if (!valid) unlinkat(target.value, temporary, 0);
    return valid ? KModErr::OK : KModErr::ERR_MODULE_STORAGE_WRITE;
}


#include "su_uid_lifetime_private.h"
#include "su_uid_index_private.h"
#include "module_base_disk_storage.h"
#include "module_base_kernel_mem.h"
#include "module_base_kernel_request.h"
#include "module_base_kernel_symbol_kaddr.h"
#ifndef ASMJIT_STATIC
#define ASMJIT_STATIC
#endif
#include "third_party/asmjit2/core.h"
#include "third_party/asmjit2/a64.h"

// The public executor header includes the complete UI/export umbrella. These
// exact existing executor declarations keep this storage TU's dependency narrow.
namespace kernel_module {
void arm64_module_asm_func_start(asmjit::a64::Assembler*);
void arm64_module_asm_func_end(asmjit::a64::Assembler*, asmjit::a64::GpX);
KModErr execute_kernel_asm_func_with_buf(const uint8_t*, uint32_t, uint64_t&);
}

namespace {
constexpr const char* uid_reader_protocol = "uid_rcu_control.20261121";
constexpr const char* uid_index_key = "uid_private_index.20261121";
constexpr const char* uid_control_key = "uid_control_kaddr.e842572c094b847aed9d11840a807b46";
KModErr uid_native_function(const char* name, uint64_t& address) {
    kernel_module::SymbolHit hit{};
    const auto error = kernel_module::kallsyms_lookup_name(name, hit, kernel_module::SymbolMatchMode::Exact);
    if (is_failed(error)) return error;
    address = hit.addr;
    if (address < UINT64_C(0xffff000000000000) || (address & 3u)) {
        errno = EPROTO;
        return KModErr::ERR_MODULE_NOT_KADDR;
    }
    return KModErr::OK;
}
}
KModErr maplesu_su_uid_reader_functions(uint64_t& lock, uint64_t& unlock) {
    auto error = uid_native_function("__rcu_read_lock", lock);
    if (is_failed(error)) return error;
    error = uid_native_function("__rcu_read_unlock", unlock);
    if (is_failed(error)) return error;
    if (lock == unlock) { errno = EPROTO; return KModErr::ERR_MODULE_NOT_KADDR; }
    return KModErr::OK;
}
KModErr maplesu_su_uid_reader_reset() {
    auto error = kernel_module::write_uint64_disk_storage(uid_reader_protocol, 0);
    return is_failed(error) ? error : kernel_module::write_uint64_disk_storage(uid_index_key, 0);
}
KModErr maplesu_su_uid_reader_activate(uint64_t control) {
    uint64_t lock = 0, unlock = 0, synchronize = 0;
    auto error = maplesu_su_uid_reader_functions(lock, unlock);
    if (is_failed(error)) return error;
    error = uid_native_function("synchronize_rcu", synchronize);
    if (is_failed(error)) return error;
    if (!control || synchronize == lock || synchronize == unlock) {
        errno = EPROTO;
        return KModErr::ERR_MODULE_PARAM;
    }
    return kernel_module::write_uint64_disk_storage(uid_reader_protocol, control);
}
KModErr maplesu_su_uid_reader_require(uint64_t control) {
    uint64_t installed = 0;
    const auto error = kernel_module::read_uint64_disk_storage(uid_reader_protocol, installed);
    if (is_failed(error)) return error;
    if (!control || installed != control) {
        errno = ESTALE;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    return KModErr::OK;
}
namespace {
KModErr uid_synchronize_native() {
    using namespace kernel_module;
    uint64_t address = 0;
    auto error = uid_native_function("synchronize_rcu", address);
    if (is_failed(error)) return error;
    struct Errors final : asmjit::ErrorHandler {
        bool failed = false;
        void handleError(asmjit::Error, const char*, asmjit::BaseEmitter*) override { failed = true; }
    } errors;
    asmjit::CodeHolder code;
    if (code.init(asmjit::Environment(asmjit::Arch::kAArch64))) return KModErr::ERR_MODULE_ASM;
    code.setErrorHandler(&errors);
    asmjit::a64::Assembler a(&code);
    arm64_module_asm_func_start(&a);
    a.movz(asmjit::a64::x16, static_cast<uint16_t>(address));
    for (unsigned shift = 16; shift < 64; shift += 16)
        a.movk(asmjit::a64::x16, static_cast<uint16_t>(address >> shift), shift);
    a.blr(asmjit::a64::x16);
    a.mov(asmjit::a64::x0, 0); // synchronize_rcu returns void.
    arm64_module_asm_func_end(&a, asmjit::a64::x0);
    if (errors.failed || code.hasUnresolvedLinks()) return KModErr::ERR_MODULE_ASM;
    const auto& bytes = code.sectionById(0)->buffer();
    if (bytes.size() > UINT32_MAX) return KModErr::ERR_MODULE_ASM;
    uint64_t status = 0;
    error = execute_kernel_asm_func_with_buf(bytes.data(), static_cast<uint32_t>(bytes.size()), status);
    if (is_failed(error)) return error;
    if (status) {
        const auto native = static_cast<int64_t>(status);
        errno = native < 0 && native >= -4095 ? static_cast<int>(-native) : EPROTO;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    return KModErr::OK;
}
}
KModErr maplesu_su_uid_synchronize() {
    // The existing SDK EXEC runs in its syscall continuation, after kprobe
    // dispatch, not inside the probe; native synchronize_rcu may sleep there.
    return uid_synchronize_native();
}
namespace {
using namespace maplesu::su_auth::detail;
bool uid_pointer(uint64_t pointer, uint64_t alignment = 8) {
    return pointer >= UINT64_C(0xffff000000000000) && !(pointer & (alignment - 1));
}
KModErr uid_state(uint64_t control, bool create, uint64_t& address, UidIndexState& state) {
    using namespace kernel_module;
    if (!uid_pointer(control)) { errno = EINVAL; return KModErr::ERR_MODULE_PARAM; }
    auto error = read_uint64_disk_storage(uid_index_key, address);
    if (is_failed(error)) return error;
    if (!address && create) {
        state = {uid_index_magic, control, {0, 0}};
        error = request_kernel_alloc_mem(sizeof(state), address);
        if (is_failed(error)) return error;
        if (!uid_pointer(address)) { errno = EPROTO; return KModErr::ERR_MODULE_NOT_KADDR; }
        error = write_kernel_mem(address, &state, sizeof(state), KernMemProt::KMP_RW);
        UidIndexState observed{};
        if (is_ok(error)) error = read_kernel_mem(address, &observed, sizeof(observed));
        if (is_ok(error) && memcmp(&state, &observed, sizeof(state))) error = KModErr::ERR_MODULE_PARAM;
        if (is_failed(error)) { (void)free_kernel_mem(address); return error; }
        error = write_uint64_disk_storage(uid_index_key, address);
        if (is_failed(error)) {
            uint64_t published = 0;
            if (is_ok(read_uint64_disk_storage(uid_index_key, published)) && published != address)
                (void)free_kernel_mem(address);
            return error;
        }
    }
    if (!uid_pointer(address)) { errno = ESTALE; return KModErr::ERR_MODULE_TEST_CHANNEL; }
    error = read_kernel_mem(address, &state, sizeof(state));
    if (is_failed(error)) return error;
    if (state.magic != uid_index_magic || state.control != control ||
        (state.slots[0] && !uid_pointer(state.slots[0])) ||
        (state.slots[1] && !uid_pointer(state.slots[1]))) {
        errno = ESTALE; return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    return KModErr::OK;
}
KModErr current_uid_state(bool create, uint64_t& address, UidIndexState& state) {
    uint64_t control = 0;
    const auto error = kernel_module::read_uint64_disk_storage(uid_control_key, control);
    return is_failed(error) ? error : uid_state(control, create, address, state);
}
}
KModErr maplesu_su_uid_index_state(uint64_t control, uint64_t& address) {
    UidIndexState state{};
    return uid_state(control, true, address, state);
}
KModErr maplesu_su_uid_index_prepare(uint64_t table, uint32_t count) {
    using namespace kernel_module;
    if (!uid_pointer(table, 4) || !count || count > uid_index_max ||
        table > UINT64_MAX - (count + UINT64_C(1)) * sizeof(uint32_t)) {
        errno = EINVAL; return KModErr::ERR_MODULE_PARAM;
    }
    std::vector<uint32_t> uids(count + 1);
    auto error = read_kernel_mem(table, uids.data(), static_cast<uint32_t>(uids.size() * sizeof(uint32_t)));
    if (is_failed(error)) return error;
    std::vector<uint8_t> bytes;
    if (!build_uid_index(table, uids, bytes)) { errno = EINVAL; return KModErr::ERR_MODULE_PARAM; }
    uint64_t address = 0;
    UidIndexState state{};
    error = current_uid_state(true, address, state);
    if (is_failed(error)) return error;
    unsigned available = 2;
    for (unsigned slot = 0; slot < 2; ++slot) {
        if (!state.slots[slot]) { available = slot; continue; }
        UidIndexHeader header{};
        error = read_kernel_mem(state.slots[slot], &header, sizeof(header));
        if (is_failed(error)) return error;
        if (header.table != table) continue;
        if (header.count != count) { errno = ESTALE; return KModErr::ERR_MODULE_TEST_CHANNEL; }
        std::vector<uint8_t> observed(bytes.size());
        error = read_kernel_mem(state.slots[slot], observed.data(), static_cast<uint32_t>(observed.size()));
        if (is_failed(error)) return error;
        if (bytes != observed) { errno = ESTALE; return KModErr::ERR_MODULE_TEST_CHANNEL; }
        return KModErr::OK;
    }
    if (available == 2) { errno = ENOSPC; return KModErr::ERR_MODULE_ALLOC_MEM; }
    uint64_t index = 0;
    error = request_kernel_alloc_mem(static_cast<uint32_t>(bytes.size()), index);
    if (is_failed(error)) return error;
    if (!uid_pointer(index)) { errno = EPROTO; return KModErr::ERR_MODULE_NOT_KADDR; }
    error = write_kernel_mem(index, bytes.data(), static_cast<uint32_t>(bytes.size()), KernMemProt::KMP_RW);
    std::vector<uint8_t> observed(bytes.size());
    if (is_ok(error)) error = read_kernel_mem(index, observed.data(), static_cast<uint32_t>(observed.size()));
    if (is_ok(error) && bytes != observed) error = KModErr::ERR_MODULE_PARAM;
    if (is_failed(error)) { (void)free_kernel_mem(index); return error; }
    const uint64_t slot = address + offsetof(UidIndexState, slots) + available * sizeof(uint64_t);
    error = write_kernel_rw_mem_atomic64(slot, index);
    if (is_failed(error)) {
        uint64_t published = 0;
        // A failed receipt may follow the actual store. Keep an exposed index.
        if (is_ok(read_kernel_mem(slot, &published, sizeof(published))) && published != index)
            (void)free_kernel_mem(index);
    }
    return error;
}
KModErr maplesu_su_uid_index_release(uint64_t table) {
    using namespace kernel_module;
    if (!table) return KModErr::OK;
    uint64_t address = 0, published = 0, index = 0;
    UidIndexState state{};
    auto error = current_uid_state(false, address, state);
    if (is_failed(error)) return error;
    error = read_kernel_mem(state.control, &published, sizeof(published));
    if (is_failed(error)) return error;
    if (published == table) { errno = EBUSY; return KModErr::ERR_MODULE_TEST_CHANNEL; }
    for (unsigned slot = 0; slot < 2; ++slot) {
        if (!state.slots[slot]) continue;
        UidIndexHeader header{};
        error = read_kernel_mem(state.slots[slot], &header, sizeof(header));
        if (is_failed(error)) return error;
        if (header.table != table) continue;
        index = state.slots[slot];
        error = write_kernel_rw_mem_atomic64(address + offsetof(UidIndexState, slots) + slot * sizeof(uint64_t), 0);
        if (is_failed(error)) return error; // Ambiguous detach retains both allocations.
        break;
    }
    // Readers which loaded a slot just before its removal can still compare its
    // table. Drain them too, even when the caller already drained table readers.
    error = maplesu_su_uid_synchronize();
    if (is_failed(error)) return error;
    if (index) {
        error = free_kernel_mem(index);
        if (is_failed(error)) return error;
    }
    return free_kernel_mem(table);
}

KModErr maplesu_su_uid_bootstrap_retire(uint64_t control, bool initialized, bool readers_detached) {
    using namespace kernel_module;
    if (!control) return KModErr::OK;
    if (!readers_detached) {
        errno = EBUSY;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    if (control < UINT64_C(0xffff000000000000) || (control & 7u)) {
        errno = EINVAL;
        return KModErr::ERR_MODULE_PARAM;
    }
    constexpr const char* control_key = "uid_control_kaddr.e842572c094b847aed9d11840a807b46";
    uint64_t published = 0, reader = 0, table = 0;
    auto error = read_uint64_disk_storage(control_key, published);
    if (is_failed(error)) return error;
    error = read_uint64_disk_storage(uid_reader_protocol, reader);
    if (is_failed(error)) return error;
    if ((published && published != control) || (reader && reader != control)) {
        errno = ESTALE;
        return KModErr::ERR_MODULE_TEST_CHANNEL;
    }
    // A failed fill may leave arbitrary bytes: it owns only the control, not a
    // pointer decoded from uninitialized memory. No other writer runs under init's lock.
    if (initialized) {
        error = read_kernel_mem(control, &table, sizeof(table));
        if (is_failed(error)) return error;
        if (table && (table < UINT64_C(0xffff000000000000) || (table & 3u) || table == control)) {
            errno = EPROTO;
            return KModErr::ERR_MODULE_NOT_KADDR;
        }
    }
    const auto clear = [&](const char* key, uint64_t value) {
        if (!value) return KModErr::OK;
        auto result = write_uint64_disk_storage(key, 0);
        if (is_failed(result)) return result;
        uint64_t observed = 0;
        result = read_uint64_disk_storage(key, observed);
        if (is_failed(result)) return result;
        if (observed) {
            errno = ESTALE;
            return KModErr::ERR_MODULE_TEST_CHANNEL;
        }
        return KModErr::OK;
    };
    uint64_t descriptor = 0;
    error = read_uint64_disk_storage(uid_index_key, descriptor);
    if (is_failed(error)) return error;
    if (table) {
        error = write_kernel_rw_mem_atomic64(control, 0);
        if (is_failed(error)) return error;
        error = maplesu_su_uid_index_release(table);
        if (is_failed(error)) return error;
    }
    error = clear(control_key, published);
    if (is_failed(error)) return error;
    error = clear(uid_reader_protocol, reader);
    if (is_failed(error)) return error;
    if (descriptor) {
        UidIndexState state{};
        error = read_kernel_mem(descriptor, &state, sizeof(state));
        if (is_failed(error)) return error;
        if (state.magic != uid_index_magic || state.control != control || state.slots[0] || state.slots[1]) {
            errno = EBUSY; return KModErr::ERR_MODULE_TEST_CHANNEL;
        }
        error = clear(uid_index_key, descriptor);
        if (is_failed(error)) return error;
        error = free_kernel_mem(descriptor);
        if (is_failed(error)) return error;
    }
    // A release error can occur after the native release. Never retry either
    // address from this single-shot cleanup or free an allocation still referenced.
    return free_kernel_mem(control);
}
