// MapleSU module archive scanning and isolated metadata reads.
#include "../compat/module_zip_abi.h"
#include "../compat/module_carrier_conversion.h"
#include "operations.h"
#include "maplesu_storage_worker_private.h"
#include "path_contract.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <functional>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

#include "zip.h"

namespace maplesu::module {

KModErr find_module_elf_in_zip(const char* module_zip_path, Descriptor& out_desc,
                               std::string& out_elf_name) {
    bool has_elf = false;
    bool has_webroot = false;
    Descriptor parsed_desc{};
    std::string selected_elf_name;

    std::string zip_path(module_zip_path);
    std::function<bool(const std::string&, std::vector<uint8_t>&&)> on_entry =
        [&has_elf, &has_webroot, &parsed_desc, &selected_elf_name](
            const std::string& name, std::vector<uint8_t>&& content) -> bool {
            if (!name.empty() && name.back() != '/') {
                const std::size_t begin =
                    (name.size() > 1 && name[0] == '.' && name[1] == '/') ? 2 : 0;
                if (!has_elf && name.find('/', begin) == std::string::npos) {
                    std::memset(&parsed_desc, 0, sizeof(parsed_desc));
                    const KModErr parse_err =
                        parse_metadata(content, parsed_desc);
                    printf("%s, a:%s\n", name.c_str(), to_string(parse_err).c_str());
                    if (parse_err == KModErr::OK) {
                        has_elf = true;
                        selected_elf_name = name;
                    }
                }
            }
            if (!name.empty() && name.back() != '/') {
                const std::size_t begin =
                    (name.size() > 1 && name[0] == '.' && name[1] == '/') ? 2 : 0;
                const std::size_t slash = name.find('/', begin);
                if (slash != std::string::npos) {
                    if (name.compare(slash + 1, 8, "webroot/") == 0 &&
                        name.size() > slash + 9) {
                        has_webroot = true;
                    }
                }
            }
            return true;
        };
    int zip_err = 0;
    zip_t* zip = zip_open(zip_path.c_str(), 0, &zip_err);
    if (zip == nullptr) {
        char err_text[128];
        zip_error_to_str(err_text, sizeof(err_text), zip_err, errno);
        std::cerr << "Failed to open archive: " << err_text << std::endl;
        return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
    }

    const zip_int64_t entry_count = zip_get_num_entries(zip, 0);
    if (entry_count < 0) {
        zip_close(zip);
        return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
    }
    for (zip_uint64_t index = 0; index != static_cast<zip_uint64_t>(entry_count);
         ++index) {
        zip_stat_t entry_stat;
        if (zip_stat_index(zip, index, 0, &entry_stat) != 0) {
            std::cerr << "zip_stat_index failed at index " << index << ": "
                      << zip_strerror(zip) << std::endl;
            zip_close(zip);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }

        const char* entry_name = zip_get_name(zip, index, 0);
        if (entry_name == nullptr) {
            std::cerr << "zip_get_name failed at index " << index << ": "
                      << zip_strerror(zip) << std::endl;
            zip_close(zip);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }

        std::string name(entry_name);
        if (!module_paths::relative_components(name)) {
            zip_close(zip);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }
        if (!name.empty() && name.back() == '/') {
            continue;
        }

        zip_file_t* entry_file = zip_fopen_index(zip, index, 0);
        if (entry_file == nullptr) {
            std::cerr << "Failed to open file " << name << ": "
                      << zip_strerror(zip) << std::endl;
            zip_close(zip);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }
        std::vector<uint8_t> content;
        content.reserve((entry_stat.valid & ZIP_STAT_SIZE)
                            ? static_cast<std::size_t>(entry_stat.size)
                            : 0);
        uint8_t read_buffer[8192];
        zip_int64_t read_size = 0;
        while ((read_size = zip_fread(entry_file, read_buffer,
                                      sizeof(read_buffer))) > 0) {
            content.insert(content.end(), read_buffer, read_buffer + read_size);
        }

        if (read_size != 0) {
            std::cerr << "Failed to read file " << name << ": "
                      << zip_file_strerror(entry_file) << std::endl;
            zip_fclose(entry_file);
            zip_close(zip);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }
        if (zip_fclose(entry_file) != 0) {
            std::cerr << "Failed to close file " << name << ": "
                      << zip_strerror(zip) << std::endl;
            zip_close(zip);
            return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
        }
        if (!on_entry(name, std::move(content))) {
            zip_close(zip);
            goto scan_finished;
        }
    }

    if (zip_close(zip) != 0) {
        zip_discard(zip);
        return KModErr::ERR_MODULE_PARSE_ZIP_FILE;
    }

scan_finished:
    if (!has_elf) {
        return KModErr::ERR_MODULE_ELF_FILE_NOT_FOUND;
    }
    if (has_webroot) {
        parsed_desc.web_ui = true;
    }
    out_elf_name.swap(selected_elf_name);
    std::memcpy(&out_desc, &parsed_desc, sizeof(out_desc));
    return KModErr::OK;
}
KModErr safe_parse_module_desc_from_zip_file(const char* module_zip_path,
                                             Descriptor& out_desc) {
    RETURN_IF_ERROR(kernel_module::maplesu_request_worker_admission());
    compat::fork_pipe_info pipe_info;

    if (compat::fork_pipe_child_process(pipe_info)) {
        Descriptor parsed_desc;
        std::memset(&parsed_desc, 0, sizeof(parsed_desc));
        KModErr child_err = [&]() -> KModErr {
            storage::WorkerScope worker;
            RETURN_IF_ERROR(worker.root());
            std::string elf_name;
            const auto result = find_module_elf_in_zip(module_zip_path, parsed_desc, elf_name);
            const auto closed = worker.close_checked();
            return is_failed(result) ? result : closed;
        }();
        const bool error_written = compat::write_errcode_from_child(pipe_info, child_err);
        std::vector<uint8_t> transfer(sizeof(parsed_desc));
        std::memcpy(transfer.data(), &parsed_desc, sizeof(parsed_desc));
        const bool data_written = error_written &&
            compat::write_transfer_data_from_child(pipe_info, transfer);
        pipe_info.close_all();
        _exit(error_written && data_written ? 0 : 1);
    }

    if (pipe_info.child_pid <= 0) {
        const int fork_error = errno;
        pipe_info.close_all();
        errno = fork_error;
        return KModErr::ERR_READ_CHILD_DATA;
    }

    KModErr child_err = KModErr::OK;
    const bool error_read = compat::read_errcode_from_child(pipe_info, child_err);
    Descriptor parsed_desc;
    const compat::FixedTransferRead transfer = compat::read_fixed_transfer_data_from_child(
        pipe_info, &parsed_desc, sizeof(parsed_desc));
    pipe_info.close_all();

    int child_status = 0;
    pid_t waited;
    do {
        waited = waitpid(pipe_info.child_pid, &child_status, 0);
    } while (waited < 0 && errno == EINTR);

    if (error_read && child_err != KModErr::OK) return child_err;
    if (!error_read) return KModErr::ERR_READ_CHILD_ERRCODE;
    if (transfer.status != compat::FixedTransferStatus::Complete ||
        waited != pipe_info.child_pid || !WIFEXITED(child_status) ||
        WEXITSTATUS(child_status) != 0) {
        return KModErr::ERR_READ_CHILD_DATA;
    }
    std::memcpy(&out_desc, &parsed_desc, sizeof(out_desc));
    return KModErr::OK;
}

}  // namespace maplesu::module

namespace maplesu::sdk {
KModErr find_module_elf_in_zip(const char* module_zip_path, module_desc& out_desc,
                               std::string& out_elf_name) {
    maplesu::module::Descriptor parsed;
    const KModErr err = maplesu::module::find_module_elf_in_zip(module_zip_path, parsed, out_elf_name);
    if (err == KModErr::OK) maplesu::compat::copy_module_descriptor(out_desc, parsed);
    return err;
}

KModErr safe_parse_module_desc_from_zip_file(const char* module_zip_path,
                                             module_desc& out_desc) {
    maplesu::module::Descriptor parsed;
    const KModErr err = maplesu::module::safe_parse_module_desc_from_zip_file(module_zip_path, parsed);
    if (err == KModErr::OK) maplesu::compat::copy_module_descriptor(out_desc, parsed);
    return err;
}
}  // namespace maplesu::sdk

#include "../compat/module_zip_exports_legacy_abi.h"
