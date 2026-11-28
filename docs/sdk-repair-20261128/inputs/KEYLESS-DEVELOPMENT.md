# Ultra 免 Key：10 路开发入口

状态：开发任务已拆分；尚未据此修改生产源码或执行验收。工作区：`WORKSPACE`。固定 KSU 对照：`WORKSPACE/.research/KernelSU` 的 `f4603d14bd7f3bf21206d6f7c622ddf1c049ade3`。

每个子 Agent 只必读本页和自己的任务卡。先修改真实选源的生产源码，附带必要的局部验证；10 路源码全部闭合，再统一构建、打包、上机回归。SDK 与所有调用端同批修改。

## 1. 共同目标

- 全新安装、升级、SU、模块、WebUI、寄生、冷启与恢复，标准 Ultra 路径均免 RootKey；后台生成 Key、假 Key 和旧 Key 降级也一并清退。
- 复用现有受验证安装身份、fullUID、授权和会话；按固定 KSU 的真实操作权限补齐行为。普通应用、Manager、helper、已 Root 调用者分别核权，不自造更强的全局前置。
- Pending、页面返回、清数据、同签重装和短暂未就绪，不触发自动回滚、重新配对或反复重装。保留真实故障恢复、显式撤除，以及未提交文件事务的必要补偿。
- 保全已有 Panic、安装器、冷启、安全模式和模块后继修复。对应实现变化时同步清理过时注释；保留有效的 ABI、CFI、页表与生命周期说明。

## 2. 10 路唯一负责人

| Agent | 生产目标 | 任务卡 |
|---|---|---|
| 01 | 内核身份、安装撤销、网关逐操作权限 | [内核](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/01-kernel.md) |
| 02 | SDK 免 Key 接口、请求传输、并发与公开头 | [SDK](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/02-sdk.md) |
| 03 | boot / init_boot 修补、载体、安装目标与镜像准备 | [镜像](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/03-images.md) |
| 04 | SDK 安装卸载、存储定位、resetprop / WebUI 工具发布 | [安装](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/04-install.md) |
| 05 | helper、无 Manager 冷启、安全模式、独立执行程序 | [冷启](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/05-cold-boot.md) |
| 06 | SU、授权撤权、32/64 位调用链与策略 | [SU](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/06-su.md) |
| 07 | 模块生命周期、KSU 兼容、原生 WebUI 与 daemon | [模块](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/07-modules-webui.md) |
| 08 | 寄生、撤除再寄生、宿主搜索与阶段提示 | [寄生](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/08-parasite.md) |
| 09 | Manager 身份、JNI、恢复、模式切换和执行路由 | [Manager](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/09-manager.md) |
| 10 | 页面静默刷新、滚动与动画、选源及构建生成逻辑 | [UI 与集成](WORKSPACE/docs/development/ultra-keyless-20261124/tasks/10-ui-build.md) |

各卡列出的源码范围均有实际修改责任；没有只产报告或只等构建的岗位。公共文件归属：SDK 公私有头＝02；内核 ABI / `manager_management.c`＝01；`native_lib_ultra.cpp` / `NativeBridge.java`＝09；SDK manifest / 顶层 CMake / Gradle / 打包脚本＝10。目录内局部构建文件随所属 Agent。更具体的卡内例外优先；索引补充文件按 10 路分组归属，不要求为清零命中修改未选源历史副本。AGENTS.md、固定基线及参考材料只读。

## 3. 按这个顺序推进

1. **10 路开工**：各自沿入口追到所选实现，先改自身内部链路。不得撤销其他人的更改；不得只改同名历史副本。当前行为优先于旧评估。
2. **接口先接上**：01 发布身份/权限语义；02 发布最终 SDK 签名；03 与 04 对齐非秘密部署定位；04 发布安装结果；09 发布 JNI/Manager 状态。只发送调用者需要的声明、结构尺寸、错误语义与改动位置，不再写架构长文。接收方可先改不依赖部分。
3. **所有者完成跨文件适配**：需要修改别人的文件，给对应 owner 发“调用点＋所需修改＋局部复现”；owner 负责落地并回传。双方核对后关闭依赖，不把适配、合并或编译错误留给主 Agent。
4. **局部验证与互审**：只运行受影响的 host / fixture / 语法 / 单元目标，使用各自临时输出目录。正式 APK、SDK 全量、全 KMI 镜像构建及设备写入留到统一阶段。互审配对：01↔06、02↔09、03↔04、05↔07、08↔10；只审改变后的合同和关键调用链。
5. **统一构建与回归**：10 卡均为 SOURCE_READY 且互审通过后，主 Agent 冻结同一源码代，统一构建、检查包内身份、上机回归。任何失败按文件/链路派回 owner；owner 修复并做局部验证，再由主 Agent 复跑受影响构建或用例。

10 份任务都由子 Agent 承担。执行器若总并发上限为 10 且含主 Agent，则先启动 9 路，首个工作槽释放即补第 10 路；不通过嵌套派遣超额。该限制只影响启动顺序，不转移开发责任。

## 4. 主 Agent 的边界与负载

主 Agent 只做分派/接收交付、接口决策收口、冻结、统一构建与回归；不接管生产源码修复。跨域错误先由报错调用点 owner 牵头定位，提供方修自己的实现，双方共同关闭；主 Agent 只追踪结果。

每路先完成卡中的主链路，再修有复现证据的遗漏；不扩成旁支框架或反复整理报告。卡已闭合的 Agent 优先接自己的集成失败，其次接互审。工作量失衡时只转交尚未动手的独立文件及完整子目标，同步更新唯一 owner；避免两人同时写一个文件。

## 5. 子 Agent 的统一交付（写在自己卡末尾）

- 状态：WORKING / WAITING_INTERFACE / SOURCE_READY；等待时注明对方与具体接口，继续独立源码。
- 改动：实际文件与已闭合行为；每个保留 Key 命中说明其用途/是否选源，不打印秘密。
- 依赖：提供和消费的接口，以及对方确认结果；未完成项逐条列明。
- 验证：命令、退出码、核心结果、源码/夹具身份与证据路径。静态检查、fixture、产物和设备结果分开写。
- 互审：配对 Agent 的结论及已修事项。SOURCE_READY 要求零未决接口、无标准 Key 回落、局部验证通过；缺设备验证允许留待统一回归，不伪报已通过。

