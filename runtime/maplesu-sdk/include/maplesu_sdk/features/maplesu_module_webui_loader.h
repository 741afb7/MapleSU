#pragma once
#include <vector>
#include <string>
#include <unistd.h>

namespace maplesu::sdk {
namespace features {
namespace web_ui {
/***************************************************************************
* 启动模块WebUI服务器（异步）
*       mod_id32            模块唯一身份ID32
*       out_port            输出端口
* 返回: OK 表示调用成功；其他值为错误码
***************************************************************************/
KModErr start_module_web_ui_server_async(const char* mod_id32, int &out_port);

} // namespace web_ui
} // namespace features
} // namespace maplesu::sdk