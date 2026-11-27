#pragma once
#include "third_party/____DO_NOT_EDIT____/private_desc_parser.h"
#include "module_web_ui_http_handler.h"
#include "module_base_install_callback.h"

/***************************************************************************
 * MapleSU 模块入口函数（必须提供）
 * 
 * 执行时机：本入口函数会在 Android 早期阶段执行，此时 Zygote64 尚未启动。
 * 
 * 参数：
 *   module_private_dir  模块私有目录（受隐藏保护；需要隐藏的文件建议放在此目录）。
 * 
 * 返回值：
 *   0    表示模块入口正常结束；
 *   非0  表示模块执行异常，返回值会记录到日志，便于排查。
 *
 * 使用说明：
 *   该入口应以快速、无阻塞的代码为主，并在执行后尽快返回。
 *
 *   适合直接执行：
 *   - 安装内核 Hook；
 *   - 修改 Android 系统属性；
 *   - 不依赖系统服务的初始化操作。
 *
 *   建议 fork() 到后台后执行：
 *   - 网络请求、大量文件扫描等耗时操作；
 *   - 依赖 Android Framework、Binder 服务或应用进程的操作；
 *   - 持续运行的监控、守护及其他业务逻辑；
 *   - 循环等待或其他可能长时间占用入口的操作。
 *
 *   后台子进程应先等待约 5～10 秒，或确认系统环境已就绪后再继续执行，以免影响系统启动。
 ***************************************************************************/
int maplesu_module_main(const char* module_private_dir);


/***************************************************************************
 * 必填元数据（不填写将导致模块无法加载）
 ***************************************************************************/
// MapleSU 模块名称（必填）
#define MAPLESU_MODULE_NAME(name)            ___MOD_NAME(name)

// MapleSU 模块版本号（必填）
#define MAPLESU_MODULE_VERSION(ver)          ___MOD_VERSION(ver)

// MapleSU 模块描述（必填）
#define MAPLESU_MODULE_DESC(desc)            ___MOD_DESC(desc)

// MapleSU 模块作者（必填）
#define MAPLESU_MODULE_AUTHOR(author)        ___MOD_AUTHOR(author)

// MapleSU 模块唯一身份ID（必填）
// 用于区分不同模块，必须是固定 32 字符字符串。
// 仅允许 [0-9a-zA-Z]，建议随机生成一次后保持不变。
#define MAPLESU_MODULE_ID32(str32)           ___MOD_ID32(str32)

/***************************************************************************
 * 可选能力（按需填写）
 ***************************************************************************/
// WebUI（可选）：Web管理页面
#define MAPLESU_MODULE_WEB_UI(WebUIHandlerClass)     ___MOD_WEB_UI(WebUIHandlerClass)

// 安装模块回调（可选）：可拒绝安装。参考：module_install_callback.h
#define MAPLESU_MODULE_ON_INSTALL(callback)      ___MOD_ON_INSTALL(callback)

// 卸载模块回调（可选）：用于清理与收尾。参考：module_install_callback.h
#define MAPLESU_MODULE_ON_UNINSTALL(callback)    ___MOD_ON_UNINSTALL(callback)

// 更新 JSON（可选）：如"https://example.com/xxx.json"
#define MAPLESU_MODULE_UPDATE_JSON(url)          ___MOD_UPDATE_JSON(url)
