#include "maplesu_module_control.h"
#include "module_err_def.h"
#include "maplesu_storage_worker_private.h"
#include <tuple>
#include "maplesu_sdk/maplesu_installer.h"
#include "maplesu_sdk/maplesu_module.h"
#include "maplesu_sdk/maplesu_root.h"
#include "module_gate_identity.h"
#include "../boot/helper_capability_contract.h"
#include "../../maplesu-sdk/sdk/src/maplesu/module/path_contract.h"
#include <cerrno>
#include <chrono>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <string_view>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unordered_map>
#include <unistd.h>

// Exported from the pinned SDK IR; use its exact path derivation and root context.
extern "C" void maplesu_system_directory(std::string*);
extern "C" void maplesu_installed_helper_path(std::string*);
namespace maplesu::sdk { KModErr unsafe_get_all_modules(std::vector<module_record>&); }
namespace default_modules::module_root { KModErr set_current_root(bool); }

namespace {
thread_local int operation_lock = -1;
struct Fd {
    int value;
    explicit Fd(int fd) : value(fd) {}
    ~Fd() { if (value >= 0) close(value); }
};
int acquire_lock() {
    if (operation_lock >= 0) return 0;
    std::string directory;
    maplesu_system_directory(&directory);
    Fd fd(open((directory + "/.module-control.lock").c_str(), O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC, 0600));
    if (fd.value < 0) return maplesu::MODULE_CONTROL_IO;
    for (int i = 0; i < 500; ++i) {
        if (flock(fd.value, LOCK_EX|LOCK_NB) == 0) {
            operation_lock = fd.value; fd.value = -1; return 0;
        }
        if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) return maplesu::MODULE_CONTROL_IO;
        usleep(10000);
    }
    return maplesu::MODULE_CONTROL_BUSY;
}
void release_lock() {
    if (operation_lock < 0) return;
    // fork children share this open-file description; closing only their copy preserves the worker lock.
    close(operation_lock);
    operation_lock = -1;
}
bool id_valid(const char* id) {
    if (!id) return false;
    constexpr size_t capacity = sizeof(maplesu::sdk::module_desc{}.id32);
    const size_t size = strnlen(id, capacity);
    return size < capacity && maplesu::module_paths::valid_id(std::string_view(id, size));
}
bool helper_read(int fd, void* output, size_t size, uint64_t offset, uint64_t file_size) {
    if (offset > file_size || size > file_size - offset) return false;
    char* bytes = static_cast<char*>(output);
    while (size != 0) {
        const auto count = pread(fd, bytes, size, static_cast<off_t>(offset));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes += count;
        size -= static_cast<size_t>(count);
        offset += static_cast<uint64_t>(count);
    }
    return true;
}
bool source_helper_supported(int fd) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr))) return false;
    const auto file_size = static_cast<uint64_t>(st.st_size);
    Elf64_Ehdr header{};
    if (!helper_read(fd, &header, sizeof(header), 0, file_size) ||
        memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 || header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_ident[EI_VERSION] != EV_CURRENT ||
        header.e_machine != EM_AARCH64 || header.e_version != EV_CURRENT ||
        (header.e_type != ET_DYN && header.e_type != ET_EXEC) || header.e_ehsize != sizeof(header) ||
        header.e_phentsize != sizeof(Elf64_Phdr) || header.e_phnum == 0 || header.e_phnum > 128 ||
        header.e_phoff < sizeof(header) || header.e_phoff > file_size ||
        uint64_t(header.e_phnum) * sizeof(Elf64_Phdr) > file_size - header.e_phoff) return false;
    Elf64_Phdr segments[128]{};
    if (!helper_read(fd, segments, size_t(header.e_phnum) * sizeof(Elf64_Phdr), header.e_phoff, file_size)) return false;
    unsigned matches = 0;
    for (unsigned index = 0; index < header.e_phnum; ++index) {
        const auto& segment = segments[index];
        if (segment.p_type != PT_NOTE) continue;
        if (segment.p_offset > file_size || segment.p_filesz > file_size - segment.p_offset || segment.p_filesz > 65536) return false;
        uint64_t offset = segment.p_offset;
        const uint64_t end = offset + segment.p_filesz;
        while (offset < end) {
            Elf64_Nhdr note{};
            if (end - offset < sizeof(note) || !helper_read(fd, &note, sizeof(note), offset, file_size)) return false;
            offset += sizeof(note);
            const uint64_t name_size = (uint64_t(note.n_namesz) + 3) & ~uint64_t(3);
            const uint64_t desc_size = (uint64_t(note.n_descsz) + 3) & ~uint64_t(3);
            if (name_size > end - offset || desc_size > end - offset - name_size) return false;
            char name[8]{};
            if (note.n_type == MAPLESU_HELPER_CAPABILITY_NOTE_TYPE && note.n_namesz == sizeof(name)) {
                if (!helper_read(fd, name, sizeof(name), offset, file_size)) return false;
                if (memcmp(name, "MapleSU", sizeof(name)) == 0) {
                    const uint64_t note_begin = offset - sizeof(note);
                    const uint64_t note_end = offset + name_size + desc_size;
                    bool readonly = false;
                    for (unsigned load_index = 0; load_index < header.e_phnum; ++load_index) {
                        const auto& load = segments[load_index];
                        if (load.p_type != PT_LOAD) continue;
                        if (load.p_offset > file_size || load.p_filesz > file_size - load.p_offset) return false;
                        const uint64_t load_end = load.p_offset + load.p_filesz;
                        if (load.p_offset < note_end && note_begin < load_end && (load.p_flags & (PF_W | PF_X))) return false;
                        if (load.p_offset <= note_begin && note_end <= load_end && load.p_flags == PF_R) readonly = true;
                    }
                    uint32_t fields[4]{};
                    if (++matches != 1 || !readonly || segment.p_flags != PF_R || note.n_descsz != sizeof(fields) ||
                        !helper_read(fd, fields, sizeof(fields), offset + name_size, file_size) ||
                        fields[0] != MAPLESU_HELPER_CAPABILITY_ABI || fields[1] < MAPLESU_HELPER_CAPABILITY_MIN_VERSION_CODE ||
                        fields[2] != MAPLESU_HELPER_CAPABILITY_MODULE_BOOT_GATE || fields[3] != 0) return false;
                }
            }
            offset += name_size + desc_size;
        }
    }
    return matches == 1;
}
bool helper_supported() {
    std::string path;
    maplesu_installed_helper_path(&path);
    Fd fd(open(path.c_str(), O_RDONLY|O_NOFOLLOW|O_CLOEXEC));
    if (fd.value < 0) return false;
    unsigned char bytes[sizeof(maplesu_gate_bytes)];
    unsigned char branch[4], segment[56];
    const bool legacy = pread(fd.value, branch, 4, MAPLESU_GATE_BRANCH_OFFSET) == 4 &&
        memcmp(branch, maplesu_gate_branch, 4) == 0 &&
        pread(fd.value, segment, 56, MAPLESU_GATE_PHDR_OFFSET) == 56 &&
        memcmp(segment, maplesu_gate_phdr, 56) == 0 &&
        pread(fd.value, bytes, sizeof(bytes), MAPLESU_GATE_FILE_OFFSET) == ssize_t(sizeof(bytes)) &&
        memcmp(bytes, maplesu_gate_bytes, sizeof(bytes)) == 0;
    return legacy || source_helper_supported(fd.value);
}
bool boot_id(char* out) {
    Fd fd(open("/proc/sys/kernel/random/boot_id", O_RDONLY|O_CLOEXEC));
    if (fd.value < 0 || read(fd.value, out, 37) != 37 || out[36] != '\n') return false;
    for (int i = 0; i < 36; ++i) {
        const char c = out[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
        else if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
    }
    return true;
}
using Reply = maplesu::ModuleControlResult;
struct BootObservation {
    char id[37]{};
    bool valid = false;
};
Reply read_control_row(const char* id, int request,
                       const maplesu::sdk::module_record& module, int parent,
                       const BootObservation* observed_boot = nullptr) {
    Reply result;
    result.state.toggleSupported = 1;
    if (module.state == maplesu::sdk::ModuleRunState::RemovedPendingReboot) {
        result.error = maplesu::MODULE_CONTROL_NOT_FOUND; return result;
    }
    result.state.runtimeState = static_cast<uint32_t>(module.state);
    if (parent < 0) { result.error = maplesu::MODULE_CONTROL_IO; return result; }
    Fd dir(openat(parent, id, O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
    if (dir.value < 0) { result.error = errno == ENOENT ? maplesu::MODULE_CONTROL_NOT_FOUND : maplesu::MODULE_CONTROL_IO; return result; }
    BootObservation current_boot;
    if (!observed_boot) {
        current_boot.valid = boot_id(current_boot.id);
        observed_boot = &current_boot;
    }
    if (!observed_boot->valid) { result.error = maplesu::MODULE_CONTROL_IO; return result; }
    const char* now = observed_boot->id;
    char record[39]{};
    Fd current(openat(dir.value, ".maplesu-state", O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC));
    bool present = current.value >= 0;
    if (present) {
        if (read(current.value, record, sizeof(record)) != 38 ||
            (record[0] != '0' && record[0] != '1') || record[37] != '\n') {
            result.error = maplesu::MODULE_CONTROL_IO; return result;
        }
        result.state.enabled = record[0] == '1';
        result.state.needsReboot = memcmp(record + 1, now, 36) == 0;
    } else if (errno != ENOENT) { result.error = maplesu::MODULE_CONTROL_IO; return result; }
    if (request >= 0) {
        // Safe mode / Fault never starts or re-enables native execution through this API.
        if (maplesu::sdk::get_maplesu_environment_state() != maplesu::sdk::MapleSUEnvState::Running) {
            result.error = maplesu::MODULE_CONTROL_ENVIRONMENT; return result;
        }
        if (result.state.enabled != uint32_t(request)) {
            // Atomic commit: an interruption leaves either the old or the new complete state.
            memcpy(record + 1, now, 36); record[0] = request ? '1' : '0'; record[37] = '\n';
            // A stale temp from an interrupted transaction is never read by the loader.
            unlinkat(dir.value, ".maplesu-state.tmp", 0);
            Fd temp(openat(dir.value, ".maplesu-state.tmp", O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC, 0600));
            if (temp.value < 0 || write(temp.value, record, 38) != 38 || fsync(temp.value) != 0 ||
                renameat(dir.value, ".maplesu-state.tmp", dir.value, ".maplesu-state") != 0 || fsync(dir.value) != 0) {
                result.error = maplesu::MODULE_CONTROL_IO; return result;
            }
            result.state.enabled = request;
            result.state.needsReboot = 1;
        }
    }
    if (request < 0) {
        struct stat opened{}, linked{};
        if (fstat(dir.value, &opened) != 0) { result.error = maplesu::MODULE_CONTROL_IO; return result; }
        if (fstatat(parent, id, &linked, AT_SYMLINK_NOFOLLOW) != 0) {
            result.error = errno == ENOENT ? maplesu::MODULE_CONTROL_NOT_FOUND : maplesu::MODULE_CONTROL_IO;
            return result;
        }
        if (!S_ISDIR(linked.st_mode) || opened.st_dev != linked.st_dev || opened.st_ino != linked.st_ino) {
            result.error = maplesu::MODULE_CONTROL_IO; return result;
        }
    }
    // A boot-state change does not establish an immediate stop or start.
    if ((!result.state.enabled && result.state.runtimeState == 1) ||
        (result.state.enabled && result.state.runtimeState == 0)) result.state.needsReboot = 1;
    result.error = 0;
    return result;
}
Reply read_control(const char* id, int request,
                   const std::vector<maplesu::sdk::module_record>& modules) {
    Reply result;
    result.state.toggleSupported = 1;
    const maplesu::sdk::module_record* found = nullptr;
    const size_t id_size = strnlen(id, sizeof(maplesu::sdk::module_desc{}.id32));
    for (const auto& item : modules) {
        const size_t size = strnlen(item.desc.id32, sizeof(item.desc.id32));
        if (size < sizeof(item.desc.id32) && size == id_size && memcmp(item.desc.id32, id, size) == 0) {
            if (found) { result.error = maplesu::MODULE_CONTROL_IO; return result; }
            found = &item;
        }
    }
    if (!found || found->state == maplesu::sdk::ModuleRunState::RemovedPendingReboot) {
        result.error = maplesu::MODULE_CONTROL_NOT_FOUND; return result;
    }
    std::string directory;
    maplesu_system_directory(&directory);
    Fd parent(open((directory + "/modules").c_str(), O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
    return read_control_row( id, request, *found, parent.value);
}
Reply execute(const char* id, int request) {
    Reply result;
    maplesu::storage::WorkerScope worker(id);
    auto err = worker.root();
    if (is_failed(err)) { result.error = static_cast<int64_t>(err); return result; }
    result = [&]() -> Reply {
        if (request >= 0) {
            result.error = acquire_lock();
            if (result.error != 0) return result;
        }
        result.state.toggleSupported = helper_supported();
        if (request == -2) { result.error = 0; return result; }
        if (!result.state.toggleSupported) { result.error = maplesu::MODULE_CONTROL_UNSUPPORTED; return result; }
        std::vector<maplesu::sdk::module_record> modules;
        err = maplesu::sdk::unsafe_get_all_modules(modules);
        if (is_failed(err)) { result.error = static_cast<int64_t>(err); return result; }
        return read_control( id, request, modules);
    }();
    const auto closed = worker.close_checked();
    if (!result.error && is_failed(closed)) result.error = static_cast<int64_t>(closed);
    return result;
}
int64_t invoke(const char* id, int request, maplesu::ModuleControlState& out) {
    out = {};
    if (request != -2 && !id_valid(id)) return maplesu::MODULE_CONTROL_INVALID_ID;
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) return static_cast<int64_t>(admission);
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) != 0) return maplesu::MODULE_CONTROL_IO;
    Fd input(pipes[0]), output(pipes[1]);
    pid_t pid = fork();
    if (pid < 0) return maplesu::MODULE_CONTROL_IO;
    if (pid == 0) {
        close(input.value);
        Reply reply;
        try { reply = execute( id, request); } catch (...) { reply.error = maplesu::MODULE_CONTROL_IO; }
        release_lock();
        const auto ignored = write(output.value, &reply, sizeof(reply)); (void)ignored;
        _exit(0);
    }
    close(output.value); output.value = -1;
    pollfd watch{input.value, POLLIN, 0};
    int ready;
    do { ready = poll(&watch, 1, 10000); } while (ready < 0 && errno == EINTR);
    Reply reply;
    const bool received = ready > 0 && read(input.value, &reply, sizeof(reply)) == ssize_t(sizeof(reply));
    if (!received) kill(pid, SIGKILL);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != pid || !received || !WIFEXITED(status) || WEXITSTATUS(status) != 0) return maplesu::MODULE_CONTROL_IO;
    out = reply.state;
    return reply.error;
}

void execute_controls(const std::vector<maplesu::sdk::module_record>& records,
                      std::vector<Reply>& replies) {
    maplesu::storage::WorkerScope worker;
    int64_t error = static_cast<int64_t>(worker.root());
    if (error == 0 && !helper_supported()) error = maplesu::MODULE_CONTROL_UNSUPPORTED;
    if (error != 0) {
        for (size_t index = 0; index < records.size(); ++index)
            if (id_valid(records[index].desc.id32)) replies[index].error = error;
        return;
    }
    std::unordered_map<std::string_view, size_t> counts;
    counts.reserve(records.size());
    for (const auto& record : records)
        if (id_valid(record.desc.id32)) ++counts[record.desc.id32];
    Fd parent(-1);
    std::string directory;
    BootObservation observed_boot;
    bool prepared = false;
    for (size_t index = 0; index < records.size(); ++index) {
        const auto& record = records[index];
        const char* id = record.desc.id32;
        if (!id_valid(id)) continue;
        if (counts[id] != 1) {
            replies[index].state.toggleSupported = 1;
            replies[index].error = maplesu::MODULE_CONTROL_IO;
            continue;
        }
        if (record.state != maplesu::sdk::ModuleRunState::RemovedPendingReboot && !prepared) {
            maplesu_system_directory(&directory);
            directory += "/modules";
            parent.value = open(directory.c_str(), O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            if (parent.value >= 0) observed_boot.valid = boot_id(observed_boot.id);
            prepared = true;
        }
        replies[index] = read_control_row( id, -1, record, parent.value, &observed_boot);
    }
    if (parent.value >= 0) {
        // A held directory can survive replacement of its current pathname.
        struct stat opened{}, linked{};
        if (fstat(parent.value, &opened) != 0 || lstat(directory.c_str(), &linked) != 0 ||
            !S_ISDIR(linked.st_mode) || opened.st_dev != linked.st_dev || opened.st_ino != linked.st_ino) {
            for (auto& reply : replies)
                if (reply.error == 0) reply.error = maplesu::MODULE_CONTROL_IO;
        }
    }
    const auto closed = worker.close_checked();
    if (is_failed(closed)) {
        for (auto& reply : replies)
            if (!reply.error) reply.error = static_cast<int64_t>(closed);
    }
}

std::vector<Reply> invoke_controls(const std::vector<maplesu::sdk::module_record>& records) {
    std::vector<Reply> failed(records.size());
    bool valid = false;
    for (size_t index = 0; index < records.size(); ++index) {
        if (id_valid(records[index].desc.id32)) valid = true;
        else failed[index].error = maplesu::MODULE_CONTROL_INVALID_ID;
    }
    if (!valid) return failed;
    const auto admission = kernel_module::maplesu_request_worker_admission();
    if (is_failed(admission)) {
        for (auto& reply : failed)
            if (reply.error != maplesu::MODULE_CONTROL_INVALID_ID) reply.error = static_cast<int64_t>(admission);
        return failed;
    }
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) != 0) return failed;
    Fd input(pipes[0]), output(pipes[1]);
    const pid_t pid = fork();
    if (pid < 0) return failed;
    if (pid == 0) {
        close(input.value);
        auto replies = failed;
        try { execute_controls( records, replies); } catch (...) { replies = failed; }
        release_lock();
        // A list can exceed PIPE_BUF; the parent drains complete replies as they arrive.
        const char* bytes = reinterpret_cast<const char*>(replies.data());
        size_t remaining = replies.size() * sizeof(Reply);
        while (remaining != 0) {
            const ssize_t count = write(output.value, bytes, remaining);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) _exit(1);
            bytes += count;
            remaining -= size_t(count);
        }
        _exit(0);
    }
    close(output.value); output.value = -1;
    auto replies = failed;
    char* bytes = reinterpret_cast<char*>(replies.data());
    size_t remaining = replies.size() * sizeof(Reply);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (remaining != 0) {
        const auto budget = std::chrono::ceil<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (budget <= 0) break;
        pollfd watch{input.value, POLLIN, 0};
        const int ready = poll(&watch, 1, int(budget));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) break;
        const ssize_t count = read(input.value, bytes, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        bytes += count;
        remaining -= size_t(count);
    }
    if (remaining != 0) kill(pid, SIGKILL);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited != pid || remaining != 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) return failed;
    return replies;
}
}

// Called only in the SDK's forked mutation worker. Re-entrant during update cleanup.
extern "C" KModErr maplesu_lock_module_root(bool allow) {
    const auto err = default_modules::module_root::set_current_root(allow);
    if (is_failed(err)) return err;
    return static_cast<KModErr>(acquire_lock());
}
extern "C" [[noreturn]] void maplesu_module_worker_exit(int code) {
    release_lock(); _exit(code);
}
namespace maplesu {
int64_t module_control_capability(bool& supported) {
    ModuleControlState result;
    const auto error = invoke( nullptr, -2, result);
    supported = error == 0 && result.toggleSupported;
    return error;
}
int64_t get_module_control(const char* id, ModuleControlState& out) { return invoke(id,-1,out); }
std::vector<ModuleControlResult> get_module_controls(
    const std::vector<maplesu::sdk::module_record>& records) { return invoke_controls( records); }
int64_t set_module_enabled(const char* id, bool enabled, ModuleControlState& out) {
    return invoke(id,enabled ? 1 : 0,out);
}
}
