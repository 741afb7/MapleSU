## 2026-11-22 MAIN12 repair后安全模式复发与原生首错（09:25:02 UTC）

14-5保数据安装通过之后，唯一test_owner执行一次真实产品repair确认，产品自行重启；原机a261972a当前boot为 `dd521066-6fcf-4982-8b4f-132c340abb36`，仍安全模式。原生收据 `main10-tests/device-candidate14-5-ui-r1/NATIVE-FIRST-FAILURE.json` SHA256 `9659450eb1ea3d6cf8b731ba03bc992ee782a89ff708285380227690f8d70305`：首先报告 vendor-state=-19999、phase12/applied0；先前root/root-storage/root-environment/su-storage均0，首个阻断为su-redirect=-19980，真实保护保留。后续SU UID cleanup=-19999及ERR_MODULE_DUPLICATE另记。产品日志导出中的SDK Key Root返回0、UID0、Enforcing；Manager免Key SU为errno2，目标应用执行证明仍缺。safeModeFixed=false，根因尚未证明闭合；原APK封包/签名/保数据安装通过证据不撤销，也不代替安全模式/SU功能验收。

claim17六族native exitCode均0，整体metadata/资格仍红；r4 strict provider VAR源采用、r5六族provider VAR再生与恢复脚本inspection整字典比较失败分开记账，r6恢复脚本修复准备中，不清资格。TaskProfile consumer rev3已review并采用（`main12-owner/task-profile-consumer-r3/ADOPTION.json`），采用动作未编测，不记新包/设备通过。安全模式根因候选尚未采用且继续最高优先；主线goal保持active，原截止逾期不重置。原119需求、30子项及全部约束/历史窄通过保留，新增完整项与设备功能通过均0。

本次只更新现成证据关联：`.tmp/supervision-20261121/main12-acceptance/NATIVE14-5-FAILURE-CLAIM17.json`、`DELIVERY.json`。原Key、授权、模块、六分区备份及本boot首错现场保留；本记录任务无生产修改、审计、构建、测试或ADB。以下历史段落保持各自时点。

## 2026-11-22 MAIN12 原机保数据安装与后续源码增量（08:49:33 UTC）

固定test_owner已于08:45:01 UTC完成14-5的 `install -r`，收据为 `main10-tests/device-candidate14-5-install-r1/RESULT.json`，状态 `CANDIDATE_INSTALLED_DATA_PRESERVED`。原机a261972a的实际base.apk SHA256为 `80f8f6c5022ee27b2f6c98c6a5daf559daee296f9c3d626f3def9ab5c8aba9d1`，fullUID10498保持；前后boot均为 `61b311c5-3d21-4d64-9a53-87e1bcc01ea1`，dataCleared=false、manualPartitionWrites=0。这里只结算实际保数据安装及身份回读；真实UI repair/SU正执行，尚无功能终态，不记安全模式恢复、SU或完整设备通过。

host7实际配对目标夹具仍保留 `BTF_ABI_FUNCTION_TYPEID_ADDRESS_AMBIGUOUS:delayed_free` 红例。主线于08:46:11 UTC逐SHA采用Session rev2两份Manager源码（`UltraKernelBtf.kt`、`UltraKernelResidentAbi.kt`），收据为 `main12-owner/session-callers-rev2/ADOPTION.json`；状态 `ADOPTED_PENDING_FIXED_TEST_OWNER`。这次源码采用晚于已装14-5构建，未反记入该APK；受影响窄回归、后续签名包与设备绑定另待实际收据。

当前索引：`.tmp/supervision-20261121/main12-acceptance/INSTALLED-CANDIDATE14-5.json`、`DELIVERY.json`。原119需求、30子项及资格保持，完整项与设备功能通过提升0；本记录任务无生产源码、构建、测试或ADB操作，历史失败/回执与原Key、授权、模块、分区备份继续保留。以下历史段落保持各自时点。

## 2026-11-22 MAIN12 诊断候选14-5实际构建完成（08:47:45 UTC）

14-5 于2026-11-22 08:43:24 UTC取得真实 `BUILT`、`inputsStable=true`，标准Release为 `BUILD SUCCESSFUL in 8m 54s`。交付的是设备诊断候选，完整资格/正式全功能交付仍未通过；07:32 UTC逾期记录保持。

- APK：`.tmp/supervision-20261121/main10-generation/release-device-candidate14-5/MapleSU-5.2.1-device-candidate.apk`，88997367字节，SHA256 `80f8f6c5022ee27b2f6c98c6a5daf559daee296f9c3d626f3def9ab5c8aba9d1`。两层构建收据为该目录 `EXECUTION.json` 与父级 `release-device-candidate14-5-EXECUTION.json`；后者SHA256 `563de493f37ad3bfc614b523d4ea513e6aaed086c5caaf84d52af451d5f14de1`。
- 14-4真实首错 `ULTRA_CLAIM_BOOTSTRAP_PATH:android14-6.1` 保留；Gradle/产品消费端/验包器三处合同最小修与固定测试代理4/4源资产合同夹具通过分别记账。当前验包器首次观测的 `redReproduced=false` 原样保留，红例依据是真实14-4构建，不拼接假红。
- 固定test_owner的现有验包/验签收据已记录包名 `dev.maplesu.manager`、版本5.2.1/50201及封包/签名范围通过；该收据明确 formalAcceptance=false、behaviorAcceptance=false、deviceAcceptance=false。保数据安装、repair与真实SU正由唯一测试负责人推进，当前最终设备结果待收据，不记安装/修复/SU通过。
- 六族诊断资产的未资格状态、bridge11恢复2、host7及既有夹具维持原范围。安全模式真正恢复、SU、模块/生命周期、25组全功能和原机完整验收尚未通过；原119需求、30子项、20约束、两组10项及历史勾选保持，本轮整项提升0。

关联入口：`.tmp/supervision-20261121/main12-acceptance/BUILT-CANDIDATE14-5.json` 与 `DELIVERY.json`。本次仅证据关联/哈希及进度增量，生产源码/构建/测试/ADB均0；原Key、授权、模块、分区备份及故障现场保留。以下历史段落保持各自时点。

## 2026-11-22 MAIN12 构建与候选实际进度（08:11:13 UTC）

正式 APK 尚未交付；2026-11-22 07:32:00 UTC 截止已过，根收据为 `main12-owner/DEADLINE-0732.json`。本轮新设备写入仍为0，安全模式恢复、真实 SU、25组全功能及指定原机验收均未通过。原119唯一需求、P0–P8、20约束、两组10项及历史勾选保留；29原子项加在线更新卸载追加项共30项，本轮新增完整通过0。

- 源码与构建分账：claim14 六族真实 native build 的 exitCode 均为0，但 android12-5.10 / android13-5.15 缺实际目标 KCFI callback contract，整体 BUILD-EXECUTION 为 FAILED/exit1；其余四族该轮 metadataError 为 null。六族 `diagnostic-six-assets-14` 已封包，收据明确 `PACKAGED_UNQUALIFIED_NOT_DEVICE_ACCEPTED`、formalRelease=false、deviceQualified=false，未借诊断包结算正式交付。
- Manager host7 的 CP9/F18/session-signature 编译为 COMPILED/exit0、inputsStable=true；bridge11 原严格 Kbuild/handoff 成功及 recovery2 的 session ABI/package exit0 已独立绑定。原失败执行和恢复执行均保留，不提升为APK或原机通过。
- candidate14-1 实际因 `PackageUltraManagerClaim is a non-static inner class` 失败；最小 Gradle 源修当前 SHA256 为 `53a8279edbaa905cc59f006060625cf22ab7033bb6e51e62700bea0badaf2858`。candidate14-2 已推进 provider 构建，但 Rust 输出遇 `No space left on device`，inputsStable=true；原 `build.json/build.log` 首错已保全。candidate14-3 已启动，本次取证状态为 `BUILDING`，尚无本次正式APK或设备验收结论。
- 模块更新后 marker 丢失竞态已完成源码修复与固定测试代理真实 shell 红绿；真实在线更新入口、Manager重建、完整回调/开机生命周期与原机仍分别待验。既有 loader17、family verifier17、thin helper4、F14等夹具保留原范围，本次未重复审计、编测或执行ADB。

当前关联收据：`.tmp/supervision-20261121/main12-acceptance/CANDIDATE14-EVIDENCE.json`、`ACCEPTANCE-LEDGER.json`、`DELIVERY.json`；构建原件位于 `main10-generation/claim-{freeze,build}-14`、`diagnostic-six-{assets,evidence}-14`、`manager-host-cp9-f18-session-signature-7`、`release-device-candidate14-{1,2,3}` 与 `main12-owner/candidate14-2-provider-first-error/`。以下历史段落保持各自时点。

## 2026-11-22 追加：管理器在线更新模块后卸载失败

用户新增 `ADD-MOD-UPDATE-UNINSTALL-01`：模块列表“显示更新”直接更新安装后偶发卸载失败，需重装旧版方可卸载。已作为独立必修/待验项写入需求、CASES.json的additionalCases及DEVICE-PLAN；原29子项保留，新增1项单列，总计30子项。通过标准为按真实入口更新后直接卸载及既有开机收尾成功，无需重装旧版，覆盖重启前后、管理器重建、失败重试和多轮更新卸载。当前生产主线和native_registry均已实际ACK，独占八份模块树/后端/事务/repository/viewmodel源码追踪；接单回执为`.tmp/supervision-20261121/main11-owner/ADD-MOD-UPDATE-UNINSTALL-01-ACK.json`。尚未完成修复或实机验收，后续由固定test_owner执行验证；旧CASE-12回执匹配修复及22/22 JVM结果不结算本项。

## 2026-11-22 原机身份复核与正式包验证门禁回归

- 00:50 UTC，指定原机 PLQ110/a261972a 以 ADB UID 2000 只读复核：4 KiB、`_a`、boot ID `7ff19abb-1fa2-4552-b844-147eedef0e28` 前后一致；Manager 5.2.1 已装基础 APK 的 SHA-256 与留存 r23 诊断包完全一致。Android 开机完成不等于 Manager Ready；本轮未读保护标记、未执行 repair、未更换 APK。上次保全首错为 `root-environment=-19999 / filldir64: ERR_MODULE_TEST_CHANNEL`。
- Manager 完整 `compileReleaseKotlin`、`compileReleaseJavaWithJavac` 于 2026-11-21 18:38:13 UTC 成功，仍绑定留存 r23 SDK。M03 两份生产源码和测试 SHA 与采用及编译记录一致；原机 UID 2000 源码回放 16/16、三个中文路径实际提取通过；已装 r23 的历史实际 parser 仍为 14/16。未重新运行已知失败，未验收实际模块生命周期。
- `scripts/verify-release-apk.py` 将最终 APK 哈希与元数据复核放到清单复核之后、标记成功之前。新增负例证明旧门禁在末项清单读取期间遇到 APK 字节改变仍返回成功；修后全套 36/36 通过。仅核验门禁和测试变更，未构建 APK、签名或安装。
- 历史 r25 四源已采用，manifest 260 成员无错；条件 KMI/stage 组合 49/49，未打开 RESIDENT/PUBLISH。上述结果不结算当前全源 clean、运行时资格或最终 APK。保留历史审核中断及部分产物。
- 原 `2026-11-21 23:30 UTC` 构建与 `2026-11-22 00:00 UTC` 交付未达；监督转达的新交付时点为 `2026-11-22 06:50:50 UTC`，尚无正式包或启动回执。119 项完整勾选保持 SDK-01/07/08，最终 CASE **0/29**。

本轮证据：`.tmp/supervision-20261121/handback-20261122-0050/` 下的 `DEVICE-BASELINE.json`、`ACK-{acceptance,manager}.md`、`DELIVERY-{acceptance,manager}.md`、`RELEASE-GATE-{BEFORE,AFTER}.log`。

## 2026-11-21 原机源码回放与限定完整链接增量（2026-11-21T18:36:00.548204+00:00）

新源码已在原设备UID2000完成8原包两模式16次解析：旧源码14/16，新源码16/16，三个中文路径实际提取通过，原包SHA及前后boot `7ff19abb-1fa2-4552-b844-147eedef0e28`保持。Root、真实模块安装、脚本和生产控制写入均0。该证据为真实Java parser及extract逐字函数端口；已安装r23产品尚未变化，其实际parser14/16失败记录继续保留，不结算产品或模块生命周期通过。首轮系统Killed/exit137原因未定、空列表夹具断言纠正及原失败收据均保留。

主线已核前像采用resident条件下的 `native_imports.c/h`，采用收据为 `provider-registry-intake-main7/TYPED-IMPORT-ADOPTED.json`。新限定完整模块链接通过：44个r6冻结对象加两个新目标TU，实际模块链接、严格modpost、`.mod.o`、`.ko`及ARM64 postlink均通过；baseline裸 `__module_address` 被严格modpost准确拒绝，最终get/put为真实T定义、五个wait CRC与目标匹配。最终ko SHA `729ac2b6f8379598deeae36adde474ab809e3428ae01bc49d922bd8ca155c018`。这不是全源clean或产品Release；运行时binder、PUBLISH及原机发布未验，PUBLISH gate仍0。

SU真实batch/guard/cleanup源回放25场景605检查通过，unknown错误释放变异被捕获；未证新增Bug，生产修改0，三个关键源与r23实际选源SHA一致。真实provider发布/排空、lost-reply/unknown协调和原机SU效果仍开放。

stage当前37测试、45 failures/21 errors为主线本轮真实工具观测，完整首轮原始日志尚未落文件；共同首错缺 `preinit/runner.c`，旧前像对照未完成，尚未归因本次修改，未记全stage通过。已有私有include闭包限定绿；原审查任务已接续前像/当前真实日志与最小fixture核实。Manager完整 `compileReleaseKotlin`/`compileReleaseJavaWithJavac` 于18:33:28 UTC启动，记录时状态 `RUNNING`，沿保留r23 SDK，尚无通过结论；未构建APK或新SDK。

当前产品仍r23 safe3/`root-environment=-19999`，没有新SDK/JNI/Manager产品或生产环境repair。119完整勾选仍SDK-01/07/08、29CASE整项闭合0；全部范围、主线active/监督paused及23:30正式构建/00:00争取交付时点保持。后续出口为当前全源clean桥与同代选源、实际typed/KMI绑定、producer/root-loader v2、真实PUBLISH/DISABLE/RETIRE及在途排空，继而SDK/helper/backend/JNI/APK与指定原设备完整验收；源端口与限定链接不合并为产品通过。

证据根`.tmp/supervision-20261121/`：`main7-module-unicode/device-original8-observed2/RESULT.json`、`main7-typed-module-import/{FULL-LINK-RESULT.json,DELIVERY-full-link.md}`、`main7-su-rollback/{RESULT.json,DELIVERY.md}`、`main7-manager-compile/RESULT.json`。新文档收据：`main7-document-update/RESULT-source-device-link.json`；前三轮收据保持。

## 2026-11-21 安装测试判据与provider源码采用增量（2026-11-21T18:18:47.273570+00:00）

`RealModuleInstallAndroidTest` 的假绿判据已修并经原机验证：18:15:14–37 UTC，UID2000执行真实测试方法与外部seams，缺包、解析失败、安装错误、未列出、unknown、archiveError、未提交七种错误由旧假绿转为断言失败，成功控制仍通过；状态 `PASS_ORACLE_BEFORE_AFTER_8`。前后boot仍 `7ff19abb-1fa2-4552-b844-147eedef0e28`，Root、真实模块安装、脚本执行及生产控制写入均0。该证据证明验收判据捕错，未证明真实模块安装或生命周期通过；已安装r23原包parser结果仍为14/16，M03两模式INVALID_ARCHIVE_PATH。

主线已核对前像SHA，把native-compat `adoptOnly` 四文件采用到源码，并补 `scripts/build-ultra-manager-claim.py` 与 `runtime/ultra/boot/init-bridge/stage.py` 的provider私有include闭包；当前文件SHA与PLAN四文件后像匹配。`provider-registry-intake-main7/PLAN.json` 仍为采用计划/前像记录，完整后验收据尚未完成，本次不结算新编译通过。RESIDENT与PUBLISH仍关闭、源选择未完整、新产品尚未构建，既有候选TU通过不转记本次整合成功。

`main7-typed-module-import` 已实际ACK，仅负责独占目录内typed/KCFI单依赖候选；`main7-module-unicode` 已实际ACK，M03 Unicode最小修复实施中，baseline46项4红保留，修后结果未验收。原legacy/producer审核中断任务继续保持中断状态。119条需求、SDK-01/07/08原三项完整勾选和29CASE最终Release闭合0均保持；正式时点及其他开放项沿前文，不扩大本次文档范围。

实际证据：`.tmp/main-rootpanic-resume-20261121/module-install-oracle-main7/device-first/RESULT.json`、`provider-registry-intake-main7/PLAN.json`，及`.tmp/supervision-20261121/main7-typed-module-import/ACK.md`、`main7-module-unicode/{ACK.md,tests-before/RESULT.json,tests-before/junit.log}`。新文档收据：`main7-document-update/RESULT-oracle-intake.json`；原两轮RESULT/DELIVERY保持。

## 2026-11-21 原机归档解析增量（2026-11-21T18:11:06.678103+00:00）

用户已手动完成厂商验证，18:09:10–11 UTC 实际续接已安装r23的只读parser：8个原包两模式共16条，14条PARSED；仅M03 `TEE-AutoList_5.2.2.zip` 在两个模式均返回 `INVALID_ARCHIVE_PATH`。原8包SHA逐一保持，产品APK `3152f2be…`、调用UID10498、前后boot均 `7ff19abb-1fa2-4552-b844-147eedef0e28`；Root、模块安装、脚本执行及生产控制写入均0。本结果是当前产品实际归档解析，不结算模块安装/生命周期、产品环境恢复或29CASE完整通过。

首轮安装120秒超时与原文档交付均保留；此前“等待人工验证/16条未运行”仅为当时状态。主线独占调查M03路径拒绝来源，生产parser尚未修改；`RealModuleInstallAndroidTest` 假绿判据修复仍在进行，尚未记通过。其他安全模式、provider、正式构建与验收缺口保持，119条需求及SDK-01/07/08原完整勾选均未变。

实际设备证据：`.tmp/main-rootpanic-resume-20261121/modules-original8-r23-readonly/device-resume-after-manual/RESULT.json`。文档增量收据：`.tmp/supervision-20261121/main7-document-update/RESULT-archive-parser.json`。

## 2026-11-21 MAIN7 当前接续（2026-11-21T18:08:27.103784+00:00）

当前唯一生产采用、完整构建与原机负责人为主线（7）`01a1ff3b-8b47-7411-81b9-da494e110a3b`，goal 为 active；监督 `01a1ff24-054a-76a2-8261-a8eb9be292fd` 的连续 goal 为 paused，仅每小时 :00/:30 巡检。以下历史段落保留各自时点，以本段及 JSON 的 `main7Current20261121` 为当前状态。

- 两个实施任务均已实际 ACK：`legacy_candidate_main6` 随后被自动审核中断，`resident_producer_main6` 随后 HTTP403/session blocked，均无最终交付。保留四源候选、PROPOSED-ABI 和部分 Kotlin；旧代理状态不记为仍在运行，未通过换代理重做被拦动作。新四文件采用审查与本文档任务均已有 TASK/实际 ACK。
- r25 已于17:52:53–55在原设备 UID2000执行 Hook40、m024七场景，全部通过，前后 boot 均为 `7ff19abb-1fa2-4552-b844-147eedef0e28`。Root、gateway、kernel text 与生产控制标记写入均0；这是源函数端口回放，尚未采用到生产，也不结算内核发布或产品恢复。
- provider 已交 `DELIVERY.md/DELIVERY.json/ADOPTION.patch`，只审 `adoptOnly` 四个 management/provider 文件，同目录三份旧 SDK 副本保持冻结。registry167、scope156、共享生命周期35项源回放及真实6.6 ARM64 KCFI/PAC/SCS management TU通过；完整链接、实际 PUBLISH/原生同址共存、SU回滚/unknown及原机验收仍开放。新增 `__module_address` 在目标 Module.symvers 无模块导出，typed/KCFI绑定资格尚未验证，其余五个新增等待符号已有真实导出。接口只采用 v2，保持 slot64/binding32/现有 anchor 字节及版本。
- 产品最后可信仍为 r23：APK `3152f2be…`、SDK `maplesu-ultra-source-51f6b3a749d084d0`，原设备 PLQ110、UID10498、上述 boot、版本5.2.1、safe3、保护文件存在、enable缺失；本 boot 首错 `root-environment=-19999`、`filldir64 ERR_MODULE_TEST_CHANNEL`。Ready、SU、模块整链尚未通过；原始SDK门禁182通过/78失败/exit7原样保留，78成员差异已独立归因，完整两次 clean SDK/六族构建仍开放。
- CASE-01–20共29子项、8原包SHA和指定设备/ABI/生命周期要求完整保留，最终Release整项闭合0。8包已推到原机并逐包校验SHA；已安装r23的16次只读parser尚未运行。首轮诊断包安装120秒超时已保留，当前停厂商拼图验证，待用户在手机上手动完成；模块安装与脚本执行均0，M08原包未执行，不把解析进度记为生命周期通过。
- 本轮8份冻结mapping/报告无损压缩完成，逐文件SHA/大小/mtime保持，零删除/移动，D盘实际余量增196612096字节。18:05附近主线实测 C312414208/D1161322496字节可用，尚未证明足够正式构建。23:30 UTC（2026-11-21）启动最终正式Release、争取00:00 UTC（2026-11-22）交付的目标保留；诊断候选不改名充当正式构建。

全部P0–P8、119唯一需求、20约束、两组10项及追加要求保持；完整勾选仍仅SDK-01/07/08，本轮新增完整勾选0。源码、选源、产物、主机回放、源端口上机和实际产品效果分别记账。

证据根 `.tmp/main-rootpanic-resume-20261121/`：`safe-mode-all/native/boot-legacy-fix/device-r25-main6/RESULT.json`、`modules-original8-r23-readonly/device-first/RESULT.json`、`space-main6/round2-text/attempt2/RESULT.json`；监督交付根 `.tmp/supervision-20261121/`：`native-compat/{DELIVERY.md,DELIVERY.json,tests/resident/LINK-DEPENDENCIES.json}`、`case01-20-acceptance/RESULT.json`、`main7-document-update/{ACK.md,RESULT.json}`。

## 2026-11-21 17:25 UTC r23原机UI提交与新boot首错

- r23同代Manager构建成功，114任务30执行/84复用，输入稳定；签名、封包、SDK/JNI、bridge配对核验通过。APK `3152f2be…`，SDK `maplesu-ultra-source-51f6b3a749d084d0`。原始SDK成员门禁182通过/78失败/exit7原样保留，独立源码review不提升原始门禁。
- 已保数据安装r23，UID10498与Key私有摘要保持。真实设置→修复→确认已越过资格检查、进入生产SDK安装，随后观察到新boot `7ff19abb-1fa2-4552-b844-147eedef0e28`、boot_completed=1。只结算实际调用与重启，不据此宣称完整成功。
- 原机保护状态回读：真实版本文件`skroot.sdk.ver`=5.2.1，helper1779776字节，safe3、空保护文件存在、duplicate本boot、work/completed为空、enable缺失。实际boot_a仍`73c956c5…`；本boot helper日志明确root-environment=-19999、filldir64 ERR_MODULE_TEST_CHANNEL。首次补采用了逻辑名VERSION，已另外按真实生产路径重读纠正；首份原始记录保留。
- repair资格精确源函数已在原机UID2000回放：native12/0、ART23/0，VERSION顺序mutation精确红例被捕获；无Root或生产control写入。该结果与本次真实UI已进入SDK安装分别记账，没有放宽Unknown守卫。
- Legacy隔离候选补齐PAC/branch/身份重入核验和长期remap backing归属；主机32项Hook与6项remap通过，原始红例保留，原机源端口回放正在执行。首个before之后的显式handle SU及SELinux shared-bool仍是独立缺口；当前生产源尚未采用此候选。

证据根`.tmp/main-rootpanic-resume-20261121/`：`device-r23/{verification/APK-VERIFICATION.json,INSTALL-RESULT.json,ui-repair/RESULT.json,post-repair-identity/RESULT.json}`、`safe-mode-all/manager/fault-repair-admission/device-fixture/device-first/RESULT.json`与`safe-mode-all/native/boot-legacy-fix/`。119个唯一需求ID完整，完整勾选仍仅SDK-01/07/08；20约束及两组10条件保持。

## 2026-11-21 16:31 UTC 文档派工确认与 r23 同代 Manager 构建

- 文档派工已核实 7 份 ACK；新增 repair 资格核查和 Legacy 候选独立审查。SDK 实际交付路径已纠正为 `generation-r23-resume2/DELIVERY.json`。
- r23 SDK 完成 staging，身份 `maplesu-ultra-source-51f6b3a749d084d0`；正式 bootstrap/final 配对、源码闭包及独立 review 通过。原始成员门禁仍为182通过/78失败/exit7，首次缺符号与第二次循环抽取失败均保留。
- 主线已核验18个当前构建输入/收据摘要，启动同代 Manager 构建；APK尚未生成或安装。本代含14源修复与publisher最小选源接线；Legacy候选保持隔离，当前provider缺口尚未结算修复。
- 设备仍以原机真实 UI repair、失败回执、helper本boot日志及Refresh/SU/Ready为后续判据，保留Key/授权/模块与原备份，119项完整勾选不增加。

证据根 `.tmp/main-rootpanic-resume-20261121/`：`safe-mode-all/TASK-DISPATCH.json`、`generation-r23-resume2/DELIVERY.json`、`generation-r23-manager/{INPUT-VERIFICATION.json,COMBINED-EXECUTION.json}`。本段源码/构建进度不代表原机生产环境验收。

## 2026-11-21 16:20 UTC Normal原机回放与SDK依赖闭包修复

- 文档任务已取得5份实际ACK，送达与交付状态见 `safe-mode-all/TASK-DISPATCH.json`；不再以空消息Payload代表派工成功。gateway/Legacy兼容定位及Normal源回放已交付，SDK同源续建与Legacy内置Hook实施分别推进。
- 原设备UID2000执行Normal精确源函数：按键/锁存16场景99检查、Rust属性短路128场景384检查均0失败；Root0、生产状态写0、boot8509保持。两个Android安全模式属性实读均为空。首次query为异步stop，worker完成前仍有在途事件；实际Normal内核冷启动/输入/ioctl/模块禁用效果不由这些夹具替代。
- r23-resume1已消除原helper缺符号，但final260稳定性门禁真实exit5，捕获publisher放m053后引入payload安装器循环。已验证并采用第二个最小选源修复：共享publisher归直接调用者m055，m048/m053不重复包含。隔离实际bootstrap/final helper摘要一致 `446fcf9f…`，backend配对通过；两次正式失败均保留，后续独立resume2不放宽门禁。
- 当前root-environment首错并非Android属性，而是重构后m029全部依赖原Image未包含的自定义provider。最小兼容实施先限定原API无显式handle的builtin boot生命周期；有handle/actor/shared-bool和Owner路径保持原合同，跨CPU发布、在途资源及重复安装先验证，不以省略初始化或伪造Ready达成绿色。

证据 `.tmp/main-rootpanic-resume-20261121/` 下 `safe-mode-all/{normal/device-first/RESULT.json,normal/device-readonly/RESULT.json,native/gateway-fix/DELIVERY.md,native/legacy-compatible-path/DELIVERY.md}`、`generation-r23/resume/{ADOPTED2.json,pairing-probe2/RESULT.json}` 与 `generation-r23-resume1/EXECUTION.json`。本轮复核119个唯一需求ID仍完整，完整勾选仅SDK-01/07/08；整套安全模式修复及软件环境验收尚未完成。

## 2026-11-21 16:01 UTC 文档派工送达、r22原机结果与r23续建

- 已改用落盘任务正文＋完整上下文继承，gateway代理实际读取并写下 `safe-mode-all/native/gateway-fix/ACK.txt`；不是仅发送成功。SDK续建任务独立落在 `generation-r23/RESUME-TASK.txt`，要求单独ACK，避免空Payload继续空转。设备仍由主线统一操作。
- r22已完成同签保数据安装（UID10498、Key私有摘要不变），正式生产恢复方法在原机取得完整原核 `346d51ea…`；实际boot仍 `85092eb5-28bd-483d-bf22-e666ee059d37`。本轮UI仅完成正常冷启动/页面观察，Unknown缺版本时修复按钮隐藏，未提交SDK安装、未新增分区写入或重启；软件环境尚未完成恢复。
- 当前boot只读探针（Root0、EXEC0、publication0、Application.onCreate未运行）实测gateway descriptor存在、capabilities=7，但私有尾magic/schema/bytes全0，provider get/put均缺失。该证据与 `m070→m075→m071→m029→Legacy provider` 的-19999失败路径对应；缺provider的源修复仍由gateway代理定位，不填伪地址、不跳过ownership/失败检查。
- 已采用helper3、handoff7、Manager4共14个生产源文件。原设备限定源回放分别为：helper旧33项7失败→新33/0；安装时序旧47项27失败→新47/0；publisher旧6项2失败→新14/0；Manager ART绿色82项（旧UI2失败、失败回执14失败）。publisher使用独立临时目录和user-xattr测试seam，不冒充生产SELinux验收；ART不是实际Manager UI或SDK安装验收。
- r23首次同源SDK构建真实失败（producer exit9）：helper链接缺 `maplesu::install::publish_empty_state(char const*, char const*)`。原日志/收据保留；续建先纠正实际依赖接线，保留260门禁与独立source/provider/ABI核验。当前r22仍使用r20 SDK，14源修改尚未作为完整同代包安装。

