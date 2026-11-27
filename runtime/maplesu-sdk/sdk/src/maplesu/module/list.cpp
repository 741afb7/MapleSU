
//
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <new>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "../compat/module_list_legacy_abi.h"
#include "../compat/module_carrier_conversion.h"
#include "operations.h"
#include "maplesu_storage_worker_private.h"
#include "state.h"
#include "maplesu_storage_private.h"

namespace maplesu::module {
namespace {

namespace file_utils {

// Read a file, or at most max_bytes from its prefix when the caller needs a bounded field.
//     seekg(0,end) -> tellg()<0 检查 -> seekg(0,beg) -> resize(size) ->
//     read(buf,size) -> !fail()
static bool read_file_bytes(const char* file_path, std::vector<uint8_t>& out_bytes,
                            size_t max_bytes = SIZE_MAX) {
    std::ifstream file_stream(file_path, std::ios::binary);
    if (!file_stream) {
        return false;
    }
    file_stream.seekg(0, std::ios::end);
    const std::streamoff file_size = file_stream.tellg();
    if (file_size < 0) {
        return false;
    }
    file_stream.seekg(0, std::ios::beg);
    const size_t read_size = std::min(static_cast<size_t>(file_size), max_bytes);
    out_bytes.resize(read_size);
    file_stream.read(reinterpret_cast<char*>(out_bytes.data()), static_cast<std::streamsize>(read_size));
    return !file_stream.fail();
}

// 读文本文件到 std::string；读取失败返回 false。
static bool read_text_file(const char* file_path, std::string& out_text, size_t max_bytes) {
    std::vector<uint8_t> file_bytes;
    const bool is_read_ok = read_file_bytes(file_path, file_bytes, max_bytes);
    if (is_read_ok) {
        out_text.assign(file_bytes.begin(), file_bytes.end());
    }
    return is_read_ok;
}

// 路径是否存在（不抛异常重载）。
static bool file_exists(const std::filesystem::path& path) {
    std::error_code error_code;
    return std::filesystem::exists(path, error_code);
}

}  // namespace file_utils


// std::vector<std::string> -> std::set<std::string>（去重，供状态清单查表）
static std::set<std::string> v_to_s(const std::vector<std::string>& value_list) {
    std::set<std::string> value_set;
    for (const std::string& value : value_list) {
        value_set.insert(value);
    }
    return value_set;
}

static std::string get_system_file_dir_path() {
    std::string directory;
    if (is_failed(storage::current_system_directory(directory))) return {};
    return directory;
}

// std::vector<T> <-> 字节流（fork 父子进程之间传模块记录用）
// 因此这里用内部链接的模板实现，不产生额外导出符号。
namespace {

// 序列化：把 vector<T> 的裸字节放进 vector<uint8_t>。
template <typename T>
std::vector<uint8_t> records_to_bytes(const std::vector<T>& records) {
    std::vector<uint8_t> bytes(records.size() * sizeof(T));
    if (!bytes.empty()) {
        memcpy(bytes.data(), records.data(), bytes.size());
    }
    return bytes;
}

// 反序列化：字节数是 sizeof(T) 整数倍才接受，否则返回空 vector。
template <typename T>
std::vector<T> bytes_to_records(const std::vector<uint8_t>& bytes) {
    if (bytes.size() % sizeof(T) != 0) {
        return std::vector<T>();
    }
    std::vector<T> records(bytes.size() / sizeof(T));
    if (!records.empty()) {
        memcpy(records.data(), bytes.data(), records.size() * sizeof(T));
    }
    return records;
}

}  // namespace

// 枚举全部已安装模块（由 safe_ 版在子进程和存储工作域里调用）
//
//   1. module_root::set_current_root(true)，失败直接返回该错误码
//   2. 读三份全局状态清单 -> std::set（running / abnormal / removed_pending_reboot）
//   3. opendir(<system>/modules/)；失败返回 ERR_MODULE_OPEN_DIR
//   4. 遍历目录项：跳过 "."、".."、非目录项
//        路径 = <modules>/<name>/<name>（见 the module directory layout）
//        不存在 / 读失败 / 解析失败 -> 跳过该项
//        parse_metadata 填 Record.desc
//        状态清单命中 -> record.state = 1/2/3（未命中保持 NotRunning=0）
//        读 <system>/modules_new_state/<name>（4 字节）覆盖 state
//        读 <system>/modules_new_desc/<name>（文本）填 record.desc.desc
//        record.modify_time = stat(路径).st_mtime
//   5. closedir；按修改时间升序 std::sort；把 record 依次搬进 out_list
KModErr unsafe_get_all_modules_impl(std::vector<Record>& out_list,
                                    const std::vector<std::string>* excluded = nullptr) {
    storage::WorkerScope worker;
    const KModErr set_root_err = worker.root();
    if (is_failed(set_root_err)) {
        return set_root_err;
    }

    std::set<std::string> running_mod_list;              // 运行中
    std::set<std::string> abnormal_mod_list;             // 运行异常
    std::set<std::string> removed_pending_reboot_mod_list;  // 已删除待重启

    {
        std::vector<std::string> module_name_list;
        RETURN_IF_ERROR(maplesu::module_state::read(maplesu::module_state::List::Running, module_name_list));
        running_mod_list = v_to_s(module_name_list);
    }
    {
        std::vector<std::string> module_name_list;
        RETURN_IF_ERROR(maplesu::module_state::read(maplesu::module_state::List::Abnormal, module_name_list));
        abnormal_mod_list = v_to_s(module_name_list);
    }
    {
        std::vector<std::string> module_name_list;
        RETURN_IF_ERROR(maplesu::module_state::read(maplesu::module_state::List::Removed, module_name_list));
        removed_pending_reboot_mod_list = v_to_s(module_name_list);
    }

    // 3. 打开模块清单目录
    std::string system_directory;
    RETURN_IF_ERROR(storage::current_system_directory(system_directory));
    const std::filesystem::path system_dir_path(system_directory);
    const std::filesystem::path module_list_dir_path = system_dir_path / "modules" / "";
    DIR* const module_list_dir = opendir(module_list_dir_path.c_str());
    if (module_list_dir == nullptr) {
        return KModErr::ERR_MODULE_OPEN_DIR;
    }

    // 目录项的中间结果：修改时间 + 已填好的模块记录。
    // 即它是本函数内的局部类型；sizeof == 8 + sizeof(Record) + 对齐。
    struct Item {
        int64_t       modify_time;  // stat().st_mtime，排序键
        Record record;       // 解析出来的模块记录
    };
    static_assert(sizeof(Item) == 9248, "unexpected layout size");

    std::vector<Item> module_items;
    module_items.reserve(10);

    // 4. 遍历目录
    for (;;) {
        errno = 0;
        struct dirent* dir_entry = readdir(module_list_dir);
        if (!dir_entry) {
            if (errno) { closedir(module_list_dir); return KModErr::ERR_MODULE_READ_FILE; }
            break;
        }
        // 跳过 "." 与 ".."
        if (dir_entry->d_name[0] == '.' &&
            (dir_entry->d_name[1] == '\0' || (dir_entry->d_name[1] == '.' && dir_entry->d_name[2] == '\0'))) {
            continue;
        }
        // 只处理目录项；部分文件系统不返回 d_type。
        if (dir_entry->d_type != DT_DIR) {
            if (dir_entry->d_type != DT_UNKNOWN) continue;
            struct stat entry_stat = {};
            if (fstatat(dirfd(module_list_dir), dir_entry->d_name, &entry_stat, AT_SYMLINK_NOFOLLOW) != 0 ||
                !S_ISDIR(entry_stat.st_mode)) continue;
        }

        // 之后所有 path(...) 构造都用"指针"重载（pathC2IPKcvE），而不是 char[256] 数组重载。
        const char* const module_name = dir_entry->d_name;
        if (excluded && std::find(excluded->begin(), excluded->end(), module_name) != excluded->end())
            continue;

        std::string module_desc_file_path =
            (module_list_dir_path / module_name / module_name).string();
        std::string state_file_path = (system_dir_path / "modules_new_state" / module_name).string();
        std::string desc_text_file_path = (system_dir_path / "modules_new_desc" / module_name).string();
        bool installing_first_image = false;
        const KModErr view = module_install::installation_list_paths(module_name, module_desc_file_path,
            state_file_path, desc_text_file_path, installing_first_image);
        if (is_failed(view)) { closedir(module_list_dir); return view; }
        if (installing_first_image) continue;
        if (!file_utils::file_exists(std::filesystem::path(module_desc_file_path))) {
            continue;
        }

        std::vector<uint8_t> module_elf_bytes;
        if (!file_utils::read_file_bytes(module_desc_file_path.c_str(), module_elf_bytes)) {
            continue;
        }

        Record module_record_item{};
        if (is_failed(parse_metadata(module_elf_bytes, module_record_item.desc))) {
            continue;
        }

        // 4.1 按状态清单给出运行状态（removed > abnormal > running > NotRunning）
        if (removed_pending_reboot_mod_list.contains(module_name)) {
            module_record_item.state = RunState::RemovedPendingReboot;
        } else if (abnormal_mod_list.contains(module_name)) {
            module_record_item.state = RunState::Abnormal;
        } else if (running_mod_list.contains(module_name)) {
            module_record_item.state = RunState::Running;
        }

        // 4.2 <system>/modules_new_state/<name>：4 字节原始状态，覆盖上面的结果
        std::vector<uint8_t> state_bytes;
        if (file_utils::read_file_bytes(state_file_path.c_str(), state_bytes, sizeof(RunState) + 1) &&
            state_bytes.size() == sizeof(RunState)) {
            memcpy(&module_record_item.state, state_bytes.data(), sizeof(RunState));
        }

        // 4.3 <system>/modules_new_desc/<name>：模块描述文本（按 desc 容量截断并保留 NUL）
        std::string desc_text;
        if (file_utils::read_text_file(desc_text_file_path.c_str(), desc_text,
                                       sizeof(module_record_item.desc.desc) - 1) && !desc_text.empty()) {
            memset(module_record_item.desc.desc, 0, sizeof(module_record_item.desc.desc));
            const size_t copy_size = std::min(desc_text.size(), sizeof(module_record_item.desc.desc) - 1);
            strncpy(module_record_item.desc.desc, desc_text.c_str(), copy_size);
            module_record_item.desc.desc[copy_size] = '\0';
        }

        // 4.4 目录项修改时间作为排序键
        int64_t modify_time = 0;
        {
            struct stat file_stat = {};
            if (stat(module_desc_file_path.c_str(), &file_stat) == 0) {
                modify_time = file_stat.st_mtime;
            }
        }

        module_items.push_back(Item{modify_time, module_record_item});
    }

    if (closedir(module_list_dir) != 0) return KModErr::ERR_MODULE_READ_FILE;
    RETURN_IF_ERROR(worker.close_checked());

    // 5. 按修改时间升序排序，再按顺序把 record 搬到 out_list
    std::sort(module_items.begin(), module_items.end(), [](const Item& lhs, const Item& rhs) {
        return lhs.modify_time < rhs.modify_time;
    });

    out_list.reserve(module_items.size());
    for (const Item& module_item : module_items) {
        out_list.push_back(module_item.record);
    }
    return KModErr::OK;
}

// The caller sees a new list only after complete frames and the exact child's
// successful exit. Failed reads close the pipes before waiting for the writer.
KModErr safe_get_all_modules_impl(std::vector<Record>& out_list) {
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    compat::LegacyListPipe pipe_info;
    KModErr result_err = KModErr::OK;

    if (compat::fork_pipe_child_process(pipe_info)) {
        std::vector<Record> child_list;
        const KModErr child_err = unsafe_get_all_modules_impl(child_list);
        bool written = compat::write_errcode_from_child(pipe_info, child_err);
        if (written) {
            const std::vector<uint8_t> serialized_records = records_to_bytes(child_list);
            written = compat::write_transfer_data_from_child(pipe_info, serialized_records);
        }
        pipe_info.close_all();
        _exit(written ? 0 : 1);
    }

    if (pipe_info.child_pid <= 0) return KModErr::ERR_READ_CHILD_ERRCODE;

    std::vector<uint8_t> serialized_records;
    if (!compat::read_errcode_from_child(pipe_info, result_err)) {
        result_err = KModErr::ERR_READ_CHILD_ERRCODE;
    } else {
        try {
            if (!compat::read_transfer_data_from_child(pipe_info, serialized_records) && result_err == KModErr::OK)
                result_err = KModErr::ERR_READ_CHILD_DATA;
        } catch (const std::bad_alloc&) {
            if (result_err == KModErr::OK) result_err = KModErr::ERR_READ_CHILD_DATA;
        } catch (const std::length_error&) {
            if (result_err == KModErr::OK) result_err = KModErr::ERR_READ_CHILD_DATA;
        }
    }
    pipe_info.close_all();

    int child_status = 0;
    pid_t waited;
    do { waited = waitpid(pipe_info.child_pid, &child_status, 0); } while (waited < 0 && errno == EINTR);
    if ((waited != pipe_info.child_pid || !WIFEXITED(child_status) || WEXITSTATUS(child_status) != 0) &&
        result_err == KModErr::OK) result_err = KModErr::ERR_READ_CHILD_ERRCODE;
    if (result_err != KModErr::OK) return result_err;
    if (serialized_records.size() % sizeof(Record) != 0) return KModErr::ERR_READ_CHILD_DATA;

    try {
        std::vector<Record> restored_records = bytes_to_records<Record>(serialized_records);
        out_list = std::move(restored_records);
    } catch (const std::bad_alloc&) {
        return KModErr::ERR_READ_CHILD_DATA;
    } catch (const std::length_error&) {
        return KModErr::ERR_READ_CHILD_DATA;
    }
    return KModErr::OK;
}

}  // namespace

KModErr unsafe_get_all_modules(std::vector<Record>& out_list) {
    return unsafe_get_all_modules_impl(out_list);
}
KModErr unsafe_get_boot_modules(std::vector<Record>& out_list,
                                const std::vector<std::string>& excluded) {
    return unsafe_get_all_modules_impl(out_list, &excluded);
}
KModErr safe_get_all_modules(std::vector<Record>& out_list) {
    return safe_get_all_modules_impl(out_list);
}
}  // namespace maplesu::module

