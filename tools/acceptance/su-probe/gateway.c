#define _GNU_SOURCE
#include "gateway_protocol.h"
#include <errno.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

struct identity { uid_t uid[3]; gid_t gid[3]; int uid_error, gid_error; };
static atomic_uint call_sequence = ATOMIC_VAR_INIT(0);

static void identity_read(struct identity *value) {
    memset(value, 0, sizeof(*value));
    value->uid_error = getresuid(&value->uid[0], &value->uid[1], &value->uid[2]) == 0 ? 0 : errno;
    value->gid_error = getresgid(&value->gid[0], &value->gid[1], &value->gid[2]) == 0 ? 0 : errno;
}

static void identity_json(const struct identity *value, char *out, size_t size) {
    snprintf(out, size, "{\"uidError\":%d,\"gidError\":%d,\"uids\":[%u,%u,%u],\"gids\":[%u,%u,%u]}",
        value->uid_error, value->gid_error, (unsigned)value->uid[0], (unsigned)value->uid[1],
        (unsigned)value->uid[2], (unsigned)value->gid[0], (unsigned)value->gid[1], (unsigned)value->gid[2]);
}

static void hex(const void *data, size_t size, char *out) {
    static const char digits[] = "0123456789abcdef";
    const uint8_t *bytes = data;
    for (size_t index = 0; index < size; ++index) {
        out[index * 2] = digits[bytes[index] >> 4];
        out[index * 2 + 1] = digits[bytes[index] & 15];
    }
    out[size * 2] = 0;
}

static uint64_t nanos(const struct timespec *time) {
    return (uint64_t)time->tv_sec * UINT64_C(1000000000) + (uint64_t)time->tv_nsec;
}

JNIEXPORT jstring JNICALL
Java_dev_maplesu_acceptance_gateway_GatewayActivity_nativeGateway(JNIEnv *env, jclass type,
    jint kind, jlong request_id) {
    (void)type;
    struct gateway_request request;
    struct gateway_response response;
    struct gateway_host host;
    uint8_t packet[GATEWAY_PACKET_BYTES];
    if (!gateway_prepare(kind, (uint64_t)request_id, &request, &response, &host, packet))
        return (*env)->NewStringUTF(env, "{\"probeError\":\"INVALID_GATEWAY_CASE\"}");
    char response_before[97], response_after[97], request_before[97], request_after[97];
    char packet_before[171], packet_after[171], host_before[33], host_after[33];
    hex(&response, sizeof(response), response_before);
    hex(&request, sizeof(request), request_before);
    hex(packet, sizeof(packet), packet_before);
    hex(&host, sizeof(host), host_before);
    struct identity before, after;
    identity_read(&before);
    const unsigned sequence = atomic_fetch_add_explicit(&call_sequence, 1, memory_order_relaxed);
    struct timespec started = {0}, finished = {0};
    const int start_error = clock_gettime(CLOCK_MONOTONIC, &started) == 0 ? 0 : errno;
    errno = 0;
    /* The only active operation: raw ABI execve, with no SDK or subprocess. */
    const long rv = syscall(__NR_execve, packet, NULL, NULL);
    const int observed_errno = errno;
    const int finish_error = clock_gettime(CLOCK_MONOTONIC, &finished) == 0 ? 0 : errno;
    identity_read(&after);
    hex(&response, sizeof(response), response_after);
    hex(&request, sizeof(request), request_after);
    hex(packet, sizeof(packet), packet_after);
    hex(&host, sizeof(host), host_after);
    char before_json[256], after_json[256], result[2400];
    identity_json(&before, before_json, sizeof(before_json));
    identity_json(&after, after_json, sizeof(after_json));
    const uint64_t start_ns = nanos(&started), finish_ns = nanos(&finished);
    const long long elapsed = start_error || finish_error || finish_ns < start_ns ? -1 :
        (long long)(finish_ns - start_ns);
    const int count = snprintf(result, sizeof(result),
        "{\"variant\":\"%s\",\"requestId\":%llu,\"pid\":%u,\"pointerBits\":%u,"
        "\"processCallIndex\":%u,\"temperature\":\"%s\",\"syscallNumber\":%ld,"
        "\"rv\":%ld,\"errnoObserved\":%d,\"monotonicStartNs\":%llu,\"monotonicEndNs\":%llu,"
        "\"syscallNanos\":%lld,\"startClockError\":%d,\"finishClockError\":%d,"
        "\"responseBeforeHex\":\"%s\",\"responseAfterHex\":\"%s\","
        "\"requestBeforeHex\":\"%s\",\"requestAfterHex\":\"%s\","
        "\"packetBeforeHex\":\"%s\",\"packetAfterHex\":\"%s\","
        "\"hostBeforeHex\":\"%s\",\"hostAfterHex\":\"%s\",\"before\":%s,\"after\":%s}",
        gateway_case_name(kind), (unsigned long long)(uint64_t)request_id, (unsigned)getpid(),
        (unsigned)(sizeof(void *) * 8), sequence, sequence == 0 ? "PROCESS_FIRST" : "SAME_PID_WARM",
        (long)__NR_execve, rv, observed_errno, (unsigned long long)start_ns,
        (unsigned long long)finish_ns, elapsed, start_error, finish_error,
        response_before, response_after, request_before, request_after, packet_before, packet_after,
        host_before, host_after, before_json, after_json);
    if (count < 0 || (size_t)count >= sizeof(result))
        return (*env)->NewStringUTF(env, "{\"probeError\":\"GATEWAY_RESULT_TRUNCATED\"}");
    return (*env)->NewStringUTF(env, result);
}