主 Agent 的交付清单只需汇总这 10 张卡；构建出错就回派，无需亲自重写源码。

## 6. 最后统一验什么

无 Key 全新安装/升级/清数据/同签重装；boot 与 init_boot-only/KMI/多品牌镜像；无 Manager 冷启与安全模式；32/64 位 SU 和撤权/多用户；模块安装更新执行卸载；月虹、Zygisk-Next 等 WebUI；DeepSeek 撤除再寄生；页面返回滚动/动画/耗时；Panic 既有修复。版本由主线统一指定，若发布 5.2.1，APK/SDK/helper/claim/载体均须为该次源码构建的同代产物。

原 20 组及追加问题逐项沿用现有原始清单，不能用本页的分组摘要替代。详细用例在本目录 `ACCEPTANCE.csv`；旧需求证据入口：`WORKSPACE/.tmp/supervision-20261123-build-acceptance/source-repairs-current/kmi-implementation-20261124/final-requirements-review/`。历史结论只作线索，以当前源码和本轮结果复核。

按需附件：`COMPONENT-FILE-MAP.md` / `SOURCE-INVENTORY.json` 定位文件；`SDK-API-MIGRATION.md` 查接口；`AUTHORIZATION-MATRIX.csv` 查固定 KSU 对照。附件不是新增协议规格，任务卡也未预定新角色、命令编号、tag 位宽或 ABI 版本号。

## 2026-11-27 r9 接续：独立应用修复与原 ID 核验

记录时间：2026-11-27T18:52:12.644803+00:00。原执行于 2026-11-27 18:15:46 UTC 的平台中止继续保留；本轮仅推进独立的应用状态、取消重试、应用编译与文档回归，原受阻授权/SELinux 动作执行 0 次。完整范围和历史失败继续保留，正式交付及原机功能验收尚待完成。

- 首页真实显示缓存遗漏与单飞 launch 启动失败占用已修：36/36 窄测试（34 行为、2 源码合同），两个新增红例均先失败后通过。当前 Root、Manager、运行观测继续使用本轮事实。
- 恢复控制流取消已修：Auto 的观察/诊断/refresh 原样传播取消和中断；idle 取消结束该轮。真实源码及显式类型夹具 88 项，修前 11 失败，修后 88/88。原 actor 整文件和提交 guard 语句保持字节一致，成功或未知提交继续防重放。
- 当前 39 Java 与 49 Kotlin 源（含真实 HomeViewModel、完整首页 Compose、取消修复）直接编译通过，共 405 类。当前字符串 XML 经真实 aapt2 生成 compile-only 符号，旧未纳入源码的类与非字符串 R 继续显式作为依赖；这不是正常 Gradle、资源整包处理或同代 Release。52 类原测试及本轮新增测试的编译结果见编译交接，未用零 ID 的编译符号执行 JUnit。
- 原 80/20、119 主需求、反馈 #1–#90、142 验收、50 KPN 及 64 条持续/追加约束按原 ID 保留。465 行用于检索；CASE 29 原子项已计入 119，数量不替代原项验收。当前原文/提取字段完整性通过，旧 r8 错误副本保持历史。
- 修正矩阵十个提取字段及四条约束的文字/来源，历史原文和旧指针另存。涉及 SDK-02/04、CASE-02/20、KPN-22/24/29/33、CONSTRAINT-08/09/19/20；没有功能整项或设备验收提升。
- 应用冻结 2340 个 Kotlin/Java/XML/Gradle 输入逐 SHA 核对；SDK r7 仍 READY 未构建，claim r7 仍 STALE_PREPARATION_REJECTED，正式 native 输入未绑定。既有 r8 镜像仍 diagnosticOnly=true、releaseQualified=false。
- 固定 KSU f4603d14bd7f3bf21206d6f7c622ddf1c049ade3 已用 git show 读取实际首页源码；参考工作树当前 HEAD 为 528bafc5696196f3a9500cee2e7e18497421cd32，路径本身不作为固定提交证明。

交接与证据：`WORKSPACE/.tmp/successor-r9/HANDOFF.md`、`RESULT.json`、`APPLICATION-FREEZE.json`、`VERIFY-CURRENT.json`；各支 `home-lifecycle/`、`recovery-cancellation/`、`manager-compile/`、`requirements/`；原文修正见 `document-correction/`。本轮设备仅执行 transport/boot/package 只读观察，没有安装、刷写、Root/授权 probe 或删除文件。


## 2026-11-27 r10 接续：真实调用方取消与摘要来源

记录时间：2026-11-27T19:22:45.494328+00:00。本轮仅新增两份应用生产文件的修复、一份既有源码合同调整和三项新仓库测试；原执行于 2026-11-27 18:15:46 UTC 的平台中止保持。既有工作树、r9 收据及历史失败保留，原受阻动作、设备、SDK/native/claim 构建和删除均为 0。

- 首页手动检查将进度清理移至 Job completion，并覆盖 launch 同步抛错。红例为六项行为中三项失败；绿例六项行为和一项受影响源码合同通过。方法及 Job 声明逐字提取，外围 scope/检查/刷新为明确夹具；完整 ViewModel 和 Android 页面卡死根因未由此验证。
- 完整恢复调用链的 verifier/事实读取及 submit 外层保留原取消、中断和 interrupt flag；已知准备未提交取消释放 marker，未知提交保留 guard。11 份完整当前应用源、14 项调用方集成与 3 项仓库测试，红 17 项中 11 失败，绿 17/17；取消后安装次数为 0。Installer 为真实源码上下文与类型夹具，实际 transport Outcome 和设备操作仍分账。
- 当前完整 HomeViewModel 与 UltraEnvironmentUpgrade 独立增量编译通过，57 类、354,677 字节；2341 个应用输入、7031 个复用依赖、405 个 r9 类前后 SHA 一致。沿用 Kotlin 2.4.20 三插件，未扩源；旧 r5 未选源依赖和 compile-only R 限制保留，尚非正常 Gradle 或同代 Release。
- 20 个原验收字符串与 12 个原记录摘要从历史前像独立重导，32/32 匹配；20 个验收字符串另与历史 AUDIT 原文和行号匹配。原 80/20、119 主需求、反馈 #1–#90、KPatch-Next 及追加约束保留，465 行完整原项仍为 NOT_RUN，新增完整原项通过数为 0。
- 首页和恢复的独立互审未发现本轮确认可执行缺陷。r9 原有 36/88 项同源套件未重跑；观察时的 pending 状态保留在各支历史快照，以 r10 主线绑定的最终收据记录后续结果。正式 native 输入仍未绑定，SDK r7 READY 未构建、claim r7 STALE_PREPARATION_REJECTED，完整原机恢复和时延待验。

