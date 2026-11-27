#include "gateway_protocol.h"
#include <assert.h>
#include <stdio.h>

/* Shape-only host fixture: never issues a syscall or predicts a device errno. */
int main(void) {
    unsigned checks = 0;
    for (unsigned value = 0; value < 256; ++value) {
        uint8_t mask, encoded;
        gateway_encode_byte((uint8_t)value, &mask, &encoded);
        assert(mask && encoded);
        assert((uint8_t)(encoded + mask) == value);
        ++checks;
    }
    struct gateway_request request;
    struct gateway_response response;
    struct gateway_host host;
    uint8_t packet[GATEWAY_PACKET_BYTES];
    for (int kind = 0; kind < GATEWAY_CASE_COUNT; ++kind) {
        assert(gateway_prepare(kind, 7, &request, &response, &host, packet));
        assert(request.magic == (UINT32_C(0x3251534d) ^ (kind == GATEWAY_BAD_MAGIC)));
        assert(request.version == 3 && request.length == 48 && request.request_id == 7);
        assert(request.command == (kind == GATEWAY_ECHO ? 2u : 1u));
        assert(request.expected_epoch == (kind == GATEWAY_ECHO ? 1u : 0u));
        assert(request.response_user == (uint64_t)(uintptr_t)&response);
        assert(request.arg0 == (kind == GATEWAY_ECHO ? UINT64_C(0x1122334455667788) : 0));
        assert(request.arg1 == (kind == GATEWAY_ECHO ? (uint64_t)(uintptr_t)&host : 0));
        assert(host.low == 0 && host.high == 0);
        for (unsigned index = 0; index < sizeof(response); ++index)
            assert(((uint8_t *)&response)[index] == 0xa5);
        assert(packet[0] == (uint8_t)('M' ^ (kind == GATEWAY_WRONG_PREFIX)));
        assert(memcmp(packet + 1, "apleSU:owner:v3:UID:epoch:host:0000000000000000", 47) == 0);
        uint8_t frame[GATEWAY_FRAME_BYTES];
        for (unsigned index = 0; index < GATEWAY_FRAME_BYTES; ++index) {
            assert(packet[48 + index] && packet[66 + index]);
            frame[index] = (uint8_t)(packet[48 + index] + packet[66 + index]);
        }
        assert(frame[0] == 52 && frame[1] == (kind == GATEWAY_WRONG_SELECTOR ? 0xa3 : 0xa2));
        for (unsigned index = 2; index < 10; ++index) assert(frame[index] == 0);
        uint64_t address = 0;
        for (unsigned index = 0; index < 8; ++index) address |= (uint64_t)frame[10 + index] << (index * 8);
        assert(address == (uint64_t)(uintptr_t)&request && packet[84] == 0);
        ++checks;
    }
    assert(!gateway_prepare(-1, 7, &request, &response, &host, packet));
    assert(!gateway_prepare(GATEWAY_CASE_COUNT, 7, &request, &response, &host, packet));
    printf("gateway encoding checks=%u; no syscall/device expectations\n", checks + 2);
    return 0;
}