证据根 `.tmp/main-rootpanic-resume-20261121/`：`device-r22/{deliver-RESULT.json,green-final-RESULT.json}`、`safe-mode-all/{ADOPTION/,helper/android-arm64/device-root/RESULT.json,handoff/device-ordering/RESULT.json,handoff/android-publisher/device-first/RESULT.json,manager/art/DEVICE-RESULT.json,native/passive-metadata/no-startup/device-r22/RESULT.json}`、`generation-r23/{EXECUTION.json,producer.log}`。安全模式矩阵22类入口/保留/退出及8项覆盖缺口继续逐条保留；全部119项、20条约束与两组10条件不缩减，不新增完整需求勾选。

## 2026-11-21 15:26 UTC 安全模式全链并行排查与原机证据

用户要求子代理定位并修复所有安全模式原因；已分派 helper 启动状态机、Manager 修复入口、安装提交衔接、native root-environment、入口/验收矩阵五个独立范围。生产候选保持隔离，原设备由主代理统一操作；当前 Manager r22 分类构建输入冻结。

- 原机 boot 仍为 `85092eb5-28bd-483d-bf22-e666ee059d37`、boot_completed=1。新增一次只读 JNI Root 调用收集实际 `init.log`，无分区/控制文件写入。日志曾记录 root-environment=-19999、filldir64 ERR_MODULE_TEST_CHANNEL 和 bootstrap protection retained；每行缺 boot 绑定，故不把历史失败精确归到本 boot。证据 `safe-mode-all/device/init-RESULT.json` 与 `init-decoded.log`。
- 已明确：boot_fail_protect 空文件按存在即开启；旧 safe_flag=3 可跨 boot 继承保护，提前退出会保留旧 work/completed。kernel_verified 只结算内核更新，不代表环境软件安装成功。RECOVERY_SUSPENDED 是下游保护状态；readUnknown 也可能仅因 VERSION 缺失，二者不独立证明新的安全模式根因。
- 限定缺陷验证在进行：安装失败前过早清保护标记、helper 实际 open/fsync 的 EINTR 处理、Unknown 缺版本时 UI 修复入口被隐藏。另沿真实 native filldir64 测试失败继续定位。上述候选尚未合入/上机，不提前报修复完成。
- r22 首轮 C 盘耗尽，续建 R8 报 Deflater has been closed，两次失败均保留，均未安装。自己的生成构建目录迁往 D 时遇 Windows 句柄，已通过保留原件并补齐复制形成完整 D 构建工作区，第二次续建 `generation-r22-resume2/` 进行中。当前使用既有 r20 SDK/JNI，不重写原件/Key/授权/六份备份。

随后按同源候选红绿→统一合入构建→真实 UI repair→原机启动/保护退出/Refresh/SU/Ready 验收推进。源码、主机、源测试、已安装生产效果分别结算；119 项完整勾选保持。

## 2026-11-21 14:55 UTC 内核分区与事务闭合，软件修复首个静默返回待定位

已通过生产installer service读取任务`c177e146-7edd-467d-aee1-6e3bc31caa25`：boot/_a、target LITE、state REBOOT_REQUESTED、error空、rebootObserved=true、operationActive=false。独立只读Root命令读取实际`boot_a`完整SHA `73c956c597827c6bf70f2b536c12d56de55c5663f2f6013cf39de7684fb9662d`，与candidate/partition收据相同；不只是重启时间相关。新boot仍safe_flag3、enable缺失、work/completed旧7e08、duplicate为8509，说明软件环境尚未恢复。

在新boot第二次实际UI保数据修复，预检导入的镜像已是73c956…，完整恢复仍得原核346d51…，未再报CURRENT_MISMATCH。但本次返回preflight、failure空、submittedBootId空，没有SDK提交或重启。之后真实readFacts：running/suspended/quiescing/submitted均false、installedVersion=null、bundledVersion=5.2.1、双方helper归一摘要相同、App5.2.0→5.2.1、readUnknown=true。页面同时有current_boot_protection/Fault记录；这些后读事实不冒充当次拒绝分支。

r22只给现有显式repair的state/facts/scope返回记非敏感原因，资格、超时、Key/boot/模式及一次提交守卫逐字保留，继续同代SDK/JNI构建，再设备定位首个实际返回。首次诊断readFacts的R8参数被优化，接线错误保留，按真实残留签名修正后才读得上述事实；未据测试错误改生产行为。

证据`.tmp/main-rootpanic-resume-20261121/{root/installer-status-r21/device.log,device-r21/live-state-8509/rootops-live8509.log,device-r21/ui-repair-8509/,root/repair-facts-r21-v2/device.log,generation-r22/}`。完整Refresh/SU/Ready仍未结算，119完整勾选保持。

## 2026-11-21 14:46 UTC r21生产原机绿色与内核修复重启

r21续建114 tasks（21执行/93复用）成功，首次磁盘失败及损坏executionHistory丢弃记录保留。全输入续建前后快照稳定，签名/封包/SDK四payload与bridge校验通过；SDK/JNI仍是r20同代，APK SHA `5dc20d45612834601ab3c21bca2ef626236399385d90676c964776a12d4f2e28`。原机保数据安装成功，UID10498、Key与私有摘要基线相同。

- r21正式release路径的实际生产恢复方法，对同一设备私有导入boot829e48…返回完整原核346d51…，原输入摘要未变；与r20同机CURRENT_MISMATCH形成设备红绿。该调用Root0、不写分区，不只算源测试APK通过。
- 实际设置→修复→确认后，生产状态已落`kernel_verified`、lastFailure为空、原boot5520的提交标记保留。设备随后进入boot `85092eb5-28bd-483d-bf22-e666ee059d37`，sys.boot.reason=`reboot,`、boot_completed=1；未用shell直接写分区或手改control。保护安装事务的独立状态/分区身份回读继续进行，不仅凭发生重启结算完整成功。
- 重启后正常Application+MainActivity实际启动，仍`ready=false / RECOVERY_SUSPENDED / STATE_Fault`，环境版本尚缺。故当前只结算内核恢复识别与kernel_verified阶段，不称完整工作环境/Refresh/Ready通过；接着走真实软件环境修复与回读。未放宽超时或伪造就绪。

证据根`.tmp/main-rootpanic-resume-20261121/`：`device-r21/{deliver-RESULT.json,verification/APK-VERIFICATION.json,device-recovery-RESULT.json,ui-repair-5520/,post-repair-local.log,startup-8509/}`、`root/recovery-production-red-r20/device.log`及`recovery-migration-r21/NTFS-CONTENT-PRESERVATION.json`。原119项完整勾选保持。

## 2026-11-21 14:32 UTC 原机ART回归32/0

恢复迁移相关32项测试已在原机a261972a的Android ART执行，32/0、UID10524、Root调用0；覆盖确切归档优先于旧嵌入inverse、额外kernel改动拒绝、未知镜像继续严格失败、元数据损坏拒绝、原输入不变及现有恢复/升级识别。此为当前生产源码编译的源测试APK，不冒充已安装Manager或完整内核写入/Ready验收。r20原生产恢复方法也在同机直接复现CURRENT_MISMATCH，设备红例保留。

初轮r21 release在Gradle持久缓存写入遇D盘空间耗尽，原raw失败及wrapper收据写入errno28（空文件）保留；未通过产物不安装。空间已回升，复用已编译输出继续同源构建，内核更新将继续走生产事务。新JUnit用例首轮有目标输出预先存在的测试接线错误，已定向修正；独立当前源码主机32/0及旧排序红例通过。源绑定与首次失败均单独记账。

证据`.tmp/main-rootpanic-resume-20261121/recovery-migration-r21/{art/DEVICE-RESULT.json,HOST-SOURCE-BINDING.json,host-final/RESULT.json,BUILD-RESUME.json}`；后继构建`generation-r21-resume/`。119项完整勾选不变。

## 2026-11-21 14:20 UTC 原机前台首错闭合与恢复迁移修复

- 用户误报判定后的继续执行保持active。r20原包已恢复安装，原机UID10498、Key与原备份保留。约13:55 UTC设备重连进入boot `55204512-ba37-455f-86ca-2cc46e2453d3`；未主动发起该次重启，具体原因尚待核对，不归为已证Panic。
- 后台准备无终态时，独立只读observer捕获线程`do_freezer_trap`，恢复后同次部署成功；8次纯只读Root命令全返回成功。该证据不支持继续修改pipe/重试/锁或延长超时。早期辅助monitor栈和跳过Application初始化造成的Activity异常，均与生产prepare首错分开记账。
- 真实Application+MainActivity前台调用生产Context Refresh一次，准备阶段`ALREADY_BACKED_UP;IDENTITY_UPGRADE_PREPARED`通过；SDK约11.463秒返回`ERR_MODULE_NOT_KADDR/-19987`、submissionUnknown=true。默认verified字段不代表完整环境通过。当前版本仍缺、enable缺失、boot-session仍旧；未手补control或恢复旧授权，精确授权失败phase仍待动态确认。
- 实际UI“修复工作环境”在preflight失败：`ULTRA_KERNEL_RECOVERY_CURRENT_MISMATCH`，submittedBootId为空，无SDK提交或预期重启。新设备私有导入镜像SHA `829e4866dbdd38c92a73edaecb16611dcbe7a05291d5714b06619e7622694896`，与历史executor诊断镜像逐字节身份一致。
- 已证其嵌入inverse仍描述诊断前镜像：146范围中仅第2项(kernel offset261696/432B)摘要失配；历史3指令改动位于该范围，52B trampoline还在旧inverse之外。逐项逆转历史增量后完整boot恢复至原source SHA，再由全部原inverse验证恢复原kernel SHA `346d51ea1f63413ffa5d99c5c426806291af3573f0a1d66a7621c388e835880e`。新增逆记录只含原字节与当前范围摘要，不含replacement或Key。
- 生产最小改动：复用既有整kernel摘要归档通道，在嵌入inverse前优先验证确切归档；explicit repair对已验证归档旧图进入既有内核更新流程。没有全局吞异常、版本猜测或降低未知/损坏镜像门禁。r21复用r20 SDK/JNI同代构建中，尚未安装，不计完整设备验收通过。

证据根`.tmp/main-rootpanic-resume-20261121/`：`root/preparation-passive-observer-r20/device-first/`、`root/rootcmd-series-r20/device-first/`、`root/refresh-production-r20-foreground/device-first/RESULT.json`、`device-r20/ui-repair-5520/`、`root/recovery-image-identity-r20/device.log`、`recovery-migration-r21/ARCHIVE-PROOF.json`。原119项、20约束与两组10项保持，不新增整项勾选。

# MapleSU SDK 重构进展与剩余工作

## 2026-11-21 13:39 UTC r20上机与准备阶段回执挂起定位

r20已同签保数据安装，UID10498与boot368b5b24保持；真实Context Refresh一次在原120秒观测窗口内未取得终态。当前boot只读回读确认版本文件缺失、helper1776896B、WebUI353912B及三份su1154104B；摘要在设备收据内。未将旧boot指针或载荷状态冒充当前结果。

最小化到真实生产backupKey准备函数（未调用installEnvironment），原机场景仍55秒无终态。隔离Root边界观测已证明最后一条Root命令获取Root成功、popen成功、read EOF、pclose状态0、子回执发送成功，但父侧尚未读取到首个错误码字段，故继续核对同一pipe身份与接收端；此时尚未证明FD重用或具体根因。诊断关闭整TU与r20对象一致，基线JNI重链与r20逐字节一致，仅archive成员35/60和APK JNI条目变化，不作为新生产代际。

诊断辅助记录另账：新prepareprobe安装被ROM交互页阻塞，未执行功能；后续复用已安装refreshprobe包。一次线程栈观测实际抓到辅助monitor的Native调用而非目标prepare入口，该栈不用于断言生产准备首错。全程未清数据/Key/授权，未主动重启或写分区；完整Refresh、Ready和五冷进仍待通过。

## 2026-11-21 13:21 UTC 按用户误报判定恢复设备实测

用户最新已将此前暂停事项判定为误报并明确要求继续上机实测；历史现场与失败证据保留，旧暂停字段不再作为当前执行停止条件。当前目标active，保原数据/Key/授权/六份备份，核对boot368b5b24、UID10498及仍安装r18后，执行同签r20安装、当前boot环境回读、精确绑定后的真实Refresh。尚无新增整项验收通过。

## 2026-11-21 13:15 UTC r20产物校验完成，原机局部回归通过

r20同代SDK/helper/backend/四payload/JNI/签名APK已构建并完成交付校验。SDK `maplesu-ultra-source-59ed0d2b9a34b2d7`，APK SHA256 `c9669b6a6754abae35ed8e6b69410c80f8e414d3b8f450a24e4bbdfcc9a851bb`，JNI `58665e62a037968790f83c7767e50699e95c2fd4803708eb7fd02226f3c62d5e`。包含存储存在性错误分类修复和r19准备SDK-Key通道修复；该APK尚未安装，后者原场景仍缺设备绿色结果。

- 原机真实读函数体7项：旧1失败→修复后7/0，当前源码重编复跑仍7/0；新窄观测版同7项通过，实测捕获stat EACCES13和open EACCES13。仅路径选择使用临时目录seam，均UID2000、无Root/legacy执行器调用；不算完整SDK授权刷新、普通应用侧信道或Panic通过。生产Android21整TU摘要与r20实际core对象逐字节相同，测试源码与产物接点有独立绑定。
- SDK按编译命令、当前依赖内容、旧对象摘要核验复用293对象、实际14次编译，非clean。原raw exit7继续保留，独立来源审阅与stage通过；原C盘stage请求被既有“候选须仓库内”守卫拒绝，失败收据保留，后继仅把stage位置放回仓库内，SDK大构建输出仍在C盘，未放宽门禁。Manager release成功，签名/封包/SDK四payload及bridge完整配对校验通过。2388份Manager源与既有r19快照一致，并在本次release期快照到封包后保持稳定；此快照采集晚于Kotlin编译，不称新增编译前快照。
- 精确诊断已改为观测真实`filesystem::exists`结果、error_code/类别及失败时ifstream实际errno，关闭时整TU与生产逐字节相同；原机临时路径已验证其日志准确性，受保护存储实际失败尚未观测，不继续沿用“access成功→ifstream失败”的推断。
- 设备最后只读核对仍boot368b5b24、UID10498，安装包仍r18 storage-scope `a9186d0d…`；未清数据、未改授权、未主动reboot或写分区。旧boot载荷已发布/版本未提交的读回与新boot运行状态分账，新boot实际运行状态尚未对齐。Panic相关触发保持暂停，完整Refresh/Ready/五冷进及Panic不新增验收勾选。

证据：`.tmp/main-rootpanic-resume-20261121/device-r20/{deliver-RESULT.json,verification/APK-VERIFICATION.json}`、`generation-r20/{COMBINED-EXECUTION.json,CONTINUATION.json}`、`root/storage-read-errors-device/{AFTER.json,PRODUCT-BINDING.json,ADOPTED.json}`、`panic/storage-status-observation-r20/{PREPARED.json,DEVICE-FIXTURE.json}`及`panic/offline-368b5b24/REPORT.md`。原119项、20约束和两组10项保持，正式5.2.1尚未交付验收。

## 2026-11-21 12:59 UTC 存储错误分类源码修复与原机红绿

已采用最小源码修复：`__read_disk_blob`使用`std::filesystem::exists(path, error_code)`保留实际状态错误；只有确定的缺失才返回`ERR_MODULE_STORAGE_NOT_FOUND`，权限/IO等状态查询失败返回`ERR_MODULE_STORAGE_READ`。原二进制读取、打开错误、类型与发布行为保持，没有新建或改写受保护控制文件。

- 原机a261972a/UID2000以真实生产函数体、真实Android filesystem/ifstream执行7项；仅路径选择为临时目录seam。旧版权限场景误报-19964，7项1失败；修复后7/0，二次当前源码重编重跑仍7/0。覆盖缺文件、缺父目录、父项非目录、二进制内容、stat权限失败、open权限失败、空文件。无Root调用、无内核执行触发、无分区写入，boot仍368b5b24。真实Android21整TU也编译成功。该局部红绿不等于完整SDK Refresh已经通过。
- 更正前轮定位：源码在存在性检查失败时返回-19964，ifstream打开失败返回-19962；此前仅有控制文件access(F_OK)=0的诊断，尚缺实际`filesystem::exists`错误码及授权事务首错phase。当前不把控制文件的ifstream失败或FSuid异常写成已证根因。
- 新Panic离线：PID/TGID20774、任务年龄23.53ms、首次BTI取指失败；完整dump未覆盖故障页/父task/请求包。保存用户LR匹配r15/r16诊断桥`0b70280a…`的`0x4b5e14`，并验证BL到`__wrap_syscall`再尾跳`syscall`、LR保留。已查12份去重桥产物，本次当前安装桥不匹配；缺崩前maps，仍只结算调用点家族匹配，不宣称具体旧进程或NX根因已定。
- r20同代SDK/helper/backend/四payload/JNI/APK构建进行中，实际选源相对r17仅1项SDK源变化，包含r19准备SDK-Key通道修复。输出放C盘；仅将已完成r19 unsigned/aligned两个中间APK逐文件校验搬移，正式/诊断签名包、dump和备份原样保留。尚未安装r20，Panic相关触发仍暂停。

证据：`.tmp/main-rootpanic-resume-20261121implementation{BEFORE.json,AFTER.json,SOURCE.patch,PRODUCTION-ANDROID21.json,run-device.py}`、`panic/offline-368b5b24/{DUMP-ANALYSIS.json,DISPATCH-SYSCALL-BINDING.json,ALL-SAVED-BRIDGE-CANDIDATES.json}`、`generation-r20/COMBINED-EXECUTION.json`。原119项、20约束和两组10项不变，本次不新增整项验收勾选。

## 2026-11-21 12:41 UTC r18定向失败、r19修复产物与新Panic

r18真实Context Refresh一次30.474秒返回`UPGRADE_PREPARATION_FAILED/UNREADABLE;IDENTITY_DEPLOY_FAILED`、submissionUnknown=false，尚未提交SDK。窄回执已捕获标准su启动`IOException/errno13`以及后续部署24.769秒超时；具体实时成因继续待证。生产准备阶段已删除可选标准su分支，统一使用刚通过root-proof的SDK-Key client；原超时、资格、Key/boot/mode守卫保持，不重放已提交脚本。此修复已进入r19构建与签名包，尚未安装或取得原场景绿色结果。

- 独立Native SDK Refresh两次各有确定终态，均为`ERR_MODULE_STORAGE_NOT_FOUND/-19964`。第二次明确首错`refresh-authorization`，实际控制文件存在且8B/600/root，故不据错误名认定文件缺失。更窄open errno/FSuid观测已备好，尚未在原机执行。真实su/WebUI/helper/resetprop发布阶段均code0，版本尚未提交；仅载荷发布通过，不算完整热更新。
- 12:36:23 UTC新增NX Panic，boot变为`368b5b24-ef2c-46a1-bb13-50d0716d43a4`，PC `ffffffe71202c000`、`invoke_syscall+0x58/0x114`。完整268435456B dump SHA256 `ce3f934c79fb4263f7387c794bc41f72c16cd6473932f9b59c77c05ef1fdf83c`已保存，原件复制前后元数据及摘要一致。最后一次功能调用结束时仍是7e08；此后未主动reboot或压力触发，不把时间先后写成具体因果。
- r19上机脚本首项boot检查即停止，未安装、未提交Refresh。一次既定Root调用只保全新现场及匹配broker，`brokerPausedPid=[]`，不宣称broker已暂停；Manager已force-stop。Panic相关功能触发暂停，先离线绑定故障生产者。新boot运行结果与此前7e08分开。当前安装包仍r18 storage-scope诊断`a9186d0d…`，UID10498和Key保留。

证据根`.tmp/main-rootpanic-resume-20261121/`：`root/refresh-production-r18/device-first/RESULT.json`、`root/sdk-refresh{,-storage}-r18/device-first/RESULT.json`、`root/upgrade-sdk-channel-r18/`、`device-r19/deliver-RESULT.json`、`panic/panic-368b5b24/FULL-CAPTURE.json`。正式5.2.1、完整Ready/五冷进、标准su和Panic未结算；119项、20约束及两组10项保持。

## 2026-11-21 12:09 UTC r17原机部署与新首错

r17同代SDK构建/review/stage与release完成，SDK `maplesu-ultra-source-adee53141be29e3b`，原APK `f65b357f33d00e08274cf4a8120046c8f3ec760a92fa2703b8d11013ddefe5a1`；su发布后竞态与部分Refresh版本缺失恢复两处源码已进入产物。原raw exit7及来源审阅边界保持。隔离诊断只替换归档成员25/53；两成员关闭诊断的TU/composite及原JNI重放均字节相同。首轮安装阶段TU因文件basename改变而不一致，保留失败证据后恢复原basename重建通过，未放宽比较。

- 原机保数据覆盖诊断APK `3deb6db6dca2eebc2b02a0b5b3f9ff05de7e4e062e31105a55cacfe73786ca35`，UID10498、Key保留、boot仍7e08。真实Context Refresh只调用一次，32.123秒返回`UPGRADE_PREPARATION_FAILED/ALREADY_BACKED_UP;IDENTITY_DEPLOY_FAILED`、submissionUnknown=false；尚未进入SDK Refresh，安装阶段日志0，不算m048/lifecycle完整生产复验通过。
- 定向回读：新APK/JNI受保护generation元组及launcher已发布，现有broker仍旧；版本仍缺失、helper/WebUI仍旧、三份su仍与已证新载荷匹配。部分完成语义保留，没有人为补版本、恢复旧授权或重复提交SDK。
- 原机缩小复现：同一真实生产deployScript+ProcessSuExec一次803853us、exit0、完整`MAPLESU_IDENTITY_READY`；再经实际b67维护runner/ch7通道、原15000ms和现有eligibility守卫一次1227833us、exit0。两次仅写保护tuple、未重启broker或提交SDK；它们不替代完整prepare。原两次sync单独观测约50ms，非必现15秒卡住。首轮失败具体命令回执仍缺，拒绝用该单次通过推断唯一根因。
- 继续修复：仅给实际维护runner及identity deploy增加不含脚本/Key/路径的elapsed/exit/timeout/IO/守卫分类。r18使用同一r17 SDK/JNI构建中；700ms/15000ms预算和所有资格守卫保持，等待实际失败回执后修行为。未新开全项目审计。

证据根`.tmp/main-rootpanic-resume-20261121/`：`device-r17/deliver-RESULT.json`、`generation-r17/MAPPING-BINDINGS.json`、`panic/manager-exec-observation-r17/{NATIVE.json,DELIVERY.json}`、`root/refresh-production-r17/device-first/RESULT.json`、`root/{deploy-command-r17,deploy-maintenance-r17}/device-first/RESULT.json`。完整Ready/五冷进、目标应用SU矩阵、模块与Panic根因继续未结算。

## 2026-11-21 11:40 UTC 部分刷新恢复分支设备回归与r17构建

补充su完整身份回读：r16当前kernel/canonical/Manager fullUID三个su，均与r15真实1154104B载荷按原Key+NUL仅49B替换后的完整SHA相同（`dadbfbf11ea3e241b5af172a164440422d0ff6a79b6af169a2ef734d7d5eb4a6`）。原参考SHA `b303b351c57548942be8644a461d671c7e42532e52f42bd296bb4f6210f24935`，唯一4096B模板槽核对，Key仅在私有进程参与计算、未输出；不只按文件长度猜代际。证据`root/su-payload-verify-r15/device-r16-first/RESULT.json`，仍不把其余未换helper/WebUI或完整热更算通过。

SDK Refresh本身会先退役版本文件，旧分支随后却要求版本必须存在，导致一次中途失败后连显式软件重提交也被版本缺失挡住。已最小修复为允许`FileAbsent`的现有部分环境继续逐件重发；整个环境`EnvironmentAbsent`、版本读取错误、Owner/非法模式仍在写前失败。所有身份/存储/状态撤销/逐件发布/当前durable fullUID授权/版本最后提交/不启动helper语义保持，不用人工补版本或Boot重启恢复。

真实`install_environment`函数体编成arm64设备夹具（仅OS/worker/transport/组件调用是原有seam）：原机59检查前像2失败→后像59/0，覆盖版本缺失成功重发、版本缺失时WebUI失败保留部分终态、不可读版本无写入及原52项。它是设备执行的编排夹具，不是实际内核完整安装通过。r17同代SDK/helper/backend/四payload/JNI/APK组合构建已启动，包含上述恢复分支及m048发布竞态修复；诊断衍生包将另带无Key/路径的安装阶段码/errno日志，不作为原产品身份。

证据：`.tmp/main-rootpanic-resume-20261121implementation{LIFECYCLE-BUILD.json,LIFECYCLE-DEVICE.json,LIFECYCLE-RECOVERY-ADOPTED.json,LIFECYCLE-RECOVERY.patch}`、`generation-r17/COMBINED-EXECUTION.json`。当前设备仍是r16部分状态，无额外Refresh提交，正式5.2.1和完整验收尚未完成。

## 2026-11-21 11:33 UTC su发布后路径竞态：设备夹具红绿与源码修复

实际m048 su安装器在atomic publisher已完成0700/system_file标签并发布后，仍按原路径重复chmod/setxattr。现有授权准备会把system raw su原子迁入canonical；原路径随后消失即可产生与本轮相同的`ERR_SET_FILE_SELINUX/ENOENT`。原机隔离夹具执行真实m048载荷处理、真实publisher和Android文件调用，仅使用合成Key、临时目录、提权stub及单变量canonical rename：旧源码在迁移场景返回-19915/errno2，候选返回0；正常、publisher标签拒绝、第二次发布失败、提权失败原语义均保留，前后10次输出全部符合预期，完整Root回执、boot不变。

已采用m048源码修复：删除这两次发布后的重复路径变更及因此新增的孤儿helper/常量/头文件；publisher的写前FD权限、标签和失败检查保持。真实Android21生产TU编译通过。该修复尚未进入后继SDK/APK，不能归给当前r16；受控夹具证明了竞态缺陷，不等于已捕获本轮安装失败的确切时序。当前部分安装状态仍保留，未重提交Refresh、未补造版本或放宽标签错误。下一步是结合精确安装阶段回执处理版本缺失的恢复分支，再同代构建与原场景复验。

证据相对`.tmp/main-rootpanic-resume-20261121/`：`root/refresh-publication-r16/{SU-POSTPUBLISH-BUILD.json,su-postpublish-device/RESULT.json,SU-POSTPUBLISH-VERIFIED.json,SU-PUBLICATION-ADOPTED.json,SU-PUBLICATION.patch,PRODUCTION-COMPILE.json}`；`verify-su-postpublish.py`可复核本次设备收据。未结算完整软件刷新、完整Ready或正式5.2.1。

## 2026-11-21 11:27 UTC r16原机复测：目录首错消除、标准su首次成功、刷新部分失败

- 产品与设备：r16 release在8m48s构建成功，2847个本轮Manager/native接线/固定SDK输入前后稳定，复用原r15 SDK/helper/backend/payload，原JNI逐字节相同。原APK `df77ca0e36e118d23373b6e4dbd090f9e67fb848749b2e74bd8f48141f9efa02`已保留；实际保数据安装的是仅替换执行边界JNI的诊断衍生包`2f31df762fb19771166c5040b7b71bda00d342bff0c96fe8a46fca3b616c2f99`，UID10498，非正式产品验收。
- 原机生产Refresh一次：原`DIRECTORY:3`已消除，真实准备返回`IDENTITY_UPGRADE_PREPARED`后进入SDK安装；43.191秒返回`ERR_SET_FILE_SELINUX/-19915`，`submissionUnknown=true`、Key保留。未盲重试。失败Outcome的默认`verified=true`与`ok=false`分开解释，未结算升级成功。6963事件/1406事务，零first-error/同址观测重叠，1笔缺exec-return、20笔缺terminal、3笔解析anomaly，不宣称所有日志齐全或Panic已修。
- 部分写入已核：kernel su与canonical su均已由218120B变为1154104B，完整SHA相同`dadbfbf11ea3e241b5af172a164440422d0ff6a79b6af169a2ef734d7d5eb4a6`，尚未做私有Key槽归一化全字节代际证明；system raw su已被迁入canonical。helper仍1586520B、WebUI仍351000B（随包应1776896B/353912B），真实`skroot.sdk.ver`已缺失；这是部分刷新状态，未伪造版本或恢复旧授权快照。
- 标准su有实际进展：在该部分状态下，通过r16实际保留的`ProcessSuExec`主候选`/system/bin/su -c 'id -u'`，Manager UID10498得到`0\n`、退出码0、402730us、非超时，原700ms预算保持。仅该调用通过，目标应用/fullUID全矩阵、交互、32/64位、无Manager冷启、完整Ready/五冷进仍待验收。
- 剩余首错缩小：将真实`executable_publish.cpp`编成一次诊断fixture，原机同一Root链对实际system/modules父目录各发布一个独立测试文件，均`source=0/code=0/Complete`、system_file标签正确并立即清理。静态目录权限或必现标签拒绝不成立；实际安装失败组件/errno、重复提权及canonical迁移时序仍需精确关联，尚未采用猜测性锁或删权限检查。版本缺失状态先重建失败语义，再恢复完整启动测试，避免隐式Boot安装。
- 回归补充：真实目录for-loop前后在同一Root进程得`OLD_DIRECTORY=3 NEW_DIRECTORY=0`。既有JVM八进制回归的反射参数落后于当前generation入参，已仅补测试两行；该测试未在本轮另报主机通过，设备45/45回归是独立实测。

