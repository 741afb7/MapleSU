# 普通应用双 ABI SU 样本

独立测试包 `dev.maplesu.acceptance.su.arm32` / `dev.maplesu.acceptance.su.arm64`，分别仅含 ARM32 / AArch64 JNI 库；无 Manager SDK、共享 UID、特殊权限或后台入口。Activity 默认自身观测；只有显式 `operation=su` 才由应用自身执行 `/system/bin/su -c ...`。

源码阶段仅执行局部检查：

```powershell
python tools/acceptance/su-probe/build.py --syntax-only --out .tmp/su-probe-syntax
```

冻结后由主线构建两包；输出目录中的测试证书仅供这些样本使用，重复构建保留同一证书。脚本不连接设备或安装应用：

```powershell
python tools/acceptance/su-probe/build.py --out .tmp/ultra-keyless-20261124/acceptance/su-probe
```

`RESULT.json` 记录源码、APK 哈希、证书和 ELF 头；`commands.json` 记录所有命令及返回值。脚本逐包检查实际 ELF class/machine、单 ABI badging 和 APK 签名。源阶段语法检查不标记 APK 构建通过。

先核设备支持的 ABI，再安装对应包；32 位 JNI 只在支持 `armeabi-v7a` 的设备运行，64 位设备上执行 64 位 SU 子进程不充当 32 位应用证据：

```text
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist32
adb install -t -r .tmp/ultra-keyless-20261124/acceptance/su-probe/su-probe-arm64.apk
adb shell cmd package list packages -U --user 0 dev.maplesu.acceptance.su.arm64
adb shell am start -S -W -n dev.maplesu.acceptance.su.arm64/dev.maplesu.acceptance.su.ProbeActivity --es operation su --es case deny-before --es expect deny
adb shell cat /sdcard/Android/data/dev.maplesu.acceptance.su.arm64/files/last-result.json
```

每包分开记录实际 fullUID、签名摘要、`process64Bit`、JNI `pointerBits`、调用前后 real/effective/saved UID/GID、SU 退出码和原始输出。通过当前 Manager 授权并完成同 revision 回读后，重新显式启动，改 `case=allow` / `expect=allow`；撤权并回读后，重新启动，改 `case=deny-after` / `expect=deny`。`expectationMet` 还要求普通调用者身份未变、无超时且输出收集完整。探针错误或超时不算拒绝成功。

对 ARM32 包用同样流程替换包名和 APK。多用户安装时加相应 `--user USER_ID`，以该用户实际 package fullUID 与 JSON fullUID 一致为准；授权只作用于该 fullUID，另一用户维持拒绝。UID 重用/换签名、在途生命周期与重复请求继续使用原始验收清单，不以此一次性探针替代。

普通未请求视角差分单独运行 `observe`，此分支不执行 SU、SDK 或任何子进程，不添加权限、服务、接收器或后台入口。观测均以该普通应用的真实权限进行：

- 自身 `/proc/self/{mountinfo,attr/current,status,maps,fd}`。
- 固定相关系统属性，直接调用本进程 Bionic `__system_property_get`，不运行 getprop、不用隐藏 API。属性返回长度 0 明确记为 `EMPTY_MISSING_OR_NOT_READABLE`，保留返回值及观察到的 errno；不把空值当作属性不存在或读取通过。
- 固定相关路径的 `lstat` 与存在/读/写/执行 `access`，记录实际结果、errno、所有者和mode。路径包括常见 su 入口、`/data/adb` 的相关子树、`/dev/kernelsu`、SELinux节点及两个固定 proc 文件；不读取其他应用私有目录或猜测随机部署 namespace。
- 本应用可枚举的 `/proc` 原始目录项，以及可见数字 PID 的 `cmdline`、`status`、`attr/current`、`exe`；每次读取保留成功内容或拒绝/退出错误。原始字节以 Base64 放入 `processes.json`，不把可见PID子集当作系统完整进程清单。

`--ei samples N` 指定本次显式运行的采样次数（1–50，默认1），`--ei intervalMs N` 指定采样间隔（0–1000毫秒，默认0）。每项每次独立记录读取耗时；仅在这次 Activity 请求范围内做有界采样，没有自动轮询或定时入口。每次运行建立唯一 `observe-时间-UUID/sample-NNN/` 目录，逐样本保留原件、错误与 `observations.json`；运行结果保存为该唯一目录的 `result.json`。`last-result.json` 只作为最新结果入口，历史样本不覆盖。原件位于同一应用的外部文件目录：

```text
adb shell am start -S -W -n dev.maplesu.acceptance.su.arm64/dev.maplesu.acceptance.su.ProbeActivity --es operation observe --es case ordinary-before --ei samples 30 --ei intervalMs 100
adb pull /sdcard/Android/data/dev.maplesu.acceptance.su.arm64/files/ OBSERVATION_BEFORE
```

