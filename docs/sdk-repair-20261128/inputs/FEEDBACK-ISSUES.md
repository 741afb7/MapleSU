# MapleSU 内测反馈：全部问题回归文档（草稿）

- **编写时间**：2026-11-25（北京时间）
- **数据来源**：机器人数据库 `feedback.db`（表 `feedback`）、10 份深挖报告、2026-11-14 至 2026-11-24 每日汇总、附件日志
- **数据截止**：2026-11-25 23:54:14（最新 #90）
- **补记**：2026-11-26 07:00 补入 #90（2026-11-25 23:54 到达，在 20:00 汇总之后；11 月 25 日汇总已用汇总脚本重跑补入，见 `data/daily/2026-11-25.md`）。#90 归入第 8 组，其余内容未改。
- **状态**：草稿，供逐项回归打勾。**回归结果列全部留空，由你填写；本文不猜你修了哪些。**

## 口径说明

- **已证实**：深挖报告或日志原文确认的结论，附来源。
- **合理推断**：日志证据强、但未经深挖报告确认的判断，明确标注，不当作已证实。
- **未定位**：无深挖报告、无日志或证据不足，不编造根因。
- 日志引用均为逐字摘录，注明来源条目；长的日志只摘触发点前后关键行，并注明“节选”。
- 部分条目的崩溃日志被机器人错存进 `orig_image` / `device_info` 栏（第二条日志消息到达时机器人已处在下一等待状态），数据库原文未改动；本文引用时注明字段来源。
- “无日志”指数据库 `log_text` 为空且无附件日志文件（问题描述本身不算日志）。

## 统计总览

| 项目 | 数值 |
|---|---|
| 反馈总数 | 90 条（#1–#90，2026-11-14 14:46 至 2026-11-25 23:54） |
| 处理状态 | 90 条全部待处理 |
| 累计用户 | 771 人 |
| 镜像因 Telegram 机器人 20 MB 下载上限丢失 | 累计 14 起（只剩文件名，群内重发也取不回，需网盘链接） |
| 有深挖报告的条目 | #56、#66、#67、#73、#74、#76、#77、#80、#81、#84、#86、#89（另 #62 与 #74 同一份深挖报告） |
| 分组数 | 14 组（含“非缺陷 / 信息类”组） |

### 分组一览

| 组 | 涉及编号 | 条数 |
|---|---|---|
| 1 修补 boot 时堆 OOM | #30、#31、#62、#74、#87 | 5 |
| 2 LocalNavigator 崩溃 | #27、#56、#76、#80、#81、#89 | 6 |
| 3 Ultra 恢复身份未验证 | #84、#86 | 2 |
| 4 DirtyFrag 补丁校验失败 | #66 | 1 |
| 5 安全模式 / 启动保护 | #16、#33、#67 | 3 |
| 6 修补后 boot 疑似未生效 | #77 | 1 |
| 7 CVE-2026-98260 / Ghostkey 诉求 | #70、#71、#73 | 3 |
| 8 越狱 / 提权 / 寄生失败与越狱直接重启 | #5、#7、#9、#10、#20、#21、#22、#24、#28、#35、#44、#47、#50、#58、#59、#72、#75、#82、#90 | 19 |
| 9 刷入后重启 / 卡死 / 卡重启界面 | #2、#4、#17、#32、#36、#41、#45、#49、#52、#55、#64、#88 | 12 |
| 10 WebUI / 工作环境 | #6、#15、#18、#43、#53、#54、#60 | 7 |
| 11 安装与模块管理 | #11、#12、#13、#19、#29、#37、#38、#39、#42、#46、#57、#68 | 12 |
| 12 适配请求 | #3、#8、#14、#23、#25、#40、#48、#63 | 8 |
| 13 其他未分类 | #34、#51、#61、#65、#78 | 5 |
| 14 非缺陷 / 信息类 | #1、#26、#69、#79、#83、#85 | 6 |
| **合计** | | **90** |

---

## 1. 修补 boot 时堆 OOM（#30、#31、#62、#74、#87）

**已证实根因**（来源：`feedback-74-oom-deep-dive.md`，针对 #62 与 #74）：

- #62 与 #74 是同一机型 Xiaomi 22127RK46C（socrates）、同一版本 5.2.0（50200）、同一堆上限 256 MB、同一 R8 map id `308ae56999a94ac508a1b35cb1b6e2d0cb53ae30e52cc22af821534f2b8ba011`；两人是不同账号、不同提交，是同一 build 在同一机型上的两次独立复现。
- 两次公共尾部帧完全相同：`fs.i(:8) → zx0.run(:114) → qx2.run(:4) → wp6.run(:3) → tn0.run(:88)`，走同一条后台任务入口；分叉点在 `fs.i` 之上（#62 走 `s5.n / in1.a` 的 HashMap 路径，#74 走 `dn0.n / d04.invoke` 的 ArrayList 路径）。
- 这是应用侧内存问题，不是内核 / 模块 / 越狱问题：两次异常都是 Java 层 `OutOfMemoryError`，堆上限 256 MB 已满；与 DirtyFrag（#66）、安全模式（#67）、越狱重启（#75 等）是不同类别，不要合并根因。
- #74 原 boot.img 未取回（09:55:41 下载失败 "File is too big"，数据库只有文件名），是第 11 起 20 MB 上限致镜像丢失；没有该 boot.img，无法用这份原镜像复现。

**#87（已证实为堆 OOM，依据为数据库 `log_text` 原文，未写深挖报告）**：Ghostlock 提权时修补 boot 过程中 `Object.clone`（经 `KernelSUApplication.b`）分配约 44.7 MB 时 OOM，堆上限 256 MB，同一 R8 map id；原 boot.img 因 20 MB 上限丢失，是第 14 起。

**#30（合理推断，非深挖报告结论）**：同一条解析路径堆 OOM。日志栈为 `Arrays.copyOf → ByteArrayOutputStream.grow → in1.c(:419) → s5.n(:1001) → fs.i(:8) → zx0.run(:114) → qx2.run(:4) → wp6.run(:3) → tn0.run(:88)`，与 #62/#74 的公共尾部帧（`fs.i → zx0.run → qx2.run → wp6.run → tn0.run`）及 `in1 / s5` 帧一致，同一 R8 map id，growth limit 268435456（256 MB）；同提交者 #31 称“自己解析 boot.img 一直闪退”。是否与 #62/#74 同一根因，需你结合源码确认，本文只列证据。

**#31**：同 #30 提交者的关联提问，无独立日志，不计入 OOM 确认例数。

**触发点与关键日志原文（节选）**：

- #62（数据库 `problem` 字段原文，2026-11-19 20:06:46，Xiaomi 22127RK46C，Android 16，5.2.0，线程 main）：
  > `OutOfMemoryError: Failed to allocate a 65552 byte allocation with 286208 free bytes and 279KB until OOM, target footprint 268435456, growth limit 268435456`
  > `at java.util.HashMap.resize(HashMap.java:709)` → `at in1.a(…:110)` → `at s5.n(…:1055)` → `at fs.i(…:8)`
- #74（数据库 `log_text` 字段原文，2026-11-21 09:53:32，同机型同版本，线程 main）：
  > `OutOfMemoryError: Failed to allocate a 14241376 byte allocation with 790816 free bytes and 772KB until OOM`
  > `at java.util.Arrays.copyOf(Arrays.java:…)` → `at java.util.ArrayList.grow(ArrayList.java:…)` → `at d04.invoke(…:1148)` → `at dn0.n(…:793)`
- #87（数据库 `log_text` 字段原文，2026-11-24 21:03:41，魅族 20，Android 16，5.2.0，线程 DefaultDispatcher-worker-3）：
  > `OutOfMemoryError: Failed to allocate a 46885388 byte allocation with 5979776 free bytes and 5839KB until OOM, target footprint 268435456, growth limit 268435456`（约 44.7 MB，堆上限 256 MB）
  > `at java.lang.Object.clone(Object.java:280)` → `at com.maplesu.core.KernelSUApplication.b(…:576)`
- #30（数据库 `log_text` 字段原文，无设备信息，问题仅“闪退”）：
  > `java.lang.OutOfMemoryError: Failed to allocate a 67108880 byte allocation with 25165824 free bytes and 63MB until OOM, target footprint 227097712, growth limit 268435456`
  > `at java.util.Arrays.copyOf(Arrays.java:4275)` → `at java.io.ByteArrayOutputStream.grow(ByteArrayOutputStream.java:120)` → `at in1.c(…:419)` → `at s5.n(…:1001)` → `at fs.i(…:8)`

