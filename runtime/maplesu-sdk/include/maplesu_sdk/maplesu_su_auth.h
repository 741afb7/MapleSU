#pragma once
#include <unistd.h>
#include <iostream>
namespace maplesu::sdk {
struct su_auth_item {
    char app_package_name[256] = {0};
};
/***************************************************************************
 * 获取 user 0 的 SU allow 包名投影
 *       out_pkgs   输出：APP包名列表
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
KModErr get_su_auth_list(std::vector<su_auth_item>& out_pkgs);

/***************************************************************************
 * 旧包名接口：Owner 路径返回 EOPNOTSUPP；请使用 fullUID profile 事务
 *       app_package_name   APP包名
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
KModErr add_su_auth_list(const char* app_package_name);

/***************************************************************************
 * 旧包名接口：Owner 路径返回 EOPNOTSUPP；撤权使用 fullUID profile 事务
 *       app_package_name   APP包名
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
KModErr remove_su_auth_list(const char* app_package_name);

/***************************************************************************
 * 旧包名接口：Owner 路径返回 EOPNOTSUPP，不发布空授权快照
 * 返回: OK 表示成功；其它值为错误码
 ***************************************************************************/
KModErr clear_su_auth_list();

}