环境变更后以相同 APK、签名、fullUID 和位数再运行 `ordinary-after`，另存 `OBSERVATION_AFTER`。比较普通应用保存的原件、读取成功/拒绝分布及逐项多次耗时；PID、进程启停、fd 号码、ASLR 地址、属性自然变化等保留原始证据后单独解释，摘要不同本身不算环境泄漏。该样本记录观测事实，不自行裁定环境“不可检测”。ADB 仅触发与取回，不充当观测进程；应用权限读不到的项目保留错误，不通过 ADB Root 补齐，不加入网关主动探测或自动轮询。正式差分应固定 APK 哈希/签名、fullUID、位数和采样参数，并在相同设备/相同条件分别保存无环境、起点代、免Key代。

2026-11-24 主线只读硬件清点：当前设备 `a261972a` 的 `abilist32` 为空；ARM32 实机项仍为 `NOT_RUN`。本目录不保存任何设备成功结论。

## 独立显式 gateway 样本

主动网关差分使用新包 `dev.maplesu.acceptance.gateway.arm32` / `dev.maplesu.acceptance.gateway.arm64`。现有 passive APK、证书、包身份和观测目录保留；新包采用独立输出根和测试证书。新 manifest 仍无额外权限、服务、接收器或共享 UID。只有 Activity 收到显式 `operation=gateway` 才调用本应用 JNI 的 raw `syscall(__NR_execve, packet, NULL, NULL)`；不启动子进程，不经 SDK，不请求 OPEN/ROOT/READ 或安装变更。

```powershell
C:/Python314/python.exe -B tools/acceptance/su-probe/build.py --gateway --syntax-only --out .tmp/ultra-keyless-20261124/a06/gateway-syntax-r1
# 冻结后由主线在新的独立输出根构建；脚本不安装应用或运行设备。
python tools/acceptance/su-probe/build.py --gateway --out NEW_GATEWAY_OUTPUT
```

产物为 `gateway-probe-arm32.apk` / `gateway-probe-arm64.apk`，证书为该输出根的 `gateway-probe-test.jks`；RESULT 原有 artifact 字段保持，额外 `probeKind=gateway`。逐包仍检查单 ABI、ELF class/machine 和签名。普通包即便已安装，也不被本步骤替换。

五项 `variant` 为 `query`、`echo`、`wrong-prefix`、`wrong-selector`、`bad-magic`，默认 `all` 按此顺序运行。后面三项各从正常 QUERY 仅修改一个变量。request/response 为明确 48B、8 字节对齐的独立测试帧；response 预填 `0xA5`。ECHO 的 host 两字段固定零，不发现或猜测受保护身份。结果记录实际 rv、紧邻 syscall 保存的 errno、响应前后完整 48B、request/packet/host 原字节、仅包住 syscall 的 CLOCK_MONOTONIC 耗时和 clock 错误，以及调用前后 real/effective/saved UID/GID。未预置任何设备 errno 或“通过”结论。

每条还有 native 进程静态原子 `processCallIndex`：仅值 0 标 `PROCESS_FIRST`，后续为 `SAME_PID_WARM`。同 PID 再启动 Activity 也不会重置该序号。`round` / `caseOrder` / `sampleIndex` 保留实际顺序；round 0 的五项不等于五个冷调用。首调用只代表本测试进程首次主动请求，不声称系统缓存已冷。Java 同步保本包 fullUID、全部 signer 摘要、ABI、PID，以及前后自身 status（含 Seccomp）和 attr/current 原件或真实读取错误。

`samples=1..100` 表示每项次数，默认 31；默认一次获得至少 30 个同 PID 暖样本/项。每次运行保存唯一 `gateway-时间-UUID/`，每条 `sample-NNNN.json` 与最终 `result.json` 均保留；`last-result.json` 仅为最新入口。示例（只由主线运行）：

```text
adb shell am start -S -W -n dev.maplesu.acceptance.gateway.arm64/dev.maplesu.acceptance.gateway.GatewayActivity --es operation gateway --es variant all --es case warm-before --ei samples 31
adb pull /sdcard/Android/data/dev.maplesu.acceptance.gateway.arm64/files/ GATEWAY_BEFORE
```

进程首调用采样须分别选择每种 `variant`、`samples=1`，由 ADB 显式 force-stop/启动至少 30 次；各次用唯一 `case`，等待对应 case 的最终 result 保存后再进入下一次。分别核每项至少 30 个 `processCallIndex=0` 样本和至少 30 个 `processCallIndex>0` 暖样本，保留真实 PID/启动回执。未凑齐或进程退出/结果写入失败保留缺口。取回、汇总和应用启动由主线 ADB 完成，不向测试应用加入自启、轮询服务或进程制造逻辑。

在产品部署前采集该独立普通身份起点，部署后以相同 APK/签名/fullUID/位数/采样参数重复；比较原始返回、响应改写和耗时分布。主动 gateway 与 passive observe 分开记账，前者不充当“未请求应用态”证据。ARM32 仍需实际支持该 ABI 的设备；本轮源码/编码检查不记设备通过。