交接与证据：`WORKSPACE/.tmp/successor-r10/HANDOFF.md`、`RESULT.json`、`APPLICATION-FREEZE.json`、`FINAL-RECHECK.json`；分支 `home-integration/`、`recovery-integration/`、`compile-coverage/`、`requirements-provenance/`、`peer-reviews/`。本次文档只追加进度及 aggregate JSON 的 latestSuccessorR10 字段，原条目及状态保留。


## 2026-11-27 r11 接续：首页取消与重建后的更新卡

记录时间：2026-11-27T19:54:35.826167+00:00。相对 r10 仅 HomeViewModel 与 UltraEnvironmentPanel 两份应用生产源变化，2341 输入已冻结；正式仓库测试文件变更为 0，新增行为测试位于可重放主机审计目录。

- 首页真实 checker 在父任务仍活跃时抛取消，原捕获会继续执行 home-probe/update；现有 onFailure 现在原样抛取消，越界中断恢复实际执行线程 flag 后原样抛。普通错误、父任务取消已有保护、worker IE 转 UNKNOWN 与 foreground 缓存重用保留。真实 checker/resolver/deadline 加逐字 Home 调用方，红 8 项中 3 失败，绿 8/8；越界 IE 为明确 Clock 夹具，完整 VM/SDK 操作未执行。
- 安装进行中重建页面，新更新卡曾只保存 inProgress=true 快照，安装结束后 busy 持续为 true。现有前台读取协程等待本地 Job 和共享占用退出，再清 busy 并补读；100ms 等待只看内存标记，离页即取消，startUpgrade 的 finally 收尾。完整 Panel、两套卡片函数与实际策略在显式 Compose/Android/SDK 值夹具下，最终红 14 项中 2 失败，绿 14/14；500ms 忙等待的 facts/version 读取不增加，pause 后无活动读取子任务，恢复结束各补一次读取。忙结束不充当安装成功证据。
- 两份完整当前入口独立增量编译通过，62 类；Panel 为首次当前源入口编译。2341 应用输入、7031 复用依赖、57 个 r10 类和 405 个 r9 类前后 SHA 核对一致。Kotlin 2.4.20 三插件、旧未选源类与 compile-only R 限制继续单列，此结果尚非完整 Gradle/Manager/Release。
- 独立互审和原项对照按真实入口收口；checkForUpgrade 当前没有生产调用，其孤立边界没有被用于宣告 UI 缺陷。CASE-10e/API-02/OWN-03 及关联反馈只记录相关应用子分支，119、原 80/20、反馈 #1–#90、KPatch-Next 与追加范围保持，完整原项新增通过数为 0，465 行完整原项仍 NOT_RUN。

证据：`WORKSPACE/.tmp/successor-r11/HANDOFF.md`、`RESULT.json`、`APPLICATION-FREEZE.json`、`FINAL-RECHECK.json`；各支 home-refresh/、panel-lifecycle/、entry-compile/、original-scope/ 和 peer-reviews/。r9/r10 封存材料与早期失败保留。本轮原受阻动作、设备、SDK/native/claim 构建、整包与删除均为 0；2026-11-27T18:15:46Z 平台中止保持，正式同代产物及原机完整恢复/时延继续待验。


## 2026-11-27 r12 接续：首页空状态刷新与真实恢复入口

记录时间：2026-11-27T20:47:19.091759+00:00。相对 r11，本轮 8 份生产源与 2 份仓库测试变化，2343 个应用输入冻结；新增 RecoveryRuntime 和 RecoveryViewModelTest 纳入同一代身份，r9/r10/r11 证据保持原字节。

- 首页在无有效 snapshot、损坏 codec 或 prefs 读取失败时，原 isDirty() 会返回 false，已完成 UNKNOWN 的前台入口遂跳过 checker。现有条件改为 snapshot()?.dirty ?: true，复用既有单飞与发布链；有效干净状态保留短路。18 份完整生产源包含真实 HomeViewModel、HomeUiState、store/codec/resolver/checker，实际 StateFlow 发布红 9 项中 3 失败、绿 9/9；受影响既有源码合同另为 1/1。Android、ModeRuntime/Root/native 值与 prefs 传输仍为显式夹具，Compose/原机首帧与时延未执行。
- 模式切换失败入口原已能进入 Route.Recovery(exactId)，但主路由默认不可读源且动作未接。MainActivity 现明确传稳定 ProductionRecoveryRuntime，只消费既有 Facade 的 statusFor(exactId)、retryVerify、rollback、reboot 与既有状态投影；attach/refresh 只读，动作由用户触发。显式 factsSource 优先，runtime baseline/decision 保持 null；import/export 无后端接线，未补造 W16 的 baseline/boot/WAL/Root 事实或新增 Facade 前置。
- 完整恢复 VM/Screen/两主题/Runtime、实际 ClientStatus/projection/Session 的最终套件 21/21（9 个新增仓库方法、12 个 host 方法）。原入口红 2/2 失败已转绿；接线期间引入的验证成功后旧 Session 错误残留另先在既有 10 项中复现 2 失败，再由同 id 明确 outcome 收敛，最终仍只计 21 项一次。普通失败重试、重复点击、未知结果刷新/诊断和重建不重放按 host 证据单列；Session 的 blocked/images/foreign/UNCERTAIN 保留分支仅静态核对。旧 VM identity 候选基线 1/1 通过，早期 fixture 失败保留且不作为产品缺陷。
- 两支独立双轴复核确认问题 0。本轮 8 份变更完整入口（含 MainActivity）在固定依赖 classpath 下实际编译通过，156 类、931743 字节；未变投影/Session 等另有本轮完整 host 源资格，固定 classpath 的旧 ABI 依赖继续明列。2343 应用输入、7031 复用依赖和 r11/r10/r9 的 62/57/405 类逐 SHA 前后核对；compile-only R、未选源依赖和非 Gradle/非整包资格保留。
- 原 119、原 80/20、反馈 #1–#90、KPatch-Next 与所有追加范围继续保留，311 原文入口仅引用前代封存，不替代 465 行原项检索账本。完整原项、KPN、设备新增通过均为 0。固定 KSU 对照仅支持源码中的 StateFlow 初始构造与后台刷新组织，不用其源码替代本项目首帧或设备测量。

