#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

#define MAPLESU_MODULE_ENTRY_NAME maplesu_module_entry
#define MAPLESU_MODULE_ENTRY_SYMBOL "\x5f\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x6d\x61\x69\x6e\x5f\x31\x38\x66\x64\x66\x33\x39\x33\x38\x38\x35\x33\x36\x33\x37\x31\x32\x33\x34\x39\x65\x66\x33\x64\x65\x35\x34\x31\x31\x62\x64\x35" "_keyless_v1"
#define MAPLESU_MODULE_ENTRY_ASM __asm__(MAPLESU_MODULE_ENTRY_SYMBOL)
#define MAPLESU_MODULE_IDENTITY_NAME maplesu_module_identity
#define MAPLESU_MODULE_IDENTITY_SYMBOL "\x69\x6e\x6a\x65\x63\x74\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x75\x75\x69\x64\x33\x32\x5f\x65\x34\x63\x31\x34\x33\x39\x35\x35\x66\x32\x65\x62\x37\x65\x65\x62\x65\x31\x64\x37\x31\x31\x33\x30\x66\x34\x32\x38\x66\x37\x61"
#define MAPLESU_MODULE_IDENTITY_ASM __asm__(MAPLESU_MODULE_IDENTITY_SYMBOL)
#define MAPLESU_MODULE_INSTALL_NAME maplesu_module_install_entry
#define MAPLESU_MODULE_INSTALL_SYMBOL "\x5f\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x6f\x6e\x5f\x69\x6e\x73\x74\x61\x6c\x6c\x5f\x6b\x36\x4a\x6b\x69\x62\x72\x6a\x55\x77\x7a\x58\x46\x70\x73\x68\x76\x46\x75\x49\x77\x64\x55\x4e\x52\x50\x34\x66\x59\x74\x4c\x41" "_keyless_v1"
#define MAPLESU_MODULE_INSTALL_ASM __asm__(MAPLESU_MODULE_INSTALL_SYMBOL)
#define MAPLESU_MODULE_UNINSTALL_NAME maplesu_module_uninstall_entry
#define MAPLESU_MODULE_UNINSTALL_SYMBOL "\x5f\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x6f\x6e\x5f\x75\x6e\x69\x6e\x73\x74\x61\x6c\x6c\x5f\x65\x70\x6d\x6f\x73\x6f\x38\x35\x68\x77\x34\x77\x31\x72\x7a\x6a\x61\x67\x69\x75\x7a\x64\x6d\x6e\x79\x71\x61\x6d\x6b\x37\x65\x70" "_keyless_v1"
#define MAPLESU_MODULE_UNINSTALL_ASM __asm__(MAPLESU_MODULE_UNINSTALL_SYMBOL)
#define MAPLESU_MODULE_WEBUI_NAME maplesu_module_webui_entry
#define MAPLESU_MODULE_WEBUI_SYMBOL "\x5f\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x77\x65\x62\x5f\x75\x69\x5f\x6d\x61\x69\x6e\x5f\x63\x65\x62\x36\x66\x30\x62\x62\x65\x62\x65\x35\x63\x38\x39\x37\x38\x65\x62\x32\x31\x36\x38\x37\x39\x39\x61\x61\x30\x63\x61\x34" "_keyless_v1"
#define MAPLESU_MODULE_WEBUI_ASM __asm__(MAPLESU_MODULE_WEBUI_SYMBOL)
#define MAPLESU_MODULE_LAST_SERVER_PORT_NAME maplesu_module_last_server_port_entry
#define MAPLESU_MODULE_LAST_SERVER_PORT_SYMBOL "\x5f\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x6c\x61\x73\x74\x5f\x77\x65\x62\x5f\x75\x69\x5f\x73\x65\x72\x76\x65\x72\x5f\x70\x6f\x72\x74\x5f\x64\x62\x32\x61\x32\x62\x65\x62\x65\x32\x62\x31\x39\x30\x34\x38\x32\x61\x64\x30\x61\x63\x37\x33\x39\x39\x36\x63\x37\x62\x38\x36"
#define MAPLESU_MODULE_LAST_SERVER_PORT_ASM __asm__(MAPLESU_MODULE_LAST_SERVER_PORT_SYMBOL)
#define MAPLESU_MODULE_BEFORE_SERVER_EXIT_NAME maplesu_module_before_server_exit_entry
#define MAPLESU_MODULE_BEFORE_SERVER_EXIT_SYMBOL "\x5f\x5f\x73\x6b\x72\x6f\x6f\x74\x5f\x6d\x6f\x64\x75\x6c\x65\x5f\x62\x65\x66\x6f\x72\x65\x5f\x73\x65\x72\x76\x65\x72\x5f\x65\x78\x69\x74\x5f\x61\x63\x74\x69\x6f\x6e\x5f\x38\x31\x61\x39\x38\x35\x33\x35\x66\x31\x32\x63\x39\x36\x61\x30\x62\x33\x65\x34\x35\x37\x37\x32\x37\x37\x30\x35\x65\x34\x34\x63"
#define MAPLESU_MODULE_BEFORE_SERVER_EXIT_ASM __asm__(MAPLESU_MODULE_BEFORE_SERVER_EXIT_SYMBOL)

