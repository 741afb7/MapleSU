// Bounded text and path helpers.
//
// Everything here is allocation-free and truncation-safe: a helper that runs
// before the Android framework cannot rely on the C++ runtime and must never
// emit a partially written path.
#include "private/helper_core.h"

helper_u32 helper_strlen(const char* text) {
    helper_u32 length = 0;
    if (text == 0) return 0;
    while (text[length] != '\0') ++length;
    return length;
}

int helper_streq(const char* left, const char* right) {
    if (left == 0 || right == 0) return 0;
    helper_u32 index = 0;
    while (left[index] == right[index]) {
        if (left[index] == '\0') return 1;
        ++index;
    }
    return 0;
}

int helper_memeq(const void* left, const void* right, helper_u32 length) {
    const unsigned char* a = static_cast<const unsigned char*>(left);
    const unsigned char* b = static_cast<const unsigned char*>(right);
    for (helper_u32 index = 0; index < length; ++index)
        if (a[index] != b[index]) return 0;
    return 1;
}

void helper_memzero(void* buffer, helper_u32 length) {
    unsigned char* bytes = static_cast<unsigned char*>(buffer);
    for (helper_u32 index = 0; index < length; ++index) bytes[index] = 0;
}

void helper_memcopy(void* destination, const void* source, helper_u32 length) {
    unsigned char* out = static_cast<unsigned char*>(destination);
    const unsigned char* in = static_cast<const unsigned char*>(source);
    for (helper_u32 index = 0; index < length; ++index) out[index] = in[index];
}

int helper_append(char* out, helper_u32 capacity, helper_u32* length, const char* text) {
    if (out == 0 || length == 0 || text == 0 || capacity == 0) return HELPER_E_INVAL;
    helper_u32 used = *length;
    for (helper_u32 index = 0; text[index] != '\0'; ++index) {
        if (used + 1 >= capacity) return HELPER_E_OVERFLOW;
        out[used++] = text[index];
    }
    out[used] = '\0';
    *length = used;
    return HELPER_OK;
}

int helper_append_char(char* out, helper_u32 capacity, helper_u32* length, char value) {
    if (out == 0 || length == 0 || capacity == 0) return HELPER_E_INVAL;
    if (*length + 1 >= capacity) return HELPER_E_OVERFLOW;
    out[*length] = value;
    out[*length + 1] = '\0';
    ++*length;
    return HELPER_OK;
}

int helper_append_uint(char* out, helper_u32 capacity, helper_u32* length, helper_u32 value) {
    char digits[11];
    int count = 0;
    if (value == 0) digits[count++] = '0';
    while (value != 0 && count < 10) {
        digits[count++] = static_cast<char>('0' + (value % 10));
        value /= 10;
    }
    while (count > 0) {
        if (helper_append_char(out, capacity, length, digits[--count]) != HELPER_OK)
            return HELPER_E_OVERFLOW;
    }
    return HELPER_OK;
}

int helper_format_i64(char* out, helper_u32 capacity, helper_i64 value) {
    if (out == 0 || capacity == 0) return HELPER_E_INVAL;
    helper_u32 length = 0;
    out[0] = '\0';
    helper_u64 magnitude;
    if (value < 0) {
        if (helper_append_char(out, capacity, &length, '-') != HELPER_OK) return HELPER_E_OVERFLOW;
        magnitude = static_cast<helper_u64>(-(value + 1)) + 1ull;
    } else {
        magnitude = static_cast<helper_u64>(value);
    }
    char digits[21];
    int count = 0;
    if (magnitude == 0) digits[count++] = '0';
    while (magnitude != 0 && count < 20) {
        digits[count++] = static_cast<char>('0' + (magnitude % 10ull));
        magnitude /= 10ull;
    }
    while (count > 0) {
        if (helper_append_char(out, capacity, &length, digits[--count]) != HELPER_OK)
            return HELPER_E_OVERFLOW;
    }
    return HELPER_OK;
}

int helper_is_lower_hex(char value) {
    return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f');
}

int helper_is_upper_hex(char value) {
    return (value >= '0' && value <= '9') || (value >= 'A' && value <= 'F');
}

int helper_is_hex(char value) { return helper_is_lower_hex(value) || helper_is_upper_hex(value); }

int helper_is_alpha_numeric(char value) {
    return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'z') ||
           (value >= 'A' && value <= 'Z');
}

// A kernel boot id is 36 characters: 8-4-4-4-12 hexadecimal groups.
int helper_boot_id_valid(const char* text, helper_u32 length) {
    if (text == 0 || length != HELPER_BOOT_ID_LEN) return 0;
    for (helper_u32 index = 0; index < length; ++index) {
        const char value = text[index];
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value != '-') return 0;
        } else if (!helper_is_hex(value)) {
            return 0;
        }
    }
    return 1;
}

// Package names are one or more dot-separated segments of [A-Za-z0-9_].
int helper_package_name_valid(const char* name, helper_u32 length) {
    if (name == 0 || length == 0 || length > 255) return 0;
    helper_u32 segment = 0;
    for (helper_u32 index = 0; index < length; ++index) {
        const char value = name[index];
        if (value == '.') {
            if (segment == 0) return 0;
            segment = 0;
            continue;
        }
        if (!helper_is_alpha_numeric(value) && value != '_') return 0;
        ++segment;
    }
    return segment != 0;
}

int helper_state_path(const struct helper_context* context, const char* suffix,
                      char* out, helper_u32 capacity) {
    if (context == 0 || suffix == 0 || out == 0 || capacity == 0) return HELPER_E_INVAL;
    helper_u32 length = 0;
    out[0] = '\0';
    if (helper_append(out, capacity, &length, context->self_path) != HELPER_OK) return HELPER_E_OVERFLOW;
    if (helper_append(out, capacity, &length, suffix) != HELPER_OK) return HELPER_E_OVERFLOW;
    return HELPER_OK;
}
