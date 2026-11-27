// su -- root handshake and process identity.
//
// The SDK's su consumes an inherited kernel session receipt for the already
// applied Profile. With no session descriptor only an already-root task may
// continue. Only explicit identity and namespace options replace the Profile.
//
#pragma once

#include <sys/types.h>
#include <vector>

namespace maplesu::payload::su {

// Claims and closes the inherited session fd, consuming its receipt once and
// checking all real/effective/saved UID/GID values. A failed scan or session never
// falls back. A complete scan without a session accepts only real/effective UID0.
// On failure, errno retains the syscall error or EPROTO/EPERM.
bool acquire_root();

// Applies an explicitly requested identity using the granted credentials:
// setgroups, then setresgid, then setresuid; preserves the first syscall error.
bool set_identity(unsigned int uid, unsigned int gid,
                  const std::vector<gid_t>& groups);

// Joins the global (init) mount namespace when the caller asked for
// --mount-master.  Returns false when the namespace could not be entered.
bool join_global_mount_namespace();

}  // namespace maplesu::payload::su