#define MAPLESU_MODULE_NAME_TAG_BEGIN "de1f663bcae792d7c9ed1a21cdaeff00331fe11b074f0303e018f43998f8db3e"
#define MAPLESU_MODULE_NAME_TAG_END "04429c3f7172e4cce7ac7a5d44b75631ce91777b5f2a5eec076a3a508a76fb17"
#define MAPLESU_MODULE_VERSION_TAG_BEGIN "7dc74a2b86805f2cd0f45c6a82d384fd568e341c6f05af17c7229c9ba6d4318b"
#define MAPLESU_MODULE_VERSION_TAG_END "8dbc019a6e51fe8d4a3db9feaf05535d70879f35626050cd4131bfe323ecefc2"
#define MAPLESU_MODULE_DESCRIPTION_TAG_BEGIN "05c62cd446c811e772c94d97fd546738328f7c6b9967ed135190ef37ff06d866"
#define MAPLESU_MODULE_DESCRIPTION_TAG_END "ed02c95e091c66100d6f2f4554f96392b2951b394ba042278eed8f236f43e1a3"
#define MAPLESU_MODULE_AUTHOR_TAG_BEGIN "1f39b220291a2fb6e4fec95f179d0faf833ca534ef8ab2ffc4bd893768694451"
#define MAPLESU_MODULE_AUTHOR_TAG_END "d409c886fd6cedd694c1b126fd19eccd90e2463e04154c193effc7103ce63f1c"
#define MAPLESU_MODULE_ID_TAG_BEGIN "b33dcddd88c07d079a8e0e9ec6ab976f15f2edba46cab91a61234cfbb982111c"
#define MAPLESU_MODULE_ID_TAG_END "3020d9a6c07a30e2649dd29a72d7e57121f0c0ca457b6d21dca5a69acbe6c2c4"
#define MAPLESU_MODULE_UPDATE_JSON_TAG_BEGIN "4363a6368f39d93cabadc121f9bfa51626da9f19699b68470d7a4fd46a9ad541"
#define MAPLESU_MODULE_UPDATE_JSON_TAG_END "9f2efdc4b7e133634ec4c1ecd85975f560be89962733a0a7b228f1160d8d0ebc"
#define MAPLESU_MODULE_WEB_UI_TAG_BEGIN "8af75246e3afc7bd603439f7d241de26"
#define MAPLESU_MODULE_WEB_UI_TAG_END "f8d65c7fde28dbbbad9926ce0bdb16bc"
#define MAPLESU_MODULE_INSTALL_TAG_BEGIN "za5hrnp5r0rjm1dnjld7xoc6tza0qvdd"
#define MAPLESU_MODULE_INSTALL_TAG_END "v2ltlw5plpqec7yr5ifeuta24l3rv7ep"
#define MAPLESU_MODULE_UNINSTALL_TAG_BEGIN "hft0lrndcqfoff3vankyj5drv30apccd"
#define MAPLESU_MODULE_UNINSTALL_TAG_END "p6tv4rinog5a0rl83tzp2ac6fl0uyqhk"
#define MAPLESU_MODULE_MINIMUM_SDK_TAG_BEGIN "d2b0e8ccfab9a93f98e1e1bd2476f1ff58dd5ccb88b2228e93587e68dddd526c"
#define MAPLESU_MODULE_MINIMUM_SDK_TAG_END "162c41660d1b17650087d7bd2d946956407165b6008b970e8fe386f2de805f4b"

