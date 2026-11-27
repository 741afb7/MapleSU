# MapleSU 开发与验收矩阵

本矩阵保留 119 个原需求编号（含 20 组、29 个 CASE 原子项），独立记录反馈 #1–#79 及追加 #80–#90；同时列出 20 条持续约束、两组各 10 条条件、12 项追加条件、142 个原验收用例和 KPatch-Next 的 50 项真实功能。

条目覆盖不表示功能全部通过。源码、选源、正式产物、主机测试和设备效果分别核定；当前正在重建的 5.2.1／50201 产物及原设备验收尚待绑定。完整步骤、正负条件、实现入口和证据状态见同目录 `DEVELOPMENT-MATRIX.json`。

## 当前状态

| 原子审计状态 | 数量 |
| --- | ---: |
| FEATURE_REQUEST | 6 |
| FIXED_DEVICE_PENDING | 19 |
| NEEDS_EVIDENCE | 88 |
| NOT_DEFECT | 5 |
| STILL_PRESENT | 1 |

以上合计 119 项＝29 个 CASE 原子项＋90 条反馈。非 CASE 的 90 个原需求另外保留完整规范及验收映射；不以旧勾选扩大本轮结论。

## 验收维度

| 维度 | 取值 | 适用规则 |
| --- | --- | --- |
| carrier | boot, init_boot-only, boot+init_boot-paired | boot具有实际内核；init_boot-only按自身ramdisk及已实现的同代内核桥生产，不把配对写boot设成通用前提。配对仅用于明确迁移/恢复写集合。 |
| kernelFamily | 5.10, 5.15, 6.1, 6.6, 6.12, 6.18, non-KMI, Linux4.x-assessment | 六族逐实际KMI/ABI记录；5.10/5.15 legacy与CFI配置单列；non-KMI与Linux4.x不由版本门放宽推成支持。 |
| layout | target-BTF, no-BTF-accessor, wrong-BTF, malformed-BTF | 输入来自目标事实；无BTF只采用已有只读语义验证路径。发行组件资格元数据不等同分析参考整镜像。 |
| imageState | stock, current-patched, 5.2.0-patched, supported-older-SDK, third-party-patched, corrupt | 已补图先识别其实际格式和可恢复范围；原厂、PRE与第三方图分别证明，未知修改不当空洞。 |
| backup | exact-stock-present, PRE-only, third-party-source-only, absent | 原厂恢复要求对应原厂证据；缺完整备份仍按已实现精确恢复分路，不统一阻断文件修补或模式导航。 |
| mode | Normal, Ultra, Normal-to-Ultra, Ultra-to-Normal, temporary-jailbreak | 每个模式验证实际后端；切换当前boot与待重启新boot分开；临时态不当第三持久模式。 |
| pageSize | 4K, 16K, 64K | 按实际镜像/配置及组件ABI证明；未取得对应目标样本保持待验。 |
| cfi | none, kcfi, legacy-lto-cfi | 目标实际符号、回调签名与CFI绑定均满足才适用；不得仅删除CFI门。 |
| slot | A-current, B-current, inactive-explicit, non-AB | slotSuffix及实际节点回读；界面不从候选/猜测推断已安装载体。 |
| installState | first-install, same-version-upgrade, cross-version-upgrade, pm-clear, same-sign-reinstall-new-fullUID, manager-never-opened | 身份、源版本、实际component与生命周期独立登记；旧session不得在新安装身份继续使用。 |
| identity | ManagerOwner, EarlyHelper, authorized-SU-app, Root-tool-non-Manager, ordinary-ungranted-app, sharedUID, secondary-user | 以真实fullUID、签名安装来源及当前任务核权，UID0不冒认Manager；观察到安装身份不声明PMS零窗口线性化。 |
| appAbi | arm32-application, arm64-application | 32位应用兼容与32位内核支持分开；仅arm64设备记录32位能力缺口。 |
| authorization | grant, deny, revoke, repeat, package-update, UID-reuse, persistent-reboot | 持久主体与在途行为按现有合同区分，新请求依当前资格判断。 |
| concurrency | single, threads, fork, exec, duplicate-click, cancel, timeout, late-reply, owner-retire-inflight | 同一request/task/attempt及已产生effects可关联；错误/丢回执不重放变更。 |
| theme | Material, Miuix | 只作用于实际页面/交互，内核、原生ABI及镜像校验不做无意义主题乘积。 |
| moduleSet | zero-modules, same-generation-native, historical-native-ABI, fixed-script-M01-M08, official-Zygisk-Next, fixed-LSPosed, official-KPatch-Next | 逐包固定SHA、版本、依赖和声明能力。历史ELF、脚本ZIP、KernelPatch KPM三类不得互相替代。 |
| moduleLifecycle | install, action, update-from-visible-entry, enable, disable, uninstall-before-reboot, cancel-removal, reboot-finish, failure-retry | 实际回调、目录、bootstrap/catalog、运行效果和列表一致；Pending仅表示真实待完成。 |
| bootState | first-cold-no-Manager, locked, unlocked, three-cold-boots, duplicate-event, real-failure-protection, half-install | 单boot单调时序证明实际事件生产与调度；页面启动不补跑错过阶段。 |
| webHost | inline-WebView, standalone-Activity, loopback-HTTP-native, existing-proxy | 按模块实际webroot/SDK/代理声明选择；各原有入口、Intent/深链/快捷方式只测实际支持路由。 |
| webLifecycle | cold-open, warm-open, back-reopen, background, rotation, process-recreate, renderer-death, port-conflict, module-generation-change | 真实页面/服务/子进程/回调清理，不以空白、吞异常或统一外部跳转结算。 |
| metadataEncoding | xz-chunks-v1, gzip-chunks-v1-compat | 保留全部decoded语义、chunk/aggregate/native长度与SHA；限额内解码，损坏/超限负例保持失败。 |

## 实现入口与闭环

| 范围 | 真实入口 | 正向验收与失败边界 |
| --- | --- | --- |
| 内核身份与逐操作权限 | `runtime/ultra/identity/kernel/manager_management.c` · `msu_management_`<br>`runtime/ultra/identity/kernel/manager_owner_watch.c` · `msu_`<br>`runtime/ultra/identity/kernel/manager_resident_host.c` · `msu_` | 沿现有 resident/owner/watch 派发修复实际权限缺口，保全 ABI、CFI、在途排空和定向撤销。 |
| SDK 接口与请求传输 | `runtime/maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp` · `module_base_kernel_request`<br>`runtime/ultra/authorization/request_protocol.h` · `MAPLESU_REQUEST_PROTOCOL_VERSION`<br>`runtime/ultra/bridge/sdk_call_scope.cpp` · `Scope` | 复用当前免 Key 请求与生命周期实现，同批适配公开头、真实选源、bridge 和消费者，保留原始错误。 |
| 目标镜像、动态布局与正向修补 | `manager/src/main/java/dev/maplesu/manager/ultrakernel/UltraKernelFlow.kt` · `prepareTarget`<br>`manager/src/main/java/dev/maplesu/manager/ultrakernel/UltraKernelBuilder.kt` · `build`<br>`manager/src/main/java/dev/maplesu/manager/ultrakernel/UltraKernelPlan.kt` · `class UltraKernelPlan`<br>`image-core/src/main/java/dev/maplesu/image/UltraInitBootProducer.java` · `class UltraInitBootProducer`<br>`manager/src/main/java/dev/maplesu/manager/ultrakernel/UltraInitBootTargetResolver.kt` · `object UltraInitBootTargetResolver` | 由目标自身事实解析布局并正向生成最小修改；重补先识别既有修改，保留逆记录、容器边界与回读，运行时不分析打包参考整图。 |
| 安装、迁移、备份与恢复 | `manager/src/main/java/dev/maplesu/manager/installer/InstallTargetPartition.kt` · `enum class InstallPartition`<br>`manager/src/main/java/dev/maplesu/manager/installer/NativeInstallSequence.kt` · `commit`<br>`manager/src/main/java/dev/maplesu/manager/installer/StockOriginalResolver.kt` · `resolveForRestore`<br>`manager/src/main/java/dev/maplesu/manager/ksu/KsuInstallProducer.kt` · `KsuInstallProducer` | 从真实入口进入目标解析与执行，按最小写集合提交并回读；正常 Pending 续接原任务，显式恢复区分真正原厂与仅移除自有改动。 |
| helper、冷启与安全模式 | `runtime/maplesu-sdk/sdk/src/helper/helper_boot.cpp` · `helper_run_boot`<br>`runtime/maplesu-sdk/sdk/src/helper/helper_session.cpp` · `helper_session_begin`<br>`runtime/maplesu-sdk/sdk/src/helper/bootstrap_runtime.cpp` · `initialize_core` | 沿现有 early→post-fs-data→post-mount→service→boot-completed 生产与消费链闭合，保留原目标 argv/env 和真实保护语义。 |
| 传统 SU、授权与任务 profile | `manager/src/main/java/dev/maplesu/manager/runtime/ultra/UltraAuthorization.kt` · `UltraAuthorization`<br>`runtime/ultra/identity/kernel/manager_task_state.c` · `msu_`<br>`runtime/maplesu-sdk/sdk/src/maplesu_core/maplesu_su_auth.cpp` · `maplesu` | 保全 UID 持久授权与现有任务会话，传统 SU 和 SDK root 同批使用免 Key 路径；未授权普通应用不新增全局机制。 |
| 模块生命周期与 KSU 脚本 | `manager/src/main/java/dev/maplesu/manager/modules/ModuleModeRouter.kt` · `class ModuleBackendRouter`<br>`manager/src/main/java/dev/maplesu/manager/modules/MapleSuModuleBackend.kt` · `MapleSuModuleBackend`<br>`manager/src/main/assets/maplesu/modules/boot.sh` · `post-fs-data` | 保留脚本与原生模块独立路由；沿当前 callback、private/global/staging/bootstrap/catalog 修真实首错与生命周期，使用当前模式后端。 |
| WebUI 全宿主与 JS 桥 | `manager/src/main/java/com/maplesu/core/ui/webui/WebUIBridge.kt` · `createHttpModuleWebView`<br>`manager/src/main/java/com/maplesu/core/ui/webui/WebUiBridgeDispatcher.kt` · `WebUiBridgeDispatcher`<br>`manager/src/main/java/dev/maplesu/manager/modules/webui/ModuleWebUiSessions.kt` · `ModuleWebUiSessions` | 按固定上游实际 API 清单逐接口接通 loader→服务→桥→回调→退出；保持脚本与原生原契约及两个皮肤。 |
| 寄生、隐藏与宿主恢复 | `manager/src/main/java/dev/maplesu/manager/litehidden/ProductionHiddenModeBackend.kt` · `enableHidden`<br>`manager/src/main/java/dev/maplesu/manager/litehidden/ProductionHiddenEnv.kt` · `ProductionHiddenEnv`<br>`manager/src/main/java/dev/maplesu/manager/liteweb/daemon/DaemonParasiteUnhide.kt` · `DaemonParasiteUnhide` | 沿当前宿主选择→同 attempt 预检→备份/注入→handover→撤除读回修失效资源，不复用陈旧 PID 或以无限重试遮盖。 |
| Manager 状态、JNI 与恢复路由 | `manager/src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentProbe.kt` · `UltraEnvironmentProbe`<br>`manager/src/main/java/dev/maplesu/manager/workmode/service/ModeSwitchFacade.kt` · `ModeSwitchFacade`<br>`runtime/ultra/bridge/native_lib_ultra.cpp` · `JNI` | 消费真实后端事实，单向续接原任务并按 boot/activation/owner/revision 隔离缓存；更新和载体文案只使用已确认事实。 |
| 可维护选源、正式产物与回归交付 | `runtime/maplesu-sdk/sdk/build.py` · `def main`<br>`scripts/build-ultra-manager-claim.py` · `def main`<br>`scripts/verify-release-apk.py` · `def main`<br>`manager/build.gradle.kts` · `versionName` | 冻结同份真实选源和工具身份；源码→对象→归档/嵌入→APK ZIP→设备加载逐环核验，清退在功能验收后进行。 |
| 临时越狱与现有交接 | `manager/src/main/java/dev/maplesu/manager/runtime/RootExecutionRouter.kt` · `RootExecutionRouter`<br>`manager/src/main/java/dev/maplesu/manager/workmode/WorkMode.kt` · `enum class WorkMode` | 按当前 provider 的真实成功/失败及 lease 收口临时执行入口，保留原数值错误与明确交接，不用其他工具成功推导本路径。 |

## 原需求逐项映射

