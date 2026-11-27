#pragma once
#include <vector>
#include <string>
#include <unistd.h>

namespace maplesu::sdk {

// MapleSU 环境安装策略
enum class InstallMode : uint32_t {
    Boot      = 0, // 刷 Boot 模式（固化到内核，自动重启）
    HotLoad   = 1, // 热启动模式
    Refresh   = 2, // 仅刷新已安装且经验证环境的软件载荷，不启动 helper 或重启
};

// 使用的漏洞策略
enum class ExploitMethod : uint32_t {
    None          = 0, // 无（默认，不使用特定漏洞）
    Magica        = 1, // Magica 漏洞方式
    CVE2026_43499 = 2, // CVE-2026-43499 漏洞方式
};

// MapleSU 环境安装配置
struct InstallConfig {
    ExploitMethod exploit = ExploitMethod::None; // 使用的漏洞策略
};

/***************************************************************************
 * 安装 MapleSU 环境
 * 说明: 首次使用前必须调用此函数，用于创建 MapleSU 所需的环境文件。
 *      mode      部署模式 (默认为刷 Boot 模式)
 *      config    安装配置 (默认为无特定漏洞)
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
KModErr install_maplesu_environment(InstallMode mode = InstallMode::Boot, const InstallConfig& config = InstallConfig());

/***************************************************************************
 * 卸载 MapleSU 环境
 * 说明: 彻底删除所有MapleSU环境文件。
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
KModErr uninstall_maplesu_environment();

/***************************************************************************
 * 获取当前 MapleSU 环境状态
 * 返回: 环境状态信息
 ***************************************************************************/
enum class MapleSUEnvState : uint32_t {
    NotInstalled = 0, // 未安装
    Running      = 1, // 运行中
    Fault        = 2, // 本次启动已进入故障保护
    Unknown      = 3  // 初始化中或状态读取未确认；保留重试，禁止推断安装/运行
};
MapleSUEnvState get_maplesu_environment_state();

/***************************************************************************
 * 查询已安装的 MapleSU 环境版本号
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
struct MapleSUSdkVersion { uint32_t major = 0, minor = 0, patch = 0; };
KModErr get_installed_maplesu_environment_version(MapleSUSdkVersion& out_ver);

/***************************************************************************
 * 读取当前 SDK 的版本号
 * 返回：SDK版本号
 ***************************************************************************/
MapleSUSdkVersion get_sdk_version();
}

constexpr bool operator<(const maplesu::sdk::MapleSUSdkVersion& a, const maplesu::sdk::MapleSUSdkVersion& b) {
    return std::tie(a.major, a.minor, a.patch) < std::tie(b.major, b.minor, b.patch);
}

constexpr bool operator==(const maplesu::sdk::MapleSUSdkVersion& a, const maplesu::sdk::MapleSUSdkVersion& b) {
    return std::tie(a.major, a.minor, a.patch) == std::tie(b.major, b.minor, b.patch);
}

constexpr bool operator!=(const maplesu::sdk::MapleSUSdkVersion& a, const maplesu::sdk::MapleSUSdkVersion& b) {
    return !(a == b);
}

constexpr bool operator>(const maplesu::sdk::MapleSUSdkVersion& a, const maplesu::sdk::MapleSUSdkVersion& b) {
    return b < a;
}

constexpr bool operator<=(const maplesu::sdk::MapleSUSdkVersion& a, const maplesu::sdk::MapleSUSdkVersion& b) {
    return !(b < a);
}

constexpr bool operator>=(const maplesu::sdk::MapleSUSdkVersion& a, const maplesu::sdk::MapleSUSdkVersion& b) {
    return !(a < b);
}
