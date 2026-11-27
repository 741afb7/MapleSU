#pragma once
#include "maplesu/compat/native_module_abi.h"
#include <cstdint>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

#include "private_mod_api_runtime_helper.h"
#include "private_maplesu_api_runtime_helper.h"
#include "../../module_web_ui_http_handler.h"
#include "../../module_base_install_callback.h"

MAPLESU_MODULE_DECLARE_EXPORTS(KModErr, kernel_module::WebUIHttpHandler::ServerExitAction);

inline char maplesu_module_name_5d1745f8a8fb4814fb0dbb5aab27bbca[1024] = {0};
#define ___MOD_NAME(val) \
    extern "C" void Error_Missing_MapleSU_Module_Name__Please_define_MAPLESU_MODULE_NAME() { \
        strncpy(maplesu_module_name_5d1745f8a8fb4814fb0dbb5aab27bbca, MAPLESU_MODULE_NAME_TAG_BEGIN val MAPLESU_MODULE_NAME_TAG_END, sizeof(maplesu_module_name_5d1745f8a8fb4814fb0dbb5aab27bbca) - 1); \
    }; \
    extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) int MAPLESU_MODULE_ENTRY_NAME(const char* module_private_dir) { \
        extern void Error_Missing_MapleSU_Module_Name__Please_define_MAPLESU_MODULE_NAME(); \
        extern void Error_Missing_MapleSU_Module_Version__Please_define_MAPLESU_MODULE_VERSION(); \
        extern void Error_Missing_MapleSU_Module_Descriptor__Please_define_MAPLESU_MODULE_DESC(); \
        extern void Error_Missing_MapleSU_Module_Author__Please_define_MAPLESU_MODULE_AUTHOR(); \
        extern void Error_Missing_MapleSU_Module_ID32__Please_define_MAPLESU_MODULE_ID32(); \
        Error_Missing_MapleSU_Module_Name__Please_define_MAPLESU_MODULE_NAME(); \
        Error_Missing_MapleSU_Module_Version__Please_define_MAPLESU_MODULE_VERSION();  \
        Error_Missing_MapleSU_Module_Descriptor__Please_define_MAPLESU_MODULE_DESC();  \
        Error_Missing_MapleSU_Module_Author__Please_define_MAPLESU_MODULE_AUTHOR();  \
        Error_Missing_MapleSU_Module_ID32__Please_define_MAPLESU_MODULE_ID32();  \
        if(strlen(maplesu_module_name_5d1745f8a8fb4814fb0dbb5aab27bbca) == 0) printf("");  \
        if(strlen(maplesu_module_ver_61432c728da7c34d7fd698461a36a421) == 0) printf("");  \
        if(strlen(maplesu_module_desc_c658b5f0a346f94d52b96379fc26b0c4) == 0) printf(""); \
        if(strlen(maplesu_module_author_857d43a4f1978f830f6a2dc91d840338) == 0) printf(""); \
        if(strlen(maplesu_module_id32_927fa0e9f491cdcbd07e8a647f09e1e2) == 0) printf(""); \
        if(strlen(maplesu_module_update_json_7310fa0a06d95799b3f9beaf60e26e85) == 0) printf(""); \
        extern void __compile_save_min_support_sdk_ver(void); \
        __compile_save_min_support_sdk_ver(); \
        return maplesu_module_main(module_private_dir); \
    }

inline char maplesu_module_ver_61432c728da7c34d7fd698461a36a421[1024] = {0};
#define ___MOD_VERSION(val) \
    extern "C" void Error_Missing_MapleSU_Module_Version__Please_define_MAPLESU_MODULE_VERSION() { \
        strncpy(maplesu_module_ver_61432c728da7c34d7fd698461a36a421, MAPLESU_MODULE_VERSION_TAG_BEGIN val MAPLESU_MODULE_VERSION_TAG_END, sizeof(maplesu_module_ver_61432c728da7c34d7fd698461a36a421) - 1); \
    };
    
inline char maplesu_module_desc_c658b5f0a346f94d52b96379fc26b0c4[1024] = {0};
#define ___MOD_DESC(val) \
    extern "C" void Error_Missing_MapleSU_Module_Descriptor__Please_define_MAPLESU_MODULE_DESC() { \
        strncpy(maplesu_module_desc_c658b5f0a346f94d52b96379fc26b0c4, MAPLESU_MODULE_DESCRIPTION_TAG_BEGIN val MAPLESU_MODULE_DESCRIPTION_TAG_END, sizeof(maplesu_module_desc_c658b5f0a346f94d52b96379fc26b0c4) - 1); \
    };
    