证据相对`.tmp/main-rootpanic-resume-20261121/`：`generation-r16/COMBINED-EXECUTION.json`、`device-r16/deliver-RESULT.json`、`root/refresh-production-r16/{device-first/RESULT.json,partial-readonly/RESULT.json,partial-payload-state/RESULT.json,su-primary-after-partial/RESULT.json}`、`root/refresh-production-r15/directory-guard-redgreen/RESULT.json`、`root/refresh-publication-r16/device-once/RESULT.json`。全过程boot仍7e08、未观察到新Panic，无主动重启/刷分区/清数据。正式5.2.1、完整软件Refresh、完整Ready及原119项未新增整项勾选。

## 2026-11-21 11:12 UTC 原机Refresh首错修复：Android shell八进制

- 原机仅调用一次真实`j67.h(Context,lg7,f67.l)`（r15生产Context适配器），真实`UltraSdkCredentials.client/read`取身份，跳过独立Application预热，未调用完整upgrade/分区入口。25.467秒返回`UPGRADE_PREPARATION_FAILED / ALREADY_BACKED_UP;IDENTITY_DEPLOY_FAILED:DIRECTORY:3`，Key保留、boot仍7e08；准备阶段失败，SDK Refresh未提交。失败Outcome的默认`verified=true`不视为已验证安装，必须结合`ok=false`解释。
- 首错证据：定向Root只读实测`/data/adb`、`/data/adb/maplesu`、identity目录均为root:700、非symlink。原机`/system/bin/sh`为Android mksh，`0700 & 022`实际返回20，而`8#700 & 8#22`返回0；原检查错误拒绝安全目录，另对777可能漏拒绝。无修改目录权限或放宽信任检查。
- 源码已修：`UltraIdentityRecovery.kt`、`UltraRecoveryKeyStore.kt`、`UltraIdleEnvironmentRecovery.kt`各一行，均显式指定八进制。原机执行实际生产表达式45项，前像39失败→后像45/0；安全/可写/特殊位均覆盖。永久回归`scripts/tests/test-ultra-shell-octal-device.py`原机45/0；此为表达式设备回归，不替代完整Refresh。r16已启动受影响Manager重建，复用未变r15 SDK/helper/backend/payload；待安装后重跑生产Refresh及标准su/Ready。

证据相对`.tmp/main-rootpanic-resume-20261121/`：`root/refresh-production-r15/{DELIVERY.json,BIND-RESULT.json,device-execute-first/RESULT.json,directory-readonly/instrument.log,octalmode-fix/SOURCE.json,octal-red/RESULT.json,octal-green/RESULT.json,octal-green-permanent/RESULT.json}`、`generation-r16/COMBINED-EXECUTION.json`。首次新诊断包安装在OEM确认页超时，完成该安装后只读绑定成功；不计作生产Refresh重复提交。无新Panic、主动重启、分区写入或清数据，正式父项仍按原条件结算。

## 2026-11-21 10:59 UTC r15构建完成与真实Manager执行边界

- 产品：r15同代SDK/helper/backend/四payload/JNI/APK构建成功，SDK `maplesu-ultra-source-5f86aa9441002a67`，原APK SHA256 `b776c59ab476e5201def2acbe45fdf69e9ef8b4c4f318b74dfa9bf3612384646`。2627个SDK选源输入零漂移，2389个Manager输入按r14快照与已记录采用后像完成事后谱系核验；不是补造build-start快照。110次编译/197对象复用，原raw gate exit7保留，review/stage成功另账。原版r15 APK尚未安装。
- 设备：同签保数据安装r15执行边界诊断衍生包（APK `71b5c9fd28fbbaf254859b39fd1dfbc16a3682a423229080bb8085763e938dad`、JNI `0b70280ae916bc515b4263a1b8f12dac21244ee044b8ef49b8b30d1d7c7fafee`），只改arm64 JNI，其余533个ZIP成员内容相同。仅保留原r15来源identity，实际诊断archive/JNI另记，不冒充原产品。UID10498、7e08 boot不变；无清数据、主动重启或分区写入。
- 真实启动一次：安装Receiver与显式启动分开标记，8秒观测后force-stop。4801事件/967事务，零first-error和同址观测重叠；2笔缺exec-return、11笔缺terminal、2笔解析anomaly，可能被预算/停止截断，不据此宣称全部完整或Panic因果。首终态仍为`ready=false/ULTRA_KEY_CHANGED`，live-observation1327ms、manager-su3468ms、总4824ms；fresh恢复第二pass已开始，截取期内未结算。完整Ready/1000ms及五冷进仍未通过。
- 独立主机：journal admission6/6、WebUI session13/13、retirement7/7通过；IO pageCurrent既有5/5按当前输入及日志摘要复用。retirement仅补测试夹具WebView方法及依赖说明，已精确采用，生产实现无对应变更。
- 下一步：一次带日志的实际生产`UltraEnvironmentInstaller.install(...RefreshToBundledBuild)`，验证保Key/fullUID授权的旧su/helper软件刷新；避开可能写分区的完整upgrade入口。尚未提交，不把子链观测算完整UI升级验收。Panic根因未闭合；35项末事件窄复核不支持helper执行停滞，真实内核free/BLR前有效权限仍缺直接证据，不重复补已有TLBI。

证据相对`.tmp/main-rootpanic-resume-20261121/`：`device-r15/deliver-RESULT.json`、`generation-r15/POSTBUILD-INPUT-CHECK.json`、`generation-r15/MANAGER-LINEAGE-CHECK.json`、`panic/manager-exec-observation-r15/device-first/RESULT.json`及`BOUNDARIES.json`、`panic/exec-lifecycle-observation/observation-review/RECEIPT.json`、`acceptance-fast-iteration/module-host-resume/remaining-host/RESULT.json`及`ADOPTED.json`。原119项与追加要求保留，未新增整项勾选。

## 2026-11-21 10:39 UTC 接手续修：载荷刷新采用与单次执行边界观测

- 源码采用：Legacy 同版本软件载荷 Refresh 的九文件已核对全部前像、备份并精确采用，见 `root/su-handshake-source/continuation/delivery/ADOPTED.json`。实际安装生命周期52、bootstrap54、Manager7及模式接点4项主机检查通过，四个真实Android TU编译通过；保Key、当前fullUID授权来源、当前启动状态及配置。Owner保持原Boot升级，Owner+Refresh及非法模式均在写前失败。多文件发布保留部分失败语义，不宣称全模式原子热更。
- 产物构建：r15已启动同代SDK/helper/backend/四payload/JNI/APK构建，使用r12逐命令、当前依赖摘要及旧对象摘要验证后的输出复用；不是全干净构建。SDK producer完成110次编译、197对象复用，原raw member gate失败仍保留，独立review/stage/release继续，尚无正式或设备采用结论。
- 设备观测：隔离member25诊断桥保留原restore TU，默认关闭对象与原对象逐字节相同；实际单次 `read_installed_helper` 排除Application启动预热，原300ms预算未变。214105us完整回读1586520B，RUID/EUID0、childStatus0、无超时，同7e08 boot；249事件/50事务，所有exec-enter均有exec-return，无first-error、无解析异常、无同址观测区间重叠。最后一笔缺complete日志，实际helper完整终态另证，不补造日志或宣称每条日志均齐全。
- 上述仅排除该次诊断调用中已观测的在途重叠/缺执行返回，诊断JNI也不冒充原生产JNI；Panic根因仍未闭合，未安装r14/r15 Manager、未清数据或写分区。标准su/完整Ready/五冷进及原119项复合条件继续按原判据执行。已采用KSU移除脚本由原命令直接13/13复验通过，无包装器依赖。

本节证据相对 `.tmp/main-rootpanic-resume-20261121/`：`generation-r15/COMBINED-EXECUTION.json`、`panic/exec-lifecycle-observation/DELIVERY.json`、`panic/exec-lifecycle-observation/device-once/RESULT.json`及`BOUNDARIES.json`、`acceptance-fast-iteration/module-host-resume/direct-adopted/RESULT.json`。未新增整项勾选。

## 2026-11-21 10:27 UTC 接手续修：r14候选完成、现场归属与模块主机复验

- 已验证（产物限定）：中断的r14以独立 `generation-r14-resume/` 续建成功，Manager/选定SDK输入摘要稳定；同签release APK `b1dec64aa5db8ca3a37ac672550cdc1eb86af2d9d37f111636b992c1ad5e1ad9`，SDK `maplesu-ultra-source-915c61c220e3b244`，JNI与r12相同。含真实LITE激活后fresh recovery投递；尚未安装或计为完整Ready。
- 已验证（主机限定）：修正Root tree私有run反射参数和KSU移除测试对Kotlin模板的过期提取；完整当前Kotlin生成的真实shell分别6/6、13/13通过。仅两测试文件已精确采用，生产树实现未为夹具变更；辅助module-initrc仍为宿主fixture边界，Android/模块开机另验。
- 已验证（现场限定）：09:52的7e08故障LR匹配r12/r13共同JNI直接dispatch调用点，旧桥直接调用点不匹配；保存TTBR1根与swapper pgdp相同。旧提取器caveat保留并在独立收据纠正，不将旧broker当作唯一生产者。Panic根因仍存在阻塞；下一观测只补ALLOC→WRITE→EXEC→RELEASE关联，不追加推测性TLBI/锁。
- 设备仍为7e08同boot，已保留普通logcat并停止Manager；本次接续尚未请求Root、清数据、重启或写分区。包替换Receiver会初始化Application，故对应触发链的复验须配合定向修复/观测后恢复。
- 原119项、20约束及两组10项已完整进入现有执行队列；修正遗漏OWN-04后唯一ID覆盖119/119，零丢项。Legacy同版本软件载荷Refresh仍在隔离收口，Owner升级行为单独验证；尚未采用/打包/上机，不将其记为热更通过。

证据入口：`.tmp/main-rootpanic-resume-20261121/resume-20261121/STATUS.json`、`generation-r14-resume/COMBINED-EXECUTION.json`、`acceptance-fast-iteration/module-host-resume/DELIVERY.json`及`ADOPTED.json`、`panic/offline-7e08-review-20261121/RECEIPT.json`、`acceptance-fast-iteration/QUEUE.json`。正式5.2.1、完整Ready/五冷进、标准su/目标应用、镜像和模块设备项仍按原条件结算；不新增整项勾选。

## 2026-11-21 10:04 UTC 快速迭代：激活恢复已采用、Panic 新现场

按原119项与追加需求继续执行，发现问题即记录/修复/复验；无全项目重审。源码分析使用隔离写集，设备由主线独占，共享构建窗口冻结。

- 源码完成：`ModeBootstrap` 在真实 `Activated(LITE)` 后向已有串行合并恢复队列投递 fresh-pass，修复旧scope撤销后缺恢复事件的问题；全部原mode/boot/owner/key守卫保留。`UltraAutoPair` 的临时r13分类日志已清理。主机旧9项3失败→候选9/0，已精确采用，证据 `root/manager-ready-continuation/mode-activation/delivery/DELIVERY.json`、`generation-r14/SOURCE-ADOPTION.json`。
- 进入产品：r14沿已验证r12 SDK/helper/backend/四payload启动受影响Manager release构建；此时尚无r14构建成功或设备通过声明。运行中旧helper/su/resident仍单独记账。
- 已确认旧su：三份设备su完整SHA `975139fdc844df0a2415b2eab6a742e2e9076dcb848f14bfd572fb3a560f1266`，与历史218120B产物按原发布算法patch key+NUL后精确相同；仅摘要落盘。当前源码握手10场景已过，但部署迁移尚未执行，标准su仍失败。正在准备保持key与授权的同版本刷新候选。
- Panic：被动发现09:52:17 UTC新NX，boot `7e086c29-3f81-408a-9297-3295941d16ed`、`DefaultDispatch/invoke_syscall+0x58`、地址 `ffffffd67bfe6000`。先保存新完整268435456B dump，SHA `ca044e29a4ed54b214cfa21b82cca1bcbec437016aacff5c23fdea01366367b2`，原件复制前后元数据及摘要一致；当前旧broker4017经cmdline/maps匹配后SIGSTOP，状态T回读。未主动reboot、未压力触发、未写分区/授权。与09:39的90a6及08:48的c956分开记录；故障路径功能触发暂停，独立源码/主机验证继续。
- 主机新增：当前生产WebUI owner-scope 15/15、script-outcome 4/4通过，证据 `acceptance-fast-iteration/HOST-EXECUTION.json`。只结算覆盖的宿主语义，不提升完整模块/设备项。旧模块移除文本提取脚本出现7失败，已记录并改用完整当前Kotlin生成shell复验，尚无对应生产故障结论。

证据路径均相对 `.tmp/main-rootpanic-resume-20261121/`。正式5.2.1、1000ms完整Ready/五冷进、目标应用授权/标准su、修补升级、模块生命周期和Panic设备项继续按实际结果结算。

## 2026-11-21 09:48 UTC r13 定向修复与新增 Panic 现场

r13 已完成 release 构建、签名和保数据安装，APK SHA256 `5a819ef08498b85c848689783efb1a6c2f1ec66d11106efc660c162a238f7011`，UID 保持10498。仅 Manager 两成员受影响重建，使用同一 r12 SDK `maplesu-ultra-source-915c61c220e3b244`；JNI SHA 与 r12 相同。构建前后 Manager src、选定 SDK 全文件摘要稳定。设备 helper/su/resident 的安装代际另账，未宣称已升级。

- 源码/主机：SU 五候选改为共享一次总预算，6项主机检查通过；AutoPair 增加临时 `[DEBUG-r13-scope]` 首失效守卫分类，原短路顺序及撤销语义保留，9项检查通过。真实 ProcessSuExec 宿主8项检查通过，700ms挂起约709ms返回，未证实固定500/750ms追加，未作推测性收尾修改。
- 设备：跳过 Application.onCreate 的诊断只调用实际生产 helper JNI 一次，198476us完成1586520B，RUID/EUID0、complete/rootVerified/receiptComplete/streamEof/childStatus0，无超时。排除独立应用预热；此前本boot已有Root操作，故不算五次完全冷进。Legacy replyObserved=false仍与完整流事实分开。
- Manager 首错：有效 r13 原场景观测 live-observation1449ms、manager-su2718ms、terminal ready=false/pass4168ms；首失效明确是 `mode-activation`，未放宽守卫。前3次脚本在候选PID筛选/已退出子进程读取处提前退出，只计诊断脚本错误，未计功能复验。后续源码发现 Activated/LITE 缺向既有串行恢复队列投递fresh-pass事件，候选主机验证中，尚未进入产品/设备。
- 标准su：独立调用实际 r13 ProcessSuExec 的主候选 `/system/bin/su -c id -u`，102666us、exit1、`su: root handshake failed (errno=2): No such file or directory`、非超时。尚未通过Manager自身SU，更未结算目标应用授权或标准su整项。
- Panic：09:39:48 UTC新增NX，boot `90a6b520-e7f4-4584-9ea8-01652cd97bd0`，地址 `ffffffe280d45000`、stackhash与既有场次相同。单次su结束09:38:38时仍为c956，之后至本次发现未发主动重启/压力测试，先后关系不作因果结论。已暂停功能复测、force-stop Manager；新boot实际加载旧桥的身份broker4093已SIGSTOP，保留原授权/config。完整新268435456B minidump已保存，SHA256 `04501ef35be2b316913082a3236ed67a2f76d30944527a46fb37b45ff68a111d`，vendor原件复制前后元数据及副本摘要匹配。新boot maps 与崩前maps严格区分，Panic仍未修复结算。

证据：`.tmp/main-rootpanic-resume-20261121/generation-r13/COMBINED-EXECUTION.json`、`device-r13/helper-once-first-RESULT.json`、`device-r13/manager-once-observed-RESULT.json`、`device-r13/production-su-primary-RESULT.json`、`panic/panic-90a6b520/FULL-CAPTURE.json`。完整现场：`HISTORICAL_EVIDENCE/maplesu-rootpanic-resume-product-20261121/panic-90a6b520/`。正式5.2.1、完整Ready、五次冷进、目标应用SU及其余设备验收继续未完成；本轮不新增父项勾选。

## 2026-11-21 08:54 UTC r12 原机 Root 回读通过与新 Panic 现场

Root accessor 与 caps 偏移复用已进入真实 r12 同代 SDK/helper/backend/四段 payload/JNI/APK；SDK `maplesu-ultra-source-915c61c220e3b244`，APK SHA256 `d794d1c322f9b1b3604e1689313fe622721b59b439273f6dbc8956ef0b5a5dcd`。r12 采用逐编译命令/当前依赖内容/对象摘要校验后的 r11 输出复用：14次编译、293对象复用，并重新组合及链接，明确不是第二次全干净可复现构建。原 raw exit7/78成员差异、25 partial及其行为边界保留，来源审阅通过不冒充原始门禁全通过。

- 源码/主机：resolver `447cbc52384964b58945492c4a6ace8f6f8d2d021ab3d21e91ff709afaa5ca23`，patch_current `93a0790ccdb47136441fc226ebc1252a46b61cbb6075a17e819d63a79e595b4e`；前轮16场景及35检查红绿证据对应当前同一源码。2627个选源输入无漂移；六份分区压缩/解压摘要及原授权/设置备份重新校验。
- 产品/设备：release构建成功，同签 `adb install -r` 保留UID10498及数据，设备安装包摘要与候选一致。原生产 `NativeBridge.readInstalledHelper` 首次完成266319us（Java调用266767us）、1586520B、rootVerified/complete/receiptComplete/streamEof=true、ruid/euid0、childStatus0、无超时。Legacy模式 root.replyObserved=false 与 owner协议回执分别解释；真实Root由UID及完整流证明。该测试复用无SDK自带的通用Java测试入口，实际加载r12 JNI；Application启动仍可能产生独立后台warmup，不将本结果记作五次冷进或纯隔离时延。
- 尚未通过：现成r12后台启动日志 live-observation776–937ms、manager-su2132–2301ms、terminal ready=false/ULTRA_KEY_CHANGED。未执行五次冷进或目标应用SU验收；安装APK不等于已替换设备旧helper/su/resident。
- Panic：08:48:02 UTC再次NX重启至 `c956cf6c-6c69-469a-a3c1-48fc54fde859`，先保存日志并暂停功能触发。随后一次既定r12 Root返回用于旧broker SIGSTOP和现场/代码保全，未修改授权/config；后来PID4081消失，退出原因未证。实际旧broker加载 `libmaplesu_pro_bridge.so` SHA `0c27249e24132948ef802bb6a6ed0c754c83fbf406f4784fedd0bf15f07e6446`，配对APK SHA `ab9f827497321ddcf5fa5240b09ed0d3a553aaf4e64578dea035014d3e814f42`。新完整268435456B dump的用户LR精确匹配该实际桥dispatch返回点；这是生产者绑定，不是NX根因已修。旧07:21完整dump、07:38/08:22日志与本次完整现场分开保留。

证据：`.tmp/main-rootpanic-resume-20261121/device-r12/`、`generation-r12/CONTINUATION-INPUT-CHECK.json`、`panic/panic-c956cf6c/FULL-CAPTURE.json`；实际新代码和dump位于 `HISTORICAL_EVIDENCE/maplesu-rootpanic-resume-product-20261121/panic-c956cf6c/`。SU总预算候选已真实Kotlin红绿（旧6检查1失败→候选6/0），尚未合入。继续按真实失败链修复；正式5.2.1与其余设备父项未结算，未新增完整勾选。

## 2026-11-21 08:20 UTC Root accessor 接续实测（r11）

已按固定基线精确采用 Root accessor 候选，生产 resolver SHA256 `447cbc52384964b58945492c4a6ace8f6f8d2d021ab3d21e91ff709afaa5ca23`。受限 sysfs 的五字段 Root 入口不先搬运完整内核 BTF；可读 sysfs、已有有效内存 BTF 及通用消费者的权威/错误语义保留。当前生产源码重新编译的16场景 ASan/UBSan 通过，见 `.tmp/main-rootpanic-resume-20261121/generation-r11/direct-validation/RESULT.json`。

- 源码/产品：resolver 已采用；r11 SDK `maplesu-ultra-source-cbff87f0b5ab5332` 已由完整 producer/review/stage 产出，helper/backend/payload 同代；APK 正在既有生产 Gradle release 构建。原 raw member 比较 exit7 与25 partial仍保原边界，未记成正式全部通过。
- 设备：原机 a261972a/6.6.89/4KB/_a 保原数据。单成员隔离诊断首次仍302220us超时；定向共享函数计数一次297802us完整回读、UID/EUID0，READ高层请求29次/354B。该诊断有边界计数，不作为最终时延验收。
- 后继修复：定位到 `PatchCurrent::set_current_caps` 重复获取已由 PatchBase 携带的 cap_inheritable 偏移，重新进入通用BTF解析。隔离候选复用该输入，真实 m072 的35检查原像5失败/后像全过，NDK通过；两成员隔离无计数实测219556us完整回读1586520B、rootVerified/receiptComplete/streamEof为true、childStatus0。候选尚未进入r11 APK，不把隔离结果算Manager完整Ready或目标应用SU通过。
- 现场/备份：六镜像位于 `HISTORICAL_EVIDENCE/maplesu-rootpanic-resume-product-20261121/device-pre-191c120f/`，压缩与解压摘要/长度均重验；原授权/设置备份沿 `device-r8/AUTH-BACKUP.json` 重新验证，未输出内容。此次boot仍30145d80，未发重启/分区写。旧9490完整dump与07:38仅日志现场分开，Panic未修复结算。

当前证据入口：`.tmp/main-rootpanic-resume-20261121/ROOT-ACCESSOR-ADOPTED.json`、`generation-r11/COMBINED-EXECUTION.json`、`root/caps-offset-reuse/SOURCE.json`、`root/helper-native-caps-reuse-r11/device-once/RESULT.json`。Manager1000ms完整就绪/五冷进、实际应用授权/标准su、镜像/模块生命周期及Panic设备项继续未验收；未新增父项勾选。

## 2026-11-21 P7 Legacy UID 索引追加（隔离候选）

Legacy 当前 checked 路径已接 native 私有 UID 哈希索引，保留 8 字节 control 与零终止数组 ABI、完整 UID、旧表兼容及已有 RCU/失败回收；未变表亦可准备索引。新增 WriterIndexPrepare=43 的 SDK wire 与 Manager 解析已成套补齐。9729 断言/500 次替换及 ASan/UBSan、真实 Android15/6.6 kprobes TU、15 个 SDK TU/14 TU 局部链接、writer13/transaction11/bootstrap25、真实 AsmJit/Unicorn7 和 Manager 旧红新绿4测试通过。

共享写入因 MAIN 输入冻结已停止，最终后继仅在 `.tmp/p7-uid-index-20261121/candidate/`；累计补丁、相对共享快照的增量补丁和验证绑定在该批 `delivery/`。9 个生产源码、3 manifest、3 测试文件及2文档待 MAIN 成套采用，详见 [UID索引交付](dual-mode/impl/_work/2026-11-15-p5-p7/P7-LEGACY-UID-INDEX-20261121.md)。三个新 native exports 须由匹配内核承载，APK 更新不代替底层生效。哈希碰撞、旧兼容扫描、主动协议及 SELinux/普通应用真实差分仍未验；正式资产未提升、设备0，SU-05/06设备父项保持原状态。本段是此前“Legacy仍逐项扫描”的候选后继，非正式采用或零时延结论。

## 2026-11-21 P7 普通应用态追加复核

接续用户对普通应用态侧信道的追加要求：Owner 受限命令已在读取内层参数前鉴权；有效授权命中前不再获取/释放额外 cred/mm 引用。两处生产源码已落地，Owner 原像红/后像812断言通过，lease实际函数24场景/190断言及ASan/UBSan通过，两个完整生产TU使用真实AArch64/Android17-6.18头编译通过。固定KSU比较与补丁入口见 [普通应用态复核](dual-mode/impl/_work/2026-11-15-p5-p7/P7-ORDINARY-APP-REVIEW-20261121.md)。

Legacy有终止符UID表仍存名单长度相关成本，Owner协议可主动探测，既有SELinux view/status/通知路径仍需实际差分；固定KSU自身也有可主动请求的driver FD及公开GET_INFO。原P6/P7源码接线成果保留，但“零侧信道/零额外时延”追加目标仍未验收。正式源选择与产物未提升、设备0、完整SU-05/06父项不勾；下文闭环均保原源码开发口径。

## 2026-11-21 P7 源码开发闭环

P7 的 SU-01–06、TRUST-01–02、OWN-01–04 共12项源码开发已完成并统一采用。补齐durable授权/受保护安装依赖与已有gate、32位compat真实入口、Legacy UID/native RCU/失败回收、受保护签名轮换、owner watch在途回收、剩余免Key完整消费者及Root失败回执。保原Ultra底层、fullUID、API/ABI、P5 x28、BTI/PAC与after跨disable；不新增普通应用态全局SELinux/挂载/属性/判定进程。

13个SDK成员的20次实际TU编译＋3次合成链接、当前JNI/policy与Manager83源通过，当前消费依赖和工具/对象SHA匹配；10个kernel对象/内部链接、最终Legacy窄闭包和各域必要红绿/生命周期/签名回归通过。聚焦补丁、准确后像与生产采用读回在 `.tmp/p7-development-20261121/delivery/`。逐项结果及真实限制见 [P7开发结果](dual-mode/impl/_work/2026-11-15-p5-p7/P7-DEVELOPMENT-CLOSEOUT-20261121.md)、[P7 Todo](dual-mode/impl/_work/2026-11-15-p5-p7/P7-TODO-20261121.md)。

同签升级保持久授权、可信签名轮换沿受保护历史；主动撤权/卸载重装/身份不匹配仍按原失败语义。正式产物、真实32位/冷启/升级、SMP与硬件保护、150ms/300ms及普通应用FD/挂载/策略/时延差分独立未验，总账设备父项保持原状态。以下旧“P7未启动”及早期缺口保留历史时点，不覆盖本段后继。

## 2026-11-20 P4 源码开发闭合（隔离候选）

P4四项开发范围已闭合，唯一事务、Root/CAPS实际接线、task/seccomp/LSM资格、提交后会话与消费者连续性、独立旧版兼容均按当前实现核对。本轮补齐m070已提交ROOT返回后置错误时的errno丢失；七文件候选在 `.tmp/p4-closeout-candidate-20261120/after/`，`SOURCE.patch` SHA-256 `53d44ef66efe69206d72518bb420554bc17eb2ece90dc1c6cd202a35261349b6`，应用检查及逐字节回放通过；沿用MAIN生产输入冻结，尚未应用到live。

当前 `manager_management.c` 为 `d4cedde0ae30813213186824138ecff3386244a7c1988e6ce4e62b977f4a1e46`，SDK request为 `a8701bd8f434a12160e6af127176ca420654c4f270ece977d95a4b09543dfb93`。当前六目标×三管理对象共18对象Kbuild通过，输入未变；矩阵验证正例和五类负例通过。精确事务四配置156场景/1,176检查、task-state五配置260场景/3,290检查、m070原像红/后像28场景绿及Android TU、PatchCurrent35、Root receipt390、native management247、wiring9均通过。旧975bfa矩阵仅作历史，不冒充当前源。

源码入口、四项完成矩阵、复现和独立未验范围见 [P4开发交付](SDK-REFACTOR-P4-DEVELOPMENT-CLOSEOUT-20261120.md) 与 [P4 Todo](SDK-REFACTOR-P4-TODO-20261120.md)。候选开发完成与MAIN采用分开；真实目标资格/引用/LSM/并发/fork/exec、完整正式资产与设备仍待验。独立MAIN producer已FINISHED/exit7/member gate失败，未提升正式包；本轮deviceOperations=0，CRED-01/02/03父项保持未勾。下列其他阶段与历史记录不变。

## 2026-11-20 P6 源码开发闭环

本轮按用户纯开发范围完成尚缺生产接线及相关编译/回归：固定 OEMimplementation 的真实 backend/factory/目标 binding/command31/七对象选源已成套；SDK 模块环境从受保护 install.mode 与实际 SDK 版本捕获，覆盖 helper/native 和显式 module-only Manager→RootService→child envp，另补 Hook/传统凭据模块及 Root installer cleanup 的实际来源环境，未知模式保持未知；Ultra 同库存 initrc/preinit、4MiB 总量/8MiB 保留、limit3、PID1 selinux_setup runner 与失败回到原 init 已接入。保持 P5 的 x28、BTI/PAC、after 跨 disable、真实 publication/shared-bool、生命周期和公开 API/ABI。

WebUI 每页 pm 与 HTTP 最终交付退休已补；用户进一步明确“与 KSU 一致、允许兼容”，两项新 provider 能力皆缺时因此采用固定 KSU 同样的直接 ksu/ksu_download 及嵌套 io/pm，不再以更新 provider 阻断模块。该旧路径不宣称具有 builder/doc-start 的 frame 隔离；原 loopback/主页面导航、身份、退休/回收保持，原始 HTTP/POST 由模块 server 处理。

固定消费者 ASan/UBSan、实际 Android21 TU、40 源 Android Kotlin/Java、页面 pm 9 项、HTTP 先红后绿与兼容组、模块环境/runner/preinit 因果回归、受影响真实 kernel/header 编译及完整 module-tools Android crate 均有直接证据。七 provider 对象按当前源/完整本地依赖与已成功 Android15/6.6 KCFI Kbuild 精确匹配复用，未重复构建。原失败日志保留；编译 R fixture 不等同 AAPT/Gradle/R8 或 APK。