证据：`WORKSPACE/.tmp/successor-r12/HANDOFF.md`、`RESULT.json`、`APPLICATION-FREEZE.json`、`FINAL-RECHECK.json`；分支 home-publication/、recovery-entry/、entry-compile/、original-scope/ 和 peer-reviews/。四文档只追加本轮进度，旧字段、状态及字节均可精确还原。2026-11-27T18:15:46Z 平台中止保持，本轮受阻动作及换代理重提、设备、SDK/native/claim、Gradle/整包、删除和 Git mutation 均为 0；正式同代产物和原机完整恢复/时延继续待验。


## 2026-11-27 r13 接续：真实启动生产者、等待与设置环境修复

记录时间：2026-11-27T21:43:30.528499+00:00。相对 r12，本轮四份生产源和一份新增仓库测试变化，2344 个应用输入冻结；r9/r10/r11/r12 封存证据保持原字节。

- 完整 Home→ModeBootstrap→Coordinator/Pipeline→ModeRuntime 链复现 checker-local 取消被两层 override 捕获后继续检测、激活 KSU。两层边界现原样传播 CancellationException，保留既有普通错误降级和 worker 的 MODE_PHASE_TWO_FAILED / bounded retry 收尾；红 2/2 失败转绿。另复现冷空启动前已开始的 awaitDetectionSettled 等待捕获旧 Deferred，即使三次阶段二 UNKNOWN 完成仍等到超时；现复用已有 revision Flow 等待，红 1/1 失败转绿。超时及取消接口保留为静态核对，不另计新红例；既有 recordGate(Failed) 先 finish 再 failBootstrap 的顺序保留，本轮没有跨 attempt 原子 failure/revision 发布合同。
- Home 最终唯一 7/7（4 个新增正式仓库方法、3 个 host 调用链方法）。实际执行完整 Home 状态发布、真实 Bootstrap/RetryState/Coordinator/Pipeline、Runtime begin/record/finish/revision/activation、DefaultModeDetector 及 checker/resolver/probe；31 份完整生产源、两个逐字包装和显式 Android/SDK/Root/存储值夹具分列。在途取消及 worker 中断、普通失败降级、真实 UNKNOWN 收尾后重试激活、四次同 revision 前台复用均有断言；没有原机首帧、时延或完整 Android 生命周期结论。
- 原设置环境修复入口与首页 installBundled、r12 模式切换 Recovery 分列。两套设置皮肤的真实 remember/panel/修复行/确认回调已执行，状态、版本和 identity fallback 三处读取取消曾降级为 Unknown/null 并误报已提交；现于 fallback 前传播取消，保留先前事实、清 busy 并接受下一次显式确认重试。两皮肤 × 三边界的红 11 项中 6 失败，绿 11/11；普通结果/失败、busy 去重及确认边界保留。取消读回前 repair 请求计数已为 1，抑制 Toast 不意味着外部请求未发生；设置 UI 与下述 Application 是两条分别验证的 host 链。
- 完整 KernelSUApplication 与实际请求 actor、warmup/startup 协作者另执行 12/12，覆盖合并、取消/中断/timeout、finally、下一请求及进程/解锁门控；未复现新产品缺陷，Application 源码保持原字节。私有恢复方法由反射调用实际实现；父设置页面仅绑定源码同一性，Android/Probe/AutoSetup/Pair/SDK/Root/Deployment 使用明确夹具。两支最终唯一测试合计 30，早期准备错误和中间绿例不累计。
- 两支独立双轴互审通过后，六份完整当前入口实际编译通过：四份变化生产源加未变 Coordinator / Application，用于补本轮实际生产者和入口编译资格；共 75 类、508288 字节。首次固定 classpath 编译因旧手工依赖子集遗漏 OkHttp/Okio 而 exit 1，0 个类，失败证据原字节保留；依据项目 BOM 和缓存变体元数据，仅加入真实 OkHttp 5.5.0 / Okio 3.18.1 两个已有 jar，在新目录经独立决策再运行一次后通过。两次编译各启动一次、重启 0，六源和 freeze 不变；没有把此修正记作应用产品缺陷。
- 2344 应用输入、7031 复用依赖和两个新增真实依赖、r12/r11/r10/r9 的 156/62/57/405 类、四份 metadata 均于对应编译前后逐 SHA 核对。修正 classpath 为 126 项、5 friends，新层优先及旧 r5 ABI、compile-only R、非 Gradle/非整包资格继续明列；未重新解析完整 Gradle release 依赖图。
- 原 119、原 80/20、反馈 #1–#90、KPatch-Next 及追加范围保持；311 原文入口引用前代封存，不替代 465 行检索账本。相关原项仅获得本轮应用子分支证据，完整原项、KPN、设备新增通过均为 0。固定 KSU 基线仍为 f4603d14bd7f3bf21206d6f7c622ddf1c049ade3，没有新增全局机制或更强安装前置。

证据：`WORKSPACE/.tmp/successor-r13/HANDOFF.md`、`RESULT.json`、`APPLICATION-FREEZE.json`、`FINAL-RECHECK.json`；分支 bootstrap-runtime/、environment-repair-entry/、entry-compile/（首次失败）、compile-dependency-correction/、entry-compile-corrected/（最终成功）、original-scope/ 和 peer-reviews/。四文档只追加 r13，旧字段、状态及字节可精确还原。2026-11-27T18:15:46Z 平台中止保持，本轮受阻动作及换代理重提、设备、SDK/native/claim、Gradle/整包、删除和 Git mutation 均为 0；正式同代产物和原机完整恢复/时延继续待验。


