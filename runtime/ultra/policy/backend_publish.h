#pragma once
#include <cstddef>
#include <cstdint>

namespace maplesu::module_policy {
// Caller verifies the APK bytes against the helper-bound SHA-256 before this
// call. Publish an immutable version name; keep earlier versions for rollback.
int64_t publish_backend(const char* sha256,
                        const unsigned char* bytes, size_t size);
}