| 编号 | 原需求／问题 | 实施动作 | 原验收用例 | 状态 |
| --- | --- | --- | --- | --- |
| SDK-01 | 每个归档成员映射 idx、来源文件、实际编译输入、producer、旧对象和旧补丁；保留同名成员的序号隔离。 | 以归档 idx 隔离同名成员，将实际选中源、编译输入、producer、旧对象和修复来源逐成员绑定。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-02 | 对比正式 SDK 与重建库，逐项处置 partial、IR、inline、符号和结构差异；不得用 adjusted 门禁替代 raw 差异说明。 | 保留原始二进制差异，逐项解释代码/数据/符号/IR差异；允许差异有精确范围与身份，产品字节变化仍失败。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-03 | 完整可维护源码成为真实构建输入，SDK/helper/payload/backend逐项记录来源与替换状态；IR、旧对象及参照二进制仅作过渡/比对，最终发布不使用它们，补齐真正行为缺口。 | 真实选源只消费可维护源码；逐成员关闭 IR/旧对象过渡，不以重打包替代行为实现。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-04 | 所有原二进制修复迁入实际参与构建的源码；验证发布包执行该实现，不得只改未选中的 C++。 | 把历史二进制修复迁入当前选中的实现，并验证最终产物实际执行每项行为。 | D001, D002, D004, L002, S001, S002, S005 | NEEDS_EVIDENCE |
| SDK-05 | build.py 形成统一源码构建入口；对 --all 与 --source-payloads 的真实差异逐一验收。 | 用现有 build.py统一源码入口，分别验证 --all 与 --source-payloads 的真实输入和输出集合。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-06 | helper 的 7 TU、su/resetprop/WebUI 及四段嵌入资源分别核对契约、来源和实际嵌入字节。 | 分别核 helper七个 TU、su/resetprop/WebUI 与四段资源的合同、来源、长度和实际嵌入字节。 | D001, D002, D003, D004, D009, L002, S001, S002, S003, S004 | NEEDS_EVIDENCE |
| SDK-07 | 新建隔离候选 SDK，配齐 include、archive、helper、backend、entry、SDK-RELEASE、SDK-IDENTITY 和 retained inputs，五个消费目标链接通过。 | 在独立输出配齐公开头、SDK归档、helper/backend/entry与身份文件，严格链接五个消费目标。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-08 | 主机行为和必要实机比对通过后正式接入，更新 CMake/Gradle 构建与身份，产物链各环记录哈希和版本。 | 在局部行为达到要求后接真实 CMake/Gradle选源，逐环绑定版本、哈希及消费者。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-09 | 两次干净源码构建可复现；保留原始库与源码构建库的独立身份，完整 APK 的库/helper/backend 来源可追溯。 | 同份源与工具链进行两次独立干净 SDK构建；最终 APK的库/helper/backend各有真实来源。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| SDK-10 | 接入验收后清理杂乱预编译与链接 SDK，仅留一套选定的最新正式预编译备份；清理前解析引用与链接目标、冻结回退材料。 | 先解析当前引用与回退材料，替换验收后清理重复预编译/链接入口，保留唯一正式备份。 | D004, L001, S002 | NEEDS_EVIDENCE |
| SDK-11 | 修正文档、manifest、源码清单和消费契约之间的漂移；两枚 MapleSU 扩展错误码按实际消费者做兼容映射。 | 按实际消费者同步文档、manifest、源码清单和两枚扩展错误码兼容映射。 | D010, S006, T001, T006, T013, T016, T021, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| LAY-01 | 审计真正执行的全部修补路径，统一离线修补器、SDK 和运行时的布局解释，避免只统一旁路 BTF 代码。 | 沿生产 Flow、SDK和内核消费者核布局，不只统一旁路解析函数。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012, T014 | NEEDS_EVIDENCE |
| LAY-02 | 建立共享布局描述：字段偏移、宽度、来源、解析版本和必要语义；目标身份不演变为指纹库或固定偏移数据库。 | 共享现有布局描述中的字段偏移、宽度、来源和解析版本，目标身份仅用于有效性。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012, T014 | NEEDS_EVIDENCE |
| LAY-03 | 优先使用目标 BTF；无 BTF 使用访问器语义与只读验证，记录识别失败的具体字段，不凭版本号猜布局。按实际能力将无BTF动态解析作为可选路径接入NGKI目标，覆盖离线生成、首次启动布局生产和运行时消费者；无BTF与NGKI分别记账，不仅扩大系列名单，不伪造owner大小或套用固定偏移。 | 优先解析目标 BTF；无 BTF从实际访问器及只读语义产生已验证字段，逐缺字段报错并单独记录 NGKI适用性。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012, T014 | NEEDS_EVIDENCE |
| LAY-04 | 替换 real_cred+8、uid+16 等未充分验证的关系推导和通过改写 GID/凭据猜字段的方法。 | 删除缺乏目标证据的相邻字段猜测与写凭据探测，使用目标解析结果。 | T014 | NEEDS_EVIDENCE |
| LAY-05 | 重构偏移缓存，隔离过期或跨目标结果，不再复用孤立四字节值；优先按本次目标解析，缓存不成为兼容前提。 | 以本次目标身份和完整布局隔离缓存，陈旧或跨 boot/kernel结果失效。 | T014 | NEEDS_EVIDENCE |
| LAY-06 | 正确处理 cred、task、seccomp、thread flags、回调参数宽度/签名及 BTF 变体；覆盖边界写入和错误调用。 | 逐字段处理 cred/task/seccomp/flags以及真实回调宽度/签名；CFI与边界写入均验证。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012, T010, T011 | NEEDS_EVIDENCE |
| LAY-07 | 目标范围 5.10–6.18，按真实内核/配置/BTF/页大小/厂商分层验证；缺样本组合保持未验收。 | 5.10、5.15、6.1、6.6、6.12、6.18按真实配置、页大小、BTF/CFI和厂商分账。 | D006, D007, D008, I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| LAY-08 | 用户确认“4系镜像”为Linux 4.x内核镜像；在保留5.10–6.18目标的同时评估4.x扩展，逐系列/架构/厂商配置记录支持条件与缺口，覆盖实际取得的4.4/4.9/4.14/4.19等样本，不静默缩小范围。核实无BTF布局解析、cred/task/seccomp、通信/compat、Hook/重定位、初始化与工具链契约；不得仅删除版本门槛或套用现有偏移认定支持。先提交源码依据与可行性方案，再以目标镜像模拟修补、回读和必要实机验证决定已支持范围；32位应用兼容与32位内核支持分别记账。 | Linux4.4/4.9/4.14/4.19先做实际 ABI、工具链和布局可行性；取得镜像/设备证据后才发布对应支持范围。 | D008, I010 | NEEDS_EVIDENCE |
| IMG-01 | 参考 KSU/KernelPatch 的目标镜像解析与修补实现，正向生成修改，复核每个修改范围、分支/调用和重建后的内核。 | 从目标内核/ramdisk正向生成计划，逐修改范围、控制流与重建结果回读。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-02 | 保留重建内核回读和哈希比对；证明写入仅发生在计划区域及必要容器元数据，覆盖错误偏移与截断输入。 | 以计划和原字节逐范围核写入；完整重建内核及必要容器元数据双向回读与哈希。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012, S005 | NEEDS_EVIDENCE |
| IMG-03 | Ultra与KSU一样识别已修补boot，支持当前版本重修补和跨SDK升级；兼容时直接更新目标区，需恢复时使用保存的原boot再修补，避免叠加补丁。验证原图/已修补图、重复执行和失败恢复的实际结果。 | 识别当前已补载体，兼容时更新目标区；需恢复时使用适用原件再正向修补，避免补丁叠加。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-04 | 测试重复修补、跨 SDK 升级、失败回滚、原 boot 缺失、镜像容器/压缩/AVB/分区差异和模拟刷写恢复。 | 重复修补、跨SDK、压缩/AVB/容量和备份缺失分别覆盖，故障按实际写集合恢复。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-05 | 明确底层镜像与 SDK 的稳定 ABI，兼容变更尽量仅升级 SDK；不兼容 ABI 的镜像升级路径必须有明确结果和回退。 | 保持镜像与SDK明确 ABI；兼容变更只换所需组件，不兼容升级返回真实结果并保全原状态。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-06 | 优化修补/刷写操作流与用户日志，以 KSU 为参考；技术诊断进入开发日志，保留原有功能与恢复能力。 | 保持既有 UI布局，前端显示必要阶段与结果，实际参数/首错/恢复诊断写开发日志。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010 | NEEDS_EVIDENCE |
| IMG-07 | 去除镜像修补检查、安装事务检查等独立前置条件；按KSU选取目标/传参/执行并回传结果。移除推荐资产、旧事务状态、Root服务或环境就绪对离线修补的附加阻断；目标解析、写入与错误处理在执行链完成，保留回读和失败恢复。 | 离线文件修补直接解析输入；Root服务、旧事务与已装环境不作额外资格门槛。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-08 | 实施并验证Ultra的init_boot路线，保留现有boot路线。参考KernelPatch/KSU/APatch实际入口，按目标镜像内核/ramdisk和分区内容区分早期辅助、配合已修补boot、或唯一修补目标。纯ramdisk加helper不等同具备内核能力；独立MapleSU内核桥须有可维护源码、目标构建/符号版本/CFI契约和真实SDK回执。先证明原厂boot字节不变、仅写init_boot冷启动后通道可用，再接完整SDK/helper及既有模块；覆盖目标分区选择、签名、重复修补、升级、恢复和实际写集合，以工作区样本逐项回读及模拟修补验证。容器、桥接、真实启动/SU证据分层验收；SDK凭据/通信/Hook的Panic根因继续独立修复。关联INIT-01、IMG-01/03/04/05、CASE-14。 | 单独完成 init_boot生产、内核桥和 SDK真实回执；只写 init_boot并证明原厂boot字节保持。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-09 | boot/init_boot选择贯穿离线修补、直接安装、工作模式切换、热更新、升级、备份与恢复：按真实镜像内容、当前槽位和已实现的后端能力自动确定目标及最小写集合，操作日志回传实际分区与KSU参数/结果，不新增安装事务、Manager就绪或资产目录门槛。对照固定KSU源码的已修补镜像更新语义，覆盖从KSU及其他管理器迁移、原图/已修补图、同版本重复安装、跨版本升级、原始备份存在/缺失、混合boot/init_boot、失败恢复；可复用的已修补镜像直接更新，需要恢复时使用真实原件再修补，禁止把第三方未知修改当作可覆盖空洞、叠加旧载荷或宣称已恢复原厂。原厂kernel保全与SDK运行时边界分别验证；独立Ultra桥未闭合时不把惰性ramdisk当作可启动Ultra。保留UI布局与已有功能，以实际候选/写集合、回读及必要冷启动证明模式切换正确。关联IMG-02/03/04/07/08、API-02、CASE-06a/14。 | 载体选择贯穿文件/直接安装/升级/切换/备份恢复，按实际槽位与已有修改计算最小写集合。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IMG-10 | 设置中的永久卸载在保存有适用原厂镜像时恢复对应boot/init_boot及槽位原厂内容：区分真正原厂备份、安装前快照和第三方已修补镜像，保留其它分区，写后回读；缺少原厂证据时准确说明可恢复范围，不把迁移前镜像称为原厂。已有KSU/其他管理器SU时参照KSU直接安装机制，识别可重修补载体后直接安装，不要求先卸载对方Manager或先恢复原厂；需要恢复再修补时采用实际可用来源。覆盖首次/迁移/升级/切换/跨槽/中断与永久卸载，日志返回实际分区、参数、退出码和恢复结果，UI布局固定。关联IMG-03/04/07/09、API-02、QA-04。 | 永久卸载区分适用原厂备份、PRE与第三方已补图；写后回读并准确报告真实恢复范围。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| IPC-01 | 独立、共享的请求/响应定义：版本、长度、命令、状态和完整返回值，正确处理错误、未知命令、32/64 位边界。 | 共享明确版本、长度和完整返回值的请求/响应，32/64位与未知命令按真实错误返回。 | D003, D009, D010, K001, K002, K003, K007, K008, K009, K010, K011, K012, S003, S004, T001, T006, T013, T016, T021 | NEEDS_EVIDENCE |
| IPC-02 | 回执与 cred、securebits、x17 约定解耦；元数据通过显式指针和版本取得，逐步替代 LR 距离约定。 | 显式版本化元数据与请求回执，保留必要历史 ABI槽并逐步替代隐含 LR/寄存器耦合。 | D010, T001, T006, T013, T016, T021 | NEEDS_EVIDENCE |
| IPC-03 | SDK 上层原调用方式不变；旧镜像、旧二进制和旧模块通过独立兼容路径承接，并做版本配对测试。 | 新公开SDK合同与旧镜像/ELF/模块各自识别和配对；历史不兼容有精确迁移结论。 | D003, D009, D010, S003, S004, T001, T006, T013, T016, T021 | NEEDS_EVIDENCE |
| CRED-01 | 新协议完成后采用 prepare_creds → 修改已验证字段 → commit_creds，失败释放新对象，保持复制后替换语义。 | 由 prepare_creds取得副本，验证后提交；失败释放新对象并保持原凭据。 | T002, T003, T004, T005, T007, T008, T009, T012, T015, T019, T022, T023, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| CRED-02 | seccomp、线程标志和 capabilities 依目标语义处理，避免凭据头连续清零和越界；旧执行器指针不悬空。 | 按目标语义处理 seccomp/flags/capabilities与宽度；只写已验证字段并保全执行器指针。 | S005, T002, T003, T004, T005, T007, T008, T009, T012, T015, T019, T022, T023, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| CRED-03 | Root 与回执在多线程、fork/clone、失败/撤权条件下均保持对象生命周期和结果正确。 | Root结果和回执绑定真实线程/fork/clone及撤权生命周期，不提前释放或重放。 | K001, K002, K003, K007, K008, K009, K010, K011, K012, T002, T003, T004, T005, T007, T008, T009, T012, T015, T019, T022, T023, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| HOOK-01 | 所有 before/after/替换 Hook 安装统一进入发布事务，覆盖已恢复源码与真正交付对象。 | 所有实际 before/after/replace安装进入现有统一发布事务，含正式选中的重建对象。 | T010, T011 | NEEDS_EVIDENCE |
| HOOK-02 | 准备完成后发布、入口回读、跨 CPU 指令缓存同步、失败可追踪，停用与跳板释放遵循仍可能被执行的生命周期。 | 先准备后发布、入口回读与跨CPU缓存同步；停用/退役后等待真实在途排空。 | S005, T010, T011 | NEEDS_EVIDENCE |
| HOOK-03 | 分支差值先按完整宽度校验再编码，编码失败不得发布；近/远分支、超界、回滚均有运行验证。 | 完整宽度校验分支差值后编码，近远跳转和越界失败均保持未发布/可恢复。 | T010, T011 | NEEDS_EVIDENCE |
| RELOC-01 | 统一指令重定位与语义；覆盖 PC 相对寻址、B/BL、条件分支、CBZ/CBNZ、字面量、原指令调用和支持范围。 | 统一受支持指令的 PC相对重定位与原调用语义，逐种指令和不支持边界验证。 | T010, T011 | NEEDS_EVIDENCE |
| INIT-01 | 明确解析、通信、凭据、内存、Hook、模块和开机服务的初始化依赖；避免重入、半初始化和无期限等待。 | 以真实事件生产端与消费者明确解析/通信/凭据/内存/Hook/模块依赖；单boot去重且初始化失败可观测。 | B001, B002, B003, B004, B005, B006, B007, B008, D005 | NEEDS_EVIDENCE |
| SDK-12 | 深度审计现有不合理实现，在逐项行为验收前不标为已解决 Panic；每类修复具备复现、失败边界和回归证据。 | 每类 Panic修复保留最小失败复现、最早分歧、生命周期边界与实际发布回归；不凭静态审计结算稳定性。 | S005 | NEEDS_EVIDENCE |
| SU-01 | 对齐KSU授权语义，形成稳定UID授权和传统su获取Root的完整路径，兼容su、su -c及交互调用，正确返回退出码和stdout/stderr；保留Ultra现有底层与功能。 | 沿固定KSU授权语义完成传统 su、su -c与交互的真实 UID/输出/退出回执，保全Ultra后端。 | U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| SU-02 | 已授权调用无需每次查询 Manager/额外进程；测试管理器关闭、未启动、进程重建和重启后执行。 | 合法持久授权在Manager关闭、未启动、重建和设备重启后独立工作，不增加逐调用Manager查询。 | B001, B002, B003, B004, B005, B006, B007, B008, D005, T017, T018, T019, T020, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| SU-03 | 支持32位软件通过传统su获取Root，兼容32/64位应用混合使用，验证入口发现、compat请求、UID、参数/结果宽度及ABI；验收必须包含实际32位应用的su命令与交互执行。 | 真实32位应用经过传统SU入口与compat链，和64位混用验证宽度、UID及交互。 | U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| SU-04 | 授权/拒绝/撤销/重复授权、共享 UID、多用户、包更新和并发的身份与持久化结果一致；减少授权故障。 | 授权/拒绝/撤权/重复、多用户/sharedUID、包更新及并发均绑定fullUID和持久签名身份。 | K001, K002, K003, K004, K005, K006, K007, K008, K009, K010, K011, K012, T002, T003, T004, T005, T007, T008, T009, T012, T015, T019, T022, T023, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| SU-05 | 未授权应用不获得Root环境、SDK、Key或其他应用授权状态等新增可观测信息；不因SU适配增加调用耗时或改变调用时序，不新增Manager/额外进程查询、IPC或等待。按固定KSU基线及同设备原始环境分别验证授权/未授权路径，记录延迟分布与测试条件。 | 普通未授权视角与授权后执行分账，环境差分及冷暖时延同机固定KSU对照。 | D006, D007, D008, K004, K005, K006, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| SU-06 | 32位软件与传统su兼容不增加SELinux策略修改或挂载；未授权应用视角不新增可见的Root相关文件、进程、属性或环境状态。对齐KSU授权语义，Ultra保留既有低暴露边界，逐项做环境差分验收。 | 32位兼容不新增普通未授权可见的策略/挂载/文件/进程/属性，逐实际观察面验收。 | D006, D007, D008, K004, K005, K006, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| TRUST-01 | 单独设计并实施 SDK 与 MapleSU 身份/签名绑定：信任根、系统早期开机、升级、签名轮换、卸载重装、旧key迁移/撤销、故障恢复和旧模块兼容。 | 复用受保护安装来源和签名身份，升级/轮换/撤销/卸载重装/旧资料迁移均有明确结果。 | K001, K002, K003, K004, K005, K006, K007, K008, K009, K010, K011, K012, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011 | NEEDS_EVIDENCE |
| TRUST-02 | 稳定通道后去除不必要Key、手动配对、重复注入和打开Manager才启动的依赖；必要认证与UID授权由明确身份和持久授权机制承担，实测无Manager冷启动及升级/重装路径。 | 标准路径无Key/手配对/重复注入/Manager冷启依赖；必要身份和UID授权由现有真实主体承担。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, D003, D009, K001, K002, K003, K007, K008, K009, K010, K011, K012, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011, S003, S004, T017, T018, T019, T020 | NEEDS_EVIDENCE |
| OWN-01 | SDK 内部命名与实现逐层归属 MapleSU，外部 SKRoot 符号/格式/数字码/持久化资料集中兼容，不破坏原 Ultra 模块。 | 内部实现使用MapleSU归属，外部SKRoot符号、格式、数值码与持久输入集中兼容。 | S006 | NEEDS_EVIDENCE |
| OWN-02 | 错误码参考 KSU 的可解释处理语义，保留原始原因、阶段和真实返回值，统一 SDK→bridge→Manager 映射。 | SDK→bridge→Manager保留原始code、阶段、原因和effects，再映射可执行用户结果。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, D010, S006, T001, T002, T003, T004, T005, T006, T007, T008, T009, T012, T013, T015, T016, T019, T021, T022, T023, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| OWN-03 | 减少重复检查、虚假未就绪、空泛提示；将正常/重试/不支持/失败清晰区分，禁止把非零一律当成功。 | 明确正常/未就绪/重试/不支持/失败与未知；减少重复检查且不合成成功。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, T002, T003, T004, T005, T007, T008, T009, T012, T015, T019, T022, T023, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| OWN-04 | KSU真实argv、stdout、stderr、完整退出码与执行阶段通过backend/bridge/Manager回传，避免压为Boolean或泛化提示；用户获得必要进度和结果日志，完整参数与诊断进入开发日志并沿用凭据脱敏。Normal、Ultra初次修补与重修补均验收。 | Normal真实ksud及Ultra回传完整argv/stdout/stderr/exit/stage，前端进度与开发诊断分层。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| MOD-01 | 核对 Normal/Ultra/越狱每个功能的实际后端，不把回退到其他通道成功视为目标 SDK 等价。 | 逐功能核 Normal/Ultra/临时越狱实际后端；路由回退结果与目标SDK结果分别记录。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-02 | Ultra 对齐 KSU ZIP 安装逻辑、环境变量、BusyBox/ksud 使用、安装回执和脚本阶段，定位真实 ksud 非零原因。 | 固定KSU ZIP安装器参数、环境、BusyBox/ksud与脚本回执，定位真实非零首因。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-03 | Normal 与选定本仓 KSU 基线在模块安装/更新/启禁/删除、脚本与启动行为上等价，同时保留 MapleSU 自有功能。 | Normal按固定KSU源码跑安装更新启禁删除及脚本启动全生命周期，保全自有功能。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-04 | Ultra 维持原生 Ultra 模块、SKRoot 外部 ABI、原模块列表/回调/状态/WebUI 的兼容。 | 原生Ultra entry/回调/描述/状态/WebUI各自保持外部兼容；旧静态SDK ELF需迁移时准确识别。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011, T017, T018, T019, T020, W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | NEEDS_EVIDENCE |
| MOD-05 | SDK/helper与MapleSU模块后端共同正确执行post-fs-data.sh、post-mount.sh（上游与模块支持时）、service.sh、boot-completed.sh及所选KSU基线支持的其他模块/公共脚本阶段，分别对齐真实触发事件、先后顺序、同步/异步与阻塞语义、执行身份/环境、次数及失败处理；属性/挂载等声明能力与Ultra环境边界冲突时逐项评估，不以新增全局策略/挂载换取表面通过。脚本安装与实际开机调度分离，模块声明和旧Ultra原生回调契约均保全。 | 按固定KSU实际支持的启动事件、顺序、同步/异步、执行环境与失败语义调用脚本和原生回调。 | B001, B002, B003, B004, B005, B006, B007, B008, D005, M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-06 | Ultra模块及其开机脚本由SDK/helper与启动链在对应阶段触发，执行时刻对齐固定官方KSU基线，不依赖打开/恢复MapleSU、Activity resume、Manager进程/在线响应、解锁后的首次页面访问或列表刷新；Manager仅提交管理操作与读取实际状态，不补跑已错过启动阶段冒充等价。逐脚本以同一boot会话的单调时间/事件顺序和真实效果验收，覆盖首次安装后生效时点、Manager从未启动的冷启动、未解锁与已解锁阶段、连续重启、升级/更新、禁用/卸载/开机收尾、重复事件不重复执行及中途失败；保持Ultra既有环境边界，不新增可见挂载、全局SELinux修改、属性或额外常驻判定进程。设备观测用的临时测试标记与生产运行机制分开，环境/时延差分按SU-05/06与QA-07记录。 | 启动链直接调度各模块阶段及卸载收尾，Manager只提交动作和读状态，缺失阶段不在页面补跑。 | B001, B002, B003, B004, B005, B006, B007, B008, D005, M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011, T017, T018, T019, T020 | NEEDS_EVIDENCE |
| MOD-07 | 固定 Zygisk-Next 的具体版本、原包与测试操作，验收刷入、重启、注入实际效果、禁用/更新/卸载，进程存在仅是中间证据。 | 固定官方Zygisk-Next原版/依赖，逐安装、注入实际效果、更新、禁用和卸载验收。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-08 | 同样固定 LSPosed 的具体发行与依赖，验证实际作用和开机生命周期；不从 lspd 进程存在推断可用。 | 固定LSPosed实际发行和依赖，验证zygote/目标应用实际作用及开机生命周期。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-09 | 建立现有全部 KSU 模块包及其声明能力的回归语料，逐包记录安装与运行；“全部兼容”以明确语料、版本和能力范围判定。 | 对现有全部固定KSU原包逐包登记版本、能力和全生命周期，支持结论限定于实际语料。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| MOD-10 | WebUI完整契约对齐：支持内联打开（管理器内嵌WebView）、独立页面及现有其他打开入口，保持UI布局；脚本webroot/AssetLoader、Ultra原生SDK/loopback HTTP及既有代理形式分别正确路由。固定实施与终验时选定的官方KSU源码提交、JS包和实际桥版本，逐项对齐全部WebUI入口、直接ksu桥、旧同步/回调与官方JS封装：exec/spawn的cwd/env、errno/stdout/stderr、事件/异常/取消及并发语义，toast/fullScreen/enableEdgeToEdge/moduleInfo/listPackages/getPackagesInfo/exit，以及当前上游io/pm、外部打开、保存与分块下载等完整接口和嵌套方法；以真实上游清单为准，不限于本仓旧副本。覆盖静态/SPA资源、fetch与页面存储、文件选择/下载、对话框、页面内跳转及上游实际支持的Intent/深链/快捷方式参数；保留原Ultra模块契约与origin/权限边界。各模式/宿主验证实际页面、关键交互和命令结果，覆盖冷/暖打开、返回、重复请求、切后台、旋转/进程重建、主题、WebView版本、模块更新/卸载与渲染进程失效；处理真实生命周期根因，禁止仅吞异常、显示空白或统一外部跳转代替兼容。关联CASE-13/18、MOD-04、SDK-06、API-03、OWN-04、FINAL-07/09。 | 按固定官方完整JS/桥/API清单，脚本与原生路由逐宿主、双主题、并发和页面生命周期验证真实交互。 | W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | NEEDS_EVIDENCE |
| JB-01 | 从 SDK/通道路由根因修复越狱模式“su 入口尚未就绪”，验证 lease/carrier/授权与实际命令执行。 | 沿provider→gate→carrier/lease→SDK找到真实首错，并以传统SU实际UID闭环。 | L003, M003, P001, P002, P003, P004, P005, P006, P007, U001, U002, U003 | NEEDS_EVIDENCE |
| JB-02 | 越狱模式模块安装和启动正常；修复始终等 Manager、重试/超时/会话失效及错误回执逻辑。 | 临时模式模块安装、执行和启动保留真实会话/超时/失败语义，消除等待Manager依赖。 | L003, M003, P001, P002, P003, P004, P005, P006, P007, U001, U002, U003 | NEEDS_EVIDENCE |
| JB-03 | 覆盖越狱临时态与持久 Ultra/Normal 的交接、退出、进程失效、设备重启和既有恢复行为。 | 临时态与持久Normal/Ultra交接、退出、进程失效和重启按现有合同恢复。 | L003, M003, P001, P002, P003, P004, P005, P006, P007, U001, U002, U003 | NEEDS_EVIDENCE |
| API-01 | Root 命令、标准 su、会话/取消、退出码、stdout/stderr、交互和授权回执完整。 | 逐Root/标准su/会话/取消/交互/授权回执核公开API与真实消费者的输出所有权和返回码。 | D010, T001, T006, T013, T016, T017, T018, T019, T020, T021, U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| API-02 | 环境安装/卸载/升级、状态/版本、恢复和模式切换保留实际语义。覆盖同版本不同构建升级；区分真实授权失败、SDK忙、取消与身份变化，不把准备阶段合成AUTH错误阻断合法升级。 | 环境安装卸载升级、状态版本、恢复切换逐入口保留真实语义；同版本不同构建升级不合成AUTH。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011 | NEEDS_EVIDENCE |
| API-03 | 模块常规/一次性安装、描述/列表、启禁/更新/卸载、回调和 WebUI 各自验证。 | 常规与一次性模块、描述列表、启禁更新卸载、回调与WebUI分别调用和执行。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011, W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | NEEDS_EVIDENCE |
| API-04 | 授权增删列清、日志开关/读清、Root 诊断、自检、已暴露设备专项和工具接口保持原功能边界。 | 按当前公开头与调用清单验证授权增删列清、日志开关读清、诊断自检与工具接口。 | D010, T001, T006, T013, T016, T021 | NEEDS_EVIDENCE |
| API-05 | Lite 兜底、寄生/迁移、隐藏模式和其他既有产品能力按当前调用路径盘点，防止清理或改名误删。 | 沿当前路由保留Lite兜底、寄生迁移、隐藏和既有产品入口，改名清理不删除功能。 | L001, L003, P001, P002, P003, P004, P005, P006, P007, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011 | NEEDS_EVIDENCE |
| QA-01 | 每个改变的功能具有必要主机/模拟/实机证据；并行复用设备采样，避免低价值重复回归。 | 每改变功能按最小必要层级留源/host/产物/设备证据；有效且输入相同的既有证据不重复跑。 | D006, D007, D008 | NEEDS_EVIDENCE |
| QA-02 | 跨镜像布局、无 BTF、错误 BTF、混合宽度与边界样本，覆盖真实目标内核系列。 | 实际目标跨布局、无/错BTF、宽度及边界分层，逐机型配置记录覆盖。 | D006, D007, D008, I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| QA-03 | 并发线程/fork 的 Root/回执、Hook 安装/失败恢复/停用/生命周期实机验收。 | 真实并发线程/fork的Root、回执、Hook发布/失败/停用及生命周期独立实测。 | D006, D007, D008, K001, K002, K003, K007, K008, K009, K010, K011, K012, T002, T003, T004, T005, T007, T008, T009, T012, T015, T019, T022, T023 | NEEDS_EVIDENCE |
| QA-04 | SDK/helper/backend/镜像/Manager 的新旧配对矩阵，验证支持的升级路径及不兼容时原状态完整。 | SDK/helper/backend/镜像/Manager新旧配对逐支持升级矩阵与不兼容恢复验证。 | A001, A004, B001, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011, U008 | NEEDS_EVIDENCE |
| QA-05 | 模拟刷写覆盖容器回封、目标范围、短写/中断/回滚、多分区、重复升级和原 boot 恢复。 | 模拟分区写覆盖短写、中断、多分区、回封回读、重复升级与原件恢复。 | I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| QA-06 | 设备按模式逐项回归授权、模块、开机和恢复，必要刷写才实施；保留测试设备恢复条件。 | 设备按模式逐项授权、模块、冷启和恢复，写入与恢复条件绑定目标身份。 | D006, D007, D008 | NEEDS_EVIDENCE |
| QA-07 | Duck Detector 采用固定包版本及实际结果作观察基准，同时记录挂载、SELinux、属性、可见文件/进程和调用延迟；现有 P0 不运行有副作用的采集。 | 固定Duck包和原环境观察挂载、SELinux、属性、文件/进程及调用延迟，记录实际测试条件。 | D006, D007, D008 | NEEDS_EVIDENCE |
| QA-08 | 最终仅将有足够源码、产物与相应设备证据的需求勾选；其他保留状态、缺少材料和下一步，不宣称全内核无 Panic。 | 勾选必须满足对应源、正式产物与必要设备证据；历史或单机结果不扩成全范围。 | D006, D007, D008 | NEEDS_EVIDENCE |
| QA-09 | 盘点工作区所有实际镜像，按原镜像/已修补镜像/重复内容和容器类型建语料；Ultra逐镜像模拟修补、解析和验证布局与写入范围，不以14组合成测试替代真实镜像遍历。 | 全工作区实际图按内容去重与容器分类，逐图真实修补/布局/范围/回读及失败首因。 | D006, D007, D008, I001, I002, I003, I004, I005, I006, I007, I008, I009, I010, I011, I012 | NEEDS_EVIDENCE |
| QA-10 | Normal离线修补对齐选定最新官方KSU成功CI的实际实现、参数和结果，并保留MapleSU功能；区分仓库旧基线、当前上游与applied，升级前再次确认目标提交。 | Normal文件修补对齐固定官方成功CI实际源码与argv/结果；选定基线升级后重新记录提交。 | A008, I001, I002, I003, I004 | NEEDS_EVIDENCE |
| CASE-01 | Y700四代Ultra未知崩溃重启，多次重启后才恢复 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | B001, B002, B003, B004, B005, B006, B007, B008, D005 | NEEDS_EVIDENCE |
| CASE-02 | Y700四代Ultra隐藏模式启动失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | P001, P002, P003, P004, P005, P006, P007 | NEEDS_EVIDENCE |
| CASE-03 | 隐藏模式端口修复失败，默认18427 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | P001, P002, P003, P004, P005, P006, P007 | FIXED_DEVICE_PENDING |
| CASE-04 | 首装超级用户列表加载失败，多次重启后才恢复 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | U001, U002, U003, U004, U005, U006, U007, U008, U009, U010, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| CASE-05 | 部分应用获取或识别不到SU权限 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| CASE-06a | 刷写被无必要条件阻拦 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011 | FIXED_DEVICE_PENDING |
| CASE-06b | 安装事务连接失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011 | FIXED_DEVICE_PENDING |
| CASE-06c | 补丁集生成失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | A001, A002, A003, A004, A005, A006, A007, A008, A009, A010 | NEEDS_EVIDENCE |
| CASE-07a | Normal离线修补与KSU实际实现不等价 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | A008, I001, I002, I003, I004 | NEEDS_EVIDENCE |
| CASE-07b | Ultra对工作区镜像的布局/模拟修补覆盖不足 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | A008, I001, I002, I003, I004 | NEEDS_EVIDENCE |
| CASE-08 | 玩机兼容模式未与KSU完全等价 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| CASE-09 | 多设备Ultra环境安装失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | B001, B002, B003, B004, B005, B006, B007, B008, D005 | NEEDS_EVIDENCE |
| CASE-10a | SDK与MapleSU身份/安装状态脱节 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| CASE-10b | Ultra频繁SU授权失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | U001, U002, U003, U004, U005, U006, U007, U008, U009, U010 | NEEDS_EVIDENCE |
| CASE-10c | 寄生路径复杂脆弱并增加环境暴露 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | P001, P002, P003, P004, P005, P006, P007 | NEEDS_EVIDENCE |
| CASE-10d | SELinux隐藏加载失败、策略读取失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| CASE-10e | 主页SELinux状态经常缺失或确认缓慢 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | X001, X002, X003, X004, X005, X006 | FIXED_DEVICE_PENDING |
| CASE-10f | 管理器卸载重装显示未安装，但旧授权应用仍有Root | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011, X001, X002, X003, X004, X005, X006 | NEEDS_EVIDENCE |
| CASE-10g | 越狱持续SU入口未就绪 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | L003, M003, P001, P002, P003, P004, P005, P006, P007, U001, U002, U003 | NEEDS_EVIDENCE |
| CASE-11 | KSU模块长期显示“将在下次重启时卸载”，部分模块重启后仍残留 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | FIXED_DEVICE_PENDING |
| CASE-12 | 部分Ultra原生模块总是卸载失败 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | FIXED_DEVICE_PENDING |
| CASE-13 | Ultra环境下部分脚本模块或原生模块WebUI打不开 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | FIXED_DEVICE_PENDING |
| CASE-14 | 部分iQOO/VIVO设备安装环境失败或启用后未取得权限 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | B001, B002, B003, B004, B005, B006, B007, B008, D005 | NEEDS_EVIDENCE |
| CASE-15 | Ultra模块列表偶发加载失败、策略设置读取不可靠、SDK频繁Busy | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | X001, X002, X003, X004, X005, X006 | FIXED_DEVICE_PENDING |
| CASE-16 | Ultra无故显示或进入安全模式 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | B001, B002, B003, B004, B005, B006, B007, B008, D005 | STILL_PRESENT |
| CASE-17 | 指定KSU模块安装/运行/卸载失败，Zygisk-Next安装遇nsfs策略错误 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | FIXED_DEVICE_PENDING |
| CASE-18 | 月虹等模块在Ultra打开WebUI闪退，部分设备兼容性差 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | FIXED_DEVICE_PENDING |
| CASE-19 | iQOO 15越狱模式SU入口未就绪，需验证根因修复能扩展至其他设备 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | L003, M003, P001, P002, P003, P004, P005, P006, P007, U001, U002, U003 | NEEDS_EVIDENCE |
| CASE-20 | Tenc🎮 游戏优化助手-C版_Log测试版在Ultra模式完全不生效 | 沿现有真实入口定位首次分歧，按原验收逐子项记录；保留既有失败保护、回读与恢复合同。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011 | NEEDS_EVIDENCE |
| FINAL-01 | 实际发布SDK的每个成员以及helper、全部payload、backend均由对应可维护源码构建；源码→构建输入→归档/嵌入资源→APK→设备实际加载逐项可追溯。IR、参照二进制和旧对象的过渡替换表全部闭合，不以重打包旧程序计源码完成。 | 发布SDK所有成员、helper/payload/backend从可维护源码构建并闭合旧IR/对象/参照过渡表。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| FINAL-02 | 布局、通信、凭据、Hook、初始化、授权、模块执行各自统一实现；替换功能通过验收后删除重复路径、无必要包装/自修复/回退及失效构建入口，保留外部SKRoot格式的集中边界兼容，内部实现归属MapleSU。提交清退清单与实际无旧入口调用证据。 | 统一现有布局/通信/凭据/Hook/初始化/模块实现，功能验收后清退重复与无效入口。 | L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011 | NEEDS_EVIDENCE |
| FINAL-03 | 消除不必要Key、手动配对、重复注入与打开Manager的启动依赖；必要认证/授权由明确身份与持久授权机制完成。签名绑定方案完成实现并通过早期开机、升级、轮换、卸载重装及旧模块兼容验收，评估报告或候选方案不单独构成通过。 | 身份签名与持久授权真正支持无Key、无手配对及无Manager冷启，并通过升级/轮换/重装。 | D003, D009, K001, K002, K003, K007, K008, K009, K010, K011, K012, L001, L003, R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011, S003, S004 | NEEDS_EVIDENCE |
| FINAL-04 | 每个既有SDK功能都有MapleSU实际调用入口、打包产物身份和验收用例；验证真实发布产物，重点闭合Ultra重修补、授权、隐藏模式、SELinux状态、越狱入口、模块安装及Manager未打开时的开机运行。功能不通过回退到另一旧通道冒充保全。 | 每既有SDK功能绑定真实入口、最终包内组件及相应用例，失败不以另一通道成功代替。 | D001, D002, D004, L002, S001, S002 | NEEDS_EVIDENCE |
| FINAL-05 | 每阶段交付完成项、残留旧实现/过渡产物、源码/交付物/必要设备验证范围和未完成项；全部原有及追加要求逐项取得相应证据后打勾。最终审查实际工作树与发布包，不以接入成功、链接通过或单设备结果缩小总目标。 | 交付已完成、过渡残留、实际源码/包/设备范围和缺口；全部原始与追加条件独立结算。 | D001, D002, D003, D004, D008, D010, S001, S002, S003, S004, S005, S006 | NEEDS_EVIDENCE |
| FINAL-06 | 以`本次策略隐藏故障归档`（开发证据未随公开快照分发：`../Log_Reference/11.14/Selinux/MapleSU_bugreport_2026-11-15_11_45.tar.gz`）为必测回归样本，从SDK源码与MapleSU实际调用链查明根因，评估并实施最合适的统一方案，消除“策略隐藏后端加载失败”及关联策略读取故障；以适配全机型为目标，按目标内核/BTF/配置正向适配，不依赖固定机型指纹。验证真实后端加载、策略读取与隐藏生效，以及冷启动、重启、升级、重装、启停、失败恢复和状态时延；清空提示、强制成功、空结果或仅进入fallback均不构成通过。保持UI布局、原功能和Ultra既有环境边界，遵守SU-05/06与QA-07的时序、SELinux、挂载及可见性约束；提交源码、实际交付物与设备证据，分别列明已测和未测机型/内核组合。 | 用固定SELinux故障原归档闭环后端加载、策略读取与隐藏实际效果，逐目标布局适配。 | B001, M005, R002, X002 | NEEDS_EVIDENCE |
| FINAL-07 | CASE-11/12/13逐项修复并以失败原包及实际发布产物验收：KSU卸载/撤销与开机卸载对齐选定官方实现，重启后实际完成清理且Manager未打开也能收尾；Ultra原生模块卸载回调、资源/文件/状态与应用列表一致，不永久滞留待卸载；原有WebUI从SDK加载到应用导航、页面资源和关键交互均可用。提交源码、交付物及必要实机证据，覆盖失败/重试/重启生命周期，保持原Ultra模块兼容、UI布局与既有环境边界。 | CASE11/12/13用原失败包验证卸载回调、不开Manager收尾和全部WebUI交互。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011, W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | NEEDS_EVIDENCE |
| FINAL-08 | CASE-14与CASE-09的环境安装可靠性按根因交付：提交SakiSU固定基线差分、失败设备/镜像复现、SDK及修补/启动/授权实现修复、实际打包身份、逐镜像模拟修补及必要实机回归证据。目标iQOO/VIVO首次安装后首次启动即正常启用，管理器未打开仍初始化并使已授权32/64位软件经传统su实际取得Root；验证vr.ko及确认关联的加载/通知干扰已消除，未改变无关模块、UI与Ultra既有环境边界。连续重启、升级重修补、槽位切换及失败恢复结果一致；不以新增阻断、等待/重试、静态标记或提示隐藏替代真实启用。已测与未测机型/内核/镜像分别列明，取得相应证据后逐项打勾。 | 原iQOO/VIVO首次安装首次冷启实际Root，SakiSU固定对照和vr.ko关联证据单独核定。 | B001, B002, B003, B004, B005, B006, B007, B008, D005 | NEEDS_EVIDENCE |
| FINAL-09 | 本批CASE-15–20、LAY-08、IMG-08、MOD-10与扩展后的CASE-13逐项交付：模块列表/策略读取可靠且SDK Busy根因有真实并发回归证据；正常路径不误入安全模式且真实保护/恢复保全；8个固定原包（原7包加CASE-20包）及指定Zygisk-Next的安装、开机运行、更新、WebUI与卸载/开机卸载按各自实际能力验收。Tenc游戏优化助手-C版Log测试包按实际配置验证功能生效与开机生命周期；两个月虹WebUI在指定失败设备上正常且保留原模块契约；内联与独立宿主、KSU全部WebUI入口/JS桥及最新选定基线新增接口有逐项调用与生命周期回归，页面、命令结果和资源一致且无对应闪退；iQOO 15越狱路径以真实UID、传统su及模块结果验证，再分设备扩展。init_boot与Linux 4.x分别交付有源码依据的可行性、实现范围和缺口，支持声明须附对应模拟修补/实机证据，不将评估通过当成功能支持。保持全部既有需求、UI与Ultra环境边界，源代码、实际交付物、设备证据分别登记，未覆盖组合明确保留待验收。 | CASE15–20、Linux4.x/init_boot范围、固定8包与指定官方Next、月虹及全WebUI接口逐项分层交付。 | M001, M002, M003, M004, M005, M006, M007, M008, M009, M010, M011, W001, W002, W003, W004, W005, W006, W007, W008, W009, W010, W011, W012, W013 | NEEDS_EVIDENCE |