## 2026-11-27 r14 接续：首页手动刷新与环境更新失败后的显式重试

记录时间：2026-11-27T22:21:40.021275+00:00。相对 r13 仅两张首页环境更新卡变化，2344 个应用输入冻结，正式仓库测试、Home 与启动生产源保持原字节；r9–r13 证据保留。

Home 手动链的当前执行场景为注入 ready KSU；本轮没有复现原 Ultra ENV_UNKNOWN_TIMEOUT。HostComposition 手动 render/resume 与同 ViewModel saved values 重建不等于 Android 配置重建，onPauseOrDispose 的平台效果也未执行。

- 首页两皮肤真实刷新按钮接入完整 HomeScreen/完整 HomeViewModel 与真实 single-flight、checker、Runtime。两个新 host 方法全部通过：在途自动检查后手动请求排队，重复点击去重，安装检查取消后释放 manual busy，保留 ViewModel 与 saveable 状态重建页面，下一次真实点击完成检查并发布 Ready；干净返回/resume 未新增检查。未复现新的 Home 产品缺陷，生产源码未变。32 份完整生产源、67 份显式 fixture 与四个逐字包装分列；两皮肤只执行 Pager/刷新按钮的完整函数 body，Android 绘制与调度、carrier/SDK/Root 值使用 fixture。
- 首页更新的两套真实卡/确认与完整 Panel、实际完整 Upgrade.installBundled/submit/submitMarked、readFacts/Policy 首次在同条 host 链执行。明确失败或已知准备取消后，实际应用标记清除且事实变为 Unverified；Panel 已允许操作并完成收尾，但两张卡仅认可 Available，既有重装确认入口消失。同一组最终红绿输入由 9 项/5 失败变为 9/0；修复只让两卡复用已有 UltraEnvironmentUpgrade.upgradeAllowed(upgrade) 判断可见性和点击，沿用原确认文案和实际提交检查。
- 第二次显式确认后实际应用编排进入 Rebooting；未知提交的取消、中断和普通传输异常仍保留同 boot 标记，返回页面时无点击重试和自动重放。14 项两皮肤可见/禁用组合及四项过期确认门控包含在九个方法内，不累计为额外测试。完整七份生产源包含真实文字 helpers 和共享确认；SDK/Root/native、Probe、AutoSetup、Installer 传输、Android/Compose 与内存偏好存储均为显式 fixture。应用标记分支不等于磁盘持久性或外部安装效果。
- 最终唯一新行为测试为 11（Home 2＋更新链 9），中间红绿不累加。Home 首次 host delegate 符号准备编译失败、更新链 green-r1 的 SideEffect 后下一帧预期错误均保留为工具/fixture 准备记录；最终 paired red-final-r2/green-r2 的测试、fixture、资源、依赖字节一致，产品差异仅两卡。
- 两支独立 Standards/Spec 互审完成后，两张变化的完整当前源在固定 classpath 实际编译通过：3 类、30571 字节，单次 compiler、同一进程句柄等待。复用 r13 的 126 CP 并加入新输出，127 CP、6 friends、5 metadata；755 个旧类按 75/156/62/57/405 层与原 7031 依赖、另列两份 OkHttp/Okio jar 前后核 SHA。旧 ABI、compile-only R、非 Gradle、非整包资格保留；未变化入口的逐源完整编译资格见本轮 REUSE-INDEX，不扩大本次两源结论。
- 原 119、原 80/20、反馈 #1–#90、KPatch-Next 及全部追加范围保持。311 原文入口和 465 行账本引用前代封存；FB-84/.03/.04、CASE-10e、API-02、OWN-02/03 及页面性能追加项只获得相应应用子分支证据，完整原项、KPN、设备新增通过为 0。固定 KSU 基线仍为 f4603d14bd7f3bf21206d6f7c622ddf1c049ade3。

证据：WORKSPACE/.tmp/successor-r14/HANDOFF.md、RESULT.json、APPLICATION-FREEZE.json、FINAL-RECHECK.json；分支 home-lifecycle、environment-update-entry、peer-reviews、compile-inputs、compile-prepared、entry-compile、original-scope。四文档仅追加本轮，JSON 仅新增 latestSuccessorR14，原字节和旧字段/状态可还原。2026-11-27T18:15:46Z 平台停止保持；本轮受阻动作与换代理重提、SDK/native/claim、Root/授权/SELinux、设备、Gradle/整包、删除及 Git mutation 均为 0。正式同代产物、原机完整恢复和冷暖时延继续待验，完整目标保持 active。


## 2026-11-27 r15 接续：Ultra Unknown、真实超时与下一次显式复查

记录时间：2026-11-27T23:00:08.823083+00:00。2344 个应用输入与 r14 原字节一致；生产源码、正式测试变化均为 0。新增三个 host 方法各在独立 JVM 执行一次，3/0；没有复现新产品红例。

