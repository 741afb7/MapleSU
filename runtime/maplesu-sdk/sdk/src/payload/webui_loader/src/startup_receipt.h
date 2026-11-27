#pragma once

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <unistd.h>
#include "maplesu/compat/native_module_abi.h"

namespace maplesu::payload::webui {

// Private SDK/payload startup channel. This is not a module or kernel ABI.
inline constexpr const char* kReadyArgument = "--maplesu-webui-ready-v1";
inline constexpr const char* kOwnerDirectoryArgument = "--maplesu-webui-directory-v1";
inline constexpr const char* kLegacyDirectoryArgument = "--maplesu-webui-directory-legacy-v1";

// Present only when the caller explicitly configured the historical module ABI.
// This record follows the unchanged directory record on the same anonymous FD.
struct LegacyCredential {
    uint32_t magic = 0x4d574b31;
    uint16_t version = 1;
    uint16_t size = 64;
    char value[49]{};
    uint8_t reserved[7]{};
};
static_assert(sizeof(LegacyCredential) == 64);

inline void clear_legacy_credential(LegacyCredential& credential) {
    volatile char* value = credential.value;
    for (size_t i = 0; i < sizeof(credential.value); ++i) value[i] = 0;
}

inline bool valid_credential(const LegacyCredential& credential) {
    if (credential.magic != 0x4d574b31 || credential.version != 1 ||
        credential.size != sizeof(credential) ||
        !maplesu::compat::native_module::valid_legacy_credential(credential.value)) return false;
    for (uint8_t byte : credential.reserved) if (byte != 0) return false;
    return true;
}

inline bool write_legacy_credential(int fd, const LegacyCredential& credential) {
    if (!valid_credential(credential)) return false;
    const char* data = reinterpret_cast<const char*>(&credential);
    size_t remaining = sizeof(credential);
    while (remaining != 0) {
        const ssize_t count = ::write(fd, data, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        data += count;
        remaining -= static_cast<size_t>(count);
    }
    return true;
}

inline bool read_legacy_credential(int fd, LegacyCredential& credential) {
    char* data = reinterpret_cast<char*>(&credential);
    size_t remaining = sizeof(credential);
    while (remaining != 0) {
        const ssize_t count = ::read(fd, data, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { clear_legacy_credential(credential); return false; }
        data += count;
        remaining -= static_cast<size_t>(count);
    }
    if (valid_credential(credential)) return true;
    clear_legacy_credential(credential);
    return false;
}

// A path fact from the authenticated worker, never a session or Root credential.
struct DeploymentDirectory {
    uint32_t magic = 0x4d574431;
    uint16_t version = 1;
    uint16_t size = 32;
    char name[16]{};
    uint64_t reserved = 0;
};
static_assert(sizeof(DeploymentDirectory) == 32);

inline bool valid_directory(const DeploymentDirectory& directory) {
    if (directory.magic != 0x4d574431 || directory.version != 1 ||
        directory.size != sizeof(directory) || directory.reserved != 0) return false;
    for (unsigned char byte : directory.name) {
        if (!((byte >= 'a' && byte <= 'z') || (byte >= 'A' && byte <= 'Z') ||
              (byte >= '0' && byte <= '9'))) return false;
    }
    return true;
}

inline bool write_deployment_directory(int fd, const DeploymentDirectory& directory) {
    const char* data = reinterpret_cast<const char*>(&directory);
    size_t remaining = sizeof(directory);
    while (remaining != 0) {
        const ssize_t written = ::write(fd, data, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        data += written;
        remaining -= static_cast<size_t>(written);
    }
    return true;
}

inline bool read_deployment_directory(int fd, DeploymentDirectory& directory) {
    char* data = reinterpret_cast<char*>(&directory);
    size_t remaining = sizeof(directory);
    while (remaining != 0) {
        const ssize_t count = ::read(fd, data, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        data += count;
        remaining -= static_cast<size_t>(count);
    }
    return valid_directory(directory);
}
struct StartupReceipt {
    uint32_t magic = 0x4d575531;
    uint32_t version = 1;
    int64_t error = 0;
    int32_t port = 0;
    int32_t system_error = 0;
};
static_assert(sizeof(StartupReceipt) == 24);

inline bool valid_receipt(const StartupReceipt& receipt) {
    return receipt.magic == 0x4d575531 && receipt.version == 1 &&
           (receipt.error != 0 || (receipt.port > 0 && receipt.port <= 65535));
}

inline bool write_startup_receipt(int fd, int64_t error, int port, int system_error = 0) {
    if (fd < 0) return true;  // legacy four-argument caller
    StartupReceipt receipt;
    receipt.error = error;
    receipt.port = port;
    receipt.system_error = system_error;
    const char* data = reinterpret_cast<const char*>(&receipt);
    size_t remaining = sizeof(receipt);
    while (remaining != 0) {
        const ssize_t written = ::write(fd, data, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        data += written;
        remaining -= static_cast<size_t>(written);
    }
    return true;
}

}  // namespace maplesu::payload::webui
