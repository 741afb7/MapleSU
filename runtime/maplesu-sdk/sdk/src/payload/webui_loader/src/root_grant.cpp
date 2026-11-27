// The SDK's worker acquires authority before exec; the loader verifies it.
#include "root_grant.h"
#include "module_err_def.h"
#include "maplesu/compat/native_module_abi.h"
#include <string>
#include <sys/syscall.h>
#include <unistd.h>

namespace maplesu::payload::webui {
long long acquire_root() {
    return static_cast<long long>(getuid() == 0 && geteuid() == 0 ? KModErr::OK : KModErr::ERR_MODULE_NO_ROOT);
}
long long acquire_legacy_root(const char* credential) {
    if (!maplesu::compat::native_module::valid_legacy_credential(credential))
        return static_cast<long long>(KModErr::ERR_MODULE_ROOT_KEY);
    if (getuid() == 0 && geteuid() == 0) return static_cast<long long>(KModErr::OK);
    std::string exec_path(credential, 47);
    syscall(__NR_execve, exec_path.c_str(), nullptr, nullptr);
    volatile char* bytes = exec_path.data();
    for (size_t i = 0; i < exec_path.size(); ++i) bytes[i] = 0;
    return acquire_root();
}
}