**回归要点**：在 socrates（Xiaomi 22127RK46C）或同等大小 boot.img 上复测修补 / 解析 boot，全程监测堆峰值；另在 #87 的魅族 20 + Ghostlock 路径与 #30 的提交者设备上复测。通过标准：同一 boot.img 连续 3 次修补 / 解析不 OOM、不闪退，且不再出现 `Failed to allocate … growth limit 268435456`。如需定位 `d04:1148` / `in1:110` / `s5:1055` 对应源码位置，需 5.2.0 的 R8 mapping（workspace 内未找到）。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #30 | 額際我 @SOSO | 未填 | 闪退 | `OutOfMemoryError: Failed to allocate a 67108880 byte allocation … growth limit 268435456`，栈经 `in1.c → s5.n → fs.i` |  |
| #31 | 額際我 @SOSO | 未填 | Ghostlock 能否自己填偏移；自己解析 boot.img 一直闪退 | 无日志（同 #30 提交者的关联提问） |  |
| #62 | 纪兔 @lyjjddd | Xiaomi 22127RK46C，Android 16，5.2.0 | 一解析 boot 就闪退 | `OutOfMemoryError: Failed to allocate a 65552 byte allocation … 279KB until OOM`，`HashMap.resize → in1.a → s5.n → fs.i` |  |
| #74 | 太午 @tw6860 | Xiaomi 22127RK46C，Android 16，5.2.0 | 修补 boot 时闪退 | `OutOfMemoryError: Failed to allocate a 14241376 byte allocation … 772KB until OOM`，`Arrays.copyOf → ArrayList.grow → d04.invoke` |  |
| #87 | sunxc 487 @wszgsjzdddsg | 魅族 20 12.7A，Android 16，5.2.0 | Ghostlock 提权时提权不了 | `OutOfMemoryError: Failed to allocate a 46885388 byte allocation … 5839KB until OOM`，`Object.clone → … → KernelSUApplication.b(:576)` |  |

---

## 2. LocalNavigator 崩溃（#27、#56、#76、#80、#81、#89）

**已证实根因**（来源：`feedback-76-localnavigator-repeat.md`、`feedback-80-81-localnavigator-quadruple.md`、`feedback-89-localnavigator-fifth.md`，另 #27 由本次附件核查补入）：

- 6 次独立复现，同一异常 `java.lang.IllegalStateException: LocalNavigator not provided`，同一 build 5.2.0（50200），同一 R8 map id `308ae56999a94ac508a1b35cb1b6e2d0cb53ae30e52cc22af821534f2b8ba011`，应用侧 67 帧逐帧相同（#89：应用侧 45 帧与 #56 逐字相同；#27 的附件 log.txt 前 48 行与 #89 的 bug.txt 去缩进后逐行相同，尾部仅 Android 框架行号不同）。
- 覆盖 6 机型 × 3 个 Android 大版本：#27 REDMI Turbo 4 24129RT7CC（rodin，Android 17）、#56 Xiaomi 24117RK2CC（Android 16）、#76 一加 Ace5 Ultra PLC110（Android 16）、#80 Xiaomi 22011211C（Android 14）、#81 Xiaomi M786BC（Android 17）、#89 realme RMX8899（Android 17）。Android 17 现为 3 例（#27、#81、#89）；realme 是反馈库首次出现的品牌。
- 触发点至少包括打开 WebUI（#27、#76、#81 三次明确；#81 为 Normal 工作模式；#27 为设置壁纸后打开任意模块的 WebUI）；#56 的问题是 Zn 模块刷不进去，#80、#89 触发操作未知。
- 同一 Compose 导航入口的确定性崩溃，与机型、Android 版本、工作模式无关；与 OOM 组（#62/#74）、#66、#67、#75 均无关，不合并。
- **纠正**：此前深挖报告称“全库只有 #56 与 #76 两条含 LocalNavigator”，是按数据库文本字段统计；#27 的崩溃日志在附件 `log.txt` 内，不在数据库字段中，本次核查附件后补入。#27 是最早的一次复现（2026-11-15 06:28:40，早于 #56 的 2026-11-16 20:29:08）。
- #81 是全库第一份两个镜像都完整取回的反馈：原镜像 init_boot.img 与修补后 maplesu-patched-f6916f7b66bc.img 各 8,388,608 字节；但这对镜像与本次 UI 崩溃无直接关系（崩溃在应用 UI 层），其价值是证明 8 MB 镜像可完整取回、印证 20 MB 上限丢失机制。
- 未知：无 5.2.0 R8 mapping，无法把 `c04:174 / hn6:21 / dv2:3` 还原为源码方法名；LocalNavigator 缺失的具体页面未知。

**触发点与关键日志原文（节选）**：

- #27（附件 `AgADSSIAAtfjyFc_log.txt`，5,849 字节，76 行完整，2026-11-15 06:28:40，Xiaomi 24129RT7CC (rodin)，Android 17，5.2.0，线程 main）：
  > `异常: java.lang.IllegalStateException: LocalNavigator not provided`
  > `at c04.invoke(…:174)` → `at hn6.getValue(…:21)` → `at dv2.a(…:3)` → `at r30.N(…:16)` → `at km1.k(…:5)`
  > 问题栏原文：`设置壁纸后打开任意模块的WebUI后管理器会闪退`
  > 注：24129RT7CC 为 REDMI Turbo 4 的国行型号（代号 rodin），与提交者填写的机型一致；该文件与 #89 的 bug.txt 同为 5,849 字节但 md5 不同，前 48 行去缩进后相同，尾部行号不同，不可混同。
- #56（`feedback-56-full-crash-log.txt`，数据库 `log_text` + `orig_image` 两字段拼接还原，2026-11-16 20:29:08，Xiaomi 24117RK2CC，Android 16，5.2.0，线程 main）：
  > `异常: java.lang.IllegalStateException: LocalNavigator not provided`
  > `at c04.invoke(…:174)` → `at hn6.getValue(…:21)` → `at dv2.a(…:3)` → `at r30.N(…:16)` → `at km1.k(…:5)` → `at o27.b(…:121)` → … → `at d9.onAttachedToWindow(…:245)`
- #76（数据库 `log_text` 字段原文，2026-11-21 12:23:21，一加 Ace5 Ultra，Android 16）：同一异常、同一栈顶、同一 map id；两份日志各 48 行，46 行逐字相同，仅时间与机型不同。
- #80（数据库 `problem` 字段原文，2026-11-21 22:37:30，Xiaomi 22011211C，Android 14）：同一异常、同一栈顶；问题栏即整份崩溃报告，无文字描述。
- #81（数据库拼接，2026-11-21 22:43:42，Xiaomi M786BC，Android 17）：同一异常；问题栏原文 `MapleSU_Normal 工作模式，打开 wedui 崩溃`（wedui 为 WebUI 笔误）。
- #89（附件 `AgAD-SMAAjY8OFQ_bug.txt`，5,849 字节，67 帧完整，2026-11-25 12:47:36，realme RMX8899，Android 17）：
  > `异常: java.lang.IllegalStateException: LocalNavigator not provided`
  > 应用侧 45 帧 `c04.invoke(:174) → hn6.getValue(:21) → dv2.a(:3) → … → d9.onAttachedToWindow(:245)` 与 #56 逐字相同；框架尾部与 #81 同为 `TraversalCallback.onVsync`

**回归要点**：在 Android 14 / 16 / 17 各至少一台设备上复测打开 WebUI（Normal 与 Ultra 两种模式）与刷入模块流程。通过标准：上述路径各执行 5 次，不再出现 `IllegalStateException: LocalNavigator not provided`。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #27 | Karin @mcxiao10 | REDMI Turbo 4（附件日志内机型 Xiaomi 24129RT7CC rodin），Android 17，HyperOS 4，5.2.0 | 设置壁纸后打开任意模块的 WebUI，管理器闪退 | `IllegalStateException: LocalNavigator not provided`（附件 log.txt，2026-11-15 06:28:40，76 行完整） |  |
| #56 | 爱 人 | Xiaomi 24117RK2CC，Android 16，5.2.0 | Zn 模块刷不进去 | `IllegalStateException: LocalNavigator not provided`，`c04.invoke(:174) → hn6.getValue(:21) → dv2.a(:3)` |  |
| #76 | 花开 @huakai6666 | 一加 Ace5 Ultra PLC110，Android 16，5.2.0 | 打开 WebUI 异常 | 同上（与 #56 仅时间、机型两行不同） |  |
| #80 | 小叶 7 @weilairr | Xiaomi 22011211C，Android 14，5.2.0 | 崩溃报告（无文字描述） | 同上；触发操作未知 |  |
| #81 | 可口雪碧 @klappmk | Xiaomi M786BC，Android 17，5.2.0，Normal 模式 | Normal 模式打开 WebUI 崩溃 | 同上；`MapleSU_Normal 工作模式，打开 wedui 崩溃` |  |
| #89 | 976936（无用户名） | realme RMX8899，Android 17，5.2.0 | 崩溃报告（无文字描述） | 同上（附件 bug.txt，67 帧完整）；触发操作未知 |  |

---

## 3. Ultra 恢复身份未验证（#84、#86）

**共同点（已证实）**：两案均 RECOVERY_ROOT_NOT_VERIFIED、导出 bugreport 时均 `MANAGEMENT_CREDENTIAL_UNAVAILABLE`、Root shell false、Runtime API -1、LKM false、Manager 5.2.1。但结论不同，**不要简单合并为同一问题**；是否同一根因，需看探针与策略后端实现才能确认。

### 3a. #84：UNKNOWN_TIMEOUT

**已证实根因**（来源：`feedback-84-environment-check.md`）：

