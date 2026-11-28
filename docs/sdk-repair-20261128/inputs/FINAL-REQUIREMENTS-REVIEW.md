# 最终需求 / Spec 只读复核

2026-11-24。只写本目录，不改生产；未执行正式 native、Gradle、ADB 或刷写。输入身份和41行逐项当前生产路径摘要见 `RESULTS.json`。

## 结论

- 原119个唯一需求、20持续约束、两组10条件仍完整保留。原20组29子项与12追加项均有当前接点，共41行；路径存在不等于行为完整或设备通过。
- 外部WebUI 43文件与最终安装7文件交付已逐字SHA对当前源码复核；结果见JSON。没有用早期交接像覆盖当前后继。
- 本轮发现的是 **2个已知但尚未实施完毕的TaskProfile范围缺口**，不是新运行故障推测。若称全部文档源码已闭合，需要完成或明确保留以下边界。
- Issue #4 end-to-end owner 已发布 SOURCE-READY，但本复核时尚无最终 REPORT/RESULTS。root需收其最终交付；不以第一阶段 `completeIssue4EndToEnd=false` 误判后继没有工作。
- 新正式Release、最终正式组件33图重跑和新同代实机效果仍独立待验；本复核新增完整勾选0。

## 仍未完成的生产合同

### [P1 / 条件支持] Native trace callback仍有额外named-peer资格

`runtime/ultra/identity/kernel/manager_task_profile_runtime.c:108–149` 在lookup不到 `__probestub_sys_enter` / `__bpf_trace_sys_enter` 时，对KCFI直接返回 `-ENOENT`；`:379` 在真正native注册前消费该结果。固定KSU的同类边界使用DDK generated callback type checker，而不要求具名同行stub发射。当前owner的 `task-profiles/ksu-native-contract-next-r1/REPORT.md` 明确只评估了替换方案，`CALLBACK-TYPE-RESULTS.json`证明真实6.6 DDK checker捕获错误签名/接受正确签名，但 `productionChanged=false`。

这不是要求关闭CFI或放宽实际register错误；应使用真实DDK native callback签名合同替换重复资格。当前设备若恰有同行符号仍可能正常，不能从该条件分支直接宣判所有目标启动失败。根源入口和当前SHA见JSON。

### [P2 / 支持范围] PREEMPT_RT仍被两个入口提前否决

`manager_task_profile_runtime.c:60–65/188` 与 `manager_task_state.c:574–578` 仍拒RT。当前 `marker_update` 在raw spinlock与return-probe原子上下文内取得tasklist rwlock；RT下该锁可睡眠。因此只删RT判断并非根修。同步RCU/消费者改造、生产回归仍只有评估，没有实施交付。

当前non-RT cohort可单独构建验收，但不能计作所有配置、全部5.10–6.18与KSU等同稳定。保留真实私有字段事实与内核错误；不新增ROM/PMS/framework或worker前置。

## 原问题与追加项收口表