实际源码差异与采用读回在 `.tmp/p6-resumed-development-20261120/delivery-final/`；逐项 P6 对应及全部真实限制见 [P6 闭环结果](dual-mode/impl/_work/2026-11-15-p5-p7/P6-CLOSEOUT-RESULT-20261120.md)、[P6 Todo](dual-mode/impl/_work/2026-11-15-p5-p7/P6-CLOSEOUT-TODO-20261120.md)。本次 P6 源码开发闭环完成，正式 SDK/helper/backend/payload/APK、原包/全部指定设备、无 Manager 冷启、三模式 32/64su、150ms/300ms 与普通应用差分仍未验；总账设备父项未勾选，P7 未启动。以下旧 P6 记录保留原时点。

## 历史：2026-11-20 P6 生产接线续接

P6 本轮已补脚本 commitUnknown→PENDING、script live 目录变更的定向 WebUI 退休、安装/启禁前 durable journal 失败阻止提交，以及单模块 helper 的 checked close。4 类脚本投影、6 组真实 shell、6 项 journal/取消、7 组 single-helper 回归通过；38 个完整生产输入的 Android Kotlin/Java 组合及 helper API21 TU 编译通过。Java 依赖/argfile 首错保留，未跳过 SDK 身份门禁；既有 12＋5 回归未重复执行。

后续补齐 Normal 热安装实际 rename→本次 nonce/ZIP ID→页面定向退休，0027 生成定义与 applied 源保持一致；完整 native TU、9组 Linux 因果及最终26项 JVM（新增9＋原17）通过。该通知保原退出码/业务双流，不以成功码或 needsReboot 推定换目录，也不构成跨进程认证结论；正式 ksud 尚未提升。

最新收敛到 EarlyHelper：m068 的标志目录已消费现有 worker StorageScope，原错误和四公开函数保留；完整 API21 两阶段 TU 与四组定向回归通过。固定 OEM/root-env/SELinux provider 尚缺，CORE/HOST 精确接口责任及 MAIN 可先构建/完整启动须等待的分界见 [固定业务接点](dual-mode/impl/_work/2026-11-15-p5-p7/P6-EARLY-FIXED-CONTRACT-20261120.md)。本次一源码增量单独交付，不扩展测试矩阵或覆盖共享单写域。

源码差异、可重放入口和 P6 八项现状见 [P6 续接交付](dual-mode/impl/_work/2026-11-15-p5-p7/P6-DEVELOPMENT-CONTINUATION-20261120.md)。固定 EarlyHelper native 业务 provider/消费者仍有实质源码缺口，认证跨进程撤销及完整宿主/原包/设备矩阵仍开放；本轮未提升正式 SDK/helper/backend/APK。整个 P6 未声明完成，P7 未启动。下列历史记录保留其原时点与范围。

## 2026-11-19 P3 源码开发闭环交付

本轮 P3 六项源码范围已完成：统一协议与完整回执、显式元数据、真实资源/执行 provider 与消费者、失败及并发生命周期、集中旧版兼容和必要 stage 接线。Key 管理 33 场景/5339 checks、native helpers 166、provider scope 149、root-loader 725、m025 75、Root/caps 32 checks 通过；7 个 Android TU 与成员/交叉组合、ARMv7/AArch64 C/C++ wire、stage 22 tests 通过，907 个编译依赖稳定。

采用源码、逐项完成矩阵及精确验证范围见 [P3 开发交付](dual-mode/impl/_work/2026-11-15-p3/P3-DEVELOPMENT-CLOSEOUT-20261119.md)。本批没有提升正式 SDK/helper/backend/APK 或部署设备；IMAGE 的 R8 native 桥最终合成、跨代完整资产配对和真实 32 位/硬件/并发/冷启/时延验收分别留账，权威 IPC 设备父项保持开放。下一轮基于此源码闭合 P4；P5 状态仍由其独立工作保持，本次未改其计划。

## 2026-11-19 P5 生产开发闭环交付

本轮已直接完成 P5 生产接线及受影响验证：Legacy/Owner 统一 publication、可睡眠 original 至 after 结束的 task-owned 引用、停止准入及两次 grace 后排空回收、64 槽预池与真实动态溢出、原生 kprobe pre/post 与 Hook 同址共存，以及 actor/shared-bool 实际消费者。x28、48B HookContext、原 API/ABI、BTI/PAC 和已准入 after 跨 disable 保留。

受影响 NDK 成员、真实 rootloader、完整 management 和 native core/ARM64/decoder 编译通过；八成员替换既有归档副本后的严格 whole-archive 链接通过。该副本只证明增量链接，没有提升正式 SDK/helper/backend/APK。源码入口、逐项结果及复现见 [P5 开发交付](dual-mode/impl/_work/2026-11-15-p5-p7/P5-DEVELOPMENT-CLOSEOUT-20261119.md) 和 [P5 Todo](dual-mode/impl/_work/2026-11-15-p5-p7/P5-TODO-20261119.md)。

本次纯开发交付已完成；真实 SMP/grace、睡眠调度/task-exit、hotplug、原生回调及硬件 PAC/BTI/CFI/GCS 尚未验收，整阶段设备父项保持开放。以下早期 P5“Legacy/after64/native 共存尚未实施”仅保留历史范围。P6/P7 另按用户要求在本次交付后顺序启动。

## 2026-11-19 11:13 UTC 本代APK构建、签名与实际设备加载

原唯一[执行清单](parallel/2026-11-19/main-closeout/REMAINING-EXECUTION.md)已细化为116张逐项卡、10组责任及29个实际动作；每项保源码/选源/产物/设备出口。119权威原行、20持续约束及两组10逐条覆盖校验通过，SDK-05/06过时缺件已更正；完整勾选仍原3项，目标active。

完整Kotlin/Java、JNI与provider/L3均实际通过。host仅补两个已有compat源、serialization依赖原已具备，窄编译32.95秒exit0；修补工具仅一行旧头引用改到现有maplesu_driver.h后最终链接通过，策略工具也链接通过。GNU dlltool、prost临时目录与module-tools默认MSVC缺link.exe分别用既有Mingw参数、进程TMP/TEMP及既有GNU Rust工具链参数解决，没有跳过生产任务。canonical-apk-r7于11:03:07退出0，APK 87,136,449B、SHA256 faf8dc6b8a741c479f18fcef753a78493bd5f06a5c9a3b0ed77e92ee2e64b535。原失败收据全部保留。

包内本代JNI/helper/backend与DEX BuildConfig身份一致；普通bootstrap经AGP strip，独立复现与实际APK完全同字节，23个ALLOC节及入口不变，原直接字节比较首错保留。首轮安装debug包被签名不一致拒绝；随后复用已有发布签名30bafb57…、v2-only重签且ZIP全部条目相同，设备包SHA256 32295212ef37cab3d5daa403bb1aeb61f3f6b12abe4e85f1c993fd92d017c028。11:09:30 adb install -r成功，保原数据；手机base.apk摘要、JNI桥c71d6eda…及backend45f83a87…实读相符，Manager进程maps含新JNI桥，Activity启动成功。本轮执行器未写分区或发出重启；前后boot UUID改变单独不作Panic证明，当前bootreason=reboot、boot82512ecd…，普通shell无pstore权限。页面工作环境仍未安装，SELinux未知；这些保真实未验结果，不算SU/环境Ready或首次冷启通过。

SOURCE仍按域完整交付后一次采用：IMAGE已交89路径与中央assets窄候选；SU10源已消费CORE task Profile、48用户态后继已合，Owner SDK write/list实际EOPNOTSUPP活调用由HOST同表authenticated sync/readback→CORE唯一R02 typed→SU实际消费者共同闭合，不重写第二授权表。MODULE继续Hook租约/退休、固定初始化真实业务、开机/卸载、WebUI与Owner状态；共享Client/JNI runRootCommand入口只留SU typed单写者。HOST与CORE续接已见真实工具执行；本轮APK仅覆盖已经采用的源码，后续域候选不热插当前构建，整体终验条件保持。

构建原收据：`.tmp/main-sdk-current-composition-20261119/current-source-adopted-r3/canonical-apk-r7/EXECUTION.json`。字节/签名/安装/加载证据：`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/current-generation-native-binding/`与`current-generation-device/`。各域最终正式组合、三模式真实32/64位SU、零模块无Manager冷启、150ms/300ms及全部指定原故障仍待证据齐全后逐项勾选。

## 2026-11-19 10:13 UTC 同代源码SDK、实际AGP与最终JNI链接

本段接续下方历史状态。269个固定输入的完整新代已于09:52:46结束：260成员归档18,884,500B，SHA256 `9469e7aa3f49c17b5be3e2a4296094b34d8ad57056c20c7a7d02fa2eb71b00d8`。backend/helper/四payload均由源码产出，bootstrap/final helper严格同字节配对、全归档whole-archive/no-undefined链接及provenance闭合通过；原raw门仍exit7，191通过/69失败、零missing/extra/deferred，81个强T迁移有当前映射，不能记为原门通过。六族3245个来源/依赖身份未变，复用原证据，没有重跑六族或冒称两次clean。

隔离candidate已通过共享source-validation及实际14项正反验证；生产AGP的verifyUltraSdkIdentity、generateDebugBuildConfig、generateDebugRFile真实成功，九身份字段与本代SDK-RELEASE一致。10:12:01原生产Gradle的buildMapleSuRuntime完成33步，JNI桥、Ultra模块/版本工具和全部默认runtime目标最终链接exit0。桥9,649,400B、SHA256 `c71d6eda944ff038bc12b7edc223ddbf668bd7e1216e0f68812b27e38ce3dbb8`，不再停留在单TU编译。正式默认SDK身份仍未提升。

Manager full31首轮七个未解析引用保留；源码中已有对应实现，后继仅将当前ModuleCatalog.java编到独立依赖目录，并加入当前RootStatusReport.kt，同一176源补集成为177个Kotlin输入，实际编译exit0。没有为消除首错修改产品源码，没有重跑15个既有JUnit。完整生产AGP assembleDebug于10:14:53实际exit1：UltraEnvironmentPanel.kt与UltraEnvironmentSettings.kt重复类/remember入口、ProductionHiddenEnv.kt重复ultraRoot、WorkModeStrings.kt三个环境切换字符串缺失。完整日志和原失败保留，唯一负责人正在准备保功能/保UI的隔离窄合候选；采用后只继续受影响APK构建。

本段实录位于`.tmp/main-sdk-current-composition-20261119/current-source-adopted-r3/`下的`BUILD-EXECUTION.json`、`CANDIDATE-VALIDATION-EXECUTION.json`、`agp-generation/EXECUTION.json`、`manager-full31-r2/EXECUTION.json`及`canonical-native/EXECUTION.json`；APK首错由`canonical-apk/EXECUTION.json`记录，未生成本轮成功APK。10:14:23设备普通shell实读仍为6.6.89/4K/slot_a/仅arm64-v8a、boot_completed=1，boot75a6f26e…；UUID变化单独不判Panic，未请求Root或写分区。

新设备dump `0c0c47f3…`绑定故障boot e1692510…，841.58秒仍为BLR首次取指权限IABT；独立frame保存时WXN=0、TTBR1根/CPU匹配，当前源码已有共享TLBI与I-cache屏障。缺故障代码物理页/有效EL2证据，rootCauseProven=false。6.6.89设备的真实BTF及clang18已有材料入口，精确源码/Module.symvers及正常桥加载资格仍有缺件；6.18桥不作为本设备资格。未部署本代APK/新镜像，正常冷启、三模式真实SU、150ms/300ms及全部失败原包仍待终验。唯一116项执行清单持续更新，119父项无新增完整勾选，目标active。

## 2026-11-19 09:12 UTC 唯一剩余执行清单与实际闭合子项

当前逐项执行入口为[剩余实施与验收清单](parallel/2026-11-19/main-closeout/REMAINING-EXECUTION.md)及同目录机器表：119项原文逐行一致，原3项完整勾选保留；116项各有唯一任务、负责人、下一动作、依赖及源码/选源/产物/设备出口。20条持续约束、两组各10项原条件均已逐条映射。复用既有成果，不重新启动全项目审查。MAIN唯一正式集成/构建/设备执行，独立桥源码归IMAGE，SDK/helper只保留一个修复负责人。

本轮已实际闭合两个子项：m081组合步骤漏私有头路径已在唯一build.py入口修复，原六TU组合复验exit0；IMAGE独立桥在真6.18.21/config da26/Module.symvers f678下源码组装、严格Kbuild/modpost/ko链接exit0，实际包检查的导入/CRC/namespace与credential参数、4K页/36B调用位移均通过。ko为3,495,992B、SHA256 41d0c377c788ab5071638affe4a1705e88d6a4d3cd7a0a91bd7a930c8d4185a0；加载、完整环境、目标执行和冷启资格仍待，不适用于当前6.6.89设备。

249固定输入后继已于09:00:12结束：260成员归档18,856,002B（685784ab…）、backend/helper和四payload均由源码产出，最终helper配对exit5原记录保留。首因缩至事后strip前的GNU build-id：09:11实际复用本代12TU，仅增加链接阶段strip，bootstrap/final完整文件均为1,719,784B、SHA256 52037e6c55f4d339dee8ffdb3c8081f410ce5546428e9d7ff6af87ad640c24da；全部ALLOC节（GNU标识除外）、动态面、key/backend身份均保全，四payload安装器未选。09:12一行helper manifest修复已采用，严格配对校验未变；完整新代产物资格仍须后继producer，未stage/提升正式SDK/APK或部署设备。3245个未变policy依赖复用；旧1e2d归档的192/68 raw仅作历史定位。本代收据为`.tmp/main-sdk-current-composition-20261119/current-source-compose-fixed-r2/BUILD-EXECUTION.json`；双链窄修收据为`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/source-validation-delivery-contract/helper-pairing-current-r2/main-double-link/EXECUTION.json`；桥收据为`.tmp/p2-boot-initboot-closeout-20261118/evidence/bridge-resume-20261119/MAIN-EXECUTION.json`与`MAIN-ARTIFACT-INSPECTION-SUCCESSOR.json`。下一轮接各域稳定源码一次组合，完整P0–P8目标保持active，尚无新增119父项勾选。

## 2026-11-19 当前主线源码、构建与设备接点

06:32 UTC后继：五文件bootstrap修复已使真实helper首链通过（1,713,456B、0bb65344…），4个payload均由源码构建。162输入的本轮完整构建生成260成员归档（18,742,594B、1e2dcd9b…），但helper对最终归档重链接后变化，原构建exit5，首因修复进行中；未stage/升正式身份。为集中收口，MAIN对这份归档独立运行原完整raw比较：260对齐、192通过/68失败、零missing/extra/defer、1个layout翻转，raw exit7原样保留；并实际全归档whole-archive/no-undefined/no-gc-sections链接exit0。后者只证明强符号提供者闭包，配对、行为/ABI与新代交付条件仍待闭合。m045诊断与JNI/Kotlin rootVerified消费者由唯一写者准备下一源码窗口候选，未热改本轮输入。6.18实际原生modpost仍运行，真实出口为Module.symvers；设备06:18实测仍为09894f94…同次启动、ADB在线。P0–P8总目标active，119整项仍仅SDK-01/07/08。精确证据见本段后继状态 SDK-BUILD-POST-CLOSURE.json；以下历史时点保留。

05:55 UTC后继：m073已完成15个U差异独立审阅并成套采用C++与SINGLE metadata，中央manifest经原build.py生成，260成员现为250 SINGLE＋10 COMPOSED；这是选源状态，raw gate仍exit7，不等于全部源码/设备终验。新155输入来源锁真实绑定工具3a1bd3b9…及中央/MODULE后继，完整SDK源码构建正在执行。六族已分别取得当前后继源的native编译/链接exit0，保持各自代际范围和reference/factory/目标资格false；新增cache adapter单方法通过，未重跑原绿组。6.18完整源码91758路径/86symlink和真实24项KMI列表已恢复，MAIN同da26配置modules_prepare实际exit0，module.lds已生成，导出/CRC生产进行中。实际AGP生成在verifyUltraSdkIdentity处以SOURCE_SDK_STALE: runtime/CMakeLists.txt停止，保原校验及失败，没有生成或替代BuildConfig。后继精确范围见同目录 `POST-CLOSURE-CURRENT.json` 及各实际执行收据；目标active，零新增119父项勾选。


中央消费域24条与 MODULE 累计78条及6条SDK环境/日志依赖已正式接入，共107个唯一路径；后一次实际写入84条（77修改、7新增）。保留MAIN的m023 errno、HOST sizeof、readback、认证scope和JSON/OOM首错。JNI私有头路径已修正并通过canonical Android TU编译；9场景mock SDK认证scope夹具在普通ADB shell通过。最终native link、新完整SDK/helper/backend/module-tools/APK及真实Owner/Root/SU不由这些局部结果验收。

本代native/support单次构建已结束：bootstrap2200B、materializer1968B、permissions788B实际编译/链接；6.1/6.6/6.12 native编译/链接通过，目标资格仍false。5.10首错为ucounts与cache原型，5.15为SU owning mm.h，6.18 native已编译但依赖采集相对路径错误。5.10 management与Carrier已窄合，builder已采用configure/cache、实际Kbuild cwd、同对象DWARF→ELF relocation→BTF静态host绑定修复；四族已有BTF对象的host绑定通过，原对象字节未变。两条实际源后继及集中重编仍在推进，不把生产源码存在当作已进入冻结选源。

m073已收到可维护C++和SINGLE metadata候选，独立reviewer正在逐项核12移除/3新增U符号的实际行为、提供者及ABI，raw门禁exit7保留；尚未成套采用，历史m073事件不再作为全局停工依据。下一同代组合将重绑extract_payloads.py实际3a1bd3b9…字节，不回退工具或豁免旧锁。完整SU整域、独立init_boot正常模块、早期factory和普通SDK免Key生产接点继续分别留账。安装变化采用受保护来源、签名/fullUID及事件驱动定向撤销；不加ROM/framework/PMS事务屏障，不宣称PMS零窗口。

6.18正常ko已固定同commit、da26配置和匹配Linux/clang/Rust/bindgen入口，正在恢复同commit完整源码；真实CRC、保护/ABI列表、module.lds及正常ko尚未产出。设备05:14/05:17 UTC实测ADB在线、Fastboot未枚举，仍为09894f94…同一次启动、sys.boot_completed=1，故未重刷此前已确认829e4866…的诊断boot。04:46取证已有31份完整稳定文件，缺新完整Panic正文及控制槽；短时稳定、旧vendor包与BootReceiver摘要均不当作Panic修复或正常冷启验收。

精确来源与范围：`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/core54-shared46-adoption/main-progress-sync/source-closure/CURRENT-DELTA.json`。源码、选源、编译/链接、正式产物和设备分账；P0–P8、119项、20条约束、两组十项及150ms/300ms条件不缩减，完整勾选仍仅SDK-01/07/08，目标active。下文保留各历史时点记录。

## 2026-11-19 SDK 工具链已采用，诊断 boot 已真实刷入

MAIN 已按完整 SDK 源域交付精确采用5修改＋5新增：build.py、source_sdk_delivery.py、payload parser、实际可维护 executable publisher include、runtime-origin-revision3 helper checker、两次 clean 可复现入口及其成对夹具。10条前像、候选来源和最终回读一致；原完整候选首错及同输入限定验证保留复用，没有重复编测。正式f7acc05 archive/SDK身份、权威119项保持原字节，本批没有构建新SDK或最终native/APK。下一正式组合仍需各域稳定业务源、当前私有ABI、六family来源和真正两次clean产物；m073原具体停止另账保留。

设备已先从FastbootD正常重启、实际Root回读证明boot_a=8e00…且init_boot/vbmeta与先前一致，闭合“第一次Sending失败是否改分区”的不确定性。用户要求继续FastbootD后，第二次完整96MB发送仍USB31、无Writing成功；重新插接后改用工具自有-S8M分片，五个分片发送/写入全部OKAY并重启。实际boot 4c3ab535…随后Root回读boot_a=829e4866dbdd38c92a73edaecb16611dcbe7a05291d5714b06619e7622694896，init_boot_a=2e4d44d4…、vbmeta_a=e87be89c…均未变，诊断部署身份现已确认。保留工具箱符合用户指令；没有声称其是USB首因。

新boot 9d199203…也实际进入系统并回读同一诊断分区；UUID变化本身仍不归因为Panic。此次导出的厂商包还是旧a8cf0230…，pstore为空，尚无新故障寄存器证据。唯一观察进程正在采集后续窗口，boot变化不重放旧失败操作，不清启动保护。诊断镜像只是52B控制状态取证增量，不是已修复结论；完整SU/三模式、首次及连续正常冷启、150ms/300ms仍待验。

D盘逼近5MB后，本侧对自有历史SDK staging作NTFS无损压缩，五份实际archive前后SHA保持；单次D可用空间由约16.8MB回到98.8MB（其他写者仍在使用），无文件删除、整树复制或唯一备份清退。证据：HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/sdk-delivery-intake/ADOPTION.json、executor-capture-delta/FBD-8M-SPARSE-ATTEMPT.json、executor-capture-delta/DIAGNOSTIC-RUNTIME-READBACK.json、sdk-delivery-intake/HISTORICAL-STAGES-NTFS-COMPRESSION.json。119父项零新增勾选，完整目标active。


## 2026-11-19 Canonical Android 编译与 USB 宿主竞争线索

CORE24 已正式采用，两个变更 JNI TU（native_lib_ultra.cpp、installed_helper_readback.cpp）现已通过 canonical runtime/CMakeLists.txt、NDK26/API31 的实际 ARM64 对象编译。仅结算本轮源码的类型/代码生成检查：配置真实保留了旧 f7acc05 SDK identity，未运行最终 native link、SDK/APK 构建或设备操作，这两件对象未批准进入新代发布。正式旧归档缺11个已检查必需导出；下一步先使 m023/m024/m060/m070 与当前私有 ABI 进入真正同代 SDK，再统一最终 native/APK 链，不手工拼旧归档。

用户对较早问题回复“系统／安全模式”后，本轮300秒有界观察仍没有ADB，Root尝试0、刷写/重启0，未确认新的boot或分区内容。Windows在8/8个采样持续保留同一Bootloader接口，同时SmartTool（紫罗兰工具箱）反复启动0–4个Fastboot子进程；这支持宿主竞争线索，尚未证明物理USB断开或锁定断连首因。已请退出工具箱并核当前屏幕；本侧未终止该外部程序。先前Sending失败无Writing/OKAY，诊断镜像仍未确认生效。

新增只读核查确认迁移后的实际printfn0仅命名变化，故障后打印叶表只读位=1/PXN=0，上层bit59=0；首指令取指失败的故障时刻控制/翻译状态仍待取证。未猜测加锁、清保护或宣称Panic已修。证据：HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/shared-android-compile/STATE.json、core48-intake/ANDROID-NEXT-BUILD-READINESS.json、executor-capture-delta/HOST-CONTENTION-OBSERVATION.json、executor-rx-static/CAUSAL-REVIEW-20261119.json。119项无新增父项勾选，完整原验收条件保留。


## 2026-11-19 源码采用与取证镜像发送状态

本节是较新结算；下文 pending、未采用及旧窗口描述仍按其历史时点保留，后继状态以本节和两个状态 JSON 的 `latestDeliveryStatusSync20261119` 为准。

- **共享十路径已正式采用**：六 Kotlin、两 CPP、一头及中央 CMake，bridge 选用 MAIN 的 SC-OOM-01 后继，SHA-256 `b1aaef1c7a328a5b10b607e6084fd337761bad6c205b41ff2204a57726b9e254`。39 份 Kotlin 与五类夹具编译通过；首轮30项有两项旧 Carrier 断言失败，首错保留，仅修过时夹具后该类10项全通过，另四类复用首轮通过证据，未宣称同一最终30项运行全过。serializer/guard 的265次分配故障、helper-readback 的3952次分配故障及2512次命令保留检查通过；只覆盖限定 host seam，实际 Android JNI TU、最终 native link、SDK/APK 与设备未由此批执行，其他 bridge 入口的分配错误边界仍待核。
- **CORE24 已完成源码窄合**：MAIN 释放窗口后，INTEGRATION 于2026-11-19 01:11:28 UTC正式采用 revision3 的19修改、5新增、零删除；48个选源输入、24个后像及十路径保护回执齐全。本次同步再次核对实际34条后像一致；全局 manifest JSONL、正式 archive 和 d03 隔离输入未变，未构建、测试或上机，仍不是P3/P4或Root/SU/Ready验收。
- **导出规则迁移已采用**：`.gitattributes` 将参照目录指向实际 `runtime/ultra/reference/`，删除14条无实物、index及最终来源图引用的旧规则，行尾规则保留；后像SHA-256为 `2ccd5706dd8b83ff3f8b42c708023b0bc40832bbab97c2aaf85582b870bf0d39`，未生成归档。
- **独立控制状态取证镜像已生成，尚无部署成功证据**：新boot为 `829e4866dbdd38c92a73edaecb16611dcbe7a05291d5714b06619e7622694896`，恢复boot仍为 `8e00f67ee2d4b01b091cb9bf1c7813e4bff49b66b193869a960a76df72aae871`。52B独立trampoline、96B私有frame保存SCTLR/TCR/TTBR1/MPIDR/DAIF；四个限定跨度共64B、实际51B差异，原BLR/LR和认证metadata坐标保全，clang汇编、IDA独立解码、零relocation及原镜像独立重放通过。这是诊断候选，不是Panic修复。
- **唯一一次fastboot发送失败**：`boot_a` 在 Sending 阶段报 `AdbWriteEndpointSync failed (31)`，无 Writing/OKAY回执。`partitionsWritten=[]`，同时保留 `partitionPossiblyWritten=boot_a`；既未认定刷入成功，也未认定boot绝对未变。没有重复刷写、清userdata或写另一slot/init_boot/vbmeta。后续USB、屏幕与boot/分区身份由MAIN唯一设备执行方重新绑定取证，发送失败本身不证明启动结果。

第二类执行器IABT首因、首次及连续正常冷启、三模式完整SU、真实32/64位及150ms/300ms仍未闭合；一次523ms真实SDK `id -u=0` 与Running仅保留原观察范围。P0–P8、119项、20条持续约束、两组十项及原停止范围全部保留，本轮零新增权威整项勾选，仍仅SDK-01/07/08。原Panic、pending/inbox与历史首错不删除。

证据：`WORKSPACE/docs/parallel/2026-11-19/shared-consumer-domain/MAIN-ADOPTION-RECEIPT.json`；`WORKSPACE/docs/parallel/2026-11-19/shared-consumer-domain/core24-adoption/ADOPTION.json`；`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/gitattributes-intake/ADOPTION.json`；`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/source-review/executor-capture-delta/FLASH-STATE.json`。本次CAS、前后像与范围收据：`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/latest-delivery-status-sync/STATUS-SYNC-20261119.json`。

## 2026-11-19 运行字节与第二份执行器故障证据

本轮新取回的 39632896B 厂商包（SHA-256 `a8cf0230f2c7003c6aa965ef562e8e7d177ddfb1f384e43b74688395559575c1`）将另一故障绑定到 boot `3930361d-c13d-453d-86a5-e4a1aa214a82`：697.694849秒、CPU0/PID21686，仍在动态执行器 `BLR x10` 首次取指处发生 `ESR=0x8600000f`。保存栈再次证明NPAGES=1，打印PTE为PXN=0/UXN=1。导出boot `c6c44258…` 与故障boot分开；这是新取得的历史故障证据，后续UUID变化本身不证明每次都是Panic或自主重启。容器尾部截断，所用首异常、boot与栈切片位于完整返回前缀内。

实际设备只读采样boot `64ddeea6-d525-49f0-afea-31306495e3fa` 已证明：六个页权限函数与已刷候选逐字节相同，实际executor的188B也精确相同（SHA-256 `c776b950914a4f5c39c7764ff5763614175cc40fbf40f40660bea3d3648b7d3d`）。这补上此前只有静态对比的缺口，但只是后续boot字节，不是故障时刻PTE/TLB或控制寄存器证明。1592B容器的17B差异位于metadata `init_path[1..17]`，首NUL由27提前到17；另 `_text` 首64B与Image/PE头不符，`_stext=_text+0x10000`。其中init_path已按原m073 IR24960–24999与实际18B写入结果锁定：该函数主动写入斜杠、16位随机字母数字及NUL；该17B差异符合原始预期。单独的Image头64B改写来源仍待查，未当作Panic首因。

同次文件回读确认常驻身份服务bridge（inode1113295）与Manager安装bridge（inode1112078）均为d03的 `0c27249e24132948ef802bb6a6ed0c754c83fbf406f4784fedd0bf15f07e6446`；`pairing.apk` 为已安装d03 Manager的 `ab9f827497321ddcf5fa5240b09ed0d3a553aaf4e64578dea035014d3e814f42`。两处inode与此前transport采样对应，排除这两处仍混用旧SDK的疑点。该非故障窗口捕获96个transport，93个来自身份服务、3个来自Manager；这不归属两次Panic的真实调用者。PID23352保存UID/GID的组合值符合旧协议ALLOC_MEM暂态回执，并非永久凭据损坏证据。

历史88B bracket的BLR/LR为+56/+60，当前188B为+64/+68，且恢复freeze不等于未修改的独立原SKP基线，撤回“执行器与原实现完全无差异”的过强表述。当前实际配对合同已核：`261784 + 68 - 4 - 208 = 261640 = 261636 + 4`，LR定位精确命中metadata；生成器按实际BLR配对，无额外偏移修复。该窄合同排除不扩大为m025全体原始等价。