- 环境检查不是“一直转圈等不到结果”，而是三次探测都在 1 秒内返回了同一个 UNKNOWN 结论，重试每次都走同一条失败路径。
- 卡住的探针是 Ultra 身份恢复（recovery），不是 su 探针：每次检测前 `MapleSUUltraIdentity: environment identity recovery=RECOVERY_ROOT_NOT_VERIFIED`；`ultra-boot-app.txt`：当前 bootId `ce31eca4-…` 下 attemptedBoot=null、observedBoot=null、result=NOT_OBSERVED，即本次启动下 Ultra 环境从未被观测到。
- 存在一条矛盾信号：su 探针报 USABLE，但 root 实际不可用（`collection.txt`：`Root shell available: false`；basic.txt：`MapleSU Runtime API: -1`、`LKM: false`；另有 `7kkernel-ksu: no driver fd and late install failed`，errno=38 ENOSYS）。USABLE 只是某一 su 路径可用，不等于 Ultra 恢复身份已验证，两者不要互相推翻。
- 生成 bugreport 时暴露凭据缺失（发生在导出诊断日志时，不是环境检查本身）：`KsuCli: 创建 root shell 失败，退回占位 shell（本次运行没有 root 能力）`。
- 日志包里另有一次独立的应用崩溃（19:01:29，PID 12911）：`FATAL EXCEPTION: main / java.lang.OutOfMemoryError: pthread_create (4112KB stack) failed: Try again`，堆栈顶为 Oplus BoostFramework 线程创建失败，是系统侧线程创建失败，**与 #62/#74 的修补 boot 时堆 OOM 是两类不同的 OOM，不要合并**。
- 包内无越狱尝试记录，无法定位到 exploit 阶段。待查线索（非结论）：`process-exits.json` 有一条 bugreport 捕获前约 74 分钟的 `[FORCE STOP] due to clear data`，是否清过数据未证实。

**触发点与关键日志原文（节选，出自 #84 bugreport 内 `logcat.txt`，仅 442 行）**：

> `MapleSUStartup: stage=environment_check` … `code=ENV_UNKNOWN_TIMEOUT detail=PROBE_FAILED:ULTRA_RECOVERY_PROBE_UNKNOWN mode=NONE inherited=false probes=ksu,ultraCurrent,su,ultra`，elapsedMs 分别为 848、529、648
> 每次检测前 `MapleSUUltraIdentity: environment identity recovery=RECOVERY_ROOT_NOT_VERIFIED`
> `KernelSU: Ultra startup … terminal ready=false kind=recovery reason=DETECTION_PENDING`
> 出自 `ultra-boot-app.txt`：当前 bootId `ce31eca4-…`，`attemptedBoot=null observedBoot=null result=NOT_OBSERVED`，`backupPath=/data/adb/maplesu/recovery/rootkey` 但 `backupResult=NOT_OBSERVED`
> bugreport 生成时 19:04:23.296：`KsuCli: 创建 root shell 失败,退回占位 shell(本次运行没有 root 能力)`；`java.lang.IllegalStateException: MANAGEMENT_CREDENTIAL_UNAVAILABLE` 于 `RootManagement.startCredential (RootManagement.kt:67)`

**回归要点**：在同款设备（一加 PLQ110，Android 16，内核 6.6.118）+ Manager 5.2.1 上复测环境检查。通过标准：环境检查一次跑完且不再 1 秒内判 UNKNOWN，Ultra 恢复身份验证通过；另核对恢复密钥文件 `/data/adb/maplesu/recovery/rootkey` 是否存在、可读。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #84 | Liu Ai @wangouguair | 一加 PLQ110，Android 16，内核 6.6.118，Manager 5.2.1 | 环境检查未完成，点重试也没用 | `code=ENV_UNKNOWN_TIMEOUT detail=PROBE_FAILED:ULTRA_RECOVERY_PROBE_UNKNOWN`（三次，elapsedMs 848/529/648）；`recovery=RECOVERY_ROOT_NOT_VERIFIED`；`result=NOT_OBSERVED` |  |

### 3b. #86：NOT_INSTALLED

**已证实根因**（来源：`feedback-86-environment-check.md`）：

- 环境检查两次失败，结论逐字相同：NOT_INSTALLED，不是 UNKNOWN——检测跑完了全程并给出否定结论，不是 #84 那种 1 秒内判 UNKNOWN 的未完成状态。
- Ultra 探针的失败点比 #84 更具体：模块文件打不开。两次检测中 `MapleSUUltraProbe: Ultra Root probe failed sdkError=ERR_MODULE_OPEN_FILE code=-19934 phase=5 systemError=2 rootPhase=complete rootSystemError=0`（systemError=2 即 ENOENT）。哪个模块文件打不开，仅凭 logcat 分辨不出，需看探针实现。
- `ultra-boot-app.txt`：`attemptedBoot=f66149cc-… observedBoot=f66149cc-… result=attempted=true installed=false reason=SDK_ERROR_POLICY_BACKEND_PREPARATION_FAILED`——本次启动下 Ultra 环境已被观测到一次尝试且确认未安装，失败原因是策略后端准备失败，不是 #84 的 NOT_OBSERVED（从未被观测到）。
- 与 #84 的不同：su 探针 8 条路径全部 UNKNOWN / NOT_USABLE，没有 #84 的 USABLE 矛盾信号；凭据交接三次 outcome=KeyPresent，但恢复身份仍 NOT_VERIFIED，是否同一密钥未知。
- “修补后 boot 是否已刷入”无法证实：数据库两栏疑似传反（原镜像栏记 `maplesu-patched-de056d53354e.img`、修补后栏记 `boot.img`），但两个文件均未落盘，无法核对；两个镜像均因 20 MB 上限丢失，是第 13 起。
- 生成 bugreport 时同样暴露凭据缺失，与 #84 逐字相同。

**触发点与关键日志原文（节选，出自 #86 bugreport 内 `logcat.txt`，仅 314 行）**：

> `MapleSUStartup: stage=environment_check` … `code=ENV_NOT_INSTALLED detail=KSU_PROBE_NEGATIVE mode=NONE inherited=false probes=ksu,ultraCurrent,su,ultra,ksu`，elapsedMs 分别为 1538 与 2436
> `MapleSUEnv: verdict=NOT_INSTALLED mode=null detail=KSU_PROBE_NEGATIVE mayPersist=true`
> `MapleSUUltraProbe: Ultra Root probe failed sdkError=ERR_MODULE_OPEN_FILE code=-19934 phase=5 systemError=2 rootPhase=complete rootSystemError=0`，随后 `Ultra environment probe -> NotInstalled kernelRootVerified=false (state=NotInstalled)`
> 出自 `ultra-boot-app.txt`：`result=attempted=true installed=false reason=SDK_ERROR_POLICY_BACKEND_PREPARATION_FAILED`
> bugreport 生成时 00:57:45.172：`KsuCli: 创建 root shell 失败,退回占位 shell (本次运行没有 root 能力)`；`MANAGEMENT_CREDENTIAL_UNAVAILABLE`（同 #84）

**回归要点**：在同款设备（一加 13 PJZ110，Android 17，内核 6.6.118）+ Manager 5.2.1 上复测环境检查，并先核对修补后 boot 是否已刷入（分区 / 槽位、刷后是否重启）。通过标准：环境检查判 INSTALLED 或给出可定位的安装失败原因，不再报 `ERR_MODULE_OPEN_FILE`；另核对探针在 phase=5 打开的是哪个文件。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #86 | 图库 | 一加 13 PJZ110，Android 17，ColorOS 17.0.0.103，内核 6.6.118，Manager 5.2.1 | 修补后检测不到环境 | `code=ENV_NOT_INSTALLED detail=KSU_PROBE_NEGATIVE`（两次）；`sdkError=ERR_MODULE_OPEN_FILE code=-19934 phase=5 systemError=2`；`reason=SDK_ERROR_POLICY_BACKEND_PREPARATION_FAILED` |  |

---

## 4. DirtyFrag 补丁校验失败（#66）

**已证实根因**（来源：`feedback-66-deep-dive.md`）：

- 10 次尝试中只有 3 次是真正的 exploit 运行，另外 7 次在 1–12 ms 内即死。
- 3 次真实运行的 native 日志除端口与 spi 外逐字节相同，失败是确定性的，不是偶发。
- 补丁校验失败，但清理恢复的校验通过——写 + 校验路径本身对原始字节是通的。
- 三种下游失败标签是同一 native 失败的不同分类：7 次 `DIRTYFRAG_REBOOT_REQUIRED_AFTER_ATTEMPT`、2 次 `MAPLESU_KSU_NOT_READY`、1 次 `MAPLESU_HOOK_EVIDENCE_MISSING`，但 native 日志完全相同，都是补丁校验失败。
- 6.12 的 KMI 镜像是随包带的，不是缺失：`jailbreak-artifacts.json` 中 `libdfroot.so` 与 `libmaplesu_dirtyfrag_bootstrap.so` 的 koImages 均含 `android16-6.12`，KMI 选对了。
- 日志包里的系统上下文文件全部是 0 字节（kallsyms.txt、dmesg.txt、mounts.txt、filesystems.txt、defconfig.gz），无法用这份包验证挂载与内核配置。

