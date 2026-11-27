# 五态共享状态聚焦测试

从工作区实际选源 `src/maplesu/module` 编译 installer、list、module_state 与原 fork helper，不复制生产函数体。输出目录需显式指定，例如：

```powershell
C:\Python314\python.exe runtime/maplesu-sdk/sdk/tools/tests/module_state/run.py --out C:\Temp\MapleSU-module-state
```

环境是当前 Windows 构建机、sdk_common 配置的 NDK r26b，以及已有 Docker 镜像 `sha256:272ada1e61c525116032246df2a790d1ccec1804b7a9e0b09203c5e650dcb78e`。脚本使用 `--pull never`、关闭网络与只读 SDK 挂载；容器 `/data` 临时，输出只写 `--out`，不部署正式产物或访问设备。

198 条状态断言覆盖非秘密部署目录、原格式/错误、真实安装/卸载 fork、list 优先级、单模块状态/描述覆盖及准入失败零 fork。另编译实际 runtime.cpp 的私有 worker 模板，7 例覆盖准入拒绝、成功、close 失败、首错、异常与 helper 身份切换。随后按真实 manifest 参数用 NDK 编译；idx56 保持两阶段 IR→ELF，idx55 组合 list+state，验证 5 个内部隐藏状态函数唯一。

主机替身仅位于外部边界：当前身份准入/Root/session 与部署事实、reset/callback、Android 文件标签、ZIP 与 ELF 元数据解析。真实 StorageScope、文件访问、目录遍历、状态变更、fork/管道与 wait 运行生产实现。门禁替身验证拒绝发生在 fork 前；真实网关核权由 SDK 侧独立窄验。Android ABI 由 NDK 原头/布局断言检查，主机替身不代表设备 Root/SELinux 已验收。

完整 boot/single 编排、正式 helper/APK、设备冷启动和并发验收留统一阶段。历史五态集成证据只作线索，本轮以当前命令结果为准。
