# 当前源码检查点

2026-11-28；这是接续开发输入。最终整包、完整SDK与全部设备功能尚未验收通过。

| 已观察范围 | 真实结论 |
|---|---|
| Manager上一候选窄编译 | 19份完整生产源经K2/Compose编译成功，441个输出；不等于整个Manager/Gradle或新APK通过 |
| Manager上一候选统一回归 | 67类、671项、15失败；原日志保留。后继测试/部分UI已调整，但未重新统一编译执行 |
| 恢复控制流窄测 | 93项/0失败；仅涵盖对应Manager fixture控制流/诊断，未证明native、SDK或设备 |
| Root诊断 | init_boot诊断证Root command10 status0/effects8、UID/GID0、TASK_STATE成功；随后pipe写入EACCES、父EPIPE，正式产品回执仍失败。diagnosticOnly，Application初始化跳过，不作为产品Root成功 |
| 真正boot升级回归 | 旧正常APK/SDK/boot精确绑定与新失败版首次分歧仍需补齐；旧init_boot诊断不替代boot验收 |

优先待修：

1. **SDK配套和Root回传**：核当前原生Root主体源码、SDK调用链和pipe/FD权限；已有原生修复仍须从同代正式构建到真实boot验证。保原errno/stage/receipt，禁止把“已提权但回传失败”归为身份变化或提权本身失败。
2. **Normal原生阳性缓存**：实际JNI选源 `third_party/kernelsu/applied/manager/app/src/main/cpp/ksu.cc` 的 `get_info()` 在版本阳性且Manager位为1时跳过 `ksuctl`，`get_version()`/`is_manager()`复用缓存；Kotlin fresh轮仍可能消费旧阳性并激活KSU业务路由。修真实native当前读及消费者，禁止靠fixture变绿关闭。未证明权限绕过或真实Root成功。legacy INHERITED的phase2映射尚保留，现新增负例只覆盖phase1。
3. **重新运行统一回归**：上次15失败分布为UltraEvidenceFirstTest 1、NormalRebootRecognitionTest 7、HomeTabCacheDisplayTest 6、HomeRuntimeRecognitionWiringTest 1。后继修改经静态核对，尚无新执行结果；从当前完整输入重新编译运行，保原失败。
4. **无owner失败详情**：Settings两主题已新增只读历史失败消费者，后继定向复审及统一运行待做；写入端已有脱敏，消费者再次调用 `UltraSdkLogging.redact` 与对应key脱敏测试仍待补。不得新增Root/probe/安装副作用或改变能力门。
5. **UI展示优化与全部原项**：按开发要求参考KSU完成两主题/原布局、状态、文字、交互和实机视觉验收；原账本及历史检查仅作输入，逐原ID重新结算。

保存失败：磁盘满曾造成两个内部准备JSON为空，未提升为有效收据；后继源码已读回完整。此包不依赖那两个内部文件。原平台中止边界见README。来源、主机、产物、设备各自给证据，禁止从源码/窄测推导完整通过。