inline char maplesu_module_author_857d43a4f1978f830f6a2dc91d840338[1024] = {0};
#define ___MOD_AUTHOR(val) \
    extern "C" void Error_Missing_MapleSU_Module_Author__Please_define_MAPLESU_MODULE_AUTHOR() { \
        strncpy(maplesu_module_author_857d43a4f1978f830f6a2dc91d840338, MAPLESU_MODULE_AUTHOR_TAG_BEGIN val MAPLESU_MODULE_AUTHOR_TAG_END, sizeof(maplesu_module_author_857d43a4f1978f830f6a2dc91d840338) - 1); \
    };
    
inline char maplesu_module_id32_927fa0e9f491cdcbd07e8a647f09e1e2[1024] = {0};
#define ___MOD_ID32(val) \
    template <std::size_t N> \
    consteval void id32_check_bd8a9bbb16b9b755b96a9f2bc0114e13(const char (&s)[N]) { \
        static_assert(N == 33, "MAPLESU_MODULE_ID32: 长度必须为32个字符"); \
    } \
    extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) void MAPLESU_MODULE_IDENTITY_NAME() { \
        id32_check_bd8a9bbb16b9b755b96a9f2bc0114e13(val); \
        strncpy(maplesu_module_id32_927fa0e9f491cdcbd07e8a647f09e1e2, MAPLESU_MODULE_ID_TAG_BEGIN val MAPLESU_MODULE_ID_TAG_END, sizeof(maplesu_module_id32_927fa0e9f491cdcbd07e8a647f09e1e2) - 1); \
        extern void __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(const char*); \
        __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(val); \
    }; \
    extern "C" void Error_Missing_MapleSU_Module_ID32__Please_define_MAPLESU_MODULE_ID32() { \
        MAPLESU_MODULE_IDENTITY_NAME(); \
    };
    
inline char maplesu_module_update_json_7310fa0a06d95799b3f9beaf60e26e85[1024] = {0};
#define ___MOD_UPDATE_JSON(val) \
    inline int ____maplesu_update_json_reg_token_4217f56a580ff098290dfd8c50aa11b9 = []() { \
        strncpy(maplesu_module_update_json_7310fa0a06d95799b3f9beaf60e26e85, MAPLESU_MODULE_UPDATE_JSON_TAG_BEGIN val MAPLESU_MODULE_UPDATE_JSON_TAG_END, sizeof(maplesu_module_update_json_7310fa0a06d95799b3f9beaf60e26e85) - 1); \
        return 0; \
    }();

inline kernel_module::WebUIHttpHandler* maplesu_web_ui_handler_6f9d89cc84c2ea1bf7364fc1afda99b5 = nullptr;
inline char maplesu_web_ui_enable_flag_e0c73f6473a0e653be67875a3cf23a53[1024] = {0};
#define ___MOD_WEB_UI(HttpHandlerClass) \
    inline HttpHandlerClass __maplesu_web_ui_handler_a36c64badf0f0cb63a14b0229738d955;   \
    inline int ____maplesu_web_ui_reg_token_e451c82c405b3e5f5c7f1e635742e544 = []() { \
        maplesu_web_ui_handler_6f9d89cc84c2ea1bf7364fc1afda99b5 = &__maplesu_web_ui_handler_a36c64badf0f0cb63a14b0229738d955; \
        strncpy(maplesu_web_ui_enable_flag_e0c73f6473a0e653be67875a3cf23a53, MAPLESU_MODULE_WEB_UI_TAG_BEGIN MAPLESU_MODULE_WEB_UI_TAG_END, sizeof(maplesu_web_ui_enable_flag_e0c73f6473a0e653be67875a3cf23a53) - 1); \
        return 0; \
    }();