- 完整 HomeScreen/VM/flight、真实 EnvironmentCheckProduction sharedRunner 与 prefs-backed Store、完整 Adapter/UltraEnvironmentProbe/Resolver/ProbeRunner、Bootstrap/Detector/Runtime 连成同条应用链。36 个完整生产源参与本轮 host 编译，70 份显式 fixture、3 份逐字包装、5 个 XML 和 10 个依赖单列，生成 421 类；编译清单不等于每个函数均执行。两皮肤仅执行四个完整 Pager/EnvironmentRefreshAction 原始函数字节。
- Material 无 snapshot 时，实际 recoveryFingerprint 产生 ULTRA_RECOVERY_PROBE_UNKNOWN，checker 为 ENV_UNKNOWN_PROBE_FAILED，Home 为 UNCONFIRMED。Miuix dirty 旧 Ultra 时，同一底层错误保留 snapshot/prefs，实际检测为 MODE_A_INSTALLED_UNBOUND，Home failure=null；旧事实没有激活当前模式。下一次真实显式按钮在底层受控值恢复后经实际 R6_A_ULTRA 发布 Ultra Ready。
- 真实默认 8000 ms 预算执行为 8002 ms；CheckRunner 已结束而原 ProbeRunner worker 仍在途。下一次真实 Miuix 按钮完成 Busy，held worker 入口仍为 1。释放后的迟到 KSU 阳性实际进入 Resolver 并记录 mayPersist=false，旧 snapshot/prefs 保持；再一次显式点击启动新观测并发布 Ultra Ready。
- 原 FB-84 的 848/529/648 ms 记录属于快速 recovery probe UNKNOWN；历史 ENV_UNKNOWN_TIMEOUT 标签不等于真实 deadline。当前 ProbeFailed/Timeout/Busy 分别记录。dirty 旧 Ultra 下三种底层结果对应的 Home failure 均为 null；本轮不授予该状态的 TIMED_OUT/BUSY 横幅映射资格。在途 Home 排队与 manual 取消沿用历史独立证据，不叠计为新增。
- baseline-r1 仅 host Flow 收集重载缺失导致编译准备失败，JUnit 启动 0；仅补新目录 fixture 后，baseline-r2 一次编译、三个 fresh JVM 全部通过。生产与正式测试未改；准备失败和中间元数据不计产品缺陷或额外方法。独立 Standards/Spec 互审保留覆盖限制。十二份关键源逐字匹配已有完整源编译资格，独立固定 classpath compiler 新增 0；旧 ABI/compile-only R、非 Gradle/非整包限制保留。

SDK/native/Root、身份恢复、安装、Application 恢复请求、Android/Compose/生命周期/调度和内存 prefs 为明确 fixture。host Ready 与 8002 ms 不是实机恢复或时延分布；真实清数据/重装、原 PLQ110 故障、无 Manager 冷启、同代产物及设备功能仍待验。原 119、原 80/20、反馈 #1–#90、KPatch-Next 和全部追加范围保持；311 原文入口与 465 行账本引用旧封存，完整原项/KPN/设备新增通过均为 0。

证据：WORKSPACE/.tmp/successor-r15/RESULT.json、HANDOFF.md、APPLICATION-FREEZE.json、FINAL-RECHECK.json；ultra-home-retry、peer-review、compile-inputs、original-scope。四文档仅追加 r15，JSON 仅新增 latestSuccessorR15，旧字节/字段/状态可还原。2026-11-27T18:15:46Z 平台停止保持，受阻动作重试/新代理/chat、SDK/native/Root/授权/SELinux/设备、Gradle/整包、删除及 Git mutation 均为 0，完整目标 active。


## 2026-11-27 r16 接续：真实激活期间的旧首页结果隔离（互审待办）

记录时间：2026-11-27T23:22:05.544634+00:00。主线统一 host 回归新增两项，2/0，每项独立 JVM 一次。真实 Bootstrap 在旧 Home 探测完成前，以及结果解析后、最终发布检查前，将模式从未判定激活为 KSU，实际 revision 均从 3 增至 5。新 Home 读取被暂挂时，旧结果没有结束 checking 或发布模式/Root；新观测返回后才发布 KSU Ready，每项随后四次回前台未追加探测。

2344 个应用输入与 r15 一致，生产源和正式测试变化均为 0，未复现新产品缺陷。复用 r15 已编译生产类及 36 份完整源的精确身份；仅七份新 host/fixture 源编译一次，生成 14 类，没有重编生产类或重跑历史 suite。改变的是 JNI/子进程取值和受控调度，ModeRuntime、Bootstrap、Detection、Home 状态由实际生产链产生；读取私有发布代际仅用于断言。没有新增 Android/Compose、皮肤按钮、设备或时延资格，也不宣称所有并发交错零窗口线性化。

既有 owner 收到的 followup_task/send_message 正文连续为空，独立 Standards/Spec 互审及 requirements owner 复核均待办；本轮由主线依项目边界执行统一回归，生产修复仍归原 Home owner。当前状态为 HOST_STALE_PUBLICATION_VERIFIED_PEER_PENDING。X004 只新增同进程未判定→KSU 分支的 host 证据，跨 boot/host/身份及完整原项继续待验。原 119、80/20、反馈 #1–#90、KPatch-Next 和全部追加范围保持，完整原项/KPN/设备新增通过均为 0。

证据：WORKSPACE/.tmp/successor-r16/RESULT.json、HANDOFF.md、APPLICATION-REUSE.json、home-stale-publication/baseline-r1/RESULT.json。2026-11-27T18:15:46Z 平台停止保持；SDK/native/Root/授权/SELinux/设备、Gradle/整包、删除、Git mutation、受阻动作重试、新代理/chat 均为 0。同代正式产物、原机完整恢复和冷暖时延继续待验，完整目标 active。


## 2026-11-28 r17 接续：真实 Application 恢复连接与 r16 独立复核

记录时间：2026-11-28T00:06:27.303884+00:00。r16 的独立 Standards/Spec、原项范围和编译复用审计已由三位既有 owner 完成，确认可执行问题为 0；r16 原先的待审历史保持原字节，由本轮新收据闭合。旧两项测试仅复核，不重复执行或计作新增覆盖。

本轮新增一个 host 连接方法，fresh JVM 中只执行一次，1/0。完整当前 HomeScreen/HomeViewModel 与逐字 Material 按钮入口，经实际 KernelSUApplication、UltraRecoveryRequests actor 进入实际 UltraEnvironmentProbe.refresh；仅在同时具有实际 App 和 Probe 调用栈的 SDK owner 取值边界注入同一个 CE。第一轮实际 App failed/取消重抛及 finally 终态为 ready=false、STARTUP_FAILED:CancellationException，目标 App pass 的 identity 调用为 0。下一次真实 Home 请求在同一 App/actor 中重新读 owner-missing，经实际 Warmup 得到 NO_PENDING_INSTALL，目标 App identity 为 1。并发 Home/checker 的全局 identity 13/21 单列；在首轮暂挂、取消后、下一轮结束三个检查点，Home 未 Ready/未 confirmed Absent，Store/prefs 为原空值，未进入安装等边界；没有据此声明所有瞬时写入轨迹。

