#pragma once
#include <optional>
#include <vector>
#include <string>
#include <unistd.h>
#include "../../module_err_def.h"
#include "../../maplesu_sdk/maplesu_module.h"
#include "../../maplesu_sdk/maplesu_su_auth.h"
#include "../../maplesu_sdk/maplesu_test.h"
namespace maplesu::sdk {
KModErr read_maplesu_log_cb(void (*cb)(const char* text));
KModErr get_all_modules_with_cb(void (*cb)(const module_record* record));
KModErr install_module_with_cb(const char* module_zip_path, void (*cb)(const char* reason));
KModErr install_module_dev_run_once_with_cb(const char* module_zip_path, void (*cb)(const char* reason));
KModErr get_su_auth_list_with_cb(void (*cb)(const su_auth_item* item));
KModErr test_maplesu_basics_with_cb(BasicItem item, void (*cb)(const char* out_str));
KModErr test_maplesu_deafult_module_with_cb(DeafultModuleName name, void (*cb)(const char* out_str));

inline KModErr read_maplesu_log(std::string& out) {
    thread_local std::string* tls_out = nullptr;
    auto cb = [](const char* text) {
        if (!tls_out) return;
        (*tls_out) = text;
    };
    tls_out = &out;
    KModErr err = read_maplesu_log_cb(cb);
    tls_out = nullptr; 
    return err;
}

inline KModErr get_all_modules_list(std::vector<module_record>& out_list) {
    thread_local std::vector<module_record>* tls_out = nullptr;
    auto cb = [](const module_record* record) {
        if (!tls_out || !record) return;
        module_record r;
        std::memcpy(&r, record, sizeof(r));
        tls_out->push_back(std::move(r));
    };
    tls_out = &out_list;
    KModErr err = get_all_modules_with_cb(cb);
    tls_out = nullptr; 
    return err;
}

inline KModErr install_module(const char* module_zip_path, std::string& out_reason) {
    thread_local std::string* tls_out = nullptr;
    auto cb = [](const char* text) {
        if (!tls_out) return;
        (*tls_out) = text;
    };
    tls_out = &out_reason;
    KModErr err = install_module_with_cb(module_zip_path, cb);
    tls_out = nullptr;
    return err;
}

inline KModErr install_module_dev_run_once(const char* module_zip_path, std::string& out_reason) {
    thread_local std::string* tls_out = nullptr;
    auto cb = [](const char* text) {
        if (!tls_out) return;
        (*tls_out) = text;
    };
    tls_out = &out_reason;
    KModErr err = install_module_dev_run_once_with_cb(module_zip_path, cb);
    tls_out = nullptr;
    return err;
}

inline KModErr get_su_auth_list(std::vector<su_auth_item>& out_pkgs) {
    thread_local std::vector<su_auth_item>* tls_out = nullptr;
    auto cb = [](const su_auth_item* item) {
        if (!tls_out || !item) return;
        su_auth_item s;
        std::memcpy(&s, item, sizeof(s));
        tls_out->push_back(std::move(s));
    };
    tls_out = &out_pkgs;
    KModErr err = get_su_auth_list_with_cb(cb);
    tls_out = nullptr; 
    return err;
}

inline KModErr test_maplesu_basics(BasicItem item, std::string& out) {
    thread_local std::string* tls_out = nullptr;
    auto cb = [](const char* text) {
        if (!tls_out) return;
        (*tls_out) = text;
    };
    tls_out = &out;
    KModErr err = test_maplesu_basics_with_cb(item, cb);
    tls_out = nullptr; 
    return err;
}

inline KModErr test_maplesu_deafult_module(DeafultModuleName name, std::string& out) {
    thread_local std::string* tls_out = nullptr;
    auto cb = [](const char* text) {
        if (!tls_out) return;
        (*tls_out) = text;
    };
    tls_out = &out;
    KModErr err = test_maplesu_deafult_module_with_cb(name, cb);
    tls_out = nullptr; 
    return err;
}

}