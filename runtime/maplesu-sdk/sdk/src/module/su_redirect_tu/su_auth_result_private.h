// One explicit result for a single authorization mutation and its recovery.
#pragma once
#include "module_err_def.h"
#include <array>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace maplesu::su_auth {
enum class Operation : uint32_t { Add = 1, Remove = 2, Clear = 3, Refresh = 4 };
enum class Commit : uint32_t { Unknown, NotCommitted, Committed };
enum class Restore : uint32_t { Unknown, NotNeeded, Restored, Incomplete };
enum class Delivery : uint32_t { Unknown, Closed, Open };
enum class Transport : uint32_t {
    NotTransferred, Complete, ForkFailed, Truncated, ReadError,
    FrameInvalid, ChildFailed, WaitFailed
};
enum class Phase : uint32_t {
    None = 0, EntryValidate = 1, EntryRoot = 2,
    TransactionRoot = 10, TransactionLock = 11, TransactionPrepare = 12,
    TransactionControlLookup = 13, TransactionControlSnapshot = 14, TransactionFreeze = 15,
    ActionValidate = 20, ActionRoot = 21, ActionLoad = 22, ActionPackage = 23,
    ActionPersist = 24, ActionRefreshList = 25,
    WriterPrepare = 30, WriterUidResolve = 31, WriterInputRange = 32,
    WriterControlLookup = 33, WriterControlRead = 34, WriterTableCompare = 35,
    WriterRetireStore = 36, WriterReserve = 37, WriterAllocate = 38,
    WriterTableWrite = 39, WriterTableVerify = 40, WriterCopySync = 41,
    WriterControlPublish = 42, WriterIndexPrepare = 43, DeliveryOpen = 50,
    WriterRestoreCopies = 100, WriterFreeUnpublished = 101,
    TransactionObserveControl = 102, TransactionRestoreControl = 103,
    TransactionRestoreCopies = 104, TransactionCloseAfterRecoveryFailure = 105,
    TransactionCloseAfterCommittedDeliveryFailure = 106
};
constexpr uint32_t kResultAvailable = 1, kRecoveryOverflow = 2;
constexpr size_t kRecoveryCapacity = 6, kResultBytes = 176;

struct RecoveryV1 {
    uint32_t phase = 0;
    uint32_t reserved = 0;
    int64_t errorCode = 0;
};
struct ResultV1 {
    uint32_t magic = 0x31524153;
    uint16_t version = 1, headerBytes = 80;
    uint32_t byteLength = kResultBytes, operation;
    int64_t resultCode = 0, primaryErrorCode = 0;
    uint32_t primaryPhase = 0;
    uint32_t commitState = static_cast<uint32_t>(Commit::NotCommitted);
    uint32_t restoreState = static_cast<uint32_t>(Restore::NotNeeded);
    uint32_t deliveryState = static_cast<uint32_t>(Delivery::Unknown);
    uint32_t flags = kResultAvailable, recoveryCount = 0, recoveryTotal = 0;
    uint32_t transportStatus = 0;
    int32_t transportErrno = 0, childWaitStatus = 0;
    uint32_t childWaitStatusValid = 0, reserved = 0;
    std::array<RecoveryV1, kRecoveryCapacity> recovery{};
    explicit ResultV1(Operation op) : operation(static_cast<uint32_t>(op)) {}
};
static_assert(sizeof(RecoveryV1) == 16 && sizeof(ResultV1) == kResultBytes);

inline void set_delivery(ResultV1& result, Delivery state) {
    result.deliveryState = static_cast<uint32_t>(state);
}
inline void set_restore(ResultV1& result, Restore state) {
    result.restoreState = static_cast<uint32_t>(
        (result.flags & kRecoveryOverflow) ? Restore::Unknown : state);
}
inline KModErr finish(ResultV1& result, KModErr error) {
    result.resultCode = static_cast<int64_t>(to_num(error));
    if (result.flags & kRecoveryOverflow) set_restore(result, Restore::Unknown);
    return error;
}
inline KModErr fail(ResultV1& result, KModErr error, Phase phase) {
    if (is_failed(error) && result.primaryPhase == 0) {
        result.primaryErrorCode = static_cast<int64_t>(to_num(error));
        result.primaryPhase = static_cast<uint32_t>(phase);
    }
    return finish(result, error);
}
inline void recover(ResultV1& result, Phase phase, KModErr error) {
    if (result.recoveryTotal != UINT32_MAX) ++result.recoveryTotal;
    if (result.recoveryCount < kRecoveryCapacity) {
        auto& record = result.recovery[result.recoveryCount++];
        record.phase = static_cast<uint32_t>(phase);
        record.errorCode = static_cast<int64_t>(to_num(error));
    } else {
        result.flags |= kRecoveryOverflow;
        set_restore(result, Restore::Unknown);
    }
}
inline ResultV1 unavailable(Operation operation, KModErr error, Transport transport, int error_number = 0) {
    ResultV1 result(operation);
    result.flags = 0;
    result.commitState = static_cast<uint32_t>(Commit::Unknown);
    set_restore(result, Restore::Unknown);
    result.transportStatus = static_cast<uint32_t>(transport);
    result.transportErrno = error_number;
    finish(result, error);
    return result;
}
inline bool legacy_code(const ResultV1& result, KModErr& error) {
    if (result.resultCode < std::numeric_limits<ssize_t>::min() ||
        result.resultCode > std::numeric_limits<ssize_t>::max()) return false;
    error = static_cast<KModErr>(static_cast<ssize_t>(result.resultCode));
    return true;
}

