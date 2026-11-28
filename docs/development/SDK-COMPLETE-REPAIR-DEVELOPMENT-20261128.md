# SDK 完整修复开发要求

2026-11-28。所有路径相对仓库根；跨设备入口见 [交接说明](../sdk-repair-20261128/README.md)。

## 交付目标

**从源码修复升级回归，恢复旧版刷 boot 后的环境识别和全部功能，完成 SDK、JNI、Manager 全链路配套，交付同代正式 SDK、签名 APK、必要镜像及逐项验收结果。** 优先查明旧正常版与新失败版的首次分歧，禁止靠反复刷写、延时、盲重试、改文案或假成功结案。

## 原文范围

必须完整处理 [SDK需求总账](../sdk-repair-20261128/inputs/SDK-REFACTOR-REQUIREMENTS.md)和[实施进度](../sdk-repair-20261128/inputs/SDK-REFACTOR-PROGRESS.md)两份主文档，以及同目录反馈 #1–#90、142项验收、50项 KPatch-Next、逐ID账本及持续/追加条件。保留原80/20、119主项与全部原ID；465是交叉索引行数，不是已修数量。历史勾选不自动授予本次验收资格。

## 必须遵守

| 范围 | 要求 |
|---|---|
| 源码与功能 | 修实际选源的生产实现和所有调用方；保 SDK/ABI、原生模块、32/64位 su、授权撤销、Panic、越狱、寄生、模块、全部 WebUI；文件修补不依赖 Root |
| 精准识别 | 最终仅 Ultra、Normal、各自有当前证据的越狱模式或未安装；**禁止显示“未完成”**，错误/Unknown不作为最终身份。整轮最多10秒，充分证据立即结束，单 session/单飞/一次最终发布；Java/JNI/native及清理共用 deadline，不闪动、不无限重开 |
| 证据与缓存 | 未安装必须有本轮可靠排除证据；外来 Root 确认非本项目模式后判未安装。Busy、超时、无 Key/owner/响应、ERR_MODULE_NO_BOOT不单独证明未安装；冷热缓存、历史与JNI阳性缓存不改变当前身份或授予能力 |
| 身份与健康 | 已证 Ultra 后 SDK/Root/授权/模块异常保 Ultra，撤无效能力证明；保原 stage/code/receipt，交责任组件并提供真实可执行的解决动作。缺证作为内部诊断异常处理，禁止猜身份、隐藏卡片或永久探测 |
| 安装与恢复 | 保显式 SDK 安装、恢复、升级和重试原能力，不新增 fresh/Ready/Root/完整备份统一前置；无 owner 的失败详情仍可读。boot/init_boot、直接安装、迁移、重补、卸载按实际载体最小写集，保回读恢复，未知提交先查询原任务，禁止重放 |
| 普通应用环境 | 未请求/未授权时，不新增全局 SELinux、挂载/namespace、属性、可见标记或常驻判定进程；授权后行为分开验。环境/侧信道/时延需实测，静态检查不替代设备结果 |
| 身份与基线 | 标准 Ultra 免 RootKey、手动配对及 Manager 在线依赖；保受保护来源、签名、fullUID、定向撤销、在途生命周期和 fresh legacy/carrier 合同。对照固定 KSU 实现；布局依目标 BTF/访问器语义验证，不猜偏移，不自加 ROM/framework/PMS 屏障，不宣称 PMS 零窗口 |
| UI与冷启 | **参考固定 KSU 首页和设置页的真实实现优化展示效果**：信息层级、卡片比例/间距、排版、状态色、按钮和反馈一致清晰。保原大卡/小卡/三卡与 Material、Miuix 两主题；两行不重复，首页简洁、详情原错完整；缓存先显示、后台静默确认且不冒充当前事实。按钮触区、滚动、底栏、字体缩放及窄屏截图和操作验收。保零模块、未打开 Manager 的首次冷启、开机脚本与卸载收尾 |

## 执行与验收

1. **固定A/B证据**：旧正常版和新失败版的 APK、SDK/helper/JNI/backend、boot/槽位、签名/fullUID、固件与安装记录逐一绑定；未知版本不猜。固定 KSU 提交为 `f4603d14bd7f3bf21206d6f7c622ddf1c049ade3`，参考入口见交接说明。
2. **审 SDK 配对使用全链路**：修补输入→真实 boot 启动→早期初始化→SDK头/库/helper/四payload/backend/claim/JNI打包→安装升级迁移→签名/fullUID/owner→会话→本轮环境读取→命令/协议/32–64 ABI→Root效果→pipe/FD回传→取消清理→bridge/Probe/Adapter/Resolver→Home/Settings→SU/模块/WebUI。逐段给 producer/consumer、函数、输入输出及首次分歧；此审计不增加手动 Key。
3. **复现首错并修生产**：一次改一个条件；同步 SDK/header/ABI/JNI 与所有消费者，保原错误、取消及未知提交语义。已观察到 Root 提交成功后 pipe EACCES，禁止误报为身份变化或提权本身失败。
4. **正式验证**：保必要失败用例、统一回归和完整变化源编译；按本次输入 SHA 重跑，准备错误和产品失败分开。冻结同代输入，完成原要求的两次 clean SDK 构建，实际打包来源可追溯。
5. **真实设备逐ID结算**：真正 boot 升级恢复原能力，并覆盖适用 Normal/Ultra/越狱、32/64 su、授权撤销、模块/WebUI、冷启、升级中断/取消/失败重试及界面/环境差分。指定8模块、Zygisk-Next/LSPosed、月虹/Tenc、iQOO/VIVO/Y700/PLQ110、iQOO15越狱、DeepSeek撤除再寄生、NGKI/无BTF/Linux4.x及实际镜像均按原文，不以单机、进程存在或返回0代全部通过。

交付：生产 diff；逐原ID的“问题→根因/函数→修复→原验收→实际结果”；SDK/Manager契约与源码→对象→资源→APK→设备来源/SHA清单；正式SDK/签名APK/必要镜像；原始测试和设备/UI证据；明确残留。**当前为接续开发检查点，已有失败和待验项见[当前状态](../sdk-repair-20261128/CURRENT-STATUS.md)，尚无完整SDK交付通过结论。**