诊断中先因kallsyms地址全0、再因头部比较门停下，首错原样保留，不计SDK功能失败。后续通过认证的live v2 metadata取基址，使用strict v2 READ完成有限采样；公开 `read_kernel_mem` 的legacy分支包含动态执行器，先前将其一概描述为直接读已更正。诊断自身取得Root仍使用一次既有动态bootstrap；只读阶段没有legacy fallback、kernel写入或safe/启动保护标记变更。本批未追加生产SDK修复，已部署的功能修复仍为m086那一行。

既有后续boot的一次真实SDK `id -u` 成功、523ms及Running观察继续保留，第二类Panic首因、首次/连续正常冷启动、完整三模式SU及150ms/300ms条件仍未闭合。目标保持active，119项整项勾选仍只有SDK-01/07/08，原两组十项、停止范围和所有追加要求不缩减。

证据入口：`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/new-window-20261119/analysis/NEW-PANIC-RECEIPT.json`；`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/runtime-permission-readback/RUNTIME-COMPARISON.json`；`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/runtime-permission-readback/64ddeea6-d525-49f0-afea-31306495e3fa-sampling.log`；`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/exec-transport-capture/CALLER-ATTRIBUTION.json`。CAS前后像及范围收据：`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/STATUS-SYNC-20261119.json`。

## 2026-11-18 d03 helper完成，第二类执行器Panic待修

后续持久日志及下一boot的completed/work回执已证明 `297e6180…`、`acbd221d…`、`ab4e8234…` 三次helper完成policy=0、selinux-native=0、identity-launch=0、module-batch=0及module-capability=C/0。先前10.43秒快照中没有policy返回值，只是采样边界，现已更正，不再将其描述为policy卡住或helper未完成。各次tombstone=-19934仍作独立未闭合记录。

新42881024B厂商Minidump将故障绑定到boot `ab4e8234-83e0-4154-ac12-36a77afa1111`：161.708520秒动态执行器由 `blr x10` 进入代码页时发生EL1 IABT level3权限异常；保存栈的NPAGES=1，已排除本次页数为0。打印的PTE快照PXN=0，按常规stage1位解释可在EL1执行，并非“PTE实际就是NX=1”；它是稍后读取值，未捕获故障CPU当时的TLB状态。权限切换、TLBI/发布同步、并发释放或复用等归因仍待源码和运行证据区分。此故障不同于历史168在8.3秒的m086 NULL读取；全payload未从dump恢复，容器尾截断与首错可用范围均保留，尚未证明第二类根因已修。

后续cbb、646及最新读取的f1c boot均出现sys.boot_completed=1/activity found，但前两次随后仍有新boot，未取得连续稳定启动、完整SU/Ready证据。总负责人已通过d03 SDK的 `set_adb_forced_disabled(key,false)` 返回0，guard marker由0变1，未修改safe/boot保护标记；ADB保持后由唯一采集方在stable-window继续记录。系统曾启动完成与helper阶段完成分别记账，原119项仍仅SDK-01/07/08勾选，三模式SU、32/64位及150ms/300ms条件保持未验。

证据沿用同一C盘su-hook-fix-device目录：`D03-LATEST-PANIC-RECEIPT.json`（SHA `c989c4bb3efb0dd899cf42e024c707628bca89197def1d49e8134d76199c72af`）、`reconnect/D03-RECONNECT-RECEIPT.json`、`KEEP-ADB-EXECUTION.json`及`stable-window/ENVIRONMENT-REPAIR-TIMELINE.json`。后者为持续追加日志，只引用本次读取时点，不把采集进程存在当验收。

## 2026-11-18 d03 上机首次快照历史（已由后续日志补全）

以下为较早采样时点记录，后续helper完成和第二类Panic以本页最新段落为准。

m086修复组合 `maplesu-pro-source-d03be84513360d93` 已完成一次规范 `--all` 构建：260/0成员门禁、来源闭包、归档配对稳定性、SDK staging与identity检查通过。archive为 `d03be84513360d93ae390035e6f70030e4e640b8e9bcce059e66a37bc0c5eb38`；实际m086对象 `5d13d028…` 与已核Android21 TU完全一致。Manager `ab9f8274…` 与测试APK `59974e2c…` 已同证书签名、原位安装Success，JNI身份逐字段匹配。此包为保留168隔离工程的派生修复，不代表迁移后全部新源最终交付；原258/260探测失败与原168 Panic证据保留，未放宽源码或门禁。

产品repair后首boot为 `297e6180-c2c2-4109-aa1c-fa4ceb527d17`，10.43秒采到1586520B helper；slot136336插入Key/NUL后的完整raw SHA为 `ac42fec7ea4a3615ff7498d0305ac05903a9217d143d5de596677319c434ea10`，与设备精确一致。`root-environment=0`、`su-redirect=0`、最后完成 `install_config.exploit=0`；safe_flag=1，completed/work为空。下一源码调用为helper_policy_start，尚未取得其进入或policy返回值证据。内核日志止于10.510741秒，随后USB失联，尚无新Panic、保护重启或稳定首启证据；屏幕状态待总负责人确认。10.43秒仅为单次快照，采样未记录policy返回值不等于持续卡住。源码module_selinux.cpp:636–659在标记缺失时会立即并于10秒后再次close_adbd，后者设置adb_enabled/development_settings_enabled=0；这提供断连解释线索，尚无该分支实际执行证据。总负责人正等待重连/屏幕状态，并已请设备进系统时重新开启开发者/USB调试、安排reconnect目录900秒有界采集。完整Root/SU/Ready及原150ms/300ms仍未验，119项无新增整项勾选。

证据在 `HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/su-hook-fix-device/` 的 `INSTALLATION.json`、`NATIVE-IDENTITY.json`、`HELPER-DEPLOYMENT-IDENTITY.json` 与本boot root/logcat日志；构建与签名证据为168隔离工程 `.tmp/su-hook-fix-generation/COMPLETION.json`、`device-delivery/APK-VERIFICATION.json`。以下168首启Panic均属历史代次，不继承为d03的新Panic。

## 2026-11-18 168 实机首启仍有 SU Hook Panic

168 Manager/测试APK已原位安装并由JNI回读；产品repair实际发布1586472B helper，Key槽插入后的完整raw SHA与设备 `7d0656ab…` 一致。首boot `fc168f67-d10d-4564-8ecd-6c9acd6f872c` 的root-environment已从原-20000变为0，随后第8.303542秒在faccessat跳板发生NULL读取0x38：`ldr x11,[x1,#8]`，现场x1=系统调用号0x30。原SKP m086为x0+8，旧核查将x0→x1误记为既有修复，此归因现被实机反证；m086已在当前生产和168派生修复源恢复x0+8，m081–m087及m026/m029入口合同与当前设备Execve生产链的限定补核已收齐。

后续保护boot `a7e18125-f41b-40ed-a525-3873ed273953` 的safe_flag=3、work/completed空；实际readiness环境Fault，channel/version为true。正常首启、完整Root/SU/Ready继续未验，不清保护。厂商本次64839680B Minidump已取回且设备/主机SHA一致；证据在 `HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/PHASE168-BOOT-ANALYSIS.json`、`HELPER-DEPLOYMENT-IDENTITY.json`、`PANIC-TARGET-ABI-EVIDENCE.json`。完整P0–P8目标active，119整项仍仅SDK-01/07/08勾选。以下历史记录按各自时点阅读。

本次已收齐三份专项审计：`PHASE168-NEXT-STAGE-SOURCE-REVIEW.json`、`SU-GENERIC-HOOK-ABI-REVIEW.json`、`PHASE168-EXECVE-PRODUCER-BINDING.json`（位于上述C盘证据目录）。当前设备append+36=`08f83d000100`与真实生成器、已刷镜像和内嵌BTF的filename=x1合同一致，未发现另一处独立首启基址缺陷；reg2/3泛化、全SDK及设备通过均未据此认定。m086实际Android21函数310行IR与原SKP相同，32项模型修前6失败/修后0失败；原168派生组合已完成为d03并上机，构建/签名/身份/部署子节点通过；后续证据已证明helper阶段完成并确认第二类执行器Panic，稳定启动及根因修复仍待闭合。首次构建的258/260探测配置失败保留，未放宽清单或门禁。

## 2026-11-18 MapleSU / Ultra 当前源码统一接入

当前生产入口为 `runtime/maplesu-sdk/`、`runtime/ultra/` 及 Manager `ultrakernel` / `runtime.ultra`，共享构建、JNI、模块 ABI 和持久输入已成套接线。1945 条当前路径、1995 条演进事件及 40 处尾部修正见[本域交付](parallel/2026-11-18/MAPLESU-ULTRA-SOURCE-INTEGRATION.md)。集中受影响验证通过：43/43 文件测试、39 源 Kotlin 编译、47 对 JNI及模块 ABI 对照、恢复状态行为；完整同代发布包和设备回归尚待唯一主线执行。活动内部旧命名和相关备注已清除；历史来源/身份、冻结输入、原样本和第三方许可保持真实，原模块/持久资料兼容保全。未新增整个阶段完成勾选。

## 2026-11-18 168 APK已安装，产品修复首启待结算

2026-11-18 15:50 UTC阶段结算：同代168 Manager与匹配测试APK已完成构建、签名及配套身份核验，并于15:50:24 UTC原位安装，两包均Success，应用数据未清。Manager SHA256为 `ee2ef7d92d0b74fe31f080ad5bda717cbe1c32dd19d8fec5e3c78873705992df`，测试包为 `8baddbf3a36280e7cec7e13efac40417f16b7ed286935a8c5b28463e599a09e7`；证书仍为 `30bafb57e3604afe93da13a1285516e043feb82b56a861a157b53ccaa27c3506`。实际currentbuildprobe已回读 `maplesu-pro-source-168dbb942b0f298b` 及archive `168dbb942b0f298b9b16a4e65a2377eddc53aa93102043a24ab60f4acb9cda7b`。

修复前同次观察仍为boot `e22fb3ee-f035-44e9-8257-5d39139b4f97`、设备helper 1585000B及safe_flag=3；生产helper reader返回complete=false、timedOut=true（transport/system/waitError=110）。随包168 helper为1586472B，APK安装与JNI身份读取不等于helper已经部署或首次初始化成功。同次只读root脚本返回UID0，仅保留为单次观察；完整Root/SU、环境Ready、保护生命周期及原150ms/300ms未通过。总负责人已在产品设置→修复→确认修复，正在采集后续首启，结果待其独立确认。

证据：`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-apk-snapshot/device-delivery/PHASE168-APK-VERIFICATION.json`、`HISTORICAL_EVIDENCE/maplesu-boot-repair-20261118/phase168-device-acceptance/INSTALLATION.json`、同目录 `before-repair-native-observer.log`。APK核验收据的installed=false为签名核验时点，后续INSTALLATION两包Success和实际JNI回读组成安装证据；原收据保持原文。完整119项只保留原SDK-01/07/08三项勾选，本次仅结算APK子阶段。

以下各时点源码、迁移窗口与旧代交付记录保留原文；其中“尚无168 APK/仍e540”等描述属于对应历史时点。

2026-11-18 迁移窗口交付：本轮260成员调用契约核查及m024完整字节尾修复已完成限定源码/主机出口，903项断言0失败；统一采用R10八项、resident route及B四路径后，实际SDK/helper/backend归档为 `168dbb942b0f298b9b16a4e65a2377eddc53aa93102043a24ab60f4acb9cda7b`。260成员门禁260 PASS/0 FAIL、最终配对、来源闭包与SDK staging通过；3条新ABI差异只按真实R10接口精确登记。junction路径别名已据同一实物和摘要规范化本代收据，原失败记录保留。

按最新统一内部命名迁移要求，当前所有本代构建进程已退出、源码读锁已释放，MIGRATION_ALLOWED=true，不新增生产修改或下一代构建。停止前Gradle已开始执行，停止阶段临时SDK目录移动曾中断packagePolicyHooks，目录已原位恢复，该协调中断不计产品缺陷；尚无本代新APK或设备通过证据。设备最后实际包仍是e540，保护与初始化失败未标完成。精确checkpoint和迁移窗口见 `docs/parallel/2026-11-18/SDK-NAMING-MIGRATION-WINDOW.md` 与 `.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/CONTRACT-REPAIR-MIGRATION-CHECKPOINT.json`。完整P0–P8及原实机条件继续active，无新增总需求整项勾选。

2026-11-18 本轮调用契约核查收齐：0–127 的128成员覆盖48处write/fill、20处hook转发、280处read及原m073独立IR写点；128–259的132成员复用完整vendor语义/原对象证据并逐分支核5个扩展，未确认第二处同类合法输入冲突。原m024对18字节只提交 `size >> 2` 个字，可能写16字节却返回OK，本次未恢复截尾缺陷。新增完整末字保全、写入与回读的真实API函数回归为903项0失败，前像588项失败；静态独立复核通过。该修复尚未构建/设备验收。当前R10/P2/B统一接入窗口已开放且没有构建读锁，八路径前像全匹配、m023未由字节修复改动；采用后像与窗口见 `docs/parallel/2026-11-18/SDK-BYTE-WRITE-INTAKE-WINDOW.md`。未新增总需求整项勾选。

2026-11-18 当前结果：扩大核查后的 SDK `e540a852ec71c5b8…` 已统一构建，260-member gate 为 260 PASS / 0 FAIL，来源闭包与最终归档配对稳定性通过；Manager 与匹配测试 APK 已签名并实际原位安装，JNI 回读同一 buildId。设备 helper 按安装器 48 字节 Key＋NUL 插入规则计算的 raw 与设备完全一致，归一化 SHA 为 `312bc13e…`。本代未取得两次完整 clean 可复现构建证明。

产品修复后首个观测 boot 为 `e22fb3ee-f035-44e9-8257-5d39139b4f97`，16.08 秒系统启动完成；环境初始化仍以 `patch avc_denied: ERR_MODULE_PARAM` / `root-environment=-20000` 失败，保留 `safe_flag=3` 与 current_boot_protection。原 m073 首步写 18 字节、prot=2 的路径，与当前 m024 非完整字长度校验冲突；已在 SDK 公共写入实现补末字保全、完整写入及回读，尚待必要回归和同代构建/实机。未清保护或强置健康。当前日志无本轮 kernel Panic 证据，也未证明无 Panic；两个 init 子进程 SIGABRT 单列待查。旧诊断器反射方法过期已修为调用生产 JNI，实际身份读取器返回超时，另记未通过。完整 119 项、首次正常冷启动、三模式真实 SU 和 150ms/300ms 条件保留，未新增整项勾选。详见 `.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/sdk-restoration-audit/CURRENT.json`。

以下带旧时点的记录作为历史保留，以本段和 CURRENT.json 当前证据为准。

2026-11-18 13:08 UTC追加：全SDK逐成员审查现已补齐vendor的hidden/inlines-hidden与AsmJit导出宏，实际60对象的1024函数、662 globals、1508类型及metadata/EH对齐，101 vendor ELF未变。核心同类配方偏差经九个实际stage窄probe确认后，统一续编85成员并复用68成员；0–42新对象可见性、类型附件与物理ABI复核通过，43–93最终对象继续核验，m028跨布局metadata资格单列。当前fdd9b4c1归档尚有10条新ABI残差精确归因中，未部署扩大核查后的新包。设备仍为e8失败后的保护boot；完整119项、首次正常冷启动、三模式真实SU及150ms/300ms条件不变。详见[全SDK核查说明](parallel/2026-11-18/SDK-RESTORATION-SEMANTICS-AUDIT.md)。

## 2026-11-18 全 SDK 恢复语义核对进行中

用户要求从本次 PAC 漏还原扩大到整个 SDK。260 个成员最初按 68 / 60 / 132 三组分配，随后将 45–69 的待核尾组移交核心负责人，当前单一所有权为 0–42 / 43–127 / 128–259，共 43 / 85 / 132；已有证据直接复用，证据目录名保留。[逐成员总账](../.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/sdk-restoration-audit/COVERAGE.json)与各组 ledger 分开记录原始对象、真实调用合同、已核范围和待核范围，不以符号一致或旧完整度判定语义通过。既有追加功能和原停止边界保留，P2 五条目标接线不交给这三位核查写者。

首轮 260 个成员均已有具体证据条目，底层 43 个成员完成本轮有限收口，核心与 vendor 的原 IR 函数差异继续逐项消解；101 个 vendor ELF 成员已核实际源码绑定与原始可执行数据 / 重定位同字节。剩余 ARM 与 x86 / CivetServer 函数分由两位代理接续，不重复已闭合差分。此计数表示记录覆盖，未提升为全函数语义或新包设备验收。

后续证据已将 vendor 统一配方偏差闭合到 F2 + C++20 + ThinLTO，实际 m097 / m154 局部构建的 60 / 159 个函数及全部 globals / 类型定义与原始 IR 对齐。仅补 F2 的 full LTO 候选快照保留，未当作通过；当前统一续编全部 60 个 vendor bitcode，101 ELF 命令不变，由两分区按实际最终对象 SHA 核验。P5 revision4 / R51 的 27 路径窄合已收到实际收据并纳入当前依赖续编，PUBLICATION 保持关闭，组合与设备证明仍待取得。

全量选源首轮为 259 个 C/C++ 成员及唯一 m073 原始 IR，25 个 partial 逐项区分 19 项历史统计、5 项正式验收待闭合和 m073 真实三段发射器数据流缺口；m073 已有 AVC 拒绝 handler 不计作缺失。本轮源码恢复子任务被工具检查标记而未交付，生产与选源未动，精确结果独立留账。来源闭包通过仍不等于全源码目标完成，完整需求勾选不变。

当前已实际修入的明确偏差包括 m002/m006 nofault 读写参数反向、m029 PAC 提前返回及 after 锚点、m031 首次缺少历史文件误报、m044 解码判定、m079 命令行指针写目标与迭代器失效、m080 空白字符范围、m256 子进程等待错误；各组保存前像、原对象依据及必要真实函数红绿结果。m255 已保留冻结 helper 三窗口兼容，新增源码 helper 只读 ELF PT_NOTE 能力消费；对应 producer 与最终 helper 构建核验同步接入，32 项 reader 回归通过。能力声明只表达模块 gate 兼容，不授身份、Root 或环境 Ready。

追加已修 m035 标签正确快路径遗漏 chmod(path, 0)，保留避免重复 setxattr 的时延优化与真实失败。模块 ID 全链恢复原 SDK 单组件 UTF-8 合同，覆盖 m062 / payload、m255、Manager 启禁、metadata 与 ZIP 安装器；KSU 保留自身 trim 行为。JNI 非 BMP ID 的入参和 JSON 回执损坏已用真实本机 JVM 复现并源修，直接受影响的测试 stub 正在同步，设备往返仍待统一验证。60 个 vendor bitcode 的 Fortify 配方已补，最终构建须核真实命令与 m121 有限 1024 字节检查，而非仅核配方文本。

包含最早 PAC/m044 修复的中间归档已生成，但两次构建分别止于磁盘不足的归档或检查点写入；旧 APK、镜像与日志已无损迁移并留收据。该候选未部署，亦非最终交付。本轮按扩大后的要求先集中修复高风险源码，随后统一同代 SDK/helper/backend/APK 与设备验收；目前设备仍是 e8 失败后的保护 boot，首次正常启动、SU、时延与全部原验收条件继续保持未完成。未新增全量需求勾选。

## 2026-11-18 SDK helper 启动循环实机修复

本轮首次初始化 Panic 首因已由后续 Minidump 闭合：启动第6.436564秒，helper PID1793在 SELinux audit 写入路径触发内核取指异常，PC `0x189f14eb0685c418` 去除PAC/KASLR后精确落在 `common_lsm_audit+0x4c`，即 `audit_log_start` 的调用返回地址。目标入口已执行 `PACIASP`，before-hook从入口+4接管；恢复源码 `emit_ret_by_entry_insn` 仅匹配AUT编码，漏识别PAC入口，提前返回生成裸RET而未先AUTIASP。原始SKP m029明确按四种PAC→对应AUT→RET生成，属于恢复偏差。原RAW与实际候选在audit入口和caller窗口字节一致，当前证据指向SDK动态hook返回序列，未发现本轮镜像字节变化。

生产源码已补四种PAC case并保留原AUT兼容；真实函数提取回归修前4种PAC失败、修后14/14通过，覆盖PAC/AUT、BTI、NOP与STP入口。该证据仅校验发出的指令字，不执行ARM或内核；包含本修复的新SDK/helper/APK整包及设备首次初始化尚待唯一执行方完成，不将此前e8构建或保护启动算作本修复通过。精确链路见[设备PAC证据](../.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/audit-return-pac/DEVICE-PAC-RETURN-EVIDENCE.json)、[14项回归](../.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/pac-return-fix/REGRESSION.json)及[本次报告](../.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/REPORT.md)。原需求勾选不变。

实机验收结论更新（2026-11-18 09:15 UTC）：本轮repair已于09:14实际写入并执行1496656B新helper后重启；设备文件SHA为 `75daa11a030fd117fed57d6c529338dfcba2310659dab75f2db738037ed188aa`，不再是紧急80f37b36版本。用户实见首次卡二屏，随后Panic再启动。`09:15:06.796630Z`捕获的 `8eee3334-6f1f-4920-a072-eea467a058f8`（uptime10.86秒）是后续保护boot；其最终boot_completed=1不计首次初始化成功。该boot回读safe_flag=3、duplicate为本boot，work/completed均空；boot_a仍为 `8e00f67ee2d4b01b091cb9bf1c7813e4bff49b66b193869a960a76df72aae871`。完整root日志保留在 `actual-product-repair/repair-boot-8eee3334-6f1f-4920-a072-eea467a058f8-root.log`（报告同级）。当前只确认升级链真实发布新helper，环境首次初始化仍失败、安全模式未消除；不勾选完成。下列09:13:58“设备仍紧急版/等待安装”描述保留为写入前历史，已由本次设备回读更新。

此前时点（2026-11-18T09:13:58Z）：Channel 自检首因已确认并修复。原 SKP 使用 `LSR X9, X22, X21`，恢复源码误将 `0x12345` 写成32位常量，实际688成员生成 `LSR W9, W22, W21`，移位32/48时重复低半部并得到 `0x0001234500012345`。现改为 `UINT64_C(0x12345)`，重建恢复X寄存器移位，实际函数提取的plain/UBSan红绿证据保留。显式Fault repair的Channel前置及Idle重复Channel已移除，真实UID、身份/boot/activation绑定、并发准入与写后验证保留；受影响30项回归通过。

本轮完整源码候选archive为 `e8ee68875ad5db2faceaa4061b8bab408b5cc3a63cbabf32fa262ad969974500`，260-member gate PASS、最终archive稳定性通过。Manager `31bf2b67097c477519b124175d18d36d62e35b31da943eb81a7191467629b65a` 与匹配测试APK `0490f435cecca5cc3b6a93b28a59c752694db43550db0ba9c1c79c248896f820` 已签名、核验ZIP条目未变并原位安装；设备JNI实际回读 `maplesu-pro-source-e8ee68875ad5db2f` 及同一archive身份。签名证书仍为 `30bafb57e3604afe93da13a1285516e043feb82b56a861a157b53ccaa27c3506`。

输入只读窗口为 `2026-11-18T09:04:43.454867Z` 至 `09:11:22.142698Z`，1996文件共享读句柄已正常释放。此前重复续编由并行R04/R05/R49/R06输入变动触发，沿用已有对象及精确增量，未取得完整clean重复性证明。产品修复本轮已通过preflight进入prepare，`root-proof/run_root_cmd` 返回OK、586ms；尚待实际安装、重启及新helper回读。最后确认的设备helper仍为紧急 `80f37b36…`；随包新helper raw `6034966a…`、归一化 `35482fbc…`、1496656B、slot135736不等于已在设备发布。safe_flag=3及首次初始化缺回执/稀疏NUL材料保留，不宣称安全模式消除、Ready、完整Root/SU或P0–P8终验，原需求勾选不变。精确身份与后续设备结果见[取证与修复报告](../.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/REPORT.md)。

以下保留截至08:01Z及首次产品拒绝的历史记录；其中“继续核对/门槛保持”已由上述源码与当前准入事实更新。

设备 a261972a 升级后出现 helper 父进程重复重入。故障及修复后的 boot_a 摘要均为 8e00f67e…；新源码 helper 交回 app_process64 前遗漏原 SKP 的父 comm→zygote64 操作，已恢复。旧源码回归真实失败、修复版 57 条断言通过；紧急 helper 80f37b36…（1489488B）原子发布后约 26 秒 sys.boot_completed=1，Activity/Package 服务正常。截至 2026-11-18T08:01:25Z，同一 boot 070e996d…uptime 9235.32 秒，已超过 2.5 小时；未刷分区、未清应用数据，safe_flag=3 与原失败材料保留。

另已完成两种不同布局的同名 ModuleBannerGuard 私有类型分名，跨 TU 红绿回归通过。包含两项修复及已接入增量的完整源码 SDK 688f3610…已实际构建并通过 260-member 门禁，同代 Manager 2e9e53e8…与测试 APK 593fdb72…完成签名、ZIP 来源核验和原位安装；设备 native identity 回读确认 688f3610…，新 SDK 单次 Root 探针 501ms 返回真实 UID0。随包完整 helper 为 7517c408…（1489456B，归一化 195abb10…，slot 135448），设备 helper 此时仍为紧急 80f37b36…，未把 APK 安装记成 helper 环境升级。

产品设置→修复环境首次仍被准入拒绝，尚未写 helper 或重启。当前 LITE 事实中环境/SDK/App 版本均为5.2.0、Readback正常、暂停/切换/本 boot 已提交均为false；直接拒因是 Channel 自检返回 ERR_MODULE_TEST_CHANNEL/-19999，保护状态仍为 current_boot_protection。Channel 自检源码继续核对，产品修复门槛保持；首轮 oem-mtk 后缺后续回执与稀疏 NUL 的成因仍留账。该单次 UID0、启动恢复和构建门禁不提升完整 Root/SU、Ready 或 P0–P8 终验，需求勾选不变。详见 [.tmp 取证与修复报告](../.tmp/mainline-sdk-device-acceptance/panic-after-upgrade/REPORT.md) 及其真实收据。

## 2026-11-18 P2 resident 源码接点与本轮验证

现有镜像加载链已实际接入 boot-owned 64B publication slot、唯一 retained host、CAS 初始化/READY 发布、失败清理与 quarantine，以及 P7 签名/fullUID/epoch/watch 和 A R02 QUERY/ECHO64/READ producer。slot 来源是目标 Image memory 范围内经完整 kallsyms 证明的 BSS→init_pg_dir 对齐间隙，由 kernel_init_freeable 包装器在本 boot 初始化；96B anchor 继续只作不可变来源描述。boot 后目标页表 RW/NX 尚无实际读取证明，链接布局核验与页权限实测分别记账。源码、宿主来源与生命周期详见 [P2 接点](parallel/2026-11-17/P2-RESIDENT-OWNER-CONTACTS.md)。

最终六 family native 编译及三份 support 构建成功；6.6/6.12 reference ABI、目标布局/函数及 KCFI 有相应证据，其他 family 的 BTF/ABI/legacy CFI/6.18 reference 缺口继续阻止资格提升。最终组合使用 resident v2 专用 bootstrap1452B、permissions788B、materializer1968B；旧正式 v1 assets 与 Key/v2 wire 保留。15/15 受影响镜像实际通过生成、独立完整 kernel 回读、同版幂等、自有记录恢复及 BlockDeviceTransaction commit→readback→restore（14 个 Boot_Research boot 加 1 个匹配设备历史 base）。26 行总账中的 11 个前置失败明确复用上一轮收据：4 个 reference 不完整、6 个独立 init_boot 缺 bridge producer、1 个 NGKI family 缺 producer；不是本次新增通过。源码与全部输入前后相同。

设备 a261972a 重连后的固定只读采集成功，Manager 摘要前后一致、独立 probe 已恢复；该机未提供 kernel_page_tables/PTDUMP，未刷入或启动新 resident kernel。本机基线候选已生成不等于 OEM 原厂真实性、AVB 启动资格或硬件救援已证。完整发布还需页权限/生命周期/安装撤销实机证据、同代 SDK/helper/APK、三模式真实 SU 和 150ms/300ms 验收；PMS 内存提交早于 VFS 事件的撤销窗口及资格迁移仍留账。当前正式 Pro assets 未提升，TRUST-02 不新增完整勾选。用户已撤销旧停止记录作为本轮停止依据；历史事件保留，本轮没有新增工具拒绝。

本轮精确源码、实际选源及验证收据见 [持久交付账](parallel/2026-11-17/evidence/mainline-integration/trust02-p2-resident-20261118/DELIVERY.json)；P5–P7 据此继续消费者、完整免 Key 和兼容资格迁移。以下各时点记录保留其原范围。



## 2026-11-18 当前源码 SDK、设备启动与原始 SKP 对齐

P2最新交付已接收核对（2026-11-18T01:25Z）：真实host/slot/producer与P7 owner/watch/epoch生产接线已实现，六family native与最终support、15项fresh离线验证已有证据；11项前置失败明确复用。状态应为“已实现待设备验证”，旧“宿主/producer尚未实施”仅属历史。目标RW/NX叶权限、新resident启动与正式组合仍欠；签名Manager681ec6实际ZIP继续打包legacy v1 bootstrap且没有permissions entry，公共头更新不代表resident已打包。完整TRUST-02保持未勾，见[P2正式交接收据](parallel/2026-11-17/evidence/mainline-integration/trust02-p2-resident-20261118/DELIVERY.json)。