namespace wire {
inline void put(uint8_t* output, size_t offset, uint64_t value, size_t bytes) {
    for (size_t index = 0; index < bytes; ++index) output[offset + index] = value >> (8 * index);
}
inline uint64_t get(const uint8_t* input, size_t offset, size_t bytes) {
    uint64_t value = 0;
    for (size_t index = 0; index < bytes; ++index) value |= uint64_t(input[offset + index]) << (8 * index);
    return value;
}
inline int64_t signed64(uint64_t value) {
    return value <= uint64_t(INT64_MAX) ? static_cast<int64_t>(value)
        : -1 - static_cast<int64_t>(UINT64_MAX - value);
}
inline bool primary_phase(uint32_t phase) {
    return phase == 0 || (phase >= 1 && phase <= 2) || (phase >= 10 && phase <= 15) ||
        (phase >= 20 && phase <= 25) || (phase >= 30 && phase <= 43) || phase == 50;
}
inline std::vector<uint8_t> encode(const ResultV1& result) {
    std::vector<uint8_t> output(kResultBytes, 0);
    auto* p = output.data();
    put(p, 0, result.magic, 4); put(p, 4, result.version, 2); put(p, 6, result.headerBytes, 2);
    put(p, 8, result.byteLength, 4); put(p, 12, result.operation, 4);
    put(p, 16, static_cast<uint64_t>(result.resultCode), 8);
    put(p, 24, static_cast<uint64_t>(result.primaryErrorCode), 8);
    put(p, 32, result.primaryPhase, 4); put(p, 36, result.commitState, 4);
    put(p, 40, result.restoreState, 4); put(p, 44, result.deliveryState, 4);
    put(p, 48, result.flags, 4); put(p, 52, result.recoveryCount, 4); put(p, 56, result.recoveryTotal, 4);
    put(p, 60, result.transportStatus, 4); put(p, 64, static_cast<uint32_t>(result.transportErrno), 4);
    put(p, 68, static_cast<uint32_t>(result.childWaitStatus), 4);
    put(p, 72, result.childWaitStatusValid, 4); put(p, 76, result.reserved, 4);
    for (size_t index = 0; index < kRecoveryCapacity; ++index) {
        const auto& row = result.recovery[index];
        put(p, 80 + 16 * index, row.phase, 4); put(p, 84 + 16 * index, row.reserved, 4);
        put(p, 88 + 16 * index, static_cast<uint64_t>(row.errorCode), 8);
    }
    return output;
}
// Only adopt a completely validated child result. Parent-only transport fields
// remain zero on the wire and are filled after closing FDs/reaping this child.
inline bool decode(const uint8_t* p, size_t size, Operation operation, ResultV1& output) {
    if (!p || size != kResultBytes) return false;
    ResultV1 candidate(operation);
    candidate.magic = get(p, 0, 4); candidate.version = get(p, 4, 2); candidate.headerBytes = get(p, 6, 2);
    candidate.byteLength = get(p, 8, 4); candidate.operation = get(p, 12, 4);
    candidate.resultCode = signed64(get(p, 16, 8)); candidate.primaryErrorCode = signed64(get(p, 24, 8));
    candidate.primaryPhase = get(p, 32, 4); candidate.commitState = get(p, 36, 4);
    candidate.restoreState = get(p, 40, 4); candidate.deliveryState = get(p, 44, 4);
    candidate.flags = get(p, 48, 4); candidate.recoveryCount = get(p, 52, 4); candidate.recoveryTotal = get(p, 56, 4);
    if (candidate.magic != 0x31524153 || candidate.version != 1 || candidate.headerBytes != 80 ||
        candidate.byteLength != kResultBytes || candidate.operation != static_cast<uint32_t>(operation) ||
        candidate.commitState > 2 || candidate.restoreState > 3 || candidate.deliveryState > 2 ||
        !(candidate.flags & kResultAvailable) || (candidate.flags & ~(kResultAvailable | kRecoveryOverflow)) ||
        candidate.recoveryCount > kRecoveryCapacity || !primary_phase(candidate.primaryPhase) ||
        ((candidate.primaryPhase == 0) != (candidate.primaryErrorCode == 0)) ||
        (candidate.resultCode == 0 && candidate.primaryPhase != 0)) return false;
    const bool overflow = candidate.flags & kRecoveryOverflow;
    if (overflow ? (candidate.recoveryCount != kRecoveryCapacity || candidate.recoveryTotal <= kRecoveryCapacity ||
                    candidate.restoreState != static_cast<uint32_t>(Restore::Unknown))
                 : candidate.recoveryCount != candidate.recoveryTotal) return false;
    for (size_t offset = 60; offset < 80; ++offset) if (p[offset] != 0) return false;
    for (size_t index = 0; index < kRecoveryCapacity; ++index) {
        auto& row = candidate.recovery[index];
        row.phase = get(p, 80 + 16 * index, 4); row.reserved = get(p, 84 + 16 * index, 4);
        row.errorCode = signed64(get(p, 88 + 16 * index, 8));
        if (row.reserved != 0 || (index < candidate.recoveryCount
                ? row.phase < 100 || row.phase > 106 : row.phase != 0 || row.errorCode != 0)) return false;
    }
    KModErr error;
    if (!legacy_code(candidate, error)) return false;
    output = candidate;
    return true;
}
} // namespace wire

KModErr add(const char* package, ResultV1& result);
KModErr remove(const char* package, ResultV1& result);
KModErr clear(ResultV1& result);
namespace detail {
KModErr write_allowlist(const std::vector<std::string>&, const char*, bool, ResultV1&);
KModErr sdk_add(const char*, ResultV1&);
KModErr sdk_remove(const char*, ResultV1&);
KModErr sdk_clear(ResultV1&);
KModErr transact_add(const char*, ResultV1&);
KModErr transact_remove(const char*, ResultV1&);
KModErr transact_clear(ResultV1&);
}
} // namespace maplesu::su_auth