2344 个应用输入与 r15 冻结精确一致，生产和正式测试修改均为 0，没有新产品红例或生产修复。41 份完整当前生产源与 83 份显式夹具、3 份逐字 wrapper、2 份证据入口联合编译，新 jar 共 469 类，未复用旧生产类。首次 compiler 因 host 缺 executing 声明退出 1，JUnit 启动 0；仅补新夹具并增加计数输出后，第二次 compiler 退出 0，再执行唯一方法。总计两次 host compiler、一次测试 JVM；历史套件与额外固定 classpath 编译均为 0。5 份额外当前源沿用的 r13 host 资格、App 的 r13 固定 classpath 和 actor 的 r9 固定 classpath 单独绑定；Warmup/Startup/Classifier 未新增 Android 固定 classpath 资格。

独立互审 Standards PASS、Spec PASS_WITH_COVERAGE_LIMITS，actionable 为 0。OWN-03 仅新增局部 Application 控制流，API-02 仅取消语义，FB-84.04/.06 仅局部 pass 取消与下一请求；CASE-10e 只关联 Home 调用方，CASE-10f 真实重装/身份恢复没有新增资格。逐项原文、剩余验收及 r9/r10/r13/r15/r16 排重见 APPLICATION-ALIGNMENT.json。这里验证 Application 一轮恢复遇到取消后 actor 接受下一请求，未新增 Home manual Job 取消、Application owner scope 取消或 Android onCreate/attach/生命周期资格。identity 恢复保持 pending 字符串夹具；Auto/Idle/Pair/能力/SDK/平台实现没有由此获得功能资格。原 119、80/20、反馈 #1–#90、KPatch-Next、全部追加范围和 311/465 行身份保持，完整原项、KPN、设备新增通过均为 0。

证据：WORKSPACE/.tmp/successor-r17/RESULT.json、HANDOFF.md、INPUTS-RECHECK.json、APPLICATION-REUSE.json、R16-REVIEW-CLOSURE.json；新增用例与调用栈位于 application-recovery/，三条独立新审计位于 peer-review/、compile-inputs/、original-scope/。2026-11-27T18:15:46Z 平台停止保持；SDK/native/Root/授权/SELinux/设备、Gradle/整包、删除、Git mutation、受阻动作重试、新代理/chat 均为 0。native 输入仍 __UNBOUND__，SDK r7 READY 未构建、claim r7 陈旧准备拒绝且 actualBuildStarted=false、r8 镜像仅诊断；同代正式产物、原机完整恢复和冷暖时延继续待验，完整目标 active。


## 2026-11-28 r18 接续：解锁后初始化续接与恢复页真实占用

记录时间：2026-11-28T01:04:22.356362+00:00。本轮从两个实际可达红例修复两个应用文件，正式仓库测试、新生产文件、MainActivity 和其他应用输入变化均为 0。2344 项当前应用输入已冻结，精确 delta 为完整 KernelSUApplication.kt 和 ModeSwitchStatusProjection.kt，其余 2342 项与 r15 冻结一致；预存工作树改动保持。

锁屏创建的同一 Application 在解锁后经逐字 onResume 和实际 Material Home retry 进入真实 actor，但红例中 Bootstrap 未启动、broker/router 均为 0。正常解锁启动控制通过。修复将已有解锁初始化体移入私有同步 helper，从原 onCreate 和已有主进程/解锁 guard 后的 request 入口调用；10,162 字节原体逐字一致。一次进入标记只表示进入，保留原步骤顺序和异常语义；同线程 startup→request 回入跳过原体，其他线程先取得同一 monitor 再请求 actor。原两方法红 2 项/1 失败，绿 2/0，每方法每代各一次 fresh 测试 JVM；App onCreate 仅一次，broker/router 入口计数各为 1，实际 Bootstrap/phaseOne/detectionComplete 由 false 变 true。SDK 身份仍缺失，Home 仍为 UNCONFIRMED、runtime=null/root=false，未 Ready、未确认 Absent，记录检查点 Store/prefs 为空；未把初始化启动当作环境就绪。41 完整当前源、84 显式夹具、4 原字节 wrapper、2 证据入口、10 外部依赖联合 host 编译，两代各 475 类。实际 marker worker 均结束；GBK 路径守卫准备错误发生在 compiler/JUnit 启动前，计数 0/0。其他线程顺序仅经源码/class 元数据核验，动态对抗并发为 0；完整 Activity、框架生命周期、Miuix 渲染及设备时延未由此验证。

恢复页红例使用完整实际 Facade.rollback 和 mutex，在低层 prepare 持有期间让新的实际 RecoveryViewModel 接入实际 Runtime。服务 wire 保持 RECOVERY_REQUIRED、phase=NEEDS_RECOVERY，notice/code/detail 原值保留，但 projection 未传 actionRunning，导致 blocksBack=false、ROLLBACK=true。实际 Facade 仍会拒绝第二操作，本例没有第二次真实写。修复仅增加 actionRunning=facadeExecuting，保留 phase 优先级和诊断。原唯一方法红 1/1→绿 1/0：busy 所有写 controls 关闭、blocksBack=true；prepare 以已知普通失败结束并 join 后，显式 refresh 恢复回滚入口且原恢复信息保持。write/lease/Root/defaultProvider/自动重放计数均为 0，work/progress 目录未产生。37 完整源（35 Kotlin、2 当前 Java 类型）、12 声明/平台/raw-provider 夹具及 10 外部依赖参与，新旧测试/夹具字节一致，动态链未注入 operationActive 布尔或最终 UI 结果。前两次准备编译分别为 602/114 和 53/18 错误/未解析名，均 JUnit 0；第三次完整闭包通过后才取得产品红。三段 javap 只读证据绑定 Mutex.isLocked→实际 Runtime→实际 VM；未扩大为底层 rollback 执行、服务写入、Android 页面 click、皮肤或设备资格。