当前源码 Manager 已在 PLQ110（`a261972a`）原位更新：APK `d27655f5…`，native archive `06f39b84…`，均已实读核对。生产生成的 boot_a 候选已在 fastbootd 实际发送/写入成功；恢复 ADB 后设备报告 `sys.boot_completed=1`、slot `_a`、boot_id `d2b67826-a2fb-4904-84a3-50042ec80cdf`。固定后台 Root 探针首次复验 767ms 返回 UID0；policy requested/effective/capabilities 均为3。此范围不等于完整应用 SU、零模块首次冷启动或全部模式已验。刷写前 pipe-read 挂起失败继续保留。



当前匹配测试包通过实际生产 reader 取得 `Source.Readback` 后，明确失败于“设备 helper 与随包 helper 不同”；旧测试包的 `qp4` 类缺失另记为测试映射过期。APK内身份、设备文件身份和本 boot 实际执行身份分开验收，尚未以版本同为5.2.0当作同代更新成功。



未读取 README，原始 SKP m073 的拒绝结果检查→当前任务资格→仅合格时置零，与当前选中原始对象一致。新增 Hide 另有 setprocattr 等入口，原 m073 保留不等于完整调用链等价。普通应用的两类拒绝保留 EACCES；修复版减少无效 context 查表，setprocattr只在表/代次资格成立时共用解析结果，其余路径保留原双解析回退。六系列 payload 已真实编译，含修复 SDK archive `44c626b9…`、Manager与匹配测试包已完整构建和APK验证，但尚未安装该中间候选。



本次未修改 Duck1082，冷停后扫描并导出完整报告：普通应用256对 A/B 均EACCES，中位数1980/1980ns、paired A−B=0ns；app_zygote父512对 A/B均781ns、paired A−B=+1ns，Duck据其规则标为正差。该单次报告不证明可重复异常增量，也不是原始SKP或修复版效果验收；设备 helper/backend 代际绑定仍待闭合。原始报告、条件和摘要见当前候选收据链接。



新增真实自授权首错为`ERR_MODULE_STORAGE_NOT_FOUND/-19964/phase13`，发生在control地址读取前。原始SKP m035 IR揭示恢复源码漏了模块id32目录，且给普通文件追加尾斜杠。现已恢复`storage/<id32>/<key>`，Linux实际TU红测12失败、修后16项通过；helper SDK内建identity也恢复原始默认，metadata仍保持原32B合同，实际bootstrap窄回归红测失败、修后80断言通过。两项源码修复已纳入组合SDK `0110be3d…`，同代Manager与匹配测试包完整构建、来源验证及签名成功，APK `681ec657…`。当前等待手机安装器拼图验证，组合版尚未安装；仍待生产环境升级、新helper冷启动和真实UID授权/SU复验，未宣称设备phase13已解决。交付身份见[组合版收据](../.tmp/mainline-sdk-device-acceptance/COMBINED-DELIVERY.json)。



当前正式候选/设备/失败与后续构建状态统一见[动态候选](parallel/2026-11-17/evidence/mainline-integration/audit-followup-validation/NEW-SDK-CANDIDATE.json)，动态任务见[EXECUTION](parallel/2026-11-17/evidence/mainline-integration/requirement-source-audit/EXECUTION.json)。完整119项仍仅原SDK-01/07/08勾选；P2/TRUST-02由另一对话负责，未修改其resident host/carrier。下文r9未更新、ADB0设备、未刷写等描述仅保留各自历史时点。



## 当前逐项审计与实机验收（2026-11-17）



[119项完整源码审计](parallel/2026-11-17/evidence/mainline-integration/requirement-source-audit/AUDIT.md)已完成；13:58快照为3项沿用原完整验收、33项已实现待设备验证、9项尚未实施、74项存在具体依赖或源码缺口。后续13路径普通批补入CASE-03生产重绑后，动态总账为3/34/9/73（按上述四态顺序），仍无新增完整勾选。分布不代表工程完成百分比，全部局部实施和已验证范围保留。原需求文字、阶段/验收条件、20条约束不变；116个未终验ID已有9项去重任务，两组各10项列有真实入口、所需设备/原样本及证据缺口。



依用户最新指令，由原唯一设备执行方逐项实测；满足对应整项条件后及时勾选，未完成项按首错/源码依赖继续实施。源码未齐、同代产物未齐、缺原失败设备及实际审核停止分别记账；历史r9结果保留其范围。源/产物/设备当前接点见[主线集成](SDK-REFACTOR-INTEGRATION.md)，历史追记按各自时点阅读。



当前已按实际阻塞重排为两条并行线：Normal新run44095205135的19资产/44路径已正式集成，原唯一执行方完成最终门禁并继续设备适用项；端口重绑、su失败退出、Hook发布与分支编码等17条普通源由同一执行方集中验证，新增完整UID批由3位源码写者继续收口。九项任务及116个未终验ID的动态去向见[实施与实机计划](parallel/2026-11-17/evidence/mainline-integration/requirement-source-audit/EXECUTION.json)。后续按实机结果勾选并继续剩余项，完整目标保持执行中。



用户最新明确本对话继续负责源码收口与验收，P2/TRUST-02由另一对话负责；本对话未修改其bootstrap/loader/carrier生产源。用户已撤销此前保留停止范围的要求，原审核事件及其历史影响继续保留为证据，后续以实际源码交付、当前命令和实际审核结果判断，不将历史事件重复写成新失败。TRUST-02仍按未交付依赖接收，未计作免Key或Root/SU通过。



## 历史：2026-11-17 目标 KMI 与首页签名增量（验证前）



当前正在集中修正 Normal 八组 KMI 矩阵、boot 文件按目标内核选源及首页模式切换签名接线。新增源码尚未完成本轮统一验证；本地已集成资产仍为下文 run44074782699 的上一代，尚未登记为新增 Rust 修改对应产物。新用户态/JNI构建与正式资产替换完成前，源码和资产状态分开记录。已通过的18份普通离线镜像结果保留原范围，本轮只追加实际受影响项与签名验证，不重写历史首错。全部119项、原3项勾选及具体停止边界保持。





## 历史：2026-11-17 Normal生产资产正式集成（run44074782699）



Normal run44074782699的source与package成功，原run44069942181的八组LKM不重编。19项实际资产、16份真实内嵌资产、ARM64/16KB/JNI导出及双来源已由唯一验证负责人独立核验；现已正式窄合工作区44路径（资产、checksum、原构建收据、payload与来源JSON），旧材料完整备份。源码固定官方成功CI #5545的f4603d14，最终复核仍为最新成功主线项。新payload SHA256为6b15979b7d5efb5d4b5ed39cf53e7b1bca22060536abf2e28248084ca592bd8d，原ko保cf60/首run来源，用户态/JNI保3dc391/第二run来源；仅Cargo.lock差异和完整kernel相同证明随包保留。额外L3 JNI/gen2行仍属历史资产，不计本次19项重建。正式Pro r9、共享HEAD/暂存区及119项原3勾保持。



最终本地门禁和设备覆盖按[集中实录](parallel/2026-11-17/evidence/mainline-integration/image-patcher-validation/REPORT.md)及其收据登记；Normal构建/正式资产集成不代表新APK、独立Ultra桥、候选OEM签名或实际Root/SU启动通过。当前逐图18份生成基线恢复（14直接输入、4历史内核恢复后基线）、6组init_boot记录恢复和25组容器范围不扩大。全交付及停止交点见[交付报告](2026-11-17-image-patcher-delivery-report.md)，精确44路径和备份见[Normal集成收据](../.tmp/mainline-source-integration/normal-production-integration/RECEIPT.json)。下文旧Normal待构建、库存和源码数量段保留各自历史时点。





更新日期：2026-11-17 UTC。总需求以 [SDK-REFACTOR-REQUIREMENTS.md](SDK-REFACTOR-REQUIREMENTS.md) 的119个唯一需求ID为准，本文只解释进展，不缩减验收条件。



## 2026-11-17 镜像集中验证与Normal生产当前结果



唯一验证负责人已完成允许范围的Boot_Research统一轮：[实录](parallel/2026-11-17/evidence/mainline-integration/image-patcher-validation/REPORT.md)、[逐图证据](parallel/2026-11-17/evidence/mainline-integration/image-patcher-validation/PER-IMAGE-RESULTS.json)。库存60文件路径、26镜像路径/25内容组，保留隐藏目录、out和别名；容器25/25、含内核编码完整往返19/19。18个非NGKI boot实际`prepareTarget`、独立完整kernel回读、同版幂等、无本地回执重补、换Key、raw与精确生成基线整图恢复18/18；其中14份基线为直接原输入，4份历史已修补图先经`ARCHIVED_KERNEL_REVERSE`恢复内核再生成，`originalContainerProven=false`，整图比较仅覆盖恢复后的生成基线，原历史整图/OEM原厂真实性未证。源码、输入、assets及runtime jar前后保持。此为普通离线生命周期已验证，换Key不等于跨SDK升级。



同18份候选完成普通文件writer直接写、短IO、部分写失败、回读损坏、journal失败及取消，另9个既有MultiPartition方法覆盖双分区中断/补偿。6组独立init_boot仅ramdisk/记录恢复通过；桥producer、目标构建/打包与真实SDK启动仍存在阻塞。strict普通恢复22/25，3份旧图`INVALID_AVB_FOOTER`及后续生产显式路径通过分层记账，strict未放宽。候选只经Builder，未走OEM/key签名消费；[输入签名结构](parallel/2026-11-17/evidence/mainline-integration/image-patcher-validation/SIGNATURE-SCOPE.json)不证明候选密码学签名、metadata或启动可信。



NGKI 5.4.61只验普通容器/codec/opaque恢复，原缺`do_execveat_common`后续停止保留，未运行generate。指定目录没有5.15、6.18及Linux4.x镜像，32位和各页大小设备组合仍待验。复用2026-11-17T10:42:50Z的ADB只读结果为0设备；未刷写、冷启动或验Manager/Root/SU。Pro正式r9及完整SDK-01/07/08三项勾选不变，所有119项与追加条件保留。



Normal生产另账：run44069942181的source＋8个LKM任务成功，14ko实际产出；首次package在`cargo fetch --locked`退出101，原因是ksud直连flate2缺锁边。隔离锁仅补该边后`cargo metadata --locked`通过，正式patch/锁固化与package复验仍进行，尚无完整19产物和最终payload成功结论。已成功LKM保原收据来源，package复用须证明除Cargo.lock外完整源及kernel逐件相同，不将旧ko写成本次新构建。以下旧矩阵、零编测及未执行库存描述保留历史时点，由本节当前证据更新其状态；完整产品仍未交付。



## 2026-11-17 接续实施（镜像集中验证前）



接续目标完整保留P0–P8、119个ID、20条约束及原停止范围；旧主聊天暂停。Normal f4603d14源码/补丁/applied与公共接口已接入，保Normal8/Ultra6及旧产物原身份；ModuleScreen非模态等待/取消、完整模块身份元数据、双形状回调等必要普通接线已完成。源索引673路径/514条有实际写入收据，包含测试与来源，不当功能数。



唯一验证负责人已完成本次普通组：当前Manager接口编译及HTTP身份修后增量、137方法首135过/2旧契约修后定向过、installer1、Ramdisk3287检查、WebUI34、Ultra9、moduleInfo1、callback4/Chromium、LocalSave2；未变B43证据复用。产物代际构建入口最后3处边界仍在修正及纯工具验证，已过普通产品组不重跑。完整勾选仍SDK-01/07/08三项；当前无新SDK/native/APK，Boot26路径/25内容组未真实修补或模拟刷写，设备0。四条关键链/m073 IR及最终交付继续存在阻塞，具体范围和源/产物/设备分账见[当前主线集成](SDK-REFACTOR-INTEGRATION.md)及其[集中验证](parallel/2026-11-17/evidence/mainline-integration/continuation-validation/REPORT.md)。下文历史零编测或旧选源统计保留历史时点，不覆盖本节。



## 当前结论



后续已修一个明确登记的普通容器残留：ZSTD ramdisk从直接报不支持改为复用当前共享有界解码、CPIO及encoded尾部检查，现有格式矩阵同步。没有执行构建/测试/设备，没有新包；此项为源码已实现待统一验证，不替代常驻宿主、P3/P4或独立Ultra桥。



本次集中写完普通构建选源及最后2处普通结果传播，共8条修改路径；14个成员汇总选源已对齐，helper来源与原有安装器用例已同步。当前m073仍为FROM_IR，全部可维护源码构建要求尚未完成；真实host、P3/P4核心、独立Ultra init_boot桥及真实init事件producer也仍缺。详见[实际构建选源与四条出口](SDK-REFACTOR-INTEGRATION.md)。没有新增编测或设备证据，不用更多外围小修代替产品闭环。



当前同版普通操作源已继续修正：模块读取失败/未知不当空列表、脚本不确定回复不重放、流式异常保全输出、mutation后库存失效及同scope状态发布；Hook一次读取失败不伪造启动Fault，诊断激活证据要求原数值码为0。所有新增源与现有fixture均未编测，仍按整体源码收口后统一验证。



本轮统筹结果见[主线集成与阶段出口](SDK-REFACTOR-INTEGRATION.md)：当前436条统一源索引中276条有实际写入收据，普通SDK/helper、typed授权、Manager环境/模块/WebUI及状态消费持续窄合原树；这比历史36路径范围更广。后续同版源码增量单列，不再重复冻结。P2及整体重构仍未完成，正式r9和3/119完整终验不变。本轮零构建/测试/设备；真实宿主slot的实施调用被自动审核拒绝，P3/P4核心与独立Ultra init_boot桥仍缺。



2026-11-17 P2 R29续批：写入内容摘要/逻辑长度、多分区写后异常恢复、取消与RuntimeException恢复、FD/原只读标志清理已完成源码和限定主机验证：Block矩阵、Multi185检查、7完整Kotlin TU及40个唯一方法通过，OS/native control边界单列。没有新正式交付、刷写或设备结论，完整勾选仍3/119。详见[R29源码与残留](dual-mode/impl/_work/2026-11-17/SOURCE-CONTINUATION-R29.md)。



R30已将旧boot与Native两套输入复制合为一份生产实现并删除重复方法；3完整TU与9新增普通文件/OS seam方法首次通过，同一FD重用/游标、短读、EOF/增长和sync失败均有证据。R31持续恢复日志故障源码已正式选择性合入，29Java TU、Block矩阵、Multi262检查及新BDT配真实Native消费者33方法通过；保持正向屏障，恢复日志失败不再阻断已确认PRE，但最终仍报告RECOVERY_REQUIRED。未重编Kotlin或SDK、未操作设备，未继承旧代身份。见[R30/R31记录](dual-mode/impl/_work/2026-11-17/SOURCE-CONTINUATION-R30-R31.md)。



R32已将B八工具/三测试与A11项普通消费者/个别manifest成套窄合原树，22项逐原/新SHA及包内字节匹配，冲突0。复用B43项纯工具mock证据及A各自host seam证据，没有重复执行、Android TU或新SDK构建，正式r9与全局manifest未改。见[原主对话实际接入](parallel/2026-11-17/ROOT-STAGE-INTEGRATION-R32.md)。m073仍FROM_IR，旧bundle对应kernel源漂移、P3/P4核心与完整交付依赖仍在。P7常驻owner的P2实际host/producer与未冻结wire接点已另记[必要交接](parallel/2026-11-17/P2-RESIDENT-OWNER-CONTACTS.md)，没有以临时payload/static或RO anchor冒充常驻身份。



R33共享writer增加非空Basic.fileKey支持；Windows旧fallback的Boot406/Ramdisk181/恢复区1604检查通过，新非空key分支未执行，不宣称完整归属窗口已修。R34已用一份LZ4块压缩器替换legacy literal-only/frame stored-only：encoder919、pipeline515、tail1033检查及106+17条独立参考流通过；没有真实镜像、SDK或设备验证，不以此替代原压缩流/签名元数据的精确保全。详见[R32–R34](dual-mode/impl/_work/2026-11-17/SOURCE-CONTINUATION-R32-R34.md)。



R35 已将首次输入精确容器恢复、有界文件记录及 Manager 实际接点接入原树。Java新/旧相关入口通过，Recovery最终300检查；16完整生产Kotlin TU窄编，17个唯一普通方法分代15＋2通过，两个实际失败由修正gap的零尾分类解决，未改预期。Flow/stock仅编译，正式r9及设备保持；完整勾选仍3/119。见[R35实际范围](dual-mode/impl/_work/2026-11-17/SOURCE-CONTINUATION-R35.md)。P5–P7阶段引用3/3已核摘要；真实RW host接点仍归主对话，当前无可复用永久slot，具体候选合同与来源缺口已更新[P2/P7接点](parallel/2026-11-17/P2-RESIDENT-OWNER-CONTACTS.md)。



R36–R37已将恢复资源收尾和init_boot显式文件记录接口接原树。Recovery300/16生命周期方法、新ramdisk419检查和真实byte/span容量增量1方法通过；真实2 boot＋2 init_boot仅按原编码组件不变做恢复载体全字节还原，源摘要保全。没有SDK/内核生成、刷写、设备或OEM认证结论，正式r9/119原勾选保持。见[本轮源码、首错及验收](dual-mode/impl/_work/2026-11-17/SOURCE-CONTINUATION-R36-R37.md)。



**源码接入里程碑已通过；整个重构仍在实施，尚未完成发布和完整实机验收。**



- P0评估交付9/9完成，独立于119项功能及终验需求。

- 119项中，完整勾选仍为SDK-01、SDK-07、SDK-08，共3项；其余116项未达到各自完整验收条件。

- 3/119是复合需求的终验计数，不代表只做了这些工作，也不作为开发工作量完成率。以下分别列出实际实现、验证范围和未完成部分。

- 正式SDK仍为P1验收的r9。P2–P4后续修改及P5–P7已窄合的普通源码尚未组成新的正式SDK/helper/payload/backend/APK；未合的核心和其他候选分别保留，也未取得本批设备功能通过证据。

- 当前主线仍在P2，修补尚未完整验收。R20/R21切换与安装收敛62例、R22另代4例及R23早期入口限定证据已归档；R25真实Key三个变体、旧base43/metadata2400检查及三Android TU通过，TSan工具环境阻塞；R26修历史归档恢复在永久卸载入口的来源遗漏和复制发布前回读，三生产源窄编/6个普通用户态方法通过。见[最新源码与限定状态](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R25-R26.md)，不提升为正式发布或设备通过。

- R27源码与限定主机验证已完成：boot406/ramdisk181检查、来源收据18项、普通任务恢复25个唯一方法通过；三个实际样本完成原组件重建，输入保全、计划外变化0。历史SERVICE恢复保留精确owner，新Pro任务不依赖旧历史；两个同根入口缺陷和两处test-only首错均留证。见[R27范围](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R27.md)，不是完整NGKI、独立桥、正式包或设备通过。

- 用户已派发A统一P3/P4、B负责SDK来源与构建交付；A/B均已提交阶段包，R32已将22项普通消费者/工具/测试按当前原SHA正式窄合源码。已有P5–P7继续，主对话专注P2 boot/init_boot修补和刷写。P2镜像层可独立推进，Ultra运行时稳定性/Root终验仍依赖P3/P4，独立init_boot另缺桥；阶段包和源码接入不等同P3/P4核心或新SDK发布完成。详见[分工与依赖](parallel/2026-11-17/P2-DEPENDENCIES-AND-HANDOFF.md)、[R32实际接入](parallel/2026-11-17/ROOT-STAGE-INTEGRATION-R32.md)。



## 阶段进度（源码深审后）



| 阶段 | 已取得的结果 | 当前未完成项 | 判断 |

|---|---|---|---|

| P0 | 原只读评估交付9/9完成；独立于119项功能终验 | 后续产品功能不继承P0评估完成。 | 已验证 |

| P1 | r9源码构建、正式提升、五消费者与本台五读/single为已验里程碑。 | 全部partial/维护源码/过渡来源、两次完整r9自举、全功能与新同代发布未闭合。 | 存在阻塞 |

| P2 | 目标布局/恢复/生成/容器/签名/最小写集已获限定实际证据。 | NGKI停止、全部语料、独立Ultra桥/生产与完整目标布局缺口，18boot不代表全支持。 | 存在阻塞 |

| P3 | QUERY/ECHO64/READ及标准执行帧与边界存在局部源码/主机成果。 | 独立EXEC/ALLOC/WRITE/释放、原回执/代次/并发合同及真实生产接点缺失，原停止保留。 | 存在阻塞 |

| P4 | 当前线程能力读取和真实错误传播已有局部修复。 | prepare_creds→修改新对象→commit/abort完整生命周期和线程/fork仍缺，依赖P3。 | 存在阻塞 |

| P5 | Legacy/Owner统一发布、完整调用引用与64预池/动态溢出、两次grace排空释放、原生kprobe共存及actor/shared消费者已直接进入生产源；相关NDK/Kbuild与严格增量链接通过。 | 正式同代产物、真实SMP/grace/睡眠/task-exit/hotplug、原生回调和硬件PAC/BTI/CFI/GCS仍待设备终验。 | 生产开发交付完成；设备终验开放 |

| P6 | Normal新版基线、Ultra模块/WebUI/授权/厂商候选等源与窄证据已有成果。 | 真实启动producer、32/64 traditional su、全模式/固定包/宿主生命周期、厂商首次Root缺口。 | 存在阻塞 |

| P7 | 签名/持久授权/命名/原错保留与局部身份恢复源码已有成果。 | 真实宿主/boot-owned slot和早期身份producer缺，去Key/无需Manager/轮换升级重装未达成。 | 存在阻塞 |

| P8 | 历史r9和当前主机/模拟/逐图/签名收据可追溯，119项深审覆盖已建立。 | 最终同代全包、必要匹配设备首次冷启动/32位/全模块/环境时延与过渡清退未完成。 | 存在阻塞 |



## 最近完成的源码与验证



这些结果属于各自冻结源码和测试范围，测试数量不相加为功能完成率。



| 改动 | 实际证据 | 尚未证明 |

|---|---|---|

| live BTF读区间、字段宽度/owner、嵌套/匿名错误与循环处理 | R4首版50场景514检查及实际Android resolver编译；独审发现空匿名聚合回归后，最小纠正另跑4个受影响场景40检查及实际Android TU，941依赖 | 纠正后完整51场景未统一重跑；全部getter/运行时/设备尚未验证 |

| v2请求Key快照与发送前后身份复核 | R4 legacy240、v2 9783、READ8750检查通过，ASan/UBSan及实际Android TU编译 | 全局setter并发与A→B→A瞬态；READ已复制批次的撤销；完整独立执行 |

| claim移除comm/cred邻接要求，VMA复用原生find_vma | 10项真实函数主体用例，5.10/6.12两份DDK实际ELF构建通过 | 其它DDK ABI、全部系列、Carrier完整接入与设备 |

| PatchCurrent能力错误传播 | 28检查、ASan/UBSan、实际m072 Android TU编译，939依赖 | 失败前原地修改的回滚、复制替换、PatchBase全链 |

| 模式切换分区参数与无整套外部备份的SERVICE路径 | R4同次29个当前Kotlin输入编译，三套JUnit 79/79，真实client/codec host Bundle seam 27/27；SERVICE复用原恢复器，BASE摘要按持久expected值核验 | 新R5 snapshot/artifact归属源码另待统一验证；host Bundle不等于Binder/Parcel；持久BASE不等于旧gateway内存plan能跨服务重建恢复；未部署APK |



依据：[R3集中批次](dual-mode/impl/_work/2026-11-16/SOURCE-UNIFIED-BATCH-R3.md)、[R4集中批次](dual-mode/impl/_work/2026-11-16/SOURCE-UNIFIED-BATCH-R4.md)、[P2](SDK-REFACTOR-P2.md)、[P3](SDK-REFACTOR-P3.md)、[P4](SDK-REFACTOR-P4.md)。



## 源码优先的最新批次



按用户最新分工，专用测试代理只在较大源码批完成后集中回归；其余代理和主代理继续源码实现。最新修改包括：移除孤立磁盘偏移缓存、7个mm getter与9个file getter接共享BTF、临时映射/文件描述符释放、f_path下溢与f_mapping判定、SERVICE snapshot产物归属；R6另有8个task getter与PID/TGID次序、3个dentry getter和原资源零初态，以及page/fdtable/files_struct、file_operations接线。



专用代理已完成R5一次相关窄验证：cache98检查、mm36场景793检查、file35场景186检查、task四个代表getter10场景31检查及四个实际Android TU通过；SERVICE R5同次29源编译、82项JUnit和27项codec通过。R5证据只覆盖对应冻结代次，不代替其它批次，也未打包或上机。源码、原行为、增量及残留见[R5–R6源码记录](dual-mode/impl/_work/2026-11-16/SOURCE-UNIFIED-BATCH-R5-SOURCE.md)。



随后R6三leaf33检查、dentry/fops99场景318检查及5个Android TU通过；R7布局94场景311检查、inode_operations45检查及5个Android TU通过。两批均保留装配/fixture异常和局部纠正，没有为测试改生产。R8继续seq/kstat/block/proc等，R9继续inode状态与锁独立解析；当前inode R9已不同于R7，不继承旧验证。范围、语义缺口与后续队列见[R7–R9记录](dual-mode/impl/_work/2026-11-16/LAYOUT-CONSUMER-SOURCE-R7-R9.md)。正式SDK/APK与设备仍未更新。



R8已追加独立结果：49场景257检查、block22检查及8个Android TU通过，未重跑早批。R9 inode状态/锁与12处stat失败接点源改继续待后批；cap访问器和rename新旧ABI另做实际调用评估，不用现有主机数字替代这些未完成项。



## 镜像覆盖的准确范围



- 只读盘点：1690个命名文件归并226个字节唯一组，192组被当前boot容器解析器解析成功。该数字不表示192份可启动的Ultra镜像或192台设备。

- 同代r2实际调用17份非NGKI输入，10份boot完成生成、内核独立回读、同版重修补、raw恢复及换Key；有输入原图时10/10整图恢复。无备份精确恢复原厂整图仅4/10，其余不等同原厂整图恢复。

- 后续case04和case09取得单图增量结果，分别保留源码/工具代次，不合成新的12/17完整矩阵。

- 四份已知历史Hook图已有另一冻结批的原核配对/逆记录恢复和离线生命周期证据，R22已核接线；早期三份AVB/Hook首错保留为历史。四图完整原厂容器、真正跨SDK/正式包/设备仍未闭合；R26另修永久卸载对归档恢复来源的消费遗漏，不以此重跑或扩大旧图证据。

- 真实NGKI原图的subject/boot-credentials两项describe通过；同runtime完整generate退出3：`PRO_KERNEL_GENERATE_TARGET_SYMBOL_MISSING:do_execveat_common`，没有spec/candidate。完整NGKI修补未通过。

- init_boot的ramdisk/容器重建、签名/回读不等于独立Ultra启动。原厂boot不动、仅init_boot取得真实内核回执和全部功能的桥仍未闭合。

- 真实跨SDK版本升级/回退、4.x完整支持、16K/64K设备、全部压缩与损坏边界、所有机型均未验收。换Key或同版重复修补不登记为跨SDK升级。



## 实机与实际发布物



| 层级 | 当前证据 | 本批边界 |

|---|---|---|

| 正式SDK | r9归档SHA-256 `f7acc05a435c3b9f5beba1112cf12b123cca57ad5c1eae3ad4c9529b7a502f0c`；helper/backend与P1来源匹配 | 后续P2–P7修复尚未组成新正式整包 |

| P1实机 | 本台6.6.89/arm64的身份、Root、版本、环境、模块和fixed single已验收 | 不扩大为全功能、32位应用、冷启动或多机型通过 |

| 2026-11-16只读复核 | PLQ110，序列号a261972a，6.6.89，槽位_a，bootCompleted=1；已装APK摘要与P1验收r9一致 | 未部署本批新源码，未新增SDK调用、安装、刷写或重启验证 |

| 指定故障设备 | Y700四代、iQOO 15等案例仍完整保留 | 尚缺对应新包/同次故障/冷启动实证，不写已根治 |



实机复核：[DEVICE-BASELINE.json](dual-mode/impl/_work/2026-11-16/evidence/source-unified-batch-r4/DEVICE-BASELINE.json)。P1范围：[r9接入验收](dual-mode/impl/_work/2026-11-15-p1/P1-INTEGRATION-ACCEPTANCE-R9.md)。



## P5–P7隔离工作的接入状态



完整拥有范围的冻结组合仍为R20：87个源码组合阶段、316条登记路径、279项覆盖层变更、41项声明删除。R20父级接受只覆盖新增diagnostics阶段并继承R19证据，不是一轮新的完整SDK或设备验收。后续候选另按同代接点组合，不把它们自动算进原R20快照。



本次只读复核统一索引已到R25：R21 typed授权包及两源回执、R23/R24子项有各自限定主机收据；R25续接源码与最终49接点选源已冻结，仍按其实际最终验证状态，不把索引整理当正式交付。最新路线选择typed M2，旧Bool M互斥；原P3/P4/去Key与早期身份依赖保持。未发送额外跨聊天消息或直接覆盖隔离候选，详见[统一接入索引](dual-mode/impl/_work/2026-11-15-p5-p7/SHARED-CONTACTS-R18.md)。



内部归属、Hook、授权、模块与WebUI已有实质修改，原先总账中的多处“尚未实施”需要更新为“已实现待设备验证（部分；隔离候选未正式合入）”。去Key、完整传统su、无Manager启动及完整模块功能仍未达标。正式合入按共享接点和源选择成套处理，避免直接复制隔离整文件覆盖当前开发修改。