## 反馈逐项映射

| 编号 | 报告的技术问题 | 关联 CASE | 状态 |
| --- | --- | --- | --- |
| #1 | 测试提交：公测须知全文（自愿参与、须备份、按“现状”提供、欢迎提 Issue / PR） | 独立条目 | NOT_DEFECT |
| #2 | 手机异常发热，有时莫名重启；模块卸载后若未重启，等莫名重启时模块卸载不掉 | CASE-01, CASE-11, CASE-12 | NEEDS_EVIDENCE |
| #3 | 偏移量没有内置，希望作者内置方案支持 | CASE-07b | FEATURE_REQUEST |
| #4 | 5.10 修补刷入后无限重启 | CASE-01, CASE-09 | NEEDS_EVIDENCE |
| #5 | 越狱模式手雷直接重启；用手雷软件可以成功提权，提取不到日志 | CASE-19 | NEEDS_EVIDENCE |
| #6 | 工作环境未知状态，点击修复无效，模块 WebUI 打不开 | CASE-09, CASE-13 | NEEDS_EVIDENCE |
| #7 | 提权不成功 | CASE-19 | NEEDS_EVIDENCE |
| #8 | iQOO 15 版本适配申请 | CASE-14, CASE-19 | FEATURE_REQUEST |
| #9 | 未能使用寄生 | CASE-10c | NEEDS_EVIDENCE |
| #10 | 提权越狱后授权超级用户，对应软件未能正常获取 root 权限 | CASE-05, CASE-19 | NEEDS_EVIDENCE |
| #11 | 刷模块时没有日志打印；需要音量键选择的模块装不进去 | CASE-08, CASE-17 | NEEDS_EVIDENCE |
| #12 | 依旧未能安装极致模式工作环境 | CASE-09 | NEEDS_EVIDENCE |
| #13 | App Profile 中没有“卸载模块”选项 | CASE-08 | FIXED_DEVICE_PENDING |
| #14 | （仅型号，无问题描述） | 独立条目 | NEEDS_EVIDENCE |
| #15 | WebUI 打不开 | CASE-13 | NEEDS_EVIDENCE |
| #16 | 直接安装转面具后重启两遍，开机显示安全模式，去设置修复三四次无效 | CASE-16 | NEEDS_EVIDENCE |
| #17 | 一键安装、卸载原面具后打开 MapleSU 会崩溃重启 | CASE-01, CASE-09 | NEEDS_EVIDENCE |
| #18 | 点到模块里面正常，在外面看说任务脚本执行失败 | CASE-08, CASE-15 | NEEDS_EVIDENCE |
| #19 | 刷入修补 boot 后安装环境显示未能安装、请稍后再试 | CASE-09 | NEEDS_EVIDENCE |
| #20 | 刷完后没有超级用户 | CASE-05, CASE-09 | NEEDS_EVIDENCE |
| #21 | 用自带 DirtyFrag 重启后卡重启 | CASE-19 | NEEDS_EVIDENCE |
| #22 | 未能提权越狱 | CASE-19 | NEEDS_EVIDENCE |
| #23 | 极致无痕模式修补完刷入 boot 不生效，有时弹出安装补丁、安装不了 | CASE-09, CASE-14 | NEEDS_EVIDENCE |
| #24 | 寄生失败，管理器丢权限，寄生应用也没有 | CASE-10c | NEEDS_EVIDENCE |
| #25 | 免解越狱不适配 | CASE-19 | NEEDS_EVIDENCE |
| #26 | 新版面具下载 | 独立条目 | NOT_DEFECT |
| #27 | 设置壁纸后打开任意模块的 WebUI，管理器闪退 | CASE-18 | FIXED_DEVICE_PENDING |
| #28 | 不管 Ultra 无痕还是 Normal 兼容，执行自带越狱都会重启 | CASE-19 | NEEDS_EVIDENCE |
| #29 | 未能刷入任何模块 | CASE-08, CASE-17 | NEEDS_EVIDENCE |
| #30 | 闪退 | CASE-06c | NEEDS_EVIDENCE |
| #31 | Ghostlock 能否自己填偏移；自己解析 boot.img 一直闪退 | CASE-06c, CASE-07b, CASE-19 | NEEDS_EVIDENCE |
| #32 | 手机一直卡在重启界面，怎么救 | CASE-01, CASE-09 | NEEDS_EVIDENCE |
| #33 | 转完面具后进入安全模式，不得给应用超级用户权限，也不得转面具 | CASE-16, CASE-05 | NEEDS_EVIDENCE |
| #34 | 没修补的情况下会卡出底栏 | CASE-04 | NEEDS_EVIDENCE |
| #35 | 寄生后恢复几次，中间疑似寄生未成功就点恢复，一直弹恢复；再点寄生报错“状态校验失败 / 预检宿主” | CASE-10c | NEEDS_EVIDENCE |
| #36 | 刷了修补的 boot 进不去面具，一点进去就卡死 | CASE-01, CASE-09 | NEEDS_EVIDENCE |
| #37 | 安装工作环境一直安装不了 | CASE-09 | NEEDS_EVIDENCE |
| #38 | 安装模块失败 | CASE-08, CASE-17 | NEEDS_EVIDENCE |
| #39 | 用不了 Zygisk，刷不进去，刷进去了也用不了 | CASE-17 | FIXED_DEVICE_PENDING |
| #40 | 以后会支持四系吗 | CASE-07b | FEATURE_REQUEST |
| #41 | 突然死机，可长按电源关机并重启；关机时短暂闪黄，logo 界面启动时间过长 | CASE-01, CASE-09 | NEEDS_EVIDENCE |
| #42 | 未能安装环境 | CASE-09 | NEEDS_EVIDENCE |
| #43 | 超级用户权限列表加载失败，安装工作环境也安装不了 | CASE-04, CASE-09 | NEEDS_EVIDENCE |
| #44 | 内置手雷越狱重启 | CASE-19 | NEEDS_EVIDENCE |
| #45 | 用 KSU 给了 root 权限、点了直接安装，重启后一直卡死 | CASE-09, CASE-01 | NEEDS_EVIDENCE |
| #46 | 频道里面的 tees 模块无效果 | CASE-17, CASE-20 | NEEDS_EVIDENCE |
| #47 | 寄生未能恢复 | CASE-10c | NEEDS_EVIDENCE |
| #48 | 无痕模式下只能刷 initboot 才能获取 root，MapleSU 只能刷 boot | CASE-14, CASE-06a | FIXED_DEVICE_PENDING |
| #49 | 给予 root 权限以刷模块的过程中有概率重启（无痕模式） | CASE-01, CASE-17 | NEEDS_EVIDENCE |
| #50 | 越狱模式手雷重启 | CASE-19 | NEEDS_EVIDENCE |
| #51 | CPU 温度与功耗忽然一瞬间变高，两三秒后恢复，重复；已排除模块，认为是管理器问题 | CASE-15 | NEEDS_EVIDENCE |
| #52 | 用久了会卡死无限重启，刷回原来的 boot 才正常 | CASE-01 | NEEDS_EVIDENCE |
| #53 | 有些模块打开的时候会闪退 | CASE-18 | NEEDS_EVIDENCE |
| #54 | 工作环境修复不了、授权不了 root，寄生隐身好像也用不了 | CASE-09, CASE-05, CASE-10c | NEEDS_EVIDENCE |
| #55 | Ultra 模式下打开管理器会重启 | CASE-01 | NEEDS_EVIDENCE |
| #56 | Zn 模块刷不进去 | CASE-17 | NEEDS_EVIDENCE |
| #57 | 修补 boot 镜像开机提示未安装 | CASE-09, CASE-10a | NEEDS_EVIDENCE |
| #58 | 用不了 DirtyFrag 提权 | CASE-19 | NEEDS_EVIDENCE |
| #59 | 其他的 DirtyFrag 提权都能正常使用、100% 成功 | CASE-19 | NEEDS_EVIDENCE |
| #60 | 工作环境未运行，进行修复、重启后依然如此 | CASE-09, CASE-16 | NEEDS_EVIDENCE |
| #61 | 侧信道问题使用的面具 maplesu | CASE-05 | NEEDS_EVIDENCE |
| #62 | 一解析 boot 就闪退 | CASE-06c, CASE-07b | NEEDS_EVIDENCE |
| #63 | 请适配 6.12 内核的设备；Ultra 模式未能使用，另一模式可用 | CASE-07b, CASE-09 | NEEDS_EVIDENCE |
| #64 | 刷入后卡死重启两次，模块全炸 | CASE-01, CASE-17 | NEEDS_EVIDENCE |
| #65 | 根本用不了，KSU 未能使用 root，直接移植 | CASE-08, CASE-05 | NEEDS_EVIDENCE |
| #66 | 越狱不了 | CASE-19 | NEEDS_EVIDENCE |
| #67 | （问题栏仅填“1”，以 bugreport 为准） | CASE-16 | NEEDS_EVIDENCE |
| #68 | 模块怎么删都删不掉 | CASE-11, CASE-12 | NEEDS_EVIDENCE |
| #69 | 下载 | 独立条目 | NOT_DEFECT |
| #70 | 能否在 MapleSU 面具里加入月虹新出的 Ghostkey 模式 | 独立条目 | FEATURE_REQUEST |
| #71 | 同 #70（措辞相同，再次提交） | 独立条目 | FEATURE_REQUEST |
| #72 | 管理器内置的临时 root 工具一用就重启；用第三方提权工具只能开玩机模式，不得开极致隐藏的工作模式 | CASE-19 | NEEDS_EVIDENCE |
| #73 | CVE-2026-98260 可以提权，MapleSU 却不支持；解析 boot 一直卡住；第三方提权软件未能选 MapleSU | CASE-19, CASE-06c | NEEDS_EVIDENCE |
| #74 | 修补 boot 时闪退 | CASE-06c | NEEDS_EVIDENCE |
| #75 | 越狱不管哪个模式都 100% 重启，但用其他工具可以正常提取 | CASE-19 | NEEDS_EVIDENCE |
| #76 | 打开 WebUI 异常 | CASE-13, CASE-18 | FIXED_DEVICE_PENDING |
| #77 | 刷 MapleSU Ultra 没权限，给不了 root 也刷不了模块 | CASE-09, CASE-05, CASE-17 | FIXED_DEVICE_PENDING |
| #78 | 刷入伪装华为 Mate90 模块后桌面一直闪退、关机，成软砖 | CASE-17, CASE-01 | NEEDS_EVIDENCE |
| #79 | 功能建议：能否把默认改成极痕模式；另咨询自己的越狱直接到玩机模式、想搞无痕模式 | 独立条目 | FEATURE_REQUEST |
| #80 | 崩溃报告（无文字描述） | CASE-18 | FIXED_DEVICE_PENDING |
| #81 | Normal 模式打开 WebUI 崩溃 | CASE-18 | FIXED_DEVICE_PENDING |
| #82 | 在 Maple 里越狱就会重启；用月虹临时 root 后 Maple 仍显示越狱不可用 | CASE-19 | NEEDS_EVIDENCE |
| #83 | 还没更新 | 独立条目 | NOT_DEFECT |
| #84 | 环境检查未完成，点重试也没用 | CASE-09, CASE-10a | NEEDS_EVIDENCE |
| #85 | 想要内测名额 | 独立条目 | NOT_DEFECT |
| #86 | 修补后检测不到环境 | CASE-09, CASE-10a | NEEDS_EVIDENCE |
| #87 | Ghostlock 提权时提权不了 | CASE-19 | NEEDS_EVIDENCE |
| #88 | 异常重启 | CASE-01 | NEEDS_EVIDENCE |
| #89 | 崩溃报告（无文字描述） | CASE-18 | FIXED_DEVICE_PENDING |
| #90 | 网页寄生功能未能正常使用，寄生软件不成功，且只能寄生一部分软件 | CASE-10c, CASE-13 | NEEDS_EVIDENCE |

