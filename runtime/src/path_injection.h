#pragma once

#include <sys/types.h>

// Called only after current-identity Root admission. fullUID is never an appId.
int maplesu_inject_path(const char* package, uid_t expected_full_uid,
                       const char* directory, int wait_ms);