**未证实 / 假设（二选一，需验证，报告原文）**：

- 假设 A：写操作被静默丢弃。若 /apex 所在分区只读或受 dm-verity 保护，写补丁后读回的仍是原始字节 → 补丁校验失败；清理写回原始字节后读回的仍是原始字节 → 恢复校验必然通过。
- 假设 B：补丁内容与该机型 crash_dump64 构建不匹配。若校验是把读回内容与“原始字节 + 预期补丁”算出的期望值比对，而该机型 crash_dump64 在被补丁偏移处的原始字节与预期不同，则写成功了但校验仍失败；恢复写回原始字节自然通过。
- 报告明确：单凭这份日志分辨不出，需看 verifier 源码，或在测试机上取 crash_dump64 样本 / 查 /apex 挂载可写性。
- 本次结论只适用于 #66；#58 / #59 / #63 / #64 / #65 是否同一根因，未证实。

**触发点与关键日志原文（节选，出自 #66 附件 `MapleSU_jailbreak_1794806058419.tar.gz` 内最新一次尝试 4f971529 的 `dirtyfrag-native.log`）**：

> `patch: crash_dump64 <- splicehelper (1408 bytes)`
> `patched 1408 bytes to /apex/com.android.runtime/bin/crash_dump64+0x0`
> `patch: crash_dump64 verification failed` / `DEVICE NOT VULNERABLE` / `ERROR - failed to patch files`
> 清理：`patched 1408 bytes to ...+0x0` 后 `restore: ... verified (1408 bytes @ 0)`，`maplesu-restore: failures=0`
> 另：`maplesu-hook-env.log` 只有一行 `expected=1 source=baseline`；logcat 全文 0 次出现 crash_dump

**回归要点**：在一加 ACE6T（PLR110，Android 16，内核 `6.12.69-android16-6-g242e1a07f878-ab15865446-4k`）上跑 DirtyFrag。通过标准：补丁校验通过，不再出现 `verification failed` / `DEVICE NOT VULNERABLE`；10 次尝试不再出现 1–12 ms 即死。区分假设 A / B 的最快一步是看 verifier 源码（确认校验比较的是什么）。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #66 | 小杨 @usgrnamk | 一加 ACE6T PLR110，Android 16，内核 6.12.69，5.2.0 | 越狱不了 | `patch: crash_dump64 verification failed` / `DEVICE NOT VULNERABLE` / `ERROR - failed to patch files`（3 次真实运行逐字节相同） |  |

---

## 5. 安全模式 / 启动保护（#16、#33、#67）

**#67 已证实根因**（来源：`feedback-67-deep-dive.md`）：

- 本次启动已触发安全模式，不是越狱失败。截图（18:11）正文：“已触发安全模式。环境尚未确认本次启动成功，未安装用户模块时也可能出现。可在「设置 → Ultra 工作环境 → 修复工作环境」中保留数据重新准备环境。”
- `basic.txt` 与启动保护记录与截图一致：`SafeMode: true`；`pro-environment-boot.txt` 写明 `boot_fail_protect: 1`、`bootstrap safe-flag: Running`、`install mode: Boot`、`bootstrap last failed`。
- Ultra 环境探针判 Fault，原因是当前启动保护；Root 不可用（`collection.txt`：`Root shell available: false`；`MapleSUSuProbe`：`su_readiness=UNKNOWN usable=NONE`）。同一文件里 `sdk_root_identity` 一行显示 `uid=0(root)`，但该文件首行自述仅为“Package-list readback and Manager-only su probe; not a target-app execution proof”，故不把 uid=0 当作 root 可用的已证实结论。
- 这不是一份 DirtyFrag 越狱日志：`diagnostics-collection.log` 列出的 18 项越狱相关文件全部 `MISSING_OR_UNREADABLE`，包内无任何 DirtyFrag 日志或尝试记录。**不要与 #66 合并根因。**
- 10 个模块中 9 个未运行，但这是安全模式下的预期后果，不是原因；唯一 running 的是“设备标识管理”1.1.0。
- 触发原因未知：安全模式的直接触发条件是“本次启动未被确认成功”叠加 boot fail protect，但哪一次启动失败、是哪个模块或哪一步导致未确认，本包内无证据。
- `identityRecovery=RECOVERY_LOCAL_KEY_VERIFIED`，备份路径可用；用户侧可按截图指引操作，不需要重刷 boot。

**#16、#33（未定位）**：用户自述进入安全模式，无深挖报告、无探针日志，触发原因未知。

**触发点与关键日志原文（节选，出自 #67 bugreport 内 logcat，两次同一记录）**：

> `MapleSUProProbe: Ultra environment probe -> Fault kernelRootVerified=false (state=Fault reason=current_boot_protection)`（19:01:18 与 19:01:21）
> 出自 `pro-boot-app.txt`：`result=attempted=false installed=false reason=STATE_Fault`，但 `identityRecovery=RECOVERY_LOCAL_KEY_VERIFIED`，`backupResult=RECENTLY_VERIFIED;ALREADY_BACKED_UP;IDENTITY_READY`
> #16、#33：无日志

**回归要点**：#67 按截图指引在「设置 → Ultra 工作环境 → 修复工作环境」保留数据重新准备环境（若近期改过模块，先停用最近改动的模块再修复），不要重刷 boot。通过标准：连续 3 次重启不再触发安全模式、模块正常运行；如修复后仍触发，需提供修复前后的新 bugreport。#16、#33 同法复测并确认转面具后是否仍进安全模式。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #16 | Charlotte Jaylinn | 红魔 7S Pro | 直接安装转面具后重启两遍，开机显示安全模式，去设置修复三四次无效 | 无日志（有 bugreport 附件，未深挖） |  |
| #33 | Eula Diana | 红魔七 | 转完面具后进入安全模式，不能给应用超级用户权限，也不能转面具 | 无日志（原镜像栏为一张 .jpg，非日志） |  |
| #67 | 安 安 | 一加 PGP110，Android 15，内核 5.10.209，Ultra 5.2.0 | （问题栏仅填“1”，以 bugreport 为准） | `Ultra environment probe -> Fault kernelRootVerified=false (state=Fault reason=current_boot_protection)`；`SafeMode: true`；`boot_fail_protect: 1` |  |

---

## 6. 修补后 boot 疑似未生效（#77）

**已证实根因**（来源：`feedback-77-bugreport-triage.md`）：

- `basic.txt`：`Root shell available: false`；`MapleSU Runtime API: -1`；`SafeMode: false`；`LKM: false`。
- `pro-environment-boot.txt` / `pro-boot-app.txt`：`[FATAL] autorun_bootstrap: NEVER EXECUTED`；`attempted=false installed=false reason=STATE_Unknown`；`boot_session.completed: MISSING_OR_UNREADABLE`；`identityRecovery=RECOVERY_ROOT_NOT_VERIFIED`。
- 两个镜像都未取回：原 boot.img（13:28:40）与修补后 `maplesu-patched-3abb0e36ab6a.img`（13:30:20）均为 "File is too big"，数据库只有文件名，是第 12 起 20 MB 上限致镜像丢失。
- 越狱尝试记录缺失：`diagnostics-collection.log` 写明 `MISSING_OR_UNREADABLE jailbreak-status.json`；bootlog.tar.gz、defconfig、kallsyms、mounts 均为 0 字节，无 DirtyFrag 尝试日志可定位。

**合理推断**：修补后的 boot 似乎未生效或未刷入——引导从未执行，不是越狱中途失败。但没有两个 boot.img，分辨不出是刷入本身失败、刷错槽位 / 分区，还是刷完引导未跑。**不要据此断言是刷机操作问题。**

**触发点与关键日志原文（节选，出自 #77 bugreport 内 `pro-environment-boot.txt` / `pro-boot-app.txt` 与 logcat 12:41:52）**：

> `[FATAL] autorun_bootstrap: NEVER EXECUTED`
> `attempted=false installed=false reason=STATE_Unknown`
> `boot_session.completed: MISSING_OR_UNREADABLE`
> `identityRecovery=RECOVERY_ROOT_NOT_VERIFIED`
> logcat：`KernelSU: Ultra 环境自动配置: Result(attempted=false, installed=false, reason=STATE_Unknown)`

**回归要点**：在同款设备（红米 K50 22041211AC，澎湃 2.0.10，Android 14，内核 5.10.209）上刷入修补后 boot，核对刷入分区 / 槽位、刷后是否重启、管理器的环境显示。通过标准：`autorun_bootstrap` 执行、Root shell 可用、管理器显示已安装。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #77 | 清风 拂过u | 红米 K50，澎湃 2.0.10，Android 14，内核 5.10.209，5.2.0 | 刷 MapleSU Ultra 没权限，给不了 root 也刷不了模块 | `[FATAL] autorun_bootstrap: NEVER EXECUTED`；`attempted=false installed=false reason=STATE_Unknown` |  |

---

## 7. CVE-2026-98260 / Ghostkey 诉求（#70、#71、#73）

**已证实根因**（来源：`feedback-73-cve98260.md`）：