inline decltype(&module_on_install) maplesu_module_on_install_func_jngvokk4mlkkwbg92nj1u7vkhyjan2qj = nullptr;
inline char maplesu_module_on_install_enable_flag_wnx27u34cpuazxit73f52qkylnq89f3s[1024] = {0};
#define ___MOD_ON_INSTALL(callback) \
    inline int ____maplesu_module_on_install_reg_token_bdc5immbyggdvjc8qh0vk817i8hfd74f = []() { \
        maplesu_module_on_install_func_jngvokk4mlkkwbg92nj1u7vkhyjan2qj = &callback; \
        strncpy(maplesu_module_on_install_enable_flag_wnx27u34cpuazxit73f52qkylnq89f3s, MAPLESU_MODULE_INSTALL_TAG_BEGIN MAPLESU_MODULE_INSTALL_TAG_END, sizeof(maplesu_module_on_install_enable_flag_wnx27u34cpuazxit73f52qkylnq89f3s) - 1); \
        return 0; \
    }();\
    extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) void MAPLESU_MODULE_INSTALL_NAME(const char* module_private_dir, void (*cb)(const char* reason)) { \
        if(!maplesu_module_on_install_func_jngvokk4mlkkwbg92nj1u7vkhyjan2qj) return;\
        cb(maplesu_module_on_install_func_jngvokk4mlkkwbg92nj1u7vkhyjan2qj(module_private_dir).c_str()); \
    };

inline decltype(&module_on_uninstall) maplesu_module_on_uninstall_func_lv5biogiKlWBDvrs1clgywqMPwmqXkry = nullptr;
inline char maplesu_module_on_uninstall_enable_flag_ngln4z5zaku6lil4vrz2jtpoecb64avp[1024] = {0};
#define ___MOD_ON_UNINSTALL(callback) \
    inline int ____maplesu_module_on_uninstall_reg_token_hu8jz8grph4qiicr6lrexkkgamy6dvaw = []() { \
        maplesu_module_on_uninstall_func_lv5biogiKlWBDvrs1clgywqMPwmqXkry = &callback; \
        strncpy(maplesu_module_on_uninstall_enable_flag_ngln4z5zaku6lil4vrz2jtpoecb64avp, MAPLESU_MODULE_UNINSTALL_TAG_BEGIN MAPLESU_MODULE_UNINSTALL_TAG_END, sizeof(maplesu_module_on_uninstall_enable_flag_ngln4z5zaku6lil4vrz2jtpoecb64avp) - 1); \
        return 0; \
    }();\
    extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) void MAPLESU_MODULE_UNINSTALL_NAME(const char* module_private_dir) { \
        if(!maplesu_module_on_uninstall_func_lv5biogiKlWBDvrs1clgywqMPwmqXkry) return;\
        maplesu_module_on_uninstall_func_lv5biogiKlWBDvrs1clgywqMPwmqXkry(module_private_dir); \
    };



extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) void MAPLESU_MODULE_WEBUI_NAME(const char* module_private_dir, const char* module_webroot_dir, const char* mod_id32, int port, KModErr& err) {
    if(strlen(maplesu_web_ui_enable_flag_e0c73f6473a0e653be67875a3cf23a53) == 0) printf("");
    KModErr unsafe_module_start_web_ui_server(const char* module_private_dir, const char* module_webroot_dir, const char* mod_id32, kernel_module::WebUIHttpHandler* handler, int port);
    err = unsafe_module_start_web_ui_server(module_private_dir, module_webroot_dir, mod_id32, maplesu_web_ui_handler_6f9d89cc84c2ea1bf7364fc1afda99b5, port);
}

extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) void MAPLESU_MODULE_LAST_SERVER_PORT_NAME(int& out_port, KModErr& err) {
    if(strlen(maplesu_web_ui_enable_flag_e0c73f6473a0e653be67875a3cf23a53) == 0) printf("");
    KModErr unsafe_module_get_last_web_ui_server_port(int& out_port);
    err = unsafe_module_get_last_web_ui_server_port(out_port);
}

extern "C" __attribute__((visibility("default"))) __attribute__((used, weak)) void MAPLESU_MODULE_BEFORE_SERVER_EXIT_NAME(kernel_module::WebUIHttpHandler::ServerExitAction& out_action, KModErr& err) {
    if(strlen(maplesu_web_ui_enable_flag_e0c73f6473a0e653be67875a3cf23a53) == 0) printf("");
    KModErr unsafe_module_get_before_server_exit_action(kernel_module::WebUIHttpHandler::ServerExitAction& out_action);
    err = unsafe_module_get_before_server_exit_action(out_action);
}

#ifdef KERNEL_MODULE_KIT_TEST
    extern "C" int fake_maplesu_module_main() {
        extern void __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c(const char*);
        __set_current_module_id32_1b0494ae8a788541db46b82cc1f0577c("u5BcRRTUwMqU6A6iLkG90jTEq8EXShrE");
        return 0;
    }
#endif