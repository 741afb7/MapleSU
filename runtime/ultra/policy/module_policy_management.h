#pragma once
#include <cstdint>

namespace maplesu::module_policy {
struct Reply {
    int64_t error = -1;
    unsigned requested = 0;
    int effective = -1;
    unsigned capabilities = 0;
    bool unverified_hide = false;
    bool can_try_unverified = false;
    bool hide_failed = false;
    char reason[80]{};
};
// -1 reads; 0/1/3 saves. 7 saves Hide with explicit unverified-kernel consent.
Reply manage(int request);
}