#define MAPLESU_MODULE_DECLARE_EXPORTS(ErrorType, ExitActionType) \
    extern "C" { \
    int MAPLESU_MODULE_ENTRY_NAME(const char*) MAPLESU_MODULE_ENTRY_ASM; \
    void MAPLESU_MODULE_IDENTITY_NAME() MAPLESU_MODULE_IDENTITY_ASM; \
    void MAPLESU_MODULE_INSTALL_NAME(const char*, void (*)(const char*)) MAPLESU_MODULE_INSTALL_ASM; \
    void MAPLESU_MODULE_UNINSTALL_NAME(const char*) MAPLESU_MODULE_UNINSTALL_ASM; \
    void MAPLESU_MODULE_WEBUI_NAME(const char*, const char*, const char*, int, ErrorType&) MAPLESU_MODULE_WEBUI_ASM; \
    void MAPLESU_MODULE_LAST_SERVER_PORT_NAME(int&, ErrorType&) MAPLESU_MODULE_LAST_SERVER_PORT_ASM; \
    void MAPLESU_MODULE_BEFORE_SERVER_EXIT_NAME(ExitActionType&, ErrorType&) MAPLESU_MODULE_BEFORE_SERVER_EXIT_ASM; \
    }

namespace maplesu::compat::native_module {

inline constexpr char kLegacyModuleEntrySymbol[] = "__skroot_module_main_18fdf393885363712349ef3de5411bd5";
inline constexpr char kLegacyModuleInstallSymbol[] = "__skroot_module_on_install_k6JkibrjUwzXFpshvFuIwdUNRP4fYtLA";
inline constexpr char kLegacyModuleUninstallSymbol[] = "__skroot_module_on_uninstall_epmoso85hw4w1rzjagiuzdmnyqamk7ep";
inline constexpr char kLegacyWebUiMainSymbol[] = "__skroot_module_web_ui_main_ceb6f0bbebe5c8978eb2168799aa0ca4";

enum class Abi { KeylessV1, LegacyRootKey };
struct Export {
    void* address = nullptr;
    Abi abi = Abi::KeylessV1;
    explicit operator bool() const { return address != nullptr; }
};

template <typename Lookup>
Export resolve_export(Lookup lookup, const char* keyless, const char* legacy) {
    if (void* address = lookup(keyless)) return {address, Abi::KeylessV1};
    return {lookup(legacy), Abi::LegacyRootKey};
}

inline bool valid_legacy_credential(const char* credential) {
    return credential && strnlen(credential, 49) == 48;
}

inline constexpr char kModuleEntrySymbol[] = MAPLESU_MODULE_ENTRY_SYMBOL;
inline constexpr char kModuleIdentitySymbol[] = MAPLESU_MODULE_IDENTITY_SYMBOL;
inline constexpr char kModuleInstallSymbol[] = MAPLESU_MODULE_INSTALL_SYMBOL;
inline constexpr char kModuleUninstallSymbol[] = MAPLESU_MODULE_UNINSTALL_SYMBOL;
inline constexpr char kWebUiMainSymbol[] = MAPLESU_MODULE_WEBUI_SYMBOL;
inline constexpr char kLastServerPortSymbol[] = MAPLESU_MODULE_LAST_SERVER_PORT_SYMBOL;
inline constexpr char kBeforeServerExitActionSymbol[] = MAPLESU_MODULE_BEFORE_SERVER_EXIT_SYMBOL;

inline constexpr char kNameBegin[] = MAPLESU_MODULE_NAME_TAG_BEGIN;
inline constexpr char kNameEnd[] = MAPLESU_MODULE_NAME_TAG_END;
inline constexpr char kVersionBegin[] = MAPLESU_MODULE_VERSION_TAG_BEGIN;
inline constexpr char kVersionEnd[] = MAPLESU_MODULE_VERSION_TAG_END;
inline constexpr char kDescriptionBegin[] = MAPLESU_MODULE_DESCRIPTION_TAG_BEGIN;
inline constexpr char kDescriptionEnd[] = MAPLESU_MODULE_DESCRIPTION_TAG_END;
inline constexpr char kAuthorBegin[] = MAPLESU_MODULE_AUTHOR_TAG_BEGIN;
inline constexpr char kAuthorEnd[] = MAPLESU_MODULE_AUTHOR_TAG_END;
inline constexpr char kIdBegin[] = MAPLESU_MODULE_ID_TAG_BEGIN;
inline constexpr char kIdEnd[] = MAPLESU_MODULE_ID_TAG_END;
inline constexpr char kUpdateJsonBegin[] = MAPLESU_MODULE_UPDATE_JSON_TAG_BEGIN;
inline constexpr char kUpdateJsonEnd[] = MAPLESU_MODULE_UPDATE_JSON_TAG_END;
inline constexpr char kWebUiBegin[] = MAPLESU_MODULE_WEB_UI_TAG_BEGIN;
inline constexpr char kWebUiEnd[] = MAPLESU_MODULE_WEB_UI_TAG_END;
inline constexpr char kInstallBegin[] = MAPLESU_MODULE_INSTALL_TAG_BEGIN;
inline constexpr char kInstallEnd[] = MAPLESU_MODULE_INSTALL_TAG_END;
inline constexpr char kUninstallBegin[] = MAPLESU_MODULE_UNINSTALL_TAG_BEGIN;
inline constexpr char kUninstallEnd[] = MAPLESU_MODULE_UNINSTALL_TAG_END;
inline constexpr char kMinimumSdkBegin[] = MAPLESU_MODULE_MINIMUM_SDK_TAG_BEGIN;
inline constexpr char kMinimumSdkEnd[] = MAPLESU_MODULE_MINIMUM_SDK_TAG_END;

inline constexpr std::size_t kModuleIdCharacters = 32;
inline constexpr std::size_t kTextFieldBytes = 1024;
inline constexpr std::size_t kDescriptionFieldBytes = 4096;
inline constexpr std::size_t kSdkVersionBytes = 12;
inline constexpr std::size_t kSdkVersionAlignment = 4;
inline constexpr std::size_t kNaturalDescriptorBytes = 9232;
inline constexpr std::size_t kNaturalDescriptorAlignment = 4;
inline constexpr std::size_t kNaturalMinimumSdkOffset = 9220;
inline constexpr std::size_t kNaturalRecordBytes = 9236;
inline constexpr std::size_t kNaturalRecordAlignment = 4;
inline constexpr std::size_t kNaturalRecordStateOffset = 9232;
inline constexpr std::size_t kPackedCarrierAlignment = 1;
inline constexpr std::size_t kRunStateBytes = 4;
inline constexpr std::uint32_t kStateNotRunning = 0;
inline constexpr std::uint32_t kStateRunning = 1;
inline constexpr std::uint32_t kStateAbnormal = 2;
inline constexpr std::uint32_t kStateRemovedPendingReboot = 3;

}
