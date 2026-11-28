# SDK 完整修复交接提示词

在从 Git 拉取的 MapleSU `main` 仓库根直接开展修复。先读：

1. `docs/sdk-repair-20261128/SDK-COMPLETE-REPAIR-DEVELOPMENT-20261128.md`
2. `docs/sdk-repair-20261128/README.md`、`CURRENT-STATUS.md`
3. `docs/sdk-repair-20261128/inputs/SDK-REFACTOR-REQUIREMENTS.md`、`SDK-REFACTOR-PROGRESS.md`
4. 同目录 `DEVELOPMENT-MATRIX.json`、`REQUIREMENTS-LEDGER.jsonl` 及其列出的完整原文。

目标：旧版刷真正 boot 后环境识别及所有功能正常，升级后持续检查不到。固定真实旧正常A与新失败B，找首次分歧，从实际生产源码恢复原能力，完成完整 SDK 与 Manager 配套，同代正式包和真实设备验收。两份主文档、原80/20、119主项、90反馈、142验收、50KPN及64持续/追加要求按原ID全部处理，摘要不替代原验收。

必做 SDK 配对审计：修补/boot启动→早期初始化→头/库/helper/四payload/backend/claim/JNI实际打包→安装升级迁移→签名/fullUID/owner→会话→当前身份读取→命令/协议/32–64 ABI→Root效果→pipe/FD回传→取消清理→bridge/Probe/Adapter/Resolver→Home/Settings→真实SU/模块/WebUI。逐段记录函数、producer/consumer、输入输出和首次分歧；不新增手动Key。

硬约束：

- 修生产源码及全部消费者，保全部原功能；禁止延时、盲重试、反复刷写、Manager在线、吞错、强置Ready、删入口或仅改测试结案。
- 身份最终只显示 Ultra、Normal、各自有当前证据的越狱模式或未安装，**禁止显示“未完成”**。整轮最多10秒，充分证据提前结束，单session/单飞/一次最终发布，Java/JNI/native与清理共用deadline，不闪动、不无限探测。
- 未安装需当前可靠排除证据；外来Root确认非本项目模式才显示未安装。Busy/超时/无Key/无owner/无响应/ERR_MODULE_NO_BOOT不是独立否定证据。缓存、JNI阳性缓存、旧boot/owner和历史均不授当前身份或能力。
- 已证Ultra后，Root/SDK/授权/模块故障保Ultra、撤无效能力、保原stage/code/receipt并交组件给真实解决动作。缺证不得猜身份或无限探测；内部诊断准确，产品身份不新增异常标签。
- 保显式SDK安装/恢复/升级与真实重试；无owner失败详情可读，不增fresh/Ready/Root/完整备份统一前置。未知提交查原任务，不自动重放；按真实boot/init_boot载体保最小写集与回读恢复。
- 普通应用未请求/未授权时，不新增全局SELinux、挂载/namespace、属性、可见标记或常驻判定进程；授权后分开验。标准Ultra免RootKey/手动配对/Manager在线；保签名、fullUID、受保护来源、定向撤销、在途及fresh legacy/carrier合同。固定KSU基线与BTF/访问器语义优先，不加ROM/framework/PMS屏障、不猜偏移。
- **同时优化UI展示效果，参考固定KSU首页与Settings实际实现**，改善信息层级、卡片比例/间距、排版、状态色、按钮及反馈；遵守以上状态语义。保大/小/三卡和Material/Miuix两主题，两行不重复；首页短摘要、详情原错完整；缓存先显示、后台静默核实；原授权/hidden/pending/能力门保全，手动安装/恢复保持可达。截图与实际点击验证触区、滚动、底栏、字体缩放和窄屏，不用隐藏功能换整洁。
- 保原SDK/ABI、原生模块、32/64 su、Panic、越狱/寄生、全部模块/WebUI、零模块无Manager首次冷启与开机/卸载脚本。保原图/原包/旧失败/他人改动；源码、选源、主机测试、正式包、设备分开记账。

先处理CURRENT-STATUS中的Root回传、Normal原生阳性缓存及待重新执行的Manager回归；历史窄编译/静态互审不代表当前完整通过。原2026-11-27T18:15:46Z平台中止边界保持，禁止自动重提该受阻授权/SELinux动作；先完成独立源码、生命周期、编译与文档工作。

执行：A/B复现→首错与生产修复→所有消费者适配→必要失败/修后与统一正式回归→两次clean SDK与同代来源冻结→真正boot及全部适用模式、功能/UI/环境实机验收→逐原ID结算。交付生产diff、逐ID矩阵、接口/来源/SHA清单、原日志、同代SDK/签名APK/必要镜像及明确残留。直接推进修复，禁止仅交计划或概括“已修复”。