| 范围 | 当前源码/host情况 | 最终出口 |
|---|---|---|
| CASE-01 Panic | offset156 TLBI修复与旧A/B已有 | 本代安装器镜像、真实SU与新稳定窗口；Y700另验 |
| CASE-02/03/10c，DeepSeek再寄生、搜索、spinner | 同一路Root预检、维护预算、撤除checkpoint、搜索与实际elapsed已接线 | 原DeepSeek撤除→再寄生至少3轮、宿主真实交互、冷暖时延与两主题 |
| CASE-04/05/10a/b/f/g/19，SU/重装身份 | shared scope/fullUID/signature/SDK会话与恢复状态机已有窄证据；TaskProfile范围见上 | 真实32/64应用传统su/交互、授权撤权、共享UID/多用户、Manager重装与未打开冷启；iQOO15单列 |
| CASE-06a/b/c/07a/b/14，直接安装/文件修补/boot↔init_boot/恢复/vbmeta | final-installer 96+3、13生产分支、4component入口host绿；去live-KMI/配对前门，保PRE/回读/补偿 | 正式APK两个入口、仅init_boot写入且boot不变、双向载体迁移、断连原任务恢复与A/B槽位；VIVO固件单列 |
| CASE-08 工作模式 | 进程一次激活，持久事务AwaitingReboot后新boot激活，保跨mode冲突 | 每模式安装/真实Root/模块/镜像/卸载与Normal↔Ultra双向切换 |
| CASE-09/16 安全模式/首启 | helper保护状态与真实producer已有接线；冷启P2已撤临时probe并排空work | 零模块与既有环境未开Manager冷启、连续重启、升级中断、真实保护负例与repair后Root/模块一致 |
| CASE-10d/e/15，策略/Busy/列表后台刷新/SDK性能 | 真实errno、scope、single-flight、静默非resort归并与稳定行key已有 | 同次JNI/SDK请求关联、超时后仍跑、并发授权安装卸载、滚动中返回与分布测量；非限时冒充优化 |
| CASE-11/12，更新后卸载/开机收尾 | SDK callback/child真实结果、PendingRemoval/Removed、boot dispatcher已有 | 原包真实安装更新卸载/撤销、脚本或callback失败、无Manager重启收尾、目录/状态/列表一致 |
| CASE-13/18，月虹/全部WebUI | 原生SDK route、LocalNavigator provider、外部io/pm/ksu_download/exec/spawn两HTTP全链host绿 | 两个月虹、官方Next、内联及外部宿主：资源/真实命令/下载/取消/返回/旋转/重建/更新卸载；指定vivo/WebView单列 |
| CASE-17 KSU模块/Next/nsfs/137 | 固定规则parser/live apply与Next1.5.3 ABI已有窄证据；15处tagged pointer统一 | 原8包+官方Next的真实开机运行与禁用更新卸载；nsfs首错与137真实终止原因 |
| CASE-20 Tenc | 原hook前的publisher→RC→helper→boot/run host链已存在 | 原包SHA d60a337b…全部实际配置效果、多用户/分身、无Manager冷启、WebUI与卸载收尾 |
| claim 5.2.1资产 | ENV/APPEND64/Carrier/bootstrap与root execmem backend已修；正式资产未在本专项生成 | 5.2.1/50201同签R8 APK内manifest/实际.so/SDK/loader/两个catalog及4raw+2module逐字来源 |

## Issues / PR状态

- #4 / PR#8：第一阶段allocator/key已修；13-only producer/INNER fallback/listener后继已SOURCE-READY，最终包待root收取。
- #5 / PR#9：13个data_user及PR遗漏的members_user、destination_user共15处已按真实ARM64 native入口统一，保范围/错误。
- #6.1配置已有，CRC/CFI真实错误继续保真；#6.2 stage→TASK_STATE caps/dispatch已接；#6.3双key与#4后继关联；#6.4 ENV/filename/APPEND64生产消费链已修，root另已完成execmem release backend整合。
- #7：重装恢复/在线内核修复入口有38 JVM及后继窄证据；vivo首阶段真实loader与stderr可见性仍待对应设备。
- PR按本地实际合同适配，不等于远程merge/close；旧初审“未修”状态不覆盖新源码交付。

## 不可被41行表替代的独立出口

LSPosed固定发行与依赖/SHA、Linux4.x可行性/样本矩阵、Duck Detector固定包和未授权/授权环境差分、150ms/300ms原语义、全部公开API（日志读清/授权管理/Root自检/工具）、SDK两次完整干净构建与旧修复保全/清退，仍按119总表独立登记。仅源码路径/1330输入不变/host API同名不代替这些验收。

33份8MiB原图的strip容量A/B为33/33布局绿（6.6同KMI9/9），这是临时r2探针，不是最终组件或跨KMI设备启动。新正式构建后需按same-KMI/跨KMI-layout分账重跑修补、repatch、完整恢复、容量与AVB；最终Release与每台设备效果独立记账。

## root最小续接顺序

1. 收ABI13最终交付并核环境/execmem整合；决定上述native callback/RT源码范围并如实处理，不能笼统称全部文档实现完。
2. 冻结当前源，核SDK交集，分别准备claim schema1 exact与无配对修补schema2 component，两链重叠源同代。
3. root唯一正式native/Gradle/JUnit/R8，核APK签名、版本、实际资产，重跑正式payload33图。
4. root唯一实机，保回滚与原模块/授权；逐项执行原包、模式迁移、SU、模块、安全模式、无Manager冷启与新Panic窗口。其他设备、RT/no-BTF/旧4.x按真实覆盖保留。
