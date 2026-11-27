#pragma once

namespace maplesu::install::core {

using err_t = long long;
constexpr err_t kOk = 0;
constexpr err_t kErrModuleParam = -20000;
constexpr err_t kErrWriteFile = -19933;
constexpr err_t kErrCreateEmptyFile = -19935;
constexpr char kSelinuxXattr[] = "security.selinux";
constexpr char kSelinuxSystemFile[] = "u:object_r:system_file:s0";
constexpr char kSelinuxKernel[] = "u:object_r:kernel:s0";
constexpr unsigned int kExecMode0777 = 0777;

}  // namespace maplesu::install::core
