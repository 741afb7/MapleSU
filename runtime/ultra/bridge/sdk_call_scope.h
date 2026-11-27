#pragma once

namespace maplesu {
enum class SdkCallAccess { Local, Read, Exclusive, Isolated };
SdkCallAccess sdk_call_access(const char* operation);

struct SdkCallContext {
    bool retry_interrupted_wait = true;
    bool owns_transport = true;
    int process_error = 0;
    int owner_pid = 0;
};

// Known calls own their transport and conflict only on the resources they
// actually share. Unknown entries retain conservative exclusive admission.
// Isolated calls retain their own bounded worker/slot and real deadline.
class SdkCallScope final {
public:
    explicit SdkCallScope(const char* operation);
    ~SdkCallScope();
    bool acquired() const { return acquired_; }
    int process_error() const { return context_.process_error; }
    SdkCallScope(const SdkCallScope&) = delete;
    SdkCallScope& operator=(const SdkCallScope&) = delete;
private:
    SdkCallAccess access_;
    unsigned read_mask_ = 0;
    unsigned write_mask_ = 0;
    bool owned_transport_ = false;
    bool acquired_ = false;
    SdkCallContext context_;
    SdkCallContext* previous_call_ = nullptr;
};
}