依据：[P5–P7阶段账本](SDK-REFACTOR-P5-P7.md)、[当前集中状态](dual-mode/impl/_work/2026-11-15-p5-p7/CURRENT-STATUS.md)、[SU实际生效边界](dual-mode/impl/_work/2026-11-15-p5-p7/SU-EFFECTIVENESS-R20.md)。



## 用户案例仍需怎样闭合



| 案例组 | 当前进展 | 仍需的结果 |

|---|---|---|

| CASE-01/02/03/04/05：Y700重启、隐藏/端口、首装列表、部分应用SU | 通用SDK/授权/状态候选已有局部修复；未取得Y700对应回归 | 同次故障证据、首次/连续冷启动、隐藏实际监听及配置持久化、实际应用32/64位su |

| CASE-06a/b/c、07a/b、08：阻断、事务、生成、两模式修补/KSU等价 | 默认文件修补和恢复路径已有修复，参数/日志及KSU固定基线偏差有窄证据 | SERVICE实际产物/设备、完整矩阵、最新选定KSU同输入/参数/结果对照；不称Normal已整体等价 |

| CASE-09/10a/b/c/f/g、14、19：安装、身份/授权/寄生/重装、越狱、厂商设备 | 身份/错误/授权/入口与初始化有原树或隔离候选 | 去Key完整闭环、真实UID/SU/模块结果、卸载重装和iQOO/VIVO/Y700等实际设备 |

| CASE-10d/e、15：SELinux、列表、策略、Busy | 状态producer/checker/repository、SDK读取与同次结果保全有候选 | 真实策略生效、同次并发首错和延迟、实际新交付，状态显示成功仍须实际功能证明 |

| CASE-11/12/17/20：卸载、KSU原包、Zygisk、Tenc不生效 | 卸载/待应用/状态及原包静态/部分脚本夹具已有证据 | 每包安装→开机阶段→实际效果→更新/启禁→卸载/收尾，Manager从未启动的冷启动 |

| CASE-13/18：WebUI打不开/闪退 | 导航上下文、路由、原生loader/HTTP、打开/销毁候选已有主机与Android窄编译 | 两个月虹原包和其它模块真实页面/命令/桥；指定失败设备、旋转/重建/更新卸载 |

| CASE-16：无模块安全模式/修复无效 | 原入口早退已有复现，隔离启动保护/会话/回执修复有窄证据 | 每类原因的真实修复动作、零模块无Manager冷启动Root及真正启动保护保全 |



所有原始案例、功能、模式、UI布局与Ultra环境边界均保留。隐藏/SELinux显示、授权名单、脚本返回0或进程存在均不单独代表对应完整功能通过。



## 当前优先顺序与阻塞



2026-11-17最新统筹覆盖下列历史小批次安排：按[主线集成与四条关键链](SDK-REFACTOR-INTEGRATION.md)完成同版源码与约束后集中验证。实际选源已超出历史36路径，统一快照及后续增量分列；无本轮编测/设备或新包证据。P7真实宿主/producer、P3→P4核心、独立init_boot桥与同代正式交付仍是出口；Boot_Research单轮尚未执行。R38五方法、R39 Java547及Rust/link.exe首错保持历史范围。



1. SERVICE/Codec R4集中窄验证已通过，继续其R5产物归属收敛及布局/缓存源码批。专用代理集中验证，源码代理持续实现；复用现有恢复器、writer/回读/补偿，不新增安装前置门槛。

2. P2收口布局消费者、已修补镜像合同与真实镜像失败分类；P3/P4按依赖实施，不在旧回执仍持有cred时单独替换Root。

3. 按共享接点正式接入P5–P7已冻结候选，核对构建实际选源及被删除旧路径，完整阶段功能未通过前不打勾。

4. 核心合同与同批源码具备可交付条件后，集中构建一次实际SDK/helper/payload/backend/APK，再做必要设备/冷启动/模块/环境与时延验证，完成P8。



旧owned EXEC实现与NGKI后续入口分析已有自动审批停止记录，保持“存在阻塞”，本轮未重试或另走实现路径。当前摘要未包含审批的详细原因，不推测原因或把这两项写为完成。其它独立源码接线、文档与验证继续推进。





## 2026-11-16 R9–R10源码继续批



已继续修改inode整组结果/窗口、task旧scan缓存与结果、ptrace生命周期及cap消费者；共享path owner和同一访问器cap-output由两源码代理同批实施。唯一测试代理先准备受影响用例，最后源码冻结后集中验证；正常结果静默留证、异常上报，主代理和其它对话持续源改，不重复R5–R8或完整构建。详见[本批源码与残留](dual-mode/impl/_work/2026-11-16/SOURCE-LIFECYCLE-BATCH-R9-R10.md)。本批尚未有新正式产物或设备结论，119项完整验收与原P2/P3/P4核心缺口保持。



R10/R11续记：已完成共享path owner和cap-output解释器；cap的27case（7真实目标）、3实际Android TU在原范围通过后，旧real_cred猜测TU/私有头从当前源与m001来源移除，唯一gen_manifest同步260成员。ptrace真实options判据已另作源码修复；inode整TUnamespace错误已修一行。后续验证被自动审查以“possible cybersecurity risk”中止，主代理未重试/转交；修后编译和R11新增用例仍未验证，完整生命周期聚合不记通过。见[批次范围](dual-mode/impl/_work/2026-11-16/evidence/source-lifecycle-batch-r9-r10/REPORT.md)。正式r9和设备保持。





## 2026-11-16 R12源码续批



已源改：SERVICE/Controller由既有完整写集统一判定NO_OP，目标相同但源需还原时继续；共享boot/init_boot差异扫描不再把未变化的一字节gap纳入diff；inode五getter独立BTF8/8/8/4/8前置。三项只写source及相关test source，未运行新测试/构建/设备，不继承旧代通过。48项API来源/caller/历史交付与执行缺口另已映射，SDK-only公开入口保持。范围与残留见[R12](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R12.md)。既有停止回归未重试/转交，119项完整验收及正式r9保持。



R13续记：四处共用的fullSizeSwitchCandidate已检查实际复制字节数、独占新文件归属及失败清理，已有目标与首异常保全；没有新增备份/事务条件。只写源码，未编译/运行。通用writer在OutputIdentity.capture失败时残留空文件的跨平台问题单列尚未实施，未用私有工作区合同冒充任意用户输出身份保护。见同一[R12/R13记录](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R12.md)。



## 2026-11-16 R18/R19 限定主机证据



R18目标上下文/进度、真实SERVICE镜像导出与writer首错保全：18个不同JUnit组合通过及writer11+4检查通过，fixture三次首错均保留；R19同次回执/任务身份与签名派生binding：12个新增受影响用例和三源窄编通过。两批实际冻结身份独立，R21继续演进的源不继承旧代验证。未执行SDK/APK、真实Binder/FD、镜像修补或设备；sync调用不证明断电持久性。详见[SOURCE-CONTINUATION-R18](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R18.md)和[R19](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R19.md)。



继续P2：R20镜像交付和R21去历史preflight资格票已在源码推进，最终受影响验证待完整批冻结。UI布局固定、实际分区/内容边界/认证/回读与失败恢复保留；119个完整ID勾选保持3项，不把直接begin或主机编译当作模式切换/实机成功。随后仍按四类旧Hook、完整NGKI及独立Ultra init_boot桥的原缺口推进，已有自动审批停止范围不重试/转交。



## 2026-11-16 R20/R21 切换入口与执行收敛



六个生产源和六个用例源已冻结，唯一测试代理集中验证中。移除Facade重复preflight、固定空间/诊断/旧就绪标志前提；SERVICE与Pro begin不依赖历史预检缓存；InstallCommit真实安装入口也去S0b，begin后才确定搬移归属并保全abort未确认状态。R20同时统一三处镜像核验交付与必要同步。真实认证/目标范围/完整性/回读与失败恢复保留，UI布局不动。详细前后行为、来源及限制见[R20/R21](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R20-R21.md)。



当前只记已实现待设备验证，不把宽松入口或建立BEGIN当成功；未新生成正式SDK/APK或设备证据，不新增完整需求勾选。四类旧Hook、完整NGKI、独立Ultra init_boot桥及原P3/P4/最终交付验收继续保持。



## 2026-11-16 R21通过与四旧图现状校正



R20/R21的31源窄编及62个受影响JUnit一次通过，零重试；只证明直接begin、同次输入、实际错误/恢复记账与普通文件交付。后续R22默认单执行方及安装prepare时刻另代待验，不继承62例。见[实际批次报告](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R20-R21.md)。



四份旧Hook图已有另一批真实原核/旧图配对和逆记录恢复，当前接线已复用同一Recovery/Flow/Builder；本轮只读复核8资产和已有收据，不重复实现/运行。四个已知输入离线范围已通过；原厂完整容器、真正跨SDK升级、全部未知旧图、正式新包/设备仍未闭合。SDK完整门禁19失败、完整NGKI及独立Ultra init_boot桥继续保留。见[来源与状态校正](dual-mode/impl/_work/2026-11-16/LEGACY-FOUR-STATUS-R22.md)；旧“缺原核/四图都未迁移”的历史段落不再当作当前这四个有限样本的结论，全部119需求与原完整勾选保持。



## 2026-11-16 R22 默认执行方与安装时刻



已继续源改：默认Runtime从固定PRO策略下的Selector/构造失败暗退SERVICE收敛为同一个direct Pro执行方；安装入口既有prepare提前到active读取前，允许执行时补齐源模式。R22单独待集中验证，不重跑或继承R21的62例；历史SERVICE恢复、真实启动与正式发布仍未完成。详见[R22源码记录](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R22.md)。全部原要求、UI布局和119项完整验收保持。



## 2026-11-16 R22限定验证与R23早期入口



R22完整Runtime/Facade窄编及4个新受影响用例已通过，初轮测试接线错误、主代理聚焦修正与组合收据保留；实际private构造无Root/资产调用，完整initialize/Android设备未执行。见[R22范围](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R22.md)。R21的62用例未重复运行，正式SDK/APK与设备仍未变。



R23继续独立Ultra早期入口source，仅正常加载一次目标模块并保留原init/argv/envp交接模型，不引入版本/符号改写、挂载或全局策略。实际第一方bridge未交付，入口未打包，不把静态目标源码或容器通过声明为init_boot已获得Root；相关父项继续未完成。SDK19成员门禁另按已有冻结收据只读归因，不以重复构建掩盖缺口。



## 2026-11-16 R23最终限定证据与R24缺口



独立Ultra早期入口10个全OS wrap场景通过；Android ARM64静态target构建后，新增检查发现原4K页布局缺口，source只加64K链接对齐并复用原两对象仅重link，最终4K/16K/64K静态页关系/W^X通过。strip-only436064B仅作空间参考；没有实际bridge、producer/正式打包、loader执行或冷启动/设备，不增加完整IMG-08/FINAL父项勾选。详见[R23最终范围](dual-mode/impl/_work/2026-11-16/SOURCE-CONTINUATION-R23.md)。



SDK19项门禁已逐项只读归因：旧强定义缺失为0，多数是请求中的符号/依赖形状改变；当前073 FROM_IR、041完整字段及260当前源→对象闭包是真实未完成，未加豁免/重跑门禁。R24只读核实当前所审getter已无原UID/GID写探测和cred邻接旧猜测，comm BTF16B/noBTF关系推断等缺口继续；m001原停止回归没有转交或复试。三个正式r9产物实际摘要重算与身份一致，未替换包或设备。原全部要求和UI布局保持，完整需求仍3/119。


## 2026-11-22 MAIN9 安全模式源码闭环与同代构建进行中

主线 threadId：01a200e0-2adf-7523-9ae4-e404cdb00605；唯一固定测试/设备执行者：test_owner。保留原06:50:50 UTC目标，用户最新最晚交付时点为07:32:00 UTC，窗口不重置。

已采用源码：Legacy Key/v2零私有尾的before支持真实显式handle；在任何可能入口发布前登记句柄与Key/process身份，独立RW disable页与RX payload页隔离，失败/未知发布保留完整依赖。停用写及回读后仍明确PENDING，未宣称readers排空；真实SU batch因此保留UID资源。SELinux仅在相同受检兼容选择使用整boot原生共享字节与184B同boot/入口收据；Owner/provider的216B共享引用合同保持。四生产文件及前像/最终SHA见 `.tmp/supervision-20261121/native-legacy-bootstrap-20261122/{DELIVERY.md,BEFORE.json,PRODUCTION-CHECKPOINT.json}`。

固定tester已取得编译成功后的缺provider及旧同页EFAULT语义红例；修后m029 55场景、SELinux 49组、真实生成ARM64 gate 4回演、实际bootstrap保护8项、SU rollback 25场景605断言通过。首个WerFault挂起与夹具缺seam编译首错单独保留，没有充当产品红例。证据 `.tmp/supervision-20261121/main9-tests/NARROW-BATCH-RESULT.json`；全部为源端口/主机或生成指令证据，设备操作0，不结算原机环境恢复或完整119项通过。

新bridge/handoff的target-kmi/stage/完整source Kbuild/严格modpost/handoff于01:48:31 UTC全exit0，见 `.tmp/supervision-20261121/main9-generation/bridge-current-2/EXECUTION.json`。第一attempt旧KMI生成器摘要首错保留，沿原精确目标输入重新生成本代契约，没有放宽校验。新SDK实际冻结1108输入、260成员，clean-1完整六族及源码payload/backend/helper构建已启动；旧r23仍只作诊断包。正式APK、同代完整封包、签名和原机repair→重启→Ready/SU/模块效果均待本代实际结果。

当前原机boot为61b311c5-3d21-4d64-9a53-87e1bcc01ea1，与旧safe3/filldir64失败boot不同；本boot未触发旧r23初始化以读取保护状态。MTK HBT actor条件分支另有隔离源码任务，公开after API的零尾provider路径仍单列开放，不将其增加为PLQ110 before bootstrap的通用前置。

## 2026-11-22 MAIN10 接续与真实构建状态

主线接续为01a20124-413b-76e3-8434-2a9ac508d6f8，固定test_owner唯一执行测试及ADB，新增证据位于main10-tests；历史已绿集合复用。原06:50:50 UTC目标和07:32 UTC最晚交付要求保持，未重新计算6小时。

MTK三源已由上一主线核SHA采用，包含真实raw HBT actor生命周期及iterate_dir原actor恢复；修前缺provider语义红与r1/r2编译首错、r3定向绿见main9-tests/MTK-NARROW-RESULT.json。SELinux实际TU漏直接request头已补，七源最终SHA以main9-generation/mtk-adoption-1/ADOPTION.json和header-completion-1/ADOPTION.json为准。当前selection-final-2冻结1108输入，未采用after隔离草稿。

bridge-current-2旧协议MAX_HOOKS=7已由实际选源核出；后继bridge-current-4纳入当前协议12和11项P7配套源，经target-kmi、stage、完整strict Kbuild/modpost及handoff四步exit0。桥SHA为979683897b1f0af080772b511c4c91ab16c216cf23bb79cb4d3b0b1e56eb46e0，实际Gradle绑定见main9-generation/bridge-current-4/GRADLE-BRIDGE-INPUTS.json。此为构建/选源结果，未提升设备资格。

final-clean-1完整SDK真实编译至raw member gate160问题后，在写最终build-report时出现ENOSPC，最终exit1且report0B；原始producer.log、门禁及失败EXECUTION保留，未恢复或拼造成功报告。02:50:21 UTC以同一冻结选源启动全新final-clean-2，重新六族、成员、helper/backend及四payload；即时C约5GB/D约9GB可用。完整SDK stage、标准Release、正式封包签名与两次真实repair/启动复发检查仍等实际回执，新增完整设备通过0。


## 2026-11-22 MAIN10 镜像全功能适配与同代输入状态

新增范围按用户原文保持：`Boot_Research` 全部镜像及镜像内全功能依镜像 BTF 等事实实时适配，用户无需提供内核源码，不以单台设备固化偏移。当前库存为26条镜像路径、25个内容组，逐图SHA与既有证据一致。5.4和5.10两份raw内核已实查没有有效标准BTF，事实调查与兼容通过分别记账；7条init_boot路径须由真实配对boot提供内核事实。精确原文及范围见main10-acceptance/IMAGE-ADAPTATION-ADDENDA.json，库存见main10-capacity/BOOT-RESEARCH-INVENTORY.json。

生产源码18文件已完成动态字段消费：91槽/1116B运行布局与372B SU目标入口profile，真实入口、KCFI、锁及生命周期资格保留。Kotlin Carrier/ResidentAbi/SpecGenerator首checkpoint已冻并交唯一test_owner定向验证；无BTF accessor首版实现19类候选规则，其余字段、对象大小和ABI证据仍开放。init_boot配对快照与目标bridge重绑定源码正在闭合，bridge-current-4只作历史成功构建，后继必须重新选当前动态源码并完整构建。native源收据见main10-native-dynamic/DELIVERY-2.json、main10-dynamic-consumers/DELIVERY.json及main10-generation/NATIVE-SOURCE-INTAKE-1.json。

SDK final-clean-2和final-clean-3按同一1108输入选源完成完整六族/source payload/helper/backend构建；两次producer原始exit7及160问题/78成员保留，review/stage均exit0。review的stageGranted及reference/behavior/device/finalAcceptance仍false，stage仅提供隔离源码验证目录。第二份clean完成时间04:10:50 UTC；必要实物同源比较由固定tester执行，尚不凭stage宣称最终交付。

实际Gradle已接显式fresh claim目录，按manifest、来源摘要及真实封包选择；新claim producer准备正在恢复原选源引用的历史收据/对象，未削弱身份门槛。标准Release、新APK正式签名、全25内容组受影响矩阵与原机repair→重启→再次repair复发验证尚待本代真实回执。旧r23、历史bridge和源端口绿例各保原范围，新增完整功能/设备父项通过0。主线唯一生产整合/构建/签名/调度，test_owner唯一测试及ADB；原06:50:50 UTC目标与07:32 UTC最晚交付保持。


## MAIN12 实际整合增量 — 2026-11-22 07:02:57 UTC

- 唯一主线 `01a201f2-0559-7ab2-b024-6758f1ee1c5c`；固定 `implementation` 是唯一测试及 ADB 执行者，当前设备新写仍为0。原06:50:50 UTC目标已到、正式包尚未形成；07:32 UTC最晚要求保持，不重算时长。
- CP9按实际内存槽与descriptor字节预算同步Carrier/native materializer计数合同，保留NX、CRC、只读区、重复target与写前完整验证。正式Manager host `cp9-f14-module-3` 于06:56:50 UTC编译成功，inputsStable=true；使用原有效SDK stage，同代r3 archive/helper/backend三件bytes/SHA一致，详见 `main12-owner/SDK-ROOT-SELECTION.json`。先前public manifest从原root解析产生的archive校验首错保留，产品校验未放宽。
- 固定tester对claim10四族实际ELF执行loader最小红绿：4旧拒绝、4修后逐字节materialize、9负例，共17/17；各section/BSS/reloc/canary及失败不写均核，目标地址来源为显式fixture，不冒充ARM实测。见 `main10-tests/loader-capacity-cp9-r2/`。最终六族新产物另绑。
- 新增第30子项ADD-MOD-UPDATE-UNINSTALL-01的具体lost-marker竞态已源码修复：RootModuleTree.marker与真实boot pending_merge共享生命周期锁。旧marker成功却仅写retired inode的红例稳定复现，修后当前树保留remove；重复操作、stale identity失败与fresh retry通过。见 `main10-tests/module-lost-marker-r1/RESULT.json`；完整UI/Android/指定原机与重启条件保持待验。
- F14两个raw定向回放保留原字段无漂移；F15/F16隔离候选尚未全部通过，生产保持F14，memberSignature/typed/device资格仍0。NGKI5.4 CFI候选未进入生产，真实copied-arena owner/shadow生命周期缺口保留。
- 103槽有限静态复核确认91→102→103均append-only；已有三个6.x产物索引、宽度、owner边界和对齐一致，真实表长1260B、session96B。不据此宣称全部镜像或设备无错位。
- fresh10保留5.10/5.15 probe目标缺接线；fresh11修probe、公开seccomp、KCFI/保留API及CP9 support，真实编译后处理首错为容器缺elftools。当前正准备保持原compiler11对象的独立postprocess12，记录真实复用来源，不冒称clean。bridge9越过profile身份首错后，在seccomp body宏缺接线处失败，首错与现场均保留。
- 原119唯一需求、20约束、两组10、P0–P8、原29子项及独立第30子项继续按各自完整条件结算；本轮没有把host/静态/夹具通过改记为整项或原机通过。


## MAIN12 07:32 UTC 截止实录 — 2026-11-22T07:33:22.238347+00:00

最晚07:32 UTC已到，未形成新正式APK，原机新写与新包验收均为0；原06:50:50 UTC截止不重算。当前goal active，继续完成真实修复与构建。Manager CP9/F18/module host-4已实际COMPILED且inputsStable；后续family bootstrap consumer/Gradle/验包器源码已冻尚待新编译。bridge11严格Kbuild与handoff均exit0；session依赖首错已修后，恢复1捕获claimBuilder在采集期间更新的身份漂移，保原现场并准备不可变分析工具恢复。claim12四个6.x族后处理exit0，5.10/5.15 thin aggregate识别失败；全部ABI资格仍未完整。claim13收口真实typed-address probes、顶层函数体识别、retained FUNC pointer及分族CFI bootstrap；未以静态或夹具替代全25组全功能和设备结论。明细见main12-owner/DEADLINE-0732.json与main10-tests/LATEST-HANDOFF.json。

## MAIN13 2026-11-22 11:30 UTC 核验增量
- safemode15-1原机首轮修复后新boot3e8ca69a…Running/safe2、SUredirect/SELinuxnative0、Manager和授权shell免KeySUuid0/Enforcing，helper同代归一SHA通过。第二次刷新停在tools准备，未再次重启，因此二次启动复发未验。
- 共同模块-22已原机精确证实install.mode=root:root/0777并安全open0，protectedcapture-22；生产只将该文件发布0600，保strictconsumer，另修batch单模块错误传播。最窄修前红/修后11项绿已通过，不代替新包各模块实测。
- 第二修复源因是工具脚本76762B被错误选到su-c固定4096合同；生产改安装作用域SDK不做未知结果重放、tools/backend先于recoveryidentity准备、失败标量与实际dottedstage保真。受影响freshKotlin25项在修正旧fixture数字code/并发时点/hoststdin后通过，原首失败与夹具修订保留。实际SUparser大脚本红与设备效果单独待验。
- 已采用Home独立SELinux读取v2、模块锁前请求时点/阶段观测、严格init_boot同现场原包逆向绑定与namespace保留、身份回读BTF-cache forkquiescence。不增300ms、不清保护、不假Ready；相应host和设备条件仍依各项回执。
- 无BTF两个实际镜像当前28字段+6精准JUnit旧红/新绿通过本轮cacheowner/filepos范围，Boot_Research/boot.img动态f_pos120/owner296、NGKI112/280；dentryowner208均为实际call/publication推导。完整28字段typed signature仍0，缺口继续，不称全图无错位。
- SDKbranch4当前唯一全六族/source payload/helper/backend全源构建正在运行，1108inputs/260members；JNI/Manager待新SDK暂存后完整Release。bridge13输入167及claims r9输入2011逐SHA与生产全一致，可复用未受影响实物/证据，收据main13-owner/UNAFFECTED-CLAIM-BRIDGE-INPUTS.json。
- 后继完整25源legacy正常moduleCFI闭包和boot模块carrier生命周期候选已隔离冻结，未混入正在构建branch4；TaskProfile64/剩余noBTF动态字段并行实现。旧publictree13个本轮关键路径全部漂移且native冻结旧代，正式开源仍需真实公开联合清单/export/strict/公开树构建/远端核回，未称已开源。原P0-P8/119/20/两组10/30子项/全部追加保原完整出口。
## MAIN13 2026-11-22 11:41 UTC branch4 stage/Release16与新增init_boot首错
- threadId01a2029e-9c2b-7403-ae53-a870c6592ce0；/root唯一生产整合/完整构建/签名，test_owner唯一测试与PLQ110 ADB。branch4 SDK raw7原件保留、同代独立review exit0、stage exit0。SDK BuildId maplesu-ultra-source-af60015a0879584f，SDK-RELEASE SHA d0714b82e05d75657323dd6342b92803a04e6b58d19439d26a1b2bf93963f480。标准Release safemode16-1 session70801当前实际构建，新JNI18对象已链接；尚未有新APK或新设备通过。
- 模块公有列表真实installLock等待红绿：旧重复root快照2次、新1次；显式refresh仍新读且缓存仍执行真实ZIP/目录校验，2项通过。真实Producer严格init_boot原镜像绑定六项通过；这些不替代Flow完整ABI/设备加载。原失败夹具均保留。
- 新增用户要求：KSU/其他管理器修补init_boot自动识别接续，无需手选；安装页移除操作选择与重复恢复入口，恢复保留设置永久卸载；init_boot直接安装ULTRA_INIT_ARTIFACT_FIELDS优先修。已分配3独占隔离owner且实际ACK/GO：boot_parity→main13-boot-catalog、repair_chain→main13-artifact-fields、module_latency→main13-install-ui；生产冻结保持。
- 首错实物：已装15-1 APK中ultra-init-bridge/manifest.json SHA2badcf32d2c2410287c0b54c451d4bacc333db7fb266363fcb3572667b607f09含taskProfileAbi/taskProfileAbiSha256，当前UltraInitBootArtifact.java严格FIELDS和bound-loop缺这对字段，故真实parser直接FIELDS。保严格未知字段/路径/摘要/类型校验，修producer-consumer合同不伪装硬件不支持。
- 全25、TP64、noBTF后继动态字段与公开组装持续并行源码；仍未全镜像/全部模块/复发终验。保留Key/授权/模块/六分区备份和首错，不重算已逾期截止。

## MAIN13 continuation 2026-11-22T12:00:39.715679+00:00
- threadId01a2029e-9c2b-7403-ae53-a870c6592ce0；/root唯一生产整合/完整构建/原签名/发布/设备调度，test_owner唯一测试与PLQ110 ADB。既有goal保持active，原截止逾期不重计。
- Release16实际BUILT/inputsStable=true，APK SHA0beb9bd6865ef5be5fbef1a66a31a81647353adb18a8d70f831c262b393c6ef1，原证书不变。固定tester验包/保数据安装fullUID10498成功；产品Refresh一次实际SDK0更新成功，真实回读新helperaf60015a，但同boot未重启：调用SDKRefresh明确定义file-only，界面自动重启承诺与真实意图不一致。FIRST-REFRESH-RESULT首错保留，复发未验，不手动重启冒充修复；repair_chain隔离补实际Manager意图调用链。
- 44唯一文件47阶段full25/TP64/生命周期/parser/UI/noBTF6逐SHA采用：main13-owner/adopt-successor-1/ADOPTION.json SHA f01343a85af49f83a353cdc68da9aaf5c87caa01857d84297aa1f933bd179d9e；另SU可选查符号去重与identitycache两源采用main13-owner/adopt-su-startup-1/ADOPTION.json SHA6bc2217b6f301843be4d4f00d3176368ec2fc84a44ef6268e4f437e43b6b01c2，保持真实错误/权限与300ms合同。源码未冒充设备通过。
- 新SDKbranch5已11:58:44真实启动完整六族260成员/1108输入/jobs4，输出BUILD_EVIDENCE/20261122/safemode-branch-5，selection-safemode-branch-5。生产SDK选源冻结，后续owner仅隔离候选。6.6bridge14与真实5.15normalmodule新选源命令准备，旧bridge13/r9不冒充新代。
- 最窄已绿：模块11、repair25、publicinventory2、Home实际调度4、真实Producer原包6、identitycache7、Artifactfields21、noBTF6十九窄例+两图六字段；每项仅其范围，全镜像typedABI/全部模块/复发/最终交付仍待证。用户KSU同兼容与未授权无新增时序要求按固定真实源码及单一tester原机分账。

## MAIN13 2026-11-22T12:21:11.866596+00:00 最新整合与真实后链
- /root唯一生产/构建/签名与设备调度，test_owner唯一测试/PLQ110 ADB保持。原截止保持，goal active。
- branch5完整六族SDK已真实260成员编译/compose/archive/link，producer原raw7保留，独立review进行；不以原raw7直接判最终通过。claim-current18-native已真实启动6.6首位六族，6.6/6.1 native exit0，其余进行；无旧r9换标签。bridge14真实strictKbuild/handoff/Session exit0，保前链；精确current18 TaskProfile+package suffix实际exit0，BUNDLE_COMPLETE_NOT_DEVICE_ACCEPTED，不重编相同KO。
- 16原机Refresh更新成功但未重启的源码根因已最小修复采用：仅显式controller改用既有Upgrade/Boot并保提交boot标记，file-only Refresh合同不变。真实调用点旧红/new9检查绿，完整新APK自动重启仍待证。
- 自动KSU/sevenk/backup结构CPIO规范化、owned自动再修补及namespace冲突5文件已采用；真实Producer6种结构fixture往返+4负例/2namespace seam通过，未把合成foreignfixture当真实第三方产物。catalog4生产/2测试源码已采用，实际现代bundle已产出；legacy5.15 target_kmi现代专属分支缺口由独占owner实施动态真实loader修复。DDK实际导出前链已exit0，保原始Image，不标OEM/全镜像通过。
- SU可选探测两真实TU：旧9warning→新0，identity/owner失效重解、原错误与mandatory诊断保留，未认证fixture零查询；原机时延/普通全环境资格待同代实测。另两源native discovery重复完整lease/Profile候选revision2已冻结未采用，保持实际session_prepare/fulllease无改，固定tester验证35最窄场景后再决定最终选源。
- noBTF7已采用，5新夹具与两真实图group_info.usage 0/4/owner8由真实allocationheader推导通过；r6既有19与六字段通过复用。comm/pid/独立typedABI/全25组26路径仍待闭合。只推进真实最早缺口，不假签名、硬绑机型或清保护。