- CVE-2026-98260 是真实存在的公开提权链（非编造的编号）：Android 内核 posix-cpu-timers use-after-free，本地提权（LPE）；公开利用已在多个机型上移植验证，均为 6.12 内核、且均为“逐内核硬编码偏移、非通用”。
- #73 附的 APK 是另一款同 CVE 工具，不是 GhostKey：静态解包核实（apktool 2.12.1，只读解包；未安装、未运行），包名 `com.rootcve.cve98260`，版本 1.35.0，内含 YuKongA 的 CVE-2026-98260 链；`libmagicroot.so` 含 `cve98260_main`，硬编码的已知可利用内核只有两个：`6.12.69-android16-6-g242e1a07f878-ab15865446-4k` 与 `6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k`。
- “第三方提权软件无法选 MapleSU”已定位到两处硬编码：一处是通用的——`run.sh` 的自动收集循环按包名找控制器，列表为 `com.rifsxd.ksunext`、`com.meweishu.kernelsu`、`me.weishu.kernelsu`、`com.derq31.kernelsu`、`com.king.kernelsu`、`com.sukisu.ultra`、`com.resukisu.resukisu`、`com.topjohnwu.magisk`，**没有 MapleSU 的包名**；`yk_run.sh` 的 stage2 只循环四个包复制 `libksud.so`，也没有 MapleSU。但 `detect_controllers.sh` 的探测是通用的，不靠包名：扫描 `/data/app/` 下各包 APK 旁边的 `lib/arm64(-v8a)/libksud.so`。所以“选不到 MapleSU”有两种可能，本次未区分：(a) MapleSU 管理器 APK 旁没有按该布局提供的 `libksud.so`；(b) 选上了但 stage2 的硬编码循环不含 MapleSU，装 ksud 阶段跳过。
- #70 / #71 要的 Ghostkey 就是同一 CVE 的另一个前端（Feiman55 的 CVE-2026-98260 一键工具 GhostKey）。所以 #70 / #71 与 #73 是同一 CVE、不同前端，诉求一致：让 MapleSU 能用这条 6.12 提权链。#70 与 #71 为同一提交者 2 分钟内重复提交，措辞相同。
- #73 设备本身：内核未知，boot.img 已丢，视频未细看。“解析 boot 一直卡住”与 #62 的解析 boot 闪退（OOM）是否同类，未证实。**不要把 #73 与 #74 / #75 合并。**
- #66 的内核正好是该工具的硬编码目标之一（`6.12.69-android16-6-g242e1a07f878-ab15865446-4k`，逐字一致），但 #66 是 DirtyFrag 补丁校验失败，CVE-2026-98260 是同一台设备上另一条已公开的提权路径，不是同一漏洞，不要把两个根因混为一谈。#72（小米 13，内核 5.15）不属于这条线。
- 报告自限：CVE 存在性引自公开仓库 / 聚合站的二手整理，未对照 NVD 或厂商公告核实官方记录。

**路线三选一（报告原文，均未决定）**：(A) 让 MapleSU 成为第三方工具的可选控制器（需核对 ksud 形态，并与工具侧的硬编码列表对接）；(B) MapleSU 原生支持 CVE-2026-98260 提权链（参考 GhostKey 的 BootImageParser + 偏移表做法）；(C) 暂不接，仅回复 #73 说明现状。

**触发点与关键证据原文（本组无崩溃日志；关键证据为 #73 附件 APK 静态解包的脚本原文）**：

> #73 问题原文：“真我gt8pro 17.0.0.105 98260可以提权 maplesu却不支持 解析boot一直卡住 第三方提权软件无法选maplesu”
> `run.sh` 控制器列表含 `com.sukisu.ultra`、`com.rifsxd.ksunext`、`com.resukisu.resukisu`、`me.weishu.kernelsu` 等，**没有 MapleSU 的包名**
> #70 / #71 问题原文（两条相同）：“可不可以在MapleSU面具里加入月虹新出的那个Ghostkey模式”

**回归要点**：按所选路线验证——(A) 第三方工具内能选中 MapleSU 并完成 ksud 安装；(B) 在 6.12 目标内核设备上 MapleSU 原生提权成功；(C) 回复 #73 说明现状（不计入技术回归）。验证前需先确认真我 GT8 Pro 的内核版本（`uname -r`）是否在工具硬编码列表内、是否已打修复，以及 MapleSU 管理器包名与 APK 旁 `libksud.so` 的有无。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #70 | 淡 墨 | REDMI Turbo 5 Max | 能否在 MapleSU 面具里加入月虹新出的 Ghostkey 模式 | 无日志（功能请求；与 #71 同提交者、2 分钟内重复提交） |  |
| #71 | 淡 墨 | REDMI Turbo 5 Max | 同 #70（措辞相同，再次提交） | 无日志（功能请求） |  |
| #73 | 损解 @sjnb778 | 真我 GT8 Pro 17.0.0.105（内核未知） | CVE-2026-98260 可以提权，MapleSU 却不支持；解析 boot 一直卡住；第三方提权软件无法选 MapleSU | 无崩溃日志；APK 静态解包：`run.sh` / `yk_run.sh` 控制器列表无 MapleSU 包名；原 boot.img 因 20 MB 上限丢失 |  |

---

## 8. 越狱 / 提权 / 寄生失败与越狱直接重启（#5、#7、#9、#10、#20、#21、#22、#24、#28、#35、#44、#47、#50、#58、#59、#72、#75、#82、#90）

**根因：除 #44 有 DirtyFrag 尝试日志外，其余均未定位。** 本组按现象并案，不代表同一根因；#58 / #59 / #63 / #64 / #65 是否与 #66 同一根因，未证实。

**#44 的日志证据（数据库 `log_text` 字段原文，节选）**：DirtyFrag 尝试，内核 `5.15.197-android13-8`，KMI `android13-5.15`。关键行：

> `[progress.jsonl] 12:22:15.209 [admission] device=OPPO PKV110 kernel=5.15.197-android13-8-o-gf507a2bdef69 sdk=36 uid=10387 backend=hook`
> `[dirtyfrag-native.log] patch: crash_dump64 <- splicehelper (1408 bytes)` / `patched 1408 bytes to /apex/com.android.runtime/bin/crash_dump64+0x0`
> `[dirtyfrag-native.log] patch: /vendor/lib64/libbinderdebug.so <- dfroot.ko (17680 bytes)` / `verify: /vendor/lib64/libbinderdebug.so +0x0 match=0` / `readback=-1 errno=13 helper=1`
> `[dirtyfrag-native.log] patch: /system/lib64/libc++.so <- shellcode (464 bytes)` / `verify: /system/lib64/libc++.so +0xf6d80 match=1`；trampoline `verify: … +0xa49f0 match=1`
> `[dirtyfrag-native.log] === init ===` / `Triggering hook...`（日志至此结束；设备随后重启，见问题描述）

即：crash_dump64 已写入（本段日志内未见 #66 那种 verification failed 行），libbinderdebug.so 校验 match=0（读回失败，errno=13），libc++.so hook 与 trampoline 校验通过，随后触发 hook 时设备重启。校验失败点与重启的因果关系未证实，根因未定位。

**其余条目的现有证据**：

- #28：`log_text` 仅为机型 / 版本 / 内核文字——“机型真我GT7 / 版本号RMX6688 16.0.10.500 / 内核版本6.6.118-android15-8-80ea6ogki498046332-4k”，无崩溃日志；问题称不管 Ultra 无痕还是 Normal 兼容，执行自带越狱都会重启。
- #58 / #59：#58 iQOO Z10 Turbo+（OriginOS 5，内核 6.6.57-android15）用不了 DirtyFrag 提权，无日志；#59 为同提交者补充——其他的 DirtyFrag 提权都能正常使用、100% 成功。
- #75：真我 GT5，越狱不管哪个模式都 100% 重启，称其他工具可以正常提取，无日志。
- #82：PLM110，越狱就重启，改用月虹临时 root 后 MapleSU 仍显示越狱不可用，无日志。**机型 PLM110，与 #79 的 PLU110 不同，不要合并。**
- #90：晴 川 @qc081123（Telegram ID 6164433571），2026-11-25 23:54:14，红米 K90 Pro Max、澎湃 4.0.6.0，问题“网页寄生功能无法正常使用 寄生软件不成功 且只能寄生一部分软件”。无日志、无镜像、无截图。**未定位**：“网页寄生”是反馈库首次出现的功能点，红米 K90 Pro Max 也是首次出现的机型；“只能寄生一部分软件”说明是部分成功、非全失败，回归前需先向提交者索要：哪些软件寄生成功、哪些失败、失败时的提示，以及 MapleSU 版本号（提交者未填）。不要与 #35（寄生后恢复报错）或 #24（寄生失败丢权限）合并根因——三者现象不同，且 #90 无日志。
- #5 / #50：同一提交者（鸡你太美，pkv110），越狱模式手雷直接重启；#5 称用手雷软件可以成功提权、提取不到日志；#50 有 jailbreak 日志附件（未深挖）。
- #35：红米 K80 Pro，隐身寄生后恢复了几次，中间疑似寄生未成功就点恢复，一直弹恢复；再点寄生报错“状态校验失败 / 预检宿主”，但管理器功能完全正常。
- 其余（#7、#9、#10、#20、#21、#22、#24、#47、#72）均无日志，见下表。