本轮实际记录为 8 次 host Kotlin compiler、2 次 host javac、6 次测试 JVM、3 次只读 javap；3 个唯一方法最终全部通过，2 个产品缺陷先红后绿，2 个 host compiler 准备失败单列，没有重跑历史 suite。固定 Android classpath 另执行 2 次：首轮因 friends 分隔符错误在源码分析前 exit 2、class 0；保留该次参数、日志和收据后，仅将同七个 friends 的分隔符纠正为逗号并换独立输出目录，纠正轮编译两份变化后的完整应用源通过。固定 CP 为 128、friends 7、三插件；具体输出与身份见编译收据。Kotlin compiler 合计 10 次，准备失败合计 3 次（host 2、固定 CP 1），测试 JVM 仍为 6 次。当前完整 projection 的此项固定 CP 源资格为首次新增，旧 r12 只具有 host 源资格及固定 CP 旧 ABI。未据此声明完整 Manager/Gradle、资源整包或同代 Release。

双方独立交叉互审 Standards PASS、Spec PASS_WITH_COVERAGE_LIMITS，原项与编译输入独立审核确认问题为 0。OWN-03/INIT-01 仅增加局部初始化续接资格，CASE-10e 与 FB-60/84 保留症状和调用方边界；API-02/OWN-02/03 仅增加真实在途占用投影及原诊断保持资格。原 119、80/20、反馈 #1–#90、KPatch-Next、全部追加范围和 311/465 原行身份保持，完整原项、设备及 KPN 新增通过均为 0。

证据：WORKSPACE/.tmp/successor-r18/RESULT.json、HANDOFF.md、APPLICATION-FREEZE.json、INPUTS-RECHECK.json、REVIEW-CLOSURE.json、FINAL-RECHECK.json；unlock-lifecycle/、recovery-discovery/host/、peer-reviews/、compile-inputs/、original-scope/ 分别保留实际输入、准备失败、红绿执行及独立审核。四份进度文档仅追加本轮，JSON 只新增 latestSuccessorR18。2026-11-27T18:15:46Z 平台停止未解除，SDK/native/Root/授权/SELinux/设备、Gradle/整包、删除、Git mutation、受阻动作重试、新代理/chat 均为 0。native 输入仍 __UNBOUND__，SDK r7 READY 未构建、claim r7 STALE_PREPARATION_REJECTED 且 actualBuildStarted=false，r8 仅诊断；同代正式产物、原机完整恢复/Root/模块/无 Manager 冷启和冷暖时延继续待验，完整目标 active。


## 2026-11-28 r19 接续：十秒身份观察与正式候选分账

用户最新要求整轮身份观察最高 10 秒，替代早期 120 秒实施选择。Home 使用单调时钟截止和协程总预算，各 worker/checker 消费剩余预算，保留底层单飞闸门；整轮只发布一次身份。结束清手动进度、旧模式历史、Ready 与越狱标签，前台信号不无限重开；未知仅保留显式重新确认。中性 Home 不主动启动 Bootstrap/恢复，fresh resolver 跳普通 SU，fresh Ultra 不执行凭据恢复；原具名恢复动作及独立 App 生命周期分开记录。已证 Ultra 后的健康超时保留身份并交组件诊断，设置入口仍核完整 owner/scope。

2346 个应用输入冻结，相对 r18 为 37 变更、2 新增、0 删除；保留外部补入的 MainActivity dp import。原 RELEASE-BUILD-FREEZE.json frozen=true 与其 SHA 保持原字节，最短保持时点不是自动重开授权。后续用户明确授权的六项正式红例修正另开 r20，共享源不混入已构建 snapshot7。

完整当前 27 份生产源、8 份正式测试、既有 TestFixtures 和 tiny runner 共 37 源经固定 Android CP 编译通过；首次仅遗漏既有测试夹具，失败保留。15 项模型/源码接线断言通过；真实 Resolver/worker 两方法覆盖 16 组当前证据组合、共用闸门、非正预算与迟到不落盘，旧 Log 实例/静态 ABI 准备失败保留。完整 App/Home session 两方法按最终独立收据记录真实起点、准备耗时、9999/10000ms 边界、连点合并和结束不自动重开；首次因旧品牌 fixture 缺当前类型的准备错误保留。局部测试不代表 Android 绘制、真实 SDK、设备时延或底层 native worker 准时退出。

Normal JNI 阳性缓存及 late-load 标记、有效越狱租约到当前标签、SDK 缺成功的明确 Ultra 阴性回执、SDK 同步调用尾流及细项故障透传仍为实际源码余项。NoKey/noOwner/ERR_MODULE_NO_BOOT 或外来 Root 本身不造未安装；完整四模式精准识别、SDK 无痕及实机效果未宣告完成。SDK/bridge 与 JNI 最小修复点附在本代只读决策材料，SDK/native 本 chat 源码无新增编辑或构建。

外部已使用 2026-11-28T02:49:19.598032Z 的 Manager snapshot7；2343 个冻结 Manager 输入、19795751 B 经独立逐字核对全部相同，额外 219 个快照文件未扩充比较资格。早期 snapshot5 与四源不同的比较保留为历史，原冻结回执不覆盖。外部实际 assembleRelease exit0/sourceDrift=0；签名候选 60763345 B、SHA256 7851227ee800c2374a3ad4d794e03a5a29a8d54d12777aa992087c61c161f6cb，包体签名/版本/ARM64 16KiB/CRC 按独立 DELIVERY 通过。该轮 54 类 558 项回归为 552 通过、6 失败，formalReleaseQualified=false；失败和候选保持原样，后续共享 test 更新不追授原回归全绿。

原 119、原 80/20、反馈 #1–#90、KPatch-Next 和追加范围保留，465 行完整原项仍 NOT_RUN，完整项/设备/KPN 晋升为 0。2026-11-27T18:15:46Z 平台停止保持，本 chat SDK/native/Root/授权/SELinux/设备/Gradle/整包/删除/Git mutation/新 agent/chat 与受阻动作重试均为 0。目标保持 active。

证据：WORKSPACE/.tmp/successor-r19/RESULT.json、HANDOFF.md、APPLICATION-FREEZE.json、RELEASE-BUILD-FREEZE.json、EXTERNAL-SNAPSHOT7-COMPARISON.json、EXTERNAL-BUILD-OBSERVATION.json、compile-inputs/fixed-final-r2/RESULT.json、fresh-detector-host/exec-r2/RESULT.json、home-session-10s-exec-r2/RESULT.json；外部候选 BUILD_EVIDENCE/20261128/user-release-r3/candidate/DELIVERY.json。