## 持续约束与追加条件

| 编号 | 条件 | 状态 |
| --- | --- | --- |
| CONSTRAINT-01 | 保留 Ultra SDK 和全部既有功能，保持原 Ultra/SKRoot 原生模块兼容；APatch/KernelPatch 是实现参考和后续对照对象，当前不替换为另一套后端。 | 持续核定 |
| CONSTRAINT-02 | 先评估、明确调用链与验收，再改代码。修改以 SDK、后端、构建、修补、模块和授权为主；UI 布局固定，局部逻辑调整限本需求所必需。 | 持续核定 |
| CONSTRAINT-03 | 前端只显示必要状态、可执行操作和结果；调用链、SDK 诊断、兼容明细、开发证据进入开发日志。日志清晰，以 KSU 的使用流程为参考。 | 持续核定 |
| CONSTRAINT-04 | 目标内核布局由目标镜像的 BTF、访问器语义和只读验证获得；不建立固定内核指纹库，不以镜像哈希识别兼容性。版本仅作诊断；交付物哈希只证明实际打包内容。 | 持续核定 |
| CONSTRAINT-05 | 移除镜像修补、安装事务的独立前置检查和阻断，对齐KSU实际执行流程，不新增“安装检查尚未完成”式门槛；目标解析和执行错误由执行链当场返回，保留回读与失败恢复。文件修补不依赖Root服务、已安装环境或安装事务就绪。 | 持续核定 |
| CONSTRAINT-06 | 保持 Ultra 既有环境边界，避免新增普通用户可见的挂载、全局 SELinux 策略、属性或常驻判定依赖；非授权应用的开销与环境变化需要测量。 | 持续核定 |
| CONSTRAINT-07 | 内部命名、实现与维护归属 MapleSU；既有外部符号、数字错误码、模块格式和持久化输入通过集中兼容层维护，禁止全局替换破坏 ABI。 | 持续核定 |
| CONSTRAINT-08 | 去除不必要SDK Key是必达目标：在现有通道稳定后实施身份/签名与持久授权方案，消除手动配对、重复注入和打开Manager才启动的依赖；独立评估用于确定实施方案，不得替代最终交付。 | 持续核定 |
| CONSTRAINT-09 | 组件维护按独立所有权并行，集成维护管理需求、依赖、边界、证据和验收。不得覆盖已有未提交改动。 | 持续核定 |
| CONSTRAINT-10 | 验收状态只用“已验证”“已实现待设备验证”“尚未实施”“存在阻塞”。编译、静态形态、进程存在、历史设备日志分别记账，不等同功能完成。 | 持续核定 |
| CONSTRAINT-11 | 完成一项并取得该项所需证据后打勾；单台设备、单个内核或部分模块通过，记录其范围，不扩大为全部内核/全部模块通过。 | 持续核定 |
| CONSTRAINT-12 | P0阶段已按只读边界完成，源码、预编译库与备份、链接SDK和设备状态均有基线。后续按用户已授权的P1接入与必要实测推进；正式消费切换、清理和设备写操作分别取得对应验收证据，唯一预编译备份继续保留。 | 持续核定 |
| CONSTRAINT-13 | 最终发布的SDK、helper、payload、backend全部来自对应可维护源码；IR、参照二进制和旧对象仅作过渡/比对。各子系统替换验收后清退重复实现，功能保全与过渡残留逐项记账，终验遵守第10节。 | 持续核定 |
| CONSTRAINT-14 | 环境安装可靠性须由SDK、目标镜像修补和启动/授权链路的根因修复达成：复现失败并证明最早异常环节，修复对应实现，验证首次安装、首次启动与实际Root结果。扩大重试/延时、反复重启、依赖Manager在线、添加包装兜底或抑制错误提示均不单独构成通过；已有失败恢复能力继续保留，验收明确真实设备与目标镜像覆盖范围。 | 持续核定 |
| CONSTRAINT-15 | 优先实现功能并使用已有工具链做聚焦验证，减少重复 SDK/Gradle/APK 全量构建；统一到实际接入节点核验交付身份与必要设备行为。 | 持续核定 |
| CONSTRAINT-16 | 第三方 SU 已可用而 MapleSU/KSU 尚未安装时，默认从当前目标分区快照或用户文件直接生产候选，对齐选定 KSU 的实际直接安装流程。删除按旧管理器身份自动寻找/还原备份的前置分支，不要求原 Manager 在线或备份齐全；必须覆盖其他软件已授 Root、init_boot 已被第三方修补的直接安装，用 KSU 实际目标/路径和真实结果对照，第三方标记本身不构成阻断条件。显式原厂恢复、永久卸载和确需移除旧载体的模式转换各自保留真实来源；具体格式不兼容由修补器返回，不把全部第三方修改宣称为可覆盖布局。 | 持续核定 |
| CONSTRAINT-17 | 模式切换入口点击后立即进入切换页；页面导航不等待安装事务连接、Root/Manager就绪、原件或完整备份。切换执行按实际载体选择最少恢复步骤，不把完整boot/init_boot baseline作为所有切换的统一前提：Ultra载体在init_boot且格式已识别时复用KSU式ramdisk精确恢复/再修补；Ultra载体在boot时先用适用原备份，再尝试源码支持的精确还原。缺少整图备份本身不阻断可证明的原地恢复；真实目标格式/执行错误由操作链返回，写后回读和失败补偿保留。覆盖备份存在/缺失、两种载体、已修补图与第三方SU，分别验收实际分区和启动结果；独立Ultra init_boot内核桥尚未闭合的事实继续记账，不据此虚报支持。关联IMG-03/04/07/08/09、API-02、CASE-06a。 | 持续核定 |
| CONSTRAINT-18 | 零模块启动为SDK/镜像修补的共同必验基线：逐机型/固件/内核/页大小/载体覆盖原图修补、5.2.0已修补图升级与重复修补，首次冷启动在Manager未打开时取得真实内核回执和已授权应用Root；分别定位无Root、真实安全模式、残留标记与状态误报。安全模式修复动作须对应真实原因，验证执行回执、重启后的通道/授权及状态，不以反复重启、清提示或强置Running验收。工作区全部实际boot/init_boot及其实际压缩/签名/损坏边界逐项登记；未取得的设备、镜像类型和启动组合明确待验，不从单机或容器回读推导全机型通过。关联QA-09、CASE-09/16、INIT-01、IMG-08、FINAL-08/09。 | 持续核定 |
| CONSTRAINT-19 | 2026-11-17最新统筹要求覆盖此前小批次验证节奏：先完成拟交付版本全部源码、公共接口、选源与约束收口，再由唯一测试执行统一执行受影响回归、Boot_Research单轮和匹配设备验收。禁止改一点回归一点，不以继续增加小批次、冻结包或重复归集候选代替产品集成。集成维护统筹常驻宿主→P7身份生产端、P3完整独立回执→P4凭据生命周期、独立Ultra init_boot桥、同代正式交付四条关键链。未解除的实际停止范围明确保留，源码未变且证据有效的项目不重跑；减少测试不减最终验收。 | 持续核定 |
| CONSTRAINT-20 | 当前阶段出口依次为：TRUST-02真实宿主/发布位置和producer实际交付，或准确记录具体停止交点；已选源合成一套主线，保留P2改动并清楚列未合入路径；Boot_Research按内容去重仅跑一轮、逐图记录真实成功与首错；产出同代SDK/helper/payload/backend/APK，在匹配设备证明“修补→未打开Manager冷启动自动安装→Manager识别→实际Root/SU”。源码缺口与自动审批停止分别记账，不将尚未实施的架构误写成审批拒绝。状态见[主线集成](SDK-REFACTOR-INTEGRATION.md)。 | 持续核定 |
| USER-ISSUE-01 | Y700四代Ultra未知崩溃/多次重启才恢复、隐藏模式启动失败、默认18427及自定义端口修复、首装超级用户列表失败、部分应用未取得su。 | NOT_ESTABLISHED |
| USER-ISSUE-02 | 无必要修补/安装前置阻断、事务连接失败、补丁集生成失败、其他SU迁移、无备份切换/重修补/恢复与永久卸载。 | NOT_ESTABLISHED |
| USER-ISSUE-03 | 多设备5.2.0修补后无Root、零模块安全模式、修复环境无效、主页/策略SELinux状态缺失或慢、策略后端加载/读取失败。 | NOT_ESTABLISHED |
| USER-ISSUE-04 | 5.2.0同版本不同构建升级AUTH失败、Manager卸载重装误报未安装但旧授权仍在、频繁SU授权失败、模块/策略读取偶发失败与SDK Busy；不得只延长超时或重试。 | NOT_ESTABLISHED |
| USER-ISSUE-05 | Normal模块/兼容问题与真实ksud非零；Ultra原生模块卸载失败、KSU模块重启后长期待卸载、Manager未打开时开机脚本与收尾缺失。 | NOT_ESTABLISHED |
| USER-ISSUE-06 | Zygisk-Next、LSPosed及现有全部固定原包的真实安装/运行/更新/禁用/卸载；Zygisk Next 1.5.3原包、nsfs/validation_failed首因与退出137均按实际证据处理。 | NOT_ESTABLISHED |
| USER-ISSUE-07 | 月虹一键隐藏v5.5.7、月虹游戏助手v1.4.7的WebUI与LocalNavigator not provided闪退，涵盖主题/导航/Activity/WebView生命周期与所有宿主形式。 | NOT_ESTABLISHED |
| USER-ISSUE-08 | iQOO/VIVO首次安装/首次开机无权限，固定SakiSU对照；vr.ko及确有证据相关的加载/通知影响与无关模块分开，按目标实物与正常加载合同处理。 | NOT_ESTABLISHED |
| USER-ISSUE-09 | iQOO 15越狱模式SU入口未就绪，provider→gate→carrier/lease→SDK→UID/SU和模块真实结果闭环；首次进入、Manager关闭/重建及32/64位传统su都要验。 | NOT_ESTABLISHED |
| USER-ISSUE-10 | CASE-20固定Tenc游戏优化助手-C版Log原包的真实效果、配置、开机阶段、WebUI与卸载收尾。路径、module id及SHA以附录原文为准；安装返回0或后台进程存在不代表生效。 | NOT_ESTABLISHED |
| FINAL-TEN-01 | 全部原需求与本文内容均有对应责任、源码和验收状态；不缩减总账的119项与后续追加条件。 | NOT_ESTABLISHED |
| FINAL-TEN-02 | 镜像修补器和关联SDK/启动/安装/状态代码已合成同代主线，旧路径与过渡材料列全；发布用到的SDK/helper/payload/backend均来自可维护源码。 | NOT_ESTABLISHED |
| FINAL-TEN-03 | Normal与指定最新官方成功KSU基线的实际参数/结果/日志、直接安装及模块启动语义对齐，保MapleSU自有功能。 | NOT_ESTABLISHED |
| FINAL-TEN-04 | boot和独立Ultra init_boot的解析、完整修补、实际启动、升级、重修补、切换、恢复/卸载均有对应证据，原boot不变的init_boot路径经过验证。 | NOT_ESTABLISHED |
| FINAL-TEN-05 | NGKI及5.10–6.18全部实际语料完成真实修补与生命周期验证；任何未通过项逐图列出，禁止用部分describe或一个样本通过宣称全部完成。 | NOT_ESTABLISHED |
| FINAL-TEN-06 | `Boot_Research`统一轮完成，保留原图、逐图成功/首错、布局与写入范围、回读、升级/恢复及模拟刷写结果。 | NOT_ESTABLISHED |
| FINAL-TEN-07 | 实际同代新包来源可追溯，匹配设备证明“修补→首次冷启动自动安装/初始化→Manager识别→授权32/64位应用传统su实际取得Root”；零模块与必要模块/安全模式恢复均验收。 | NOT_ESTABLISHED |
| FINAL-TEN-08 | 模式切换、第三方SU迁移、无外部备份、已有原厂备份、A/B槽位及失败恢复结果明确且UI布局不变，没有额外安装资格票。 | NOT_ESTABLISHED |
| FINAL-TEN-09 | 开发日志保留真实参数/阶段/错误，前端只显示用户所需结果；原模块与全部既有功能保全，环境/调用耗时约束有实际观察。 | NOT_ESTABLISHED |
| FINAL-TEN-10 | 发布包、可维护源码和构建入口、逐项需求勾选、逐镜像报告、设备覆盖、清退/残留与恢复说明完整交付。 | NOT_ESTABLISHED |
| ADD-MOD-UPDATE-UNINSTALL-01 | 模块列表显示更新入口更新后直接卸载偶发失败 | NEEDS_EVIDENCE |
| ADD-DIRECT-INSTALL-01 | 新增专项：直接安装准备、提交与目标镜像 | NEEDS_EVIDENCE |
| ADD-CARRIER-MIGRATION-01 | 新增专项：boot↔init_boot双向载体迁移与原像恢复 | NEEDS_EVIDENCE |
| ADD-INSTALLER-RECOVERY-01 | 新增专项：安装失败、断连与结果未知的原任务恢复 | NEEDS_EVIDENCE |
| ADD-MODULE-BACKGROUND-REFRESH-01 | 模块全部动作结束后后台更新列表，保留滚动、搜索和错误状态 | NEEDS_EVIDENCE |
| ADD-DEEPSEEK-REIMPLANT-01 | DeepSeek撤除后再次寄生循环与维护预检超时分类 | NEEDS_EVIDENCE |
| ADD-HOST-SELECTOR-SEARCH-01 | 寄生宿主选择器搜索、过滤与空结果交互 | NEEDS_EVIDENCE |
| ADD-INSTALL-STAGE-SPINNER-01 | 寄生与安装阶段spinner、阶段文案及终态恢复 | NEEDS_EVIDENCE |
| ADD-RAW-KERNEL-MEMORY-01 | 真实RAW内核安装修补内存与流式输出完整性 | NEEDS_EVIDENCE |
| ADD-OPTIONAL-VBMETA-01 | 普通载体迁移缺vbmeta输入与严格分路保护 | NEEDS_EVIDENCE |
| ADD-SDK-PAGE-PERFORMANCE-01 | SDK热点与页面事实读取的调用次数、预算和真实状态更新 | NEEDS_EVIDENCE |
| ADD-CLAIM-ASSET-VERSION-01 | 独立资产版本验收：最终APK manifest.json、claim.so及4raw+2module同代一致性 | NEEDS_EVIDENCE |