**回归要点**：按各条原机型 / 原模式复测越狱、提权与寄生流程。通过标准：对应模式下越狱完成、不重启，且提权后 root 可用；寄生类（#9、#24、#35、#47、#90）通过标准为寄生 / 恢复流程走完，不再出现“状态校验失败 / 预检宿主”类报错；#58 复测时需对照 #59（其他 DirtyFrag 工具是否仍 100% 成功）。#90 回归前需先索要成功/失败软件清单与失败提示，否则无法判定“只能寄生一部分”是预期限制还是缺陷。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #5 | 鸡你太美 @shoiguo886 | pkv110，16.0.2.401 | 越狱模式手雷直接重启；用手雷软件可以成功提权，提取不到日志 | 无日志 |  |
| #7 | 海豚 | 未填 | 提权不成功 | 无日志（有 bugreport 附件，未深挖） |  |
| #9 | 恋沐 K | 一加平板 Pro，系统 16 | 无法使用寄生 | 无日志（有 bugreport 附件，未深挖） |  |
| #10 | Newlife @Newha6 | 未填 | 提权越狱后授权超级用户，对应软件无法正常获取 root 权限 | 无日志 |  |
| #20 | Blender @ICANNSKAUTERR | 努比亚 Z60 Ultra | 刷完后没有超级用户 | 无日志（有 bugreport 附件，未深挖） |  |
| #21 | 往泽晓 @heyicaonima | K90 Max | 用自带 DirtyFrag 重启后卡重启 | 无日志（仅原镜像文件名 boot.img） |  |
| #22 | koi | iQOO Z9 Turbo，内核 6.1.145-android14 | 无法提权越狱 | 无日志 |  |
| #24 | Forever @mujinzz | K80 Pro 4.0.3 | 寄生失败，管理器丢权限，寄生应用也没有 | 无日志 |  |
| #28 | —（无用户名） | 真我 GT7 RMX6688，内核 6.6.118-android15 | 不管 Ultra 无痕还是 Normal 兼容，执行自带越狱都会重启 | 无崩溃日志（`log_text` 仅为机型/版本/内核文字） |  |
| #35 | 我是哑巴 已被官方永久禁言 | 红米 K80 Pro，3.0.303 | 寄生后恢复几次，中间疑似寄生未成功就点恢复，一直弹恢复；再点寄生报错“状态校验失败 / 预检宿主” | 无日志（有 bugreport 附件，未深挖） |  |
| #44 | 鸡你太美 @shoiguo886 | pkv110，16.0.2.401 | 内置手雷越狱重启 | DirtyFrag 日志：`verify: /vendor/lib64/libbinderdebug.so +0x0 match=0` / `readback=-1 errno=13`；libc++.so hook 校验 match=1；随后 `Triggering hook...` 后重启 |  |
| #47 | xxkkjs | 未填 | 寄生无法恢复 | 无日志（有 1 张截图） |  |
| #50 | 鸡你太美 @shoiguo886 | pkv110，16.0.2.401 | 越狱模式手雷重启 | 无日志（有 jailbreak 日志附件，未深挖） |  |
| #58 | 寄星月 @yuejixing1 | iQOO Z10 Turbo+，OriginOS 5，内核 6.6.57-android15，5.2.0 | 用不了 DirtyFrag 提权 | 无日志 |  |
| #59 | 寄星月 @yuejixing1 | 未填（同 #58 提交者） | 其他的 DirtyFrag 提权都能正常使用、100% 成功 | 无日志（#58 的对照补充） |  |
| #72 | 萧炎 @UZPZL | 小米 13，澎湃 OS 3，内核 5.15 | 管理器内置的临时 root 工具一用就重启；用第三方提权工具只能开玩机模式，不能开极致隐藏的工作模式 | 无日志（有 1 张截图） |  |
| #75 | Ксюша | 真我 GT5，真我 UI 5.0 | 越狱不管哪个模式都 100% 重启，但用其他工具可以正常提取 | 无日志 |  |
| #82 | takagi karin @nishikatasan | PLM110 | 在 Maple 里越狱就会重启；用月虹临时 root 后 Maple 仍显示越狱不可用 | 无日志 |  |
| #90 | 晴 川 @qc081123 | 红米 K90 Pro Max，澎湃 4.0.6.0 | 网页寄生功能无法正常使用，寄生软件不成功，且只能寄生一部分软件 | 无日志（无镜像、无截图；未填 MapleSU 版本） |  |

---

## 9. 刷入后重启 / 卡死 / 卡重启界面（#2、#4、#17、#32、#36、#41、#45、#49、#52、#55、#64、#88）

**根因：均未定位。** 本组按现象并案，不代表同一根因；各条均无日志（#2 有 bugreport 附件但文件未存下，见下）。

**现有证据**：

- #2：Gerry Farrah，一加 Ace5 至尊、ColorOS 15 501 版本，手机异常发热、有时莫名重启；模块卸载后若未重启，等莫名重启时模块就卸载不掉。汇总列出日志 `MapleSU_bugreport_2026-11-14_19_30.tar.gz`、原镜像 `boot.img`、修补后 `maplesu-patched-eebc417110cd.img`，但晨间分类注明**附件只有文件名，文件未存下**（下载 bug + 20 MB 上限），需重发。
- #52：iQOO 11 Pro（橘子 6），用久了会卡死无限重启，刷回原来的 boot 才正常。
- #64：一加 Ace6、安卓 16、内核 6.6.89，刷入后卡死重启两次，模块全炸。
- #41：一加 Ace6（ColorOS 17.0.0.104）、Ultra 模式、内核 6.6，突然死机，可长按电源关机并重启，关机时短暂闪黄，系统 logo 界面启动时间过长。
- #17：一加 Ace5 竞速版 6.1 内核，一键安装、卸载原面具后打开 MapleSU 会崩溃重启。
- #55：一加 Ace2v / PHP110，Ultra 模式下打开管理器会重启。
- #45：一加 Ace5 竞速版 PLF110，用 KSU 给了 root 权限、点了直接安装，重启后一直卡死。
- #49：一加 2 Pro 改 OPPO Pad4 Pro C16 金标系统，给予 root 权限以刷模块的过程中有概率重启（无痕模式）；另称大部分第三方修改的调度模块与温控模块均无法生效。
- #36：一加 Pad（没有 Pro），刷了修补的 boot 进不去面具，一点进去就卡死。
- #32：手机一直卡在重启界面，问怎么救；无设备信息。
- #4：K60、安卓 14，5.10 修补刷入后无限重启。
- #88：一加 ACE6，仅“异常重启”，无日志、无镜像、无截图。

**回归要点**：按各条原机型 / 原操作复测刷入与使用流程。通过标准：刷入后正常开机、不无限重启、不卡死、不卡在重启界面；#2 另需确认异常发热是否复现；#49 需复测刷模块过程中的重启概率；#88 因信息最少，回归前需先向提交者索要日志。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #2 | Gerry Farrah | 一加 Ace5 至尊，ColorOS 15 501 版本 | 手机异常发热，有时莫名重启；模块卸载后若未重启，等莫名重启时模块卸载不掉 | 无日志（附件只有文件名，文件未存下） |  |
| #4 | 铭 | K60，安卓 14 | 5.10 修补刷入后无限重启 | 无日志 |  |
| #17 | 缥烬恬霞所由偿 | 一加 Ace5 竞速版，6.1 内核 | 一键安装、卸载原面具后打开 MapleSU 会崩溃重启 | 无日志 |  |
| #32 | 大户困倦 | 未填 | 手机一直卡在重启界面，怎么救 | 无日志 |  |
| #36 | 11111 11111 | 一加 Pad（没有 Pro） | 刷了修补的 boot 进不去面具，一点进去就卡死 | 无日志（仅原镜像文件名 boot.img） |  |
| #41 | Xkxljc | 一加 Ace6，ColorOS 17.0.0.104，Ultra 模式，内核 6.6 | 突然死机，可长按电源关机并重启；关机时短暂闪黄，logo 界面启动时间过长 | 无日志 |  |
| #45 | Ana Rodríguez | 一加 Ace5 竞速版 PLF110_16.0.10.501 | 用 KSU 给了 root 权限、点了直接安装，重启后一直卡死 | 无日志（有 2 张截图） |  |
| #49 | @nbhh666nb | 一加 2 Pro 改 OPPO Pad4 Pro C16 金标系统 | 给予 root 权限以刷模块的过程中有概率重启（无痕模式） | 无日志 |  |
| #52 | 1 | iQOO 11 Pro（橘子 6） | 用久了会卡死无限重启，刷回原来的 boot 才正常 | 无日志 |  |
| #55 | 可是我爱你 @lying0021 | 一加 Ace2v PHP110_16.0.5.1301 | Ultra 模式下打开管理器会重启 | 无日志 |  |
| #64 | 小熊 @xiong040304 | 一加 Ace6，安卓 16，内核 6.6.89 | 刷入后卡死重启两次，模块全炸 | 无日志 |  |
| #88 | 我是帅哥 @shr181317 | 一加 ACE6，17.0.104 版本 | 异常重启 | 无日志 |  |

