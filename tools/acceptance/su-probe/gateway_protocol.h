#ifndef MAPLESU_ACCEPTANCE_GATEWAY_PROTOCOL_H
#define MAPLESU_ACCEPTANCE_GATEWAY_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* Test-side wire copy. No SDK dispatch or privileged request is linked. */
enum gateway_case { GATEWAY_QUERY, GATEWAY_ECHO, GATEWAY_WRONG_PREFIX,
    GATEWAY_WRONG_SELECTOR, GATEWAY_BAD_MAGIC, GATEWAY_CASE_COUNT };
enum { GATEWAY_PREFIX_BYTES = 48, GATEWAY_FRAME_BYTES = 18,
    GATEWAY_PACKET_BYTES = 85, GATEWAY_RESPONSE_BYTES = 48 };
struct __attribute__((aligned(8))) gateway_request {
    uint32_t magic;
    uint16_t version, length;
    uint32_t command, expected_epoch;
    uint64_t request_id, response_user, arg0, arg1;
};
struct __attribute__((aligned(8))) gateway_response {
    uint32_t completion_magic;
    uint16_t version, length;
    uint32_t command;
    int32_t status;
    uint64_t request_id, result, metadata_address;
    uint32_t metadata_length, metadata_version;
};
struct __attribute__((aligned(8))) gateway_host { uint64_t low, high; };
_Static_assert(sizeof(struct gateway_request) == 48, "request wire size");
_Static_assert(_Alignof(struct gateway_request) == 8, "request wire alignment");
_Static_assert(offsetof(struct gateway_request, request_id) == 16, "request id offset");
_Static_assert(offsetof(struct gateway_request, response_user) == 24, "response address offset");
_Static_assert(sizeof(struct gateway_response) == GATEWAY_RESPONSE_BYTES, "response wire size");
_Static_assert(offsetof(struct gateway_response, status) == 12, "response status offset");
_Static_assert(offsetof(struct gateway_response, metadata_length) == 40, "metadata offset");
_Static_assert(sizeof(struct gateway_host) == 16, "host wire size");

static inline const char *gateway_case_name(int kind) {
    static const char *const names[] = {"query", "echo", "wrong-prefix", "wrong-selector", "bad-magic"};
    return kind >= 0 && kind < GATEWAY_CASE_COUNT ? names[kind] : "invalid";
}

static inline void gateway_encode_byte(uint8_t byte, uint8_t *mask, uint8_t *encoded) {
    *mask = byte == 1 ? 2 : 1;
    *encoded = byte < 2 ? 255 : (uint8_t)(byte - 1);
}

static inline int gateway_prepare(int kind, uint64_t request_id, struct gateway_request *request,
    struct gateway_response *response, struct gateway_host *host, uint8_t packet[GATEWAY_PACKET_BYTES]) {
    static const char prefix[] = "MapleSU:owner:v3:UID:epoch:host:0000000000000000";
    _Static_assert(sizeof(prefix) - 1 == GATEWAY_PREFIX_BYTES, "literal prefix size");
    if (kind < 0 || kind >= GATEWAY_CASE_COUNT) return 0;
    memset(request, 0, sizeof(*request));
    memset(response, 0xa5, sizeof(*response));
    memset(host, 0, sizeof(*host));
    request->magic = UINT32_C(0x3251534d);
    request->version = 3;
    request->length = sizeof(*request);
    request->command = kind == GATEWAY_ECHO ? 2 : 1;
    request->expected_epoch = kind == GATEWAY_ECHO ? 1 : 0;
    request->request_id = request_id;
    request->response_user = (uint64_t)(uintptr_t)response;
    if (kind == GATEWAY_ECHO) {
        request->arg0 = UINT64_C(0x1122334455667788);
        request->arg1 = (uint64_t)(uintptr_t)host;
    }
    if (kind == GATEWAY_BAD_MAGIC) request->magic ^= 1;
    uint8_t frame[GATEWAY_FRAME_BYTES] = {52, 0xa2};
    if (kind == GATEWAY_WRONG_SELECTOR) frame[1] = 0xa3;
    const uint64_t address = (uint64_t)(uintptr_t)request;
    for (unsigned index = 0; index < 8; ++index) frame[10 + index] = (uint8_t)(address >> (index * 8));
    memcpy(packet, prefix, GATEWAY_PREFIX_BYTES);
    if (kind == GATEWAY_WRONG_PREFIX) packet[0] ^= 1;
    for (unsigned index = 0; index < GATEWAY_FRAME_BYTES; ++index)
        gateway_encode_byte(frame[index], &packet[GATEWAY_PREFIX_BYTES + index],
            &packet[GATEWAY_PREFIX_BYTES + GATEWAY_FRAME_BYTES + index]);
    packet[GATEWAY_PACKET_BYTES - 1] = 0;
    return 1;
}
#endif