## 本轮修改与 PR 验收

| 编号 | 修改 | 正例 | 负例 | 状态 |
| --- | --- | --- | --- | --- |
| NEW-FORWARD-01 | 现代含内核boot使用目标驱动正向修补 | 有/无ramdisk的同一目标内核保持正向路线；正式claim参与完整生成及读回。 | 默认现代boot不先查询参考catalog；真实生成校验失败保留首错，不转init_boot伪成功。 | INITIAL_BLIND_CORPUS_BLOCKED |
| NEW-FACTS-01 | 发布组件ABI事实，移除完整参考镜像 | CRC、namespace、local shape、CFI、profile、session及task proof逐点保留；发布包无target-boot.img/target.btf。 | 未绑定producer失败；事实损坏/旧generation/错ABI拒绝；5.10无BTF不新增BTF前置。 | CURRENT_SOURCE_CHANGE_PENDING_FINAL_BINDING |
| NEW-CLAIM-XZ-01 | claim元数据无损XZ压缩 | 真实builder→Kotlin decoder读回、发布器、最终APK verifier；最终ZIP实际字节测体积。 | decoded32MiB/encoded4MiB/chunk512KiB与8块/XZ32MiB内存限制；超限和损坏拒绝。 | HOST_VERIFIED_FINAL_ARTIFACT_AND_DEVICE_PENDING |
| NEW-LOGS-01 | 环境repair保全既有启动日志 | 实际生产TU旧红1/13、新绿13/13；空日志、在途追加和真实锁受测。 | 符号链接/FIFO/目录/错误owner与IO异常保留真实失败；日志子修不结算整个安全模式CASE。 | HOST_VERIFIED_FINAL_ARTIFACT_AND_DEVICE_PENDING |
| NEW-KPN-PATH-01 | 固定KernelSU WebUI环境表达式兼容 | 实际生产源主机回归31/31；固定上游processOptions同输入差分13/13，脚本文本、输出、退出码与stderr一致；CSP允许模块blob worker。 | 无效变量名、NUL及失效会话仍拒绝；cwd和args按路径及独立参数合同处理，其任意shell表达式不宣称与上游逐字等价；设备KPM尚待验证。 | HOST_VERIFIED_FINAL_ARTIFACT_AND_DEVICE_PENDING |
| NEW-MODULE-CONTRACT-01 | 固定KernelSU模块安装与热处理合同 | 主机契约38/38；真实installer→prepare→commit→hot→run消费8/8，含现代和legacy脚本、目录交换与原目录保留。 | hook退出23、重复请求75及恢复不重放保留；safe/metamodule/路径边界正确skip。主机平台映射不代表Android持久化、namespace、会话或并发已验。 | HOST_VERIFIED_FINAL_ARTIFACT_AND_DEVICE_PENDING |
| NEW-CORPUS-BLIND-01 | 全部研究镜像的无旁证盲测 | 54/54不同内容和61/61路径均已执行首轮；原输入与生产源保持不变，参考整镜像/BTF和历史旁证读取均为0。 | 完整修补通过0/54，读回及还原尚未到达；容量、ABI/CFI、旧补丁恢复、raw入口与纯init_boot上下文逐类保留实际失败，不把清点或识别成功当修补成功。 | INITIAL_BLIND_CORPUS_BLOCKED |
| PR-23 | 真实Ultra身份、Fault与Running判据 | 当前owner/SDK通道证据保留真实Fault及安全保护；合法恢复继续 | 第三方Root、残留prefs与已证absence不冒充Ultra；不复活旧Key分支 | REVIEWED_CORRECTIONS_PENDING_FINAL_BINDING |
| PR-24 | Unknown探测与已证未安装分型 | 仅已证没有Ultra通道继续Normal/KSU实际判定 | timeout、Busy、JNI缺失和畸形身份继续Unknown/故障，不转换未安装 | REVIEWED_CORRECTIONS_PENDING_FINAL_BINDING |
| PR-25 | 自编内核配置按实际路线诊断 | 匹配BTF与现有无BTF路径可按真实配置验收 | MODULE_ALLOW_BTF_MISMATCH不设全局必需，真实module BTF失配给准确诊断 | REVIEWED_CORRECTIONS_PENDING_FINAL_BINDING |
| PR-26 | 未安装结论与安全模式持久化 | 真实negative verdict不被bootstrap覆盖，首个合法同boot保护状态正确保存 | 缺快照、跨boot与mode为空分别处理，不以空nullable条件跳过合法首次状态 | REVIEWED_CORRECTIONS_PENDING_FINAL_BINDING |
| PR-29 | 新增6.6.118 HONOR目标profile | 新release精确选择与源码计数、payload和manifest严格同代 | 相近release不错误匹配；源码profile存在不宣称原设备Root成功 | REVIEWED_CORRECTIONS_PENDING_FINAL_BINDING |

## KPatch-Next 全功能验收

固定正式 v0.0.1 ZIP：SHA-256 `ad4802ef34dd564d3935f07f5d4c8298535ffe2dd8b64dfd30e2036709995039`。内核 KPatch／KPM 能力、模块脚本与 WebUI 宿主逐项分账；`kpatch hello` 未证实可用时，安装成功或网页显示不代表 KPM 已生效。

| 编号 | 功能 | 正例／验收 | 设备 |
| --- | --- | --- | --- |
| KPN-01 | 正式模块、源码与产物身份 | ZIP SHA256=ad4802ef34dd564d3935f07f5d4c8298535ffe2dd8b64dfd30e2036709995039；不把 tag/main 当完全相同的发布产物。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-02 | 安装环境与 arm64 条件 | KSU=true 是兼容用户态事实；ARCH=arm64；Root 为真实 0；未伪造 KSU 内核版本。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-03 | 首次安装与重启激活 | 候选完整、激活后 /data/adb/modules/KPatch-Next 中脚本/bin/webroot 完整；需要重启时展示真实 pending。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-04 | 覆盖更新与 WebUI 热更新 | 旧页会话撤销，新页读取真实活动字节；pending 只表示待激活；更新失败保留一致状态。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-05 | post-fs-data 与公共 service.d | 复制的 status.sh 具有可执行位；同一 boot_id 每项执行一次；状态描述更新。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-06 | 未注入 KPatch 内核的诚实状态 | kpatch hello 实际为空/失败时为 inactive 或未安装；脚本 exit 0 仅是脚本退出。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-07 | service 持久 KPM 加载 | KPM 内核 list/info 与唯一功能探针同时证明已加载；不依赖打开 Manager。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-08 | service 恢复 rehook | 配置、内核状态与功能效果三者一致。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-09 | service 恢复应用排除 | 目标包 hook 效果被排除、对照包仍有；相同应用 ID 的上游语义单独记录。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-10 | 模块状态说明与 module.prop 备份 | description 与内核事实相符；缺 id 时从 module.prop.bak 恢复。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-11 | 管理器禁用、启用、删除与重启 | 模块生命周期与 UI 记录一致；KPatch 已写入内核与模块脚本启停分开记账。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-12 | 卸载脚本清理 | kp-next 数据与 service.d/kp-next.sh 按上游清理，模块列表与目录一致。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-13 | 内联 WebUI 资产与实际桥接 | 本地 JS/CSS/locale 均加载；window.ksu 为真实生产 bridge；无需外部 WebUI App。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-14 | 主页、KPM、排除、设置、返回和退出 | 路由与标题正确；退出释放命令与旧页，返回模块列表。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-15 | 翻译、默认语言、RTL 与持久化 | locale fetch 与 XML 正确；localStorage 语言选择持久化；RTL 布局与翻转图标正确。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-16 | 系统、内核、指纹和 SELinux 信息 | 各字段真实且对应设备；无开发态 mock。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-17 | exec 结果与 root 命令身份 | errno=7，stdout/stderr 完整且分别传递，真实 root 身份；回调只归当前页。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-18 | exec/spawn 的 PATH 继承与 cwd/env | PATH 保留模块目录、平台路径、Maple bin；cwd 生效；普通 env 值保持字面值。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-19 | spawn 数据事件顺序和关闭清理 | 每次 data 在对应 exit 之前可见；exit 只一次；退出后无旧命令影响新页。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-20 | 原生文件选择和 .kpm 过滤 | File 对象与 FileReader 可用；错误扩展名被模块说明；取消无残留 UI。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-21 | 多块并发上传完整性与空文件 | 8 worker 分块拼接后 SHA256/长度与源一致，进度完成；零文件真实零字节。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-22 | 上传取消和失败清理 | part 文件按上游清理，后续上传哈希正确；页面不会用上次部分文件。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-23 | 真实 KPatch hello 和版本 | 实际能力和版本对应 kpimg；无能力时不显示 working。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-24 | kpimg 自带组件元信息 | 实际 kpimg SHA256 与版本/配置绑定；不按模块 tag 推断二进制版本。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-25 | 当前槽位和 boot 载体定位 | KPN 定位的是含 kernel 的 boot；显示当前真实槽位；不把纯 init_boot 当 kernel 目标。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-26 | 真实 boot 解包与内核解析 | cwd、工具与 stdout 解析正确；目标来源和 SHA256 保留。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-27 | 内核条件和冲突检测 | 不兼容配置按上游真实中止；普通 Ultra 可用性与 KPN 可修补条件分开。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-28 | 仅文件修补与保存输出 | kptools 解析输出为真实 KPatch；容器/header/ramdisk 与 Maple implant 必要字节保留；无分区写。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-29 | 当前槽位实际刷写与回读 | 分区前缀回读与新镜像逐字节一致；下次启动 KPatch 与 Maple Ultra 同时实际工作。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-30 | 下一槽位脚本能力与 OTA | _a/_b 正确切换；目标分区与输入对应；不存在的 UI 按钮不凭空列为已验。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-31 | 反修补与 Maple Ultra 保留 | 只撤销 KPatch；Maple Ultra 来源、root、模块仍真实可用；分区回读一致。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-32 | 新增内嵌 KPM 的元数据、参数、事件 | kptools 的实际 extra 条目、args/event 与 UI 完全一致，文件 SHA256 可追。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-33 | 已有内嵌 KPM 编辑与删除 | 最终 extras 与选择一致；保留项未丢失；被移除项不在镜像和下次内核。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-34 | 内嵌 KPM 启动事件实际效果 | 事件、参数、唯一 nonce 与内核 probe 同时证明执行；不只看镜像 extra 列表。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-35 | KPM 动态加载与可观察功能 | kpatch kpm list/info + 功能 probe 共同证明内核执行。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-36 | KPM 列表、数量、信息与刷新 | 名称、作者、版本、参数、数量和描述匹配真实内核；status 脚本执行成功。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-37 | KPM ctl0 参数与结果 | 正式 ZIP 的未转义行为和 main 的 shell 转义修复分别验；功能变化由真实内核状态证明。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-38 | KPM 卸载与效果撤销 | 模块不在内核且效果确实撤销；持久文件按上游 forget 语义删除。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-39 | KPM forget 只取消持久加载 | 当前能力保留、持久文件删除、下次不自动加载；与 unload 分开。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-40 | 持久 KPM 自动加载 | 文件与被加载 KPM 绑定，重启后状态和探针恢复，无 Manager 前置。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-41 | 临时 KPM 与重启后缺席 | 本次有效，persist 无文件，下次内核无该 KPM 与效果。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-42 | 错误 KPM 与失败语义 | 错误有真实 errno/stderr，内核列表与文件状态一致；无虚假完成。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-43 | 应用列表、名称、UID、图标 | 列表完整、真实 UID、图标与包对应；错误读包不伪造空成功。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-44 | 应用过滤、搜索与刷新 | 每项过滤正确；刷新后持久排除状态保留，图标懒加载正确。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-45 | 应用排除切换与 CSV 持久化 | CSV 包名/标志/appId 与上游语义一致，内核 exclude_set 返回与 UI 分开记录。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-46 | 应用排除真实效果及用户边界 | 排除目标实际绕过该 hook、对照仍执行；记录上游 uid%100000 的跨用户 appId 语义。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-47 | rehook 切换、持久化与真实钩子 | 内核状态、持久值、实际 hook 行为一致；操作错误有真实 stderr。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-48 | 重启菜单各 reason | 命令退出/断连与实际设备启动模式一致；recovery 输入熄屏流程正确。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-49 | 外部链接和 action.sh | 链接目标与上游对应；内联 WebUI 正常不依赖外部安装器；浏览器缺失可返回。 | NOT_RUN_CURRENT_FINAL_RELEASE |
| KPN-50 | KPatch 注入后 Ultra 双能力和识别 | 工作模式仍按真实来源为 Ultra；Maple 与 KPatch 的独立功能探针均通过；未因 kpatch 名称误识别 KSU。 | NOT_RUN_CURRENT_FINAL_RELEASE |

## 原验收用例完整索引

以下 142 项保留原完整用例的未执行状态；窄测试仅在其实际覆盖范围内记账。完整 procedure、expected、证据键和关联需求均包含在 JSON 中。

| 用例 | 层级 | 场景 | 状态 |
| --- | --- | --- | --- |
| S001 | source | 所有词法命中处置 | NOT_RUN |
| S002 | source | 实际选源与副本 | NOT_RUN |
| S003 | source | 标准归档Key符号 | NOT_RUN |
| S004 | source | 生成模板与schema | NOT_RUN |
| S005 | source | 旧修复保全 | NOT_RUN |
| S006 | source | 注释与使用示例 | NOT_RUN |
| K001 | kernel-host | 零凭据resident启动 | NOT_RUN |
| K002 | kernel-host | 现有 config/anchor 布局 | NOT_RUN |
| K003 | kernel-host | retirement生命周期 | NOT_RUN |
| K004 | device-negative | 假包或错签Manager | NOT_RUN |
| K005 | device | 同签更新与重装 | NOT_RUN |
| K006 | device-negative | 多用户Manager | NOT_RUN |
| K007 | kernel-host | 安装事件定向撤销 | NOT_RUN |
| K008 | kernel-host | watch与在途竞态 | NOT_RUN |
| K009 | device-negative | 公开前缀与host伪造 | NOT_RUN |
| K010 | device-negative | 旧Key与旧claim重放 | NOT_RUN |
| K011 | kernel-host | user namespace与Root主体 | NOT_RUN |
| K012 | kernel-host | helper实际inode | NOT_RUN |
| T001 | sdk-native | 31命令全覆盖 | NOT_RUN |
| T002 | sdk-native | 新线程默认态 | NOT_RUN |
| T003 | sdk-native | fork会话 | NOT_RUN |
| T004 | sdk-native | exec继承 | NOT_RUN |
| T005 | sdk-native | 并发资源准入 | NOT_RUN |
| T006 | sdk-native | Owner失败不降级 | NOT_RUN |
| T007 | sdk-native | 无回执变更 | NOT_RUN |
| T008 | sdk-native | 部分Root结果 | NOT_RUN |
| T009 | sdk-native | caps/task/procattr | NOT_RUN |
| T010 | sdk-native | ALLOC/WRITE/EXEC | NOT_RUN |
| T011 | sdk-native | publication/shared bool | NOT_RUN |
| T012 | sdk-native | UID事务 | NOT_RUN |
| T013 | sdk-native | 指针与APPEND64 | NOT_RUN |
| T014 | sdk-native | cache身份 | NOT_RUN |
| T015 | sdk-native | cleanup错误 | NOT_RUN |
| T016 | sdk-native | 全部public API | NOT_RUN |
| I001 | image | 33份正式组件修补 | NOT_RUN |
| I002 | image | 重复修补 | NOT_RUN |
| I003 | image | 完整原图恢复 | NOT_RUN |
| I004 | image | AVB容量与压缩 | NOT_RUN |
| I005 | image | raw载体 | NOT_RUN |
| I006 | image | init_boot-only | NOT_RUN |
| I007 | device | init_boot-only上机 | NOT_RUN |
| I008 | device | boot与init_boot双向迁移 | NOT_RUN |
| I009 | image | 离线目标KMI | NOT_RUN |
| I010 | image | CFI和页大小 | NOT_RUN |
| I011 | image-negative | 损坏与不支持图 | NOT_RUN |
| I012 | image | 无prefs导出再安装 | NOT_RUN |
| A001 | device | 全新无Key直接安装 | NOT_RUN |
| A002 | sdk-native | m049 resetprop目录 | NOT_RUN |
| A003 | sdk-native | m050 WebUI安装 | NOT_RUN |
| A004 | device | Refresh已安装环境 | NOT_RUN |
| A005 | device | 环境卸载 | NOT_RUN |
| A006 | sdk-native | 发布IO错误 | NOT_RUN |
| A007 | device | 准备阶段响应 | NOT_RUN |
| A008 | device | 两个入口一致 | NOT_RUN |
| A009 | sdk-native | StorageScope结束 | NOT_RUN |
| A010 | device | 用户取消和返回 | NOT_RUN |
| B001 | device | 未开Manager冷启 | NOT_RUN |
| B002 | device | 连续正常冷启 | NOT_RUN |
| B003 | device | helper无token | NOT_RUN |
| B004 | sdk-native | 阶段重入和重复事件 | NOT_RUN |
| B005 | device | 真实失败保护 | NOT_RUN |
| B006 | device | 安全模式负例 | NOT_RUN |
| B007 | sdk-native | helper更新在途 | NOT_RUN |
| B008 | device | boot锁与崩溃恢复 | NOT_RUN |
| U001 | device | 32位传统SU | NOT_RUN |
| U002 | device | 64位传统SU | NOT_RUN |
| U003 | device | 交互SU与环境 | NOT_RUN |
| U004 | device-negative | 未授权普通应用 | NOT_RUN |
| U005 | device | 授权撤权并发 | NOT_RUN |
| U006 | device-negative | UID重用与错签更新 | NOT_RUN |
| U007 | device | 多用户分身sharedUID | NOT_RUN |
| U008 | device | 授权持久与Manager重装 | NOT_RUN |
| U009 | sdk-native | SU句柄重放 | NOT_RUN |
| U010 | device | profile与命名空间 | NOT_RUN |
| M001 | sdk-native | 模块 entry/callback 同代迁移 | NOT_RUN |
| M002 | sdk-native-negative | 旧ABI识别 | NOT_RUN |
| M003 | device | KSU模块安装生命周期 | NOT_RUN |
| M004 | device | 启动脚本与属性 | NOT_RUN |
| M005 | device | 策略规则 | NOT_RUN |
| M006 | device | 官方Zygisk-Next | NOT_RUN |
| M007 | device | Tenc固定原包 | NOT_RUN |
| M008 | sdk-native | callback失败与卸载Pending | NOT_RUN |
| M009 | device | 无Manager卸载收尾 | NOT_RUN |
| M010 | sdk-native | 日志和工具全部API | NOT_RUN |
| W001 | device | 月虹样本一 | NOT_RUN |
| W002 | device | 月虹样本二 | NOT_RUN |
| W003 | device | Next WebUI | NOT_RUN |
| W004 | device | exec和spawn | NOT_RUN |
| W005 | device | 文件和pm API | NOT_RUN |
| W006 | device | 下载全链 | NOT_RUN |
| W007 | device | 所有宿主路线 | NOT_RUN |
| W008 | device | 页面生命周期 | NOT_RUN |
| W009 | device | 模块更新与卸载 | NOT_RUN |
| W010 | device-negative | 伪造目录FD和非Root loader | NOT_RUN |
| W011 | device | 返回滚动不中断 | NOT_RUN |
| W012 | device | 取消超时与服务回收 | NOT_RUN |
| P001 | device | DeepSeek再次寄生 | NOT_RUN |
| P002 | device | 寄生全流程免Key | NOT_RUN |
| P003 | device | 宿主搜索与动画 | NOT_RUN |
| P004 | device-negative | 宿主包或库变化 | NOT_RUN |
| P005 | device | 隐藏与Manager重装 | NOT_RUN |
| P006 | device | 宿主权限撤销 | NOT_RUN |
| P007 | device | 寄生性能根因 | NOT_RUN |
| X001 | android | JNI与R8接口 | NOT_RUN |
| X002 | android | 分层运行状态 | NOT_RUN |
| X003 | android | 后台列表差异归并 | NOT_RUN |
| X004 | android | 过期结果隔离 | NOT_RUN |
| X005 | android | 主线程与触感 | NOT_RUN |
| X006 | android | 日志错误脱敏 | NOT_RUN |
| R001 | device | 清数据自动恢复管理 | NOT_RUN |
| R002 | device | 旧部署一次迁移 | NOT_RUN |
| R003 | device | 迁移每阶段中断 | NOT_RUN |
| R004 | device | 模式切换重启 | NOT_RUN |
| R005 | device | snapshot还原 | NOT_RUN |
| R006 | device | 提交后清退 | NOT_RUN |
| R007 | device-negative | 伪造恢复记录 | NOT_RUN |
| R008 | device | 正常零回滚 | NOT_RUN |
| R009 | device | 显式恢复原图 | NOT_RUN |
| R010 | device | 丢成功回执 | NOT_RUN |
| D001 | artifact | 全部组件同代 | NOT_RUN |
| D002 | artifact | APK内实际字节 | NOT_RUN |
| D003 | artifact | 秘密槽与参数缺失 | NOT_RUN |
| D004 | build | 两次干净SDK构建 | NOT_RUN |
| D005 | device | Panic正式代稳定性 | NOT_RUN |
| D006 | performance | KSU同机对照 | NOT_RUN |
| D007 | performance | 正常工作量 | NOT_RUN |
| D008 | device | 设备与内核范围 | NOT_RUN |
| L001 | source | 根执行路由 | NOT_RUN |
| L002 | artifact | 旧root-kit打包清退 | NOT_RUN |
| L003 | device | 全部根命令消费者 | NOT_RUN |
| T017 | kernel-host | 已 Root 非 Manager 免 Key 调用 | NOT_RUN |
| T018 | device-negative | 伪造请求与资格不足 | NOT_RUN |
| T019 | sdk-native | 多调用主体线程与 fork/exec | NOT_RUN |
| T020 | kernel-host | 逐操作权限对照 | NOT_RUN |
| T021 | sdk-native | 当前请求编码与耗尽 | NOT_RUN |
| T022 | kernel-host | Manager 重装与 Root 模块 | NOT_RUN |
| T023 | kernel-host | 会话失效与退役 | NOT_RUN |
| M011 | device | 冷启模块无 Manager | NOT_RUN |
| W013 | device | 外部原生WebUI SDK调用 | NOT_RUN |
| R011 | device | 隐藏daemon免Key维护 | NOT_RUN |
| D009 | artifact | SDK 插件宏输出 | NOT_RUN |
| D010 | sdk-native | 38SDK与43JNI迁移项 | NOT_RUN |

每次收敛修改后重新绑定源码及产物身份，再更新相应状态；缺少原镜像、原模块、32 位应用或故障设备的条目继续保留具体证据缺口。