---

## 10. WebUI / 工作环境（#6、#15、#18、#43、#53、#54、#60）

**根因：均未定位。** 其中 #6 的端口事实来自 2026-11-15 晨间分类的记录（非深挖报告），其余条目只有问题描述。

**现有证据**：

- #6（来源：2026-11-15 晨间分类）：Li MY，工作环境未知状态，点击修复无效，模块 WebUI 打不开，原因 `PORT_NOT_LISTENING`，port=18427 关闭，无法授权超级用户权限、更新 App Profile。附件：日志与修补后镜像 `maplesu-patched-33e6396b4021.img` 已落盘；原镜像栏填的是设备文字“iQOO Z10 Turbo+ 6.6.89 安卓 16”而非文件名。
- #18：红米 Turbo 3（4.0.0.31 beta 第三方官改包），点到模块里面是正常的，在外面看说任务脚本执行失败。
- #15：WebUI 打不开；无设备信息。
- #43：一加 Ace Pro / 5.10.226，没办法用，超级用户权限列表加载失败，安装工作环境也安装不了；有 2 张截图。
- #53：OPPO Reno12，有些模块打开的时候会闪退；仅修补后镜像 `kernelsu_patched_20261116_042946.img` 一个附件。
- #54：红米 K70e、澎湃 3.0.4，工作环境修复不了、授权不了 root，寄生隐身好像也用不了。
- #60：红米 KPad2、已解锁、澎湃 3.0、6.12、安卓 16，显示工作环境未运行，进行修复、重启后依然如此。

**回归要点**：复测 WebUI 打开与工作环境状态。通过标准：工作环境显示正常、WebUI 可打开、端口监听正常、授权流程走通；#6 需复测修复按钮与 18427 端口监听状态。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #6 | Li MY @Lveuot | iQOO Z10 Turbo+，6.6.89，安卓 16 | 工作环境未知状态，点击修复无效，模块 WebUI 打不开 | 无崩溃日志；晨间分类记录原因 `PORT_NOT_LISTENING`，port=18427 关闭 |  |
| #15 | 爱意随风起 | 未填 | WebUI 打不开 | 无日志 |  |
| #18 | Laine Ila | 红米 Turbo 3，4.0.0.31 beta（第三方官改包） | 点到模块里面正常，在外面看说任务脚本执行失败 | 无日志（有 bugreport 附件，未深挖） |  |
| #43 | Striptizer @striptizer_02 | 一加 Ace Pro，5.10.226 | 超级用户权限列表加载失败，安装工作环境也安装不了 | 无日志（有 2 张截图） |  |
| #53 | 仁 | OPPO Reno12 | 有些模块打开的时候会闪退 | 无日志（仅修补后镜像一个附件） |  |
| #54 | 别让我流泪 | 红米 K70e，澎湃 3.0.4 | 工作环境修复不了、授权不了 root，寄生隐身好像也用不了 | 无日志 |  |
| #60 | HR @NMLBBB | 红米 KPad2，已解锁，澎湃 3.0，6.12，安卓 16 | 工作环境未运行，进行修复、重启后依然如此 | 无日志（有 2 张截图） |  |

---

## 11. 安装与模块管理（#11、#12、#13、#19、#29、#37、#38、#39、#42、#46、#57、#68）

**根因：除 #39 有安装日志原文外，其余均未定位。**

**#39 的日志证据（数据库 `log_text` 字段原文，节选）**：一加 Ace5、安卓 16，Zygisk 用不了，刷不进去、刷进去了也用不了。安装日志显示：

> `Installing Zygisk Next as an Ultra script module.`
> `- Installing Zygisk Next 1.5.3 (880-22a1bf5-release)` / `- KernelSU version:12000 (kernel)+12000 (ksud)` / `- Device sdk: 36`
> `["event"…"rule",…"line":4,"status":"error","message":"target type nsfs does not exist …"]`
> `["event"…"result"…"status":"validation failed"…"committed":false…]`
> `***`
> `!Unable to apply SELinux patches!`
> `!Your kernel may not support SELinux patch fully`
> `Error code: 1.`

即：安装日志的事实是 Zygisk Next 安装时 SELinux 补丁校验失败（第 4 条规则报错 `target type nsfs does not exist`，最终 `validation failed`，Error code 1）。该失败是否为“用不了 Zygisk”的全部原因，未证实。

**其余条目的现有证据**：

- #57：yusheng cai，一加 16（17.0.1.201，内核 6.18.21-android17），修补 boot 镜像开机提示未安装；bugreport 已落盘（汇总记录 177,671 字节）；原镜像与修补后镜像均仅剩文件名，汇总注明因超过 20 MB 未取回。
- #29：一加 Ace5、安卓 16、6.1.141，无法刷入任何模块；install_log（`MapleSU_install_log_2026-11-15-09-31-44.txt`，218 字节，5 行）原文：`Staged 2f056cd3132640a48458b867deeb5501 (4152306 bytes)`，随后两次 `SDK admission timed out; this request was not submitted.`；另有 1 张截图。
- #12：小米 15、安卓 17、内核 6.6，依旧无法安装极致模式工作环境；有 bugreport、原镜像与修补后镜像附件。
- #19：红米 K90 至尊，刷入修补 boot 后安装环境显示无法安装、请稍后再试。
- #37 / #38：同一提交者（P，一加 ACE 6t，17.0.0.101），#37 安装工作环境一直安装不了，#38 安装模块失败；各有 bugreport 与截图。
- #42：Y700 二代、系统 1.1.478，无法安装环境；原镜像与修补后镜像栏均填 `boot.img`，有 2 张截图，无日志。
- #11：一加 13（17.0.0.103），刷模块时没有日志打印；需要音量键选择的模块装不进去。
- #13：App Profile 中没有“卸载模块”选项。
- #46：一加 Ace5、安卓 16、6.1.141，频道里面的 tees 模块无效果。
- #68：一加 Ace 6、C17 系统，模块怎么删都删不掉；有 1 张截图。

**回归要点**：按各条原操作复测安装 / 刷模块 / 删模块流程。通过标准：安装流程走完、无报错、模块可正常刷入 / 卸载；#39 需在同款设备上重装 Zygisk Next 并确认 SELinux 补丁校验通过；#57 需复测修补 boot 刷入后管理器的“已安装”显示。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #11 | SingGO Game | 一加 13，17.0.0.103 | 刷模块时没有日志打印；需要音量键选择的模块装不进去 | 无日志 |  |
| #12 | 簌雨辞风 | 小米 15，安卓 17，内核 6.6 | 依旧无法安装极致模式工作环境 | 无日志（有 bugreport 与双镜像附件） |  |
| #13 | SingGO Game | 未填 | App Profile 中没有“卸载模块”选项 | 无日志 |  |
| #19 | 我最喜欢 U 991 | 红米 K90 至尊 | 刷入修补 boot 后安装环境显示无法安装、请稍后再试 | 无日志 |  |
| #29 | xxkkjs | 一加 Ace5，安卓 16，6.1.141 | 无法刷入任何模块 | install_log 附件原文：`SDK admission timed out; this request was not submitted.`（两次） |  |
| #37 | P @CZXvtnb666 | 一加 ACE 6t，17.0.0.101 | 安装工作环境一直安装不了 | 无日志（有 bugreport 附件与 1 张截图） |  |
| #38 | P @CZXvtnb666 | 一加 ACE 6t，17.0.0.101 | 安装模块失败 | 无日志（有 bugreport 附件与 1 张截图） |  |
| #39 | xufeng | 一加 Ace5，安卓 16 | 用不了 Zygisk，刷不进去，刷进去了也用不了 | `!Unable to apply SELinux patches!` / `target type nsfs does not exist` / `Error code: 1.`（Zygisk Next 1.5.3 安装日志） |  |
| #42 | 小布 @xiao6skakaj | Y700 二代，系统 1.1.478 | 无法安装环境 | 无日志（原/修补镜像栏均填 boot.img，有 2 张截图） |  |
| #46 | xxkkjs | 一加 Ace5，安卓 16，6.1.141 | 频道里面的 tees 模块无效果 | 无日志 |  |
| #57 | yusheng cai @xiaohei2949 | 一加 16，17.0.1.201，内核 6.18.21-android17 | 修补 boot 镜像开机提示未安装 | 无日志（bugreport 已落盘；双镜像因 20 MB 未取回） |  |
| #68 | T Z. @NqnpB | 一加 Ace 6，C17 系统 | 模块怎么删都删不掉 | 无日志（有 1 张截图） |  |

---

## 12. 适配请求（#3、#8、#14、#23、#25、#40、#48、#63）

**根因：均为适配 / 功能诉求，非已定位的缺陷。** 其中 #23 的“修补完刷入 boot 不生效”与 #77 的“修补后 boot 疑似未生效”症状相似，但 #23 无 bugreport、无探针日志，不能据此并入 #77 的根因。