## MAIN13 当前实际后继与用户日志 2026-11-22T12:36:09.981384+00:00
- 主线/root唯一源码整合、完整构建、原签名、发布/设备调度，固定test_owner唯一测试/ADB保持；原截止逾期不重计。
- native discovery两源35场景通过后逐SHA采用（adopt-native-precheck-2/ADOPTION.json）；真实执行 full lease/Profile不变，普通否定保持最早返回，不新增helper/env/BTF。新SDK6/session44472、claims19/session20949、bridge15/session11703均已实际启动，非旧字节换标签。
- claims18六native编译exit0且inputsStable；实际6.1缺memchr同目标声明证据、5.10/5.15原CFI缺口仍false；未宣称六族全通过。
- 用户日志实际路径/sdcard/Duck/11.22/Selinux.txt已正确PLQ110原机read-only拉取，118176字节、SHA85692e3f46650674cb81709fa1349f0cbd912f5f00dcf97653ffcbd82a704466，首个错误路径ENOENT另存。新增独占public_export source-only分析SELinux/LSPosed实际规则来源；实际ACK/GO已核，不变更设备与冻结输入。
- savedid r8新3fixture过但NGKI实际2字段仍红，未采用；legacy515窄验证在真实banner/vermagic前失败、0case，不冒充loader已绿；同owner隔离后继根因修。
- 用户要求本轮完整构建与原机测后冻结5.2.1/50201供干净开源线程，源码/原签名/精确APK/实际测试及未过范围分别绑定，尚未冻结/交付。

## 2026-11-22 13:13 UTC — actual Release17 and new boot causal evidence
- 同一主线程01a2029e-9c2b-7403-ae53-a870c6592ce0；/root生产整合/构建/原签名/设备调度，固定test_owner测试/性能/ADB独占。原截止已过，不重计、不假完成。
- safemode17-1标准Release于13:06:33终态BUILT，Gradle10m13s，完整前后inputsStable=true。SDK6 maplesu-ultra-source-71af971b49b67177、claims19、bridge16、catalog19（仅真实单现代变体）；原签名、版本5.2.1/50201。APK SHA256 9fe7623931435a23d6884d0b1cd5bf6c143ba572a7d9ef60c0a3ec1fe464706f，90904094字节，路径BUILD_EVIDENCE/20261122/release-safemode17-1/MapleSU-5.2.1-device-candidate.apk。
- 独立APK-VERIFICATION passed，正式原证书30bafb57e3604afe93da13a1285516e043feb82b56a861a157b53ccaa27c3506；13:09:36实际install-r保数据/UID10498成功，设备APK读回精确SHA。产品Boot修复/真实新boot/SU/modules/safemode复发仍由唯一tester执行中，未填通过。
- r9 saved-ID活跃性实际4夹具+NGKI suid/sgid两字段绿，按精确pre/post采用main14-owner/adopt-savedid-r9/ADOPTION.json。保4096预算、未知指令保守分支和真实冲突；全镜像/typedABI仍未完成。
- 用户日志正确路径/sdcard/Duck/11.22/Selinux.txt已保全。旧12:26报告boot3e8与新boot496e599e分别记账。新boot最窄保护读回证实：effective Load1，Hide在selinux_setprocattr阶段MODULE_POLICY_SDK_HOOK:-19968回退；SDK错误码对应idle区分配不足，exact-fit边界与真实耗尽/boot来源需3个真实history字段区分，已分派源码根因及受限读回。start历史基址/next实际返回各有用途，不将其误报为重叠。
- 同期LSPosed实际规则5条（四条shared合同+system_server apk_data_file execute）、adapter库mode777、shared-context旧boot收据。独占source owner继续实际nativevariant/契约/受保护来源修复，未静默删规则、放宽trust、强切Hide或修改Enforcing。上述SDK/LSPosed缺口未宣称在17内闭合。
- fresh legacy3实际target-kmi首错LEGACY_CFI_CALLSITE_MISSING:do_one_initcall保留；r4源码查明真实冷分支向后回BLR，r5继续实际no-CFI traceiter生产者/消费者差异，未跳校验。6.1 memchr同目标实际三参数证明与DWARF fortify四参数误混候选已交，等待固定tester窄例；原claims19 false资格保持。
- 用户本轮实测后固定一个版本用于独立干净开源线程，当前仍为诊断候选；全P0–P8/119/持续约束/追加/所有镜像未用单机或九审计完成冒充通过。

## 2026-11-22 actual successor SDK7/source handoff 2026-11-22T14:12:41.8583809Z
Hook最早失败已由原机同boot65536/61440/4096精确范围绑定，m031 exact-fit修复旧23红/新37绿及完整TU通过；小gate容量修复按真实发射器上界240byte保留，22hook5280B、102发射样例max208B、oversize发布前失败及原legacy身份/回读负例通过，两个生产源已逐SHA采用main14-owner/adopt-idle-r1-r2。SDK7实际完整六族构建13:34:51启动，尚待stage。保历史保护，不清状态或扩大池。
LSPosed实际自有adapter0777与可信gate冲突最小修复已采用zygisk4源含实际版本5.2.1/50201；normal accepted-install发布0755/0644，原bytes/root身份/links负例通过，packager6例通过；实际CLI四共享规则已过滤且第五规则保留，最终encoded比较首错仍核，未标CLI/设备通过。原生产globalSDK0777和SELinux规则合同保持。新同代native需SDK7stage。
Release17首轮产品自动Boot新boot5e6d/helper同代/corefinishedsafe2/SUuid0Enforcing真实通过；二次复发/PTY信号退出/模块和identity原产品回读待补。用户明确先交源码开源Agent，已发独立线程且实际SOURCE-HANDOFF-ACK确认；source-candidate-001在独立准备目录，不动原主库/Git/Key，最终源码/APK/原签名/设备证据冻结未完成。原截止逾期保持。

## MAIN15 current actual 2026-11-22 14:40 UTC
threadId 01a2029e-9c2b-7403-ae53-a870c6592ce0; /root sole production adoption/full build/original signing/release/device scheduling, implementation sole alltests/performance/ADB PLQ110 a261972a. Existinggoal active; originaldeadlinesexpirednotreset.
- SDK7 actual stage complete14:22:15 maplesu-ultra-source-cd97e6c0fe124e4c archivecd97e6c0fe124e4c1b99c2807e4bf801f1cfdd9af041d1bbcb7e3b4537c1eba7. True idleexactfit+compact240 source fixes selected; notdeviceaccepted.
- Zygisk SDK7 actual freshdualABI native/sdk7-3 BUILT_PACKAGED_STRUCTURE_VERIFIED_NOT_DEVICE_ACCEPTED14:34, exactsource/tool/archiveprepost stable. sdk7-1 falsewholevendorcleanprecondition andsdk7-2 stalegeneratedoverlayfirsterrorspreserved; main used pinnedpatch_upstream actualderiveafterbackingupoldoverlay, notresetvendor. Newownadapteraccepted-install0755/0644 root/linktrust preserved; CLIr6 fullpolicysemantic/negative actualgreen, fourthsharedfilter/fifthrulekept. Notyetacceptedondevice.
- reference61 memchrlinkage7d378eb0 andlegacyR6strictoperand2f1137c0/12b002f4 adoptedactualnarrowgreens. Freshmetadata20 all4modernreferenceComplete trueincl6.1; 5.10/5.15oldrawreference/CFIfalsekept, natives19exactSO reused dualprovenance nofakecompile. bridge17actualtarget/probe/Kbuild/handoff/session/TaskProfile/package0 BUNDLE_COMPLETE, catalog20oneactualmodernvariant0, notallimages. package20realexecutehitACTUAL_ELF_METADATA_DIFFERS:android12-5.10, sourceauthorr2ACK+GOisolate freshsuffix, originalfailurekept.
- Canonicallegacy20 earliesttargetABI __seccomp_filter_release missing; nocompile/nativequal, R7sourceonlyownerACK/GO. comm/pidR12twoactualimagesfourfieldsstillred; R13two-commone-shotdiagnosticrunningfixedtester, no4096budgetincrease/fullcorpusrerun.
- Release17 realproductBootsameboot5e6d exactAPK9fe76239 safe2/currentcompleted helpernorm3446c2c2 SUuid0 Enforcing +actualPTYinput/CtrlC/exit37/warnings0 greenwithinScope; secondhealthyUIrepairentryhiddenNOTEXECUTED, notfaked.
- NewUSERlivefailure14:31 policyreadfailed/SELinuxunknown/homeprobetimeout. 14:34singlecurrentreadonlycaptureproves same17sameboot actualuid0+Enforcing+safe2, Manager15541repeatedMAPLESU_SDK_BUSY-1001/PAGE_QUERY_TIMEOUT, oneuid0singlethreadchild31344futex/parent27218pipe. Evidence main10-tests/device-safemode17-live-failure-r1. No cache/markersreset/refresh/reboot/install. Source.ReadbackknownSDK6actuallysuccessatuptime2759.314separatefromlaterbusy. Exactblockedlocknotyetproved. FixedtesterboundednativebacktraceGO; module_latencyisolatefork/rootcause, boot_parityUIlifecycle, newpublic_exportSDKparallelgateevaluation/implementationassignedperuser. Noalwaysparallelunsafeunlock/fakeReady/timeoutextension.
- Sourcealreadydeliveredindependentopen-sourceAgentactualACK; finalsource/APKsamegenformalfreezeandallP0-119/corpus/sidechannel/devicequalstillpending. Noformalcompletionclaimed.

## MAIN15 actual continuation 2026-11-22T15:12:45.0647396Z — BUSY source paired adoption / fresh SDK8 running
threadId01a2029e-9c2b-7403-ae53-a870c6592ce0; /root soleproductionadoption/fullbuild/originalsign/release/devicescheduling; implementation solealltests/performance/ADB PLQ110a261972a. Goalactive; original06:50:50/07:32UTCdeadlinesexpirednotreset.
- Exactlive17sameboot5e6d stack actual child31344 fflush_all+116 -> fork_pipe_child_process/get_root/run_root_cmd, parent27218 read_full/receive_result proves inheritedFILElockdeadlock. Kotlin-1001entrybeforeJNI notnative-1005; bottomSUuid0/Enforcing/safe2/currentcompletedactualhealthy. Firstfailureandprocesspreserved; no refresh/reboot/marker/cacheclear.
- Selectedstdio3sourcesr1actualadopted ADOPTIONe922960b176a340f981d87a1f65cedf1e3eb614b9299e0eb4029dcee2543f2f0, actualhost2oldred2newgreen. Newstandardstdout/stderr4narrowboundary actualtransport2green/redirect2redworker124; NOTproductpass, r2onlyisolatedvendorworkerprivateFILEcandidateinprogress, no in-flightproductionchange.
- ActualnativeR1oldstalledred42+6newscope/5m023sessiongreen, Kotlinactualold6busy/new6admitted+deadline/identity/errorgreen; mainpaired3sourceadopted ADOPTION036dddfa4878503b59bbb444d87d429ad016543fc41d18925de28b7cba8a7803. Nativeoneactualresourceconflictowner, noJavaRWLock/2slot, requestprivateKey/auth/FD/forkcleanup preserved. DirectaffectedKotlin7testsactualgreenand2testfileadopted006487a3181c9ed2fe24b3efd0bd806507a869dea24329a28bd6a806cc4f02b4. R2nativeParasitemutableparserdomain/keyalias3onlyaffectedGOqueued; noR1rerun.
- SDK8 actualprepare1118inputs/1586kernelclosurepassed, sourcefreeze839c9373e831cb63fbbdb3ebf3df3334ed11694fd7b5879ebd5a059855ff0dbb; fullfreshSDK/helper/backend/4payloadproducer15:06:04session90952RUNNING. ReusesunchangedactualSDK7sixfamilykernelstrictproducerclosure, nooldSDKobjects/newkernelfalse. SDK8r1notclaimedcomplete/deviceaccepted; r2stdoutsourcewillrequirefreshsuccessorifadopted.
- package20r2actualrootexit0manifest2bc643e4fa67cb26ff3dc6d6256aa004033ae75ab7dda90374f703f79061909a, exactELFnonpathidentities6stable; PACKAGED_UNQUALIFIED preserved. bridge17/catalog20actualone6.6variant reuse, noallimageclaim. SDK7Zygiskactualbuilda7de152aaa4fab78f6383dd43af666b2be426ef9875961d89efda7900f65e32fnotfinalSDK8module.
- Imagecomm/pidr14actual2newreds/8affectedgreen/fourrealfields pass; rootreviewedproductionr9fullchain+RELApointer+strictbounds, adopted4source/testfiles ADOPTION9e6408a99bd9db65a76a6a102404997e0017b34040044255e9f9506d4fa3cf04. memberSignature0typedABIfalse/fullcorpusfalse; no19fullsuitererun. LegacyR8isolatedpendinglowpriority, notproductionadopted.
- FinalstandardRelease18preparationowneractualACK+GOexclusive main15-release-plan; rootwillusefinalSDKfreshJNI+Kotlin/native resources+actualclaim20r2/bridge17. NoactualnewAPKyet; testerkeeps17failureuntilnewexactSHA/certGO. Sourcepreparedopen-threadactualACKunchanged; userwantsactualtestthenfreeze5.2.1/50201. FullP0-P8/119/allcorpus/sidechannel/ABIandlifecycle finalqualificationsremainseparateandunpassedwhereevidencemissing.

## MAIN15 actual continuation 2026-11-22T15:27:05.1633868+00:00 — final BUSY candidate integration
- threadId 01a2029e-9c2b-7403-ae53-a870c6592ce0; /root sole production adoption/full build/original signing/release/device scheduling; implementation sole tests/performance/ADB PLQ110 a261972a. Existing active goal and expired original deadlines retained.
- SDK8 producer raw7 completed 15:11:56; independent review FAILED 15:12:03 at SOURCE_SDK_FILE for exact reused SDK7 kernel dependency path. Original report and error retained; SDK8 not staged and not final. Exact per-file closure verifier candidate frozen, sole tester GO pending; no directory-wide exemption.
- Native resource R2 and isolated worker stdio R2 actually adopted after affected host regressions; ADOPTION hashes c89d1c3dc6804f7f1eb15901f8733337f62bb46ea0c25059706e19277e7d7478 and eb5285ab772b256652a6118780e4043d38b504cff2dcd0b93017d5b99837ea9d. No arbitrary SDK-consumer concurrency claim; current bridge uses private request context and native resource conflicts.
- Final SDK9/standard Release18 preparation supplement1 ea32154f4acce9ecb32243af9d1550dfe84dc01c2adc9edce0e39db76a826221 reviewed. Fresh 260-member/helper/backend/payload build next, unchanged SDK7 kernel closure reused explicitly. No new APK/device acceptance yet; current Release17 failure and user data preserved.


## MAIN15 actual SDK9 started 2026-11-22T15:35:41.5660488+00:00
- root adopted exact per-file selected-bundle verifier R2 after actual sole-tester12/12 and1586identitiesStable; ADOPTION7b673657f7e4d440c85e05e16a877c39a5a68b95d4038789772daf6d5ac39203. Parent/producer own snapshots respected, exact both-provenance bytes/roles/backendTU bound, no directory exception or qualification promotion.
- SDK9 actual runner session93263 producer started15:35:00UTC, frozen1125inputs SHAe853950f23b4314ba01d6aa0e3de6c4b71a3da8f293b96dd701a4f421a935f88. Fresh all260/helper/backend/4payload, only unchangedSDK7sixfamilykernel1586-inputclosure explicitly reused. Source frozen during run; no newdevicepass/APK yet.
- Open-source thread informed lateststdio/native/verifieradopted source increments, finalfreeze pending actual SDK9/standardRelease18/originalsignature/sole-testerdevice results. No allP0-P8/119/allimages qualification claim.


## SDK9 actual first compile failure 2026-11-22T15:42:55.7850191+00:00
- SDK9 producer actualexit4 at15:37:41; oneof256 m060 maplesu_root.cpp failed four stdout/stderr assignments. Exactarchive targetaarch64-linux-android21 header usesmacroaddresses, unlike actualManager/backendruntimeAPI31 FILE pointers; hostglibc passes didnotcoverthiscompileboundary. FullSDK9report/log/selection retained, nostage/newAPK/devicepass.
- Sameowner module_latency isolatedrevision3 actualAPI-compatibleminimumsourcefix; sole fixedtester must compile exact NDKAPI21memberand31 before nextfullSDK10. No minSDKbump/FILEcopy/reset/noopfallback. Source-onlySDK10planownerACKread+GO, r3/**exclusive, onlydeclaredsourcechangesandcurrentverifier; finalRelease18existinglatebindingreused. /rootsoleproduction/build/sign andtest_ownersoleADB unchanged.


## Source change closure within latest five-minute request 2026-11-22T15:53:43.7104429+00:00
- Userlatestrequested5minutesforsource; rootstdioR3actualadopted15:52:38onlyprivateheaderpost224784aa174d5a749275ecc458ae573b7d8ecb52eaee2c7eb4ae5c8071408f5a. ADOPTION6e96f9bc5a7cc36229245e8a21a4aa274d49432ed753d485518215da7b4aa3af. ExactactualNDK21+31wholem060TU/weakDEFAULTand3newslotboundariespassedbysolefixedtester; no minSDKraise/FILEcopy/reset. No additionalunrelatedsourcechangesplanned.
- ActualSDK10rootrunnerstarted15:53session21945, selectiona05e595d6a0f45462a9f119d007fab543ae7ba808b42fecaec5d02073ec19881,1134inputs/oneSDK9headerdiff/1586unchangedkernelclosure. Fresh260/helper/backend/4payload thenoriginalreview/stage; notyetnewAPK/devicepass.
- LatestactualsourceR3adoption+SHAalready sent authorizedopen-sourcepreparationthread01a20325-0d4c-7d20-87e6-cbf81d5e5857, independentACKpending. FinalstandardRelease18 remainslateboundtoactualSDK10; originalsign/version5.2.1/50201 andfixedtesterdeviceonly. Existing17failurepreserved. Fullrequirementsandallimagesunpassedqualificationsnotpromoted.


## SDK10 original build retained; D recovery and final Release 2026-11-22T16:09:27.0158032+00:00
- ActualCfree0 caused errno28 in SDK10runner.save beforestage andtruncatedoriginalCEXECUTIONto0bytes. OriginalCbuild/report/provenance/rawgate7/independentreview remained; no originalfiles moved/deleted. D recovery main15-owner/sdk10-stage-recovery-d/EXECUTION.json bindsoriginalzero/error/report/review/frozen1134/1586kernelclosure; originalstageactualexit0 16:06:11, inputsStable=true, SDK_STAGED_NOT_DEVICE_ACCEPTED. SDKRelease813fb7d5a28e0031f4644804a0fcdaad99c4341d8432b4df6dfbe1e980740c02; BuildIdmaplesu-ultra-source-7af6f0d42a440ac7; archive7af6f0d42a440ac7616d555ab788d90f728dc0fc89df46e7976c5634cf3749e4.
- Futureoutput-onlyrootr4-output-d keepsallproductionandfrozenoldtoolsunchanged, newZygisk/Release/output/tempinD. preparepinfirsterror KeyErrorexpectedHash fixedactualnestedidentity.expectedHash only, sameoriginalcert30bafb57e3604afe93da13a1285516e043feb82b56a861a157b53ccaa27c3506.
- ActualZygiskSDK10dualABIfreshBUILT_PACKAGED_STRUCTURE_VERIFIED_NOT_DEVICE_ACCEPTED, ZIP37e400a2365bc7bb479cbecad17772d74516de47d19f1a356f8f5dc3832354ee. ActualstandardRelease18-1rootrunnerstarted session85961 withSDK10/bridge17/catalog20/claim20r2/originalsign, output main15-large-output/release-safemode18-1. No newAPK/deviceacceptanceyet. Fixedtesterkeeps17faultandwaitsactualSHA/GO; sourcealreadyopen-agentACK15:55.



## Release18 first capacity error and live device cohort 2026-11-22T16:22:01.166369+00:00
- threadId 01a2029e-9c2b-7403-ae53-a870c6592ce0; /root sole production/build/sign/device scheduling, implementation sole tests/ADB unchanged. BUSY source closed15:52 and independent open-source ACK15:55 retained.
- StandardRelease18-1 actual FAILED16:12:49 at PackageMapleSuRuntime copyTo: D main10-manager-build is Junction to full C preserve directory. Actual1,673,677,982 logical bytes copying to fresh trueD destination; original C files untouched. No SDK rebuild or functional source changes; next fresh18-2 will reuse SDK10 and original signature.
- Sole tester r1 preserved BOOT_CHANGED first error; actual newboot d6a735c7-d6f5-4a41-ab6b-37d10b5ef129, no user/device write by tester. New readonly GO issued for exact APK/fullUID/safe/helper cohort. Old31344/15541 stack remains historical priorboot evidence, not newboot state. No newAPK/device pass yet.

- 2026-11-22T16:26:50.388493+00:00 output recovery: verified all10888 files/1673677982 bytes by full SHA before switching future main10-manager-build Junction to trueD manager-build-sdk10-d1; C original and old link retained. Release18-2 actual CMake stale absolute-C-path first error preserved; only relocated provider build cache renamed inside checked D workspace, new18-3 actually started. Frozen selected files/SDK/catalog18-2==18-3 exactly, zero production changes. Sole-tester currentboot readback old17SHA/fullUID10498/uid0/Enforcing/current work-completed/helper17 and native2 rc0 preserved; product UI/SDK10 acceptance remains pending.


## Standard Release18-3 actual built and independent verification 2026-11-22T16:38:38.068607+00:00
- root guard actual BUILT_PENDING_FIXED_TESTER_AND_DEVICE16:35:55, wrapperexit0/standardRelease/R8/inputsStable=true. Exact APK90946274bytes SHA5e0971c267c339124bafc2b8bc3071a10bb0f3caad3eaf60bfd74ce1ab6b6474 at main15-large-output/release-safemode18-3/MapleSU-5.2.1-release.apk; releaseEXEC702c43502a235385e33915d2cad8c4192b86dc060cc809e1e5311f9e7373e2ca.
- Sole tester independent APK-VERIFICATION actualpassed=true, originalcert30bafb57e3604afe93da13a1285516e043feb82b56a861a157b53ccaa27c3506/dev.maplesu.manager/5.2.1/50201. Exact rootNEW18-3-GOb32228299467c999841bbc85c3c2fcbd3afc6700a83709da86d909a0c8410726 authorizes currentcohort preserving-data install and narrow actual product verification, one real SDK10 upgrade onlyifrequired. No device result prefilled.
- Open-source thread informed actualAPK/SDK10/freeze; SHA256SUMS and builtindex ready main15-release18-delivery. FullP0-P8/119/allcorpus/sidechannel qualifications unchanged, goalactive.


## MAIN16 critical continuation 2026-11-22T16:53:40.063256+00:00
- threadId01a2029e-9c2b-7403-ae53-a870c6592ce0; root sole production/build/sign/release/scheduling; test_owner sole all tests/ADB. Current18-3APK5e0971c2/newboot3a2be56b follows one real SDK10 upgrade+automaticreboot, helpernormalized1cc43e9e andpersistedBuildId7af6 actualsamegeneration, uid0/Enforcing/currentwork+completed/native2 rc0. Rootacceptednewcohort.
- Actualproductred: Home still BUILD_IDENTITY_UNVERIFIED_UPGRADE_REQUIRED despite realsamegeneration; Settings2policyread andHomeEnforcing visible; modulefirstloadingleft, notpass; newSU/PT Ynotexecuted. Userexplicitlyrequestsfixidentityfalsealarm together.
- Usertransferredold17twoNXpanics sameinvoke_syscall+0x58/0x114/stackhash4d97fea2; notSDK10/18failure. Rootreadoriginalevidence; givencomparisonb85 differscurrentlyobtained398773df, currentcopy/actualoriginalrawSHA/indexpreservedmain16-owner/STATUS.json. FaultVAarenaownership/PTEandrootcauseunproven.
- SolefixedtesterpausednotyetexecutedactiveSU/UI/repair/rebootandprioritizespanicresidue; nosecondrepair/manualreboot. module_latencyexclusive main16-nx-idle, boot_parityexclusive main16-nx-hook, public_exportexclusive main16-build-identity; eachactualACKreadthenGO, isolatedcandidateonlyzero production/tests/build/ADB. Source18frozenretained; futureadoption/newbuildseparate.
- Open-sourcepreparationthreadnotifiedactualAPK/source/evidenceandbothunclosedcriticalissues, preparationcontinueswithoutstable/fullqualificationclaims. Goalactive, expiredoriginaldeadlinesnotreset.

## MAIN17 owner continuation 2026-11-22T17:07:57.1363493Z
- Current thread01a20431-09ed-7b93-b068-f5e6cd9d2ae7, root sole production/build/sign/release/scheduling; fixed test_owner sole tests/ADB. No current build/device command found; historical lease child retained. Original expired deadlines retained.
- All three locales shared by both settings About themes now replace UI origin with MapleSU GitHub source link. Receipt main17-owner/about-source-link/ADOPTION.json; next full build/device verification pending.
- Read actual rejoin ACKs and issued GO for isolated identity_r2/idle_r2/hook_r2. Previous agent HTTP403/automatic review interruptions preserved; no candidate adopted from interrupted turns. Actual Home identity false negative remains; current empty panic residue does not prove NX repaired. Historical address association explicitly distinguishes reporting boot from crash boot and duplicate summaries from events.


## 2026-11-23：按文档逐项复测，源码修复先于完整构建

当前顺序按用户最新要求执行：先完成独立可执行的文档回归和源码修复，再冻结同代输入并恢复 SDK、双桥、Zygisk、Manager 完整构建。SDK11-r2 已主动中断，原失败和中断收据分别保留；本阶段没有新 APK、签名、安装、修复或重启。

- 安装事务：当前 17 份生产 Java 与原夹具实际编译，四个测试入口通过；File 109、MultiPartition 262、InitBoot 64，共 435 个计数检查，另有 BlockDevice 原完整断言。设备分区效果仍待实测。
- Home 与 Normal 启动链：旧测试的签名匹配及整源相等前提已按真实 owner fence、失败传递和选定补丁的精确 postimage 修正；原 5 个 Home 测试、事件与两组阶段比较实际通过。未修改生产行为以匹配旧测试。
- 路由及并发：当前 RootExecutionRouter、BoundedPageRead 整个编译单元与原 38 项 JUnit 实际通过。此证据限于宿主注入执行器，不代替普通应用设备时延。
- 模块：当前 Kotlin 生成的卸载脚本原 13 场景通过；RootModuleTree 夹具缺少当前 module-initrc 接口，模块合同夹具缺少认证 runtime-facts 接口，原始失败已保存，正在补齐真实平台调用边界后复测。在线更新后直接卸载的设备矩阵仍待验。
- 启动故障：旧片段提取器依赖已删除注释，尚未执行其 19 个原方法；后继按当前身份恢复、activation/owner 作用域及 capability 确认链重新接线，原期待逐项映射，保留真实保护与错误。
- 镜像：真实新 metadata 已产生并通过私有布局及 namespace 探针；消费者已进入 CORE successor 身份门，当前首错为 request_protocol.h 的来源链校验，后继修复中，消费者尚未采用。依赖该加载成功的负例和全部镜像复测仍待执行。
- 旧内核：实际三图只读 shape survey 通过；NGKI 导出记录实际为 24 字节，并需结合 AArch64 重定位还原指针；另外两图为 12 字节。正在修复准确布局解析，未将探针通过视为完整 resolve、native 加载或设备支持。

本轮精确证据索引：`.tmp/supervision-20261121/main18-owner/document-progress-r3/EVIDENCE.json`；当前累计生产采用仍为 15 个唯一文件，回执 `main18-owner/source-adoption-component-producer-r5/ADOPTION.json`。119 项、原 CASE 及追加第 30 项的最终包和指定设备条件继续分别验收，未增加完整需求勾选。


### 2026-11-23 文档复测增量（源码优先）

本轮已将 NGKI 实际 24 字节导出解析修复采用，并通过三幅真实内核图及 14 项负例；m048/m055 的选源声明归属校验也已通过并采用。当前累计生产采用为 18 个唯一文件，最终选源尚未重新冻结，完整构建继续停止。

模块宿主回归已推进：I06 原 6 场景、U02 原 20 次实际 shell／8 字段一致及 12 个选定后像、U01 的 32 个测试方法均通过。I08 当前源码 19 项已通过，永久入口已恢复，默认命令复测待执行。测试夹具修改与生产修改分别留有采用回执。

镜像消费者 R11 已通过窄编译，真实 metadata 加载仍停在 CORE_SELECTED_SOURCE 的后续来源角色校验，77／23 项依赖负例尚未执行；后继继续核对完整来源链。旧内核直接调用探针已越过头文件与只读临时目录问题，当前真实首错是 5.10 缺少 set_cred_ucounts，正在核对整组真实类型和调用语义。全部镜像、最终包及设备效果仍按原条件分别验收。

本次证据索引：`.tmp/supervision-20261121/main18-owner/document-progress-r4/EVIDENCE.json`；生产采用回执 `main18-owner/source-adoption-selection-r1/ADOPTION.json`，测试采用回执 `main18-owner/document-test-adoption-r3/ADOPTION.json`。

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
