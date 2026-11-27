#include "private/helper_core.h"

// The maintained loader validates the generated filename, size and ELF note
// before dlopen; it retains the DSO after entering the policy initializer.
extern "C" int maplesu_environment_policy_load();

int helper_policy_start(helper_context* context, char* message, helper_u32 capacity) {
    context->policy_ready = 0;
    if (capacity) message[0] = 0;
    const int result = maplesu_environment_policy_load();
    if (result != 0) {
        helper_u32 length = 0;
        helper_append(message, capacity, &length, "paired loader stage=");
        helper_append_uint(message, capacity, &length, static_cast<helper_u32>(result));
        return result;
    }
    context->policy_ready = 1;
    return 0;
}