**现有证据**：

- #3：Zero Arch，iQOO 15 Ultra（PD2546），OriginOS 7，内核 `6.12.58-android16-6-g690108013dcc-abogki576129896-4k`，偏移量没有内置，希望作者内置方案修复。
- #8：冰狱独宠，iQOO 15（橘子 7），版本适配申请；仅原镜像 `boot_b.img` 一个附件。
- #14：时光，内容只有设备型号 iQOO 13 / PD2408C_A_16.1.18.2；晨间分类注明疑似只填了设备、问题描述为空或即型号本身。
- #23：蕾姆，一加 16（PYB110 / ColorOS 17.0.1.201），6.18.21，极致无痕模式修补完刷入 boot 不生效，有时弹出安装补丁、安装不了，提示稍后再试；有 bugreport 与原/修补镜像附件（修补后为 zip）。
- #25：lener E，iQOO 13 / PD2408C_A_17.2.11.8，免解越狱不适配；仅原镜像 `Image.zip` 一个附件。晨间分类将其归入 B 提权失败组，此处按诉求归入适配组。
- #40：仙辞，Y700 一代 4.19.157，问“哥以后会支持四系吗”（咨询，非缺陷报告）。
- #48：爱 人，K80、系统 3.9，无痕模式；称要开三角洲外挂，只能刷 initboot 才能获取 root 权限，但 MapleSU 只能刷 boot（功能诉求：initboot 支持）。
- #63：阿八 阿巴，K90 Max、6.12 内核、HyperOS 3，请适配 6.12 内核的设备；用 Ultra 模式无法使用，但用另一个模式可以使用。

**回归要点**：按各条机型 / 内核验证适配：通过标准为对应机型 / 内核的提权或安装流程走通；如确认不支持，以实际验证或明确答复为准并在回归结果列注明。#23 需单独复测极致无痕模式下修补 boot 刷入后的生效情况，不与 #77 的结论互相套用。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #3 | Zero Arch | iQOO 15 Ultra PD2546，OriginOS 7，内核 6.12.58-android16 | 偏移量没有内置，希望作者内置方案支持 | 无日志 |  |
| #8 | 冰狱独宠 @YBC1738 | iQOO 15，橘子 7 | iQOO 15 版本适配申请 | 无日志（仅原镜像 boot_b.img） |  |
| #14 | 时光 | iQOO 13 / PD2408C_A_16.1.18.2 | （仅型号，无问题描述） | 无日志（有 jailbreak 日志附件） |  |
| #23 | 蕾姆 @Remyyds | 一加 16 PYB110，ColorOS 17.0.1.201，内核 6.18.21 | 极致无痕模式修补完刷入 boot 不生效，有时弹出安装补丁、安装不了 | 无日志（有 bugreport 与原/修补镜像附件） |  |
| #25 | lener E | iQOO 13 / PD2408C_A_17.2.11.8 | 免解越狱不适配 | 无日志（仅原镜像 Image.zip） |  |
| #40 | 仙辞 | Y700 一代，4.19.157 | 以后会支持四系吗 | 无日志（咨询，非缺陷报告） |  |
| #48 | 爱 人 | K80，系统 3.9 | 无痕模式下只能刷 initboot 才能获取 root，MapleSU 只能刷 boot | 无日志（有 1 张截图） |  |
| #63 | 阿八 阿巴 | K90 Max，HyperOS 3，6.12 内核 | 请适配 6.12 内核的设备；Ultra 模式无法使用，另一模式可用 | 无日志（仅原镜像文件名 boot.img） |  |

---

## 13. 其他未分类（#34、#51、#61、#65、#78）

**根因：均未定位。** 归类依据不足，不强行并案。

**现有证据**：

- #34：咸鱼翻身，真我（未填型号），没修补的情况下会卡出底栏；附件为一段 .mp4。
- #51：@nbhh666nb（同 #49 设备，一加 2 Pro 改 OPPO Pad4 Pro），无论是否在运行软件，CPU 温度以及功耗都会忽然一瞬间变高，两三秒后又恢复正常，重复这个过程；已通过排除法确定与设备模块无关系，就是管理器出现的问题。无日志。
- #61：骼骼，问题描述只有“侧信道问题使用的面具 maplesu”；设备信息栏为“1”。信息不足，无法归类。
- #65：ACE反作弊，一加 13T、安卓 15、6.6，“根本用不了 KSU 无法使用 root 直接移植”。描述笼统，无日志。
- #78：77 66，小米 17，刷入伪装华为 Mate90 那个模块之后，手机桌面一直闪退、关机，成软砖了。汇总明确写无附件（相关日志栏“没有”，原镜像与修补后镜像栏均为“no”）。

**回归要点**：逐条按原描述复测并记录结果。信息不足的条目（#61、#65）回归前需先向提交者索要设备信息与日志；#78 需在确认模块来源与刷入步骤后复测，回归时注意不要在主力机上重现软砖。

| 编号 | 提交者 | 机型/系统/版本 | 问题一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #34 | 咸鱼翻身 @xianyubhfs | 真我（未填型号） | 没修补的情况下会卡出底栏 | 无日志（附件为一段 .mp4） |  |
| #51 | @nbhh666nb | 一加 2 Pro 改 OPPO Pad4 Pro C16 金标系统 | CPU 温度与功耗忽然一瞬间变高，两三秒后恢复，重复；已排除模块，认为是管理器问题 | 无日志 |  |
| #61 | 骼骼 @GEIOSios | 未填（设备栏为“1”） | 侧信道问题使用的面具 maplesu | 无日志（信息不足） |  |
| #65 | ACE反作弊 | 一加 13T，安卓 15，6.6 | 根本用不了，KSU 无法使用 root，直接移植 | 无日志（描述笼统） |  |
| #78 | 77 66 @shabiganjingun | 小米 17 | 刷入伪装华为 Mate90 模块后桌面一直闪退、关机，成软砖 | 无日志（汇总明确写无附件） |  |

---

## 14. 非缺陷 / 信息类（#1、#26、#69、#79、#83、#85）

**性质**：以下 6 条不是缺陷报告，无需技术回归；列出以保证 90 条全覆盖。处置说明：#1 待你决定删除 / 标记 / 保留（可在 /admin 自删）；#79 按功能建议评估是否采纳；#26、#69、#83、#85 仅作记录，无需验证。

| 编号 | 提交者 | 机型/系统/版本 | 内容一句话 | 关键日志行 | 回归结果 |
|---|---|---|---|---|---|
| #1 | MapleAdm（用户本人） | — | 测试提交：公测须知全文（自愿参与、须备份、按“现状”提供、欢迎提 Issue / PR） | 无日志（附件栏填 `NoMachine Enterprise Desktop 试用许可.gz`，与反馈内容无关） |  |
| #26 | 小 方 | 未填 | 新版面具下载 | 无日志 |  |
| #69 | User | 未填 | 下载 | 无日志 |  |
| #79 | B L | 一加 Turbo 6，PLU110 | 功能建议：能否把默认改成极痕模式；另咨询自己的越狱直接到玩机模式、想搞无痕模式 | 无日志（有 1 张截图） |  |
| #83 | 凌霄 @fangjunhan | 未填 | 还没更新 | 无日志 |  |
| #85 | mm j @xiaomumm | 未填 | 想要内测名额 | 无日志 |  |

---

## 附注

1. **不要合并的条目**（均有深挖报告或日志依据）：#82（PLM110）与 #79（PLU110）是不同机型；#73（CVE-2026-98260）不要与 #74 / #75 合并；#67（安全模式，5.10）不要与 #66（DirtyFrag，6.12）合并根因；#84（UNKNOWN_TIMEOUT）与 #86（NOT_INSTALLED）同属“Ultra 恢复身份未验证”一族但结论不同，组内已分开写；#84 的 19:01 pthread_create OOM（Oplus BoostFramework 线程创建失败）与 #62 / #74 的修补 boot 时堆 OOM 是两类不同的 OOM。
2. **机器人侧的已知问题（不在 90 条反馈内，回归时一并注意）**：2026-11-24 22:18:13 与 22:18:47 两次 `Message_too_long` 复发（show_confirm 预览超 Telegram 4096 个 UTF-16 单位上限，均无对应反馈入库，至少一份提交卡住未入库）；UTF-16 守卫补丁（决策 A）与 effective_message 守卫补丁（决策 C）均未应用，待你决定。
3. **深挖报告原文**：`~/workspace/goals/maplesu/files/` 下 `feedback-66-deep-dive.md`、`feedback-67-deep-dive.md`、`feedback-74-oom-deep-dive.md`、`feedback-76-localnavigator-repeat.md`、`feedback-77-bugreport-triage.md`、`feedback-80-81-localnavigator-quadruple.md`、`feedback-84-environment-check.md`、`feedback-86-environment-check.md`、`feedback-89-localnavigator-fifth.md`、`feedback-73-cve98260.md`、`feedback-56-full-crash-log.txt`。
4. **回归结果填写建议**：每格填“通过 / 未通过 / 未测 / 需补充信息”，未通过的注明复现时的新日志或新现象；不要只填“已修”，以实测为准。
