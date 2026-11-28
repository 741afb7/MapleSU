# 另一台设备接续开发

在任意工作目录执行，分支统一使用 `main`：

```sh
git clone --config core.autocrlf=false --branch main https://github.com/FengQiuFQ/MapleSU.git
cd MapleSU
git pull --ff-only origin main
```

把文档ZIP解压到仓库根即可合并 `docs/`；Git已包含同一套文档。先读[开发要求](SDK-COMPLETE-REPAIR-DEVELOPMENT-20261128.md)，再将[交接提示词](SDK-COMPLETE-REPAIR-AGENT-PROMPT-20261128.md)交给接手开发者。提交身份和包内文件只保留项目内容，不加工具署名或会话目录。

- `CURRENT-STATUS.md`：本检查点的真实结果、已知根因和优先待修项。
- `inputs/`：两份主文档、119/90/142/50完整原文、465行账本及64条持续/追加条件；原ID/顺序保持。
- `evidence/`：已发生的Manager回归及Root回传诊断，不授予新代码或设备通过资格。
- `references/KernelSU/`：固定提交的Home/Settings参考源码与许可证。完整基线按下列方法取得。
- `INPUT-PROVENANCE.json`：原文与便携副本的SHA对应。仅规整本机路径、执行者及会话元数据；历史勾选、失败、验收条件和结论不提升。
- `DOCUMENT-MANIFEST.json`：包内文档长度及SHA256。原账本内历史路径/哈希是历史来源，未附的旧大包/日志不作为新机器运行前置。

```sh
git clone https://github.com/tiann/KernelSU.git reference/KernelSU
git -C reference/KernelSU checkout --detach f4603d14bd7f3bf21206d6f7c622ddf1c049ade3
```

新机器按仓库 `docs/BUILD.md`、`docs/ACTIONS.md` 及实际构建脚本配置Java/Android SDK/NDK等依赖，不复制旧机器缓存或绝对工具路径。签名材料通过既有维护者保管流程配置，文档包不含私钥/设备凭据。正式构建须重建并核对本代SDK/helper/JNI/backend/镜像，旧资源哈希或源码提交不等于正式SDK已通过。

2026-11-27T18:15:46Z原平台中止保留，自动重提对应受阻授权/SELinux动作仍禁止；该历史边界不替代独立源码修复。当前任务为接续开发，完整SDK和设备验收继续按逐ID原标准收口。