namespace maplesu::sdk {
KModErr unsafe_get_all_modules(std::vector<module_record>& out_list) {
    std::vector<maplesu::module::Record> records;
    const KModErr err = maplesu::module::unsafe_get_all_modules(records);
    if (err != KModErr::OK) return err;
    out_list.reserve(out_list.size() + records.size());
    for (const auto& record : records) {
        module_record converted;
        maplesu::compat::copy_module_record(converted, record);
        out_list.push_back(converted);
    }
    return KModErr::OK;
}
KModErr safe_get_all_modules(std::vector<module_record>& out_list) {
    std::vector<maplesu::module::Record> records;
    const KModErr err = maplesu::module::safe_get_all_modules(records);
    if (err != KModErr::OK) return err;
    try {
        std::vector<module_record> converted(records.size());
        for (std::size_t i = 0; i < records.size(); ++i)
            maplesu::compat::copy_module_record(converted[i], records[i]);
        out_list.swap(converted);
    } catch (const std::bad_alloc&) {
        return KModErr::ERR_READ_CHILD_DATA;
    } catch (const std::length_error&) {
        return KModErr::ERR_READ_CHILD_DATA;
    }
    return KModErr::OK;
}
}  // namespace maplesu::sdk


// 5.2.0 新增 helper allowlist 导出：把 <system>/modules/ 目录路径写进调用方
// 提供的 std::string 槽位。
//     240c: aa0003e8   mov  x8, x0          ; 槽位指针 -> sret 寄存器
//     2410: 17fffbd0   b    get_system_file_dir_path   ; 尾调用（0x1350）
// 即"在调用方给的槽位上就地构造返回值"。与下面这行 C++ 的 -Oz 代码生成逐字节一致
// （已用同版本 clang 验证：lvalue 引用形参 + placement new 就地产出
//   mov x8, x0; b ... ，符号大小 8）：
//   new (static_cast<void*>(&out)) std::string(get_system_file_dir_path());
// 消费方 maplesu_module_control.o 先零初始化 std::string 再 add x0,&slot; bl，
// 与本实现在空串槽位上就地构造的语义一致。
extern "C" __attribute__((visibility("hidden"))) void maplesu_system_directory(std::string& out) {
    new (static_cast<void*>(&out)) std::string(maplesu::module::get_system_file_dir_path());
}

#include "../compat/module_list_exports_legacy_abi.h"
