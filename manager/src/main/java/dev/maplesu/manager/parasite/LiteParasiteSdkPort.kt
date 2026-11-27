package dev.maplesu.manager.parasite

import dev.maplesu.manager.workmode.WorkMode

/**
 * 模式端口选择：只有 A（LITE）调用其端口工厂（生产为免 Key Ultra SDK）；KSU/未判定
 * 返回 ULTRA_UNAVAILABLE。当前调用任务身份由 SDK 核验，不启动旧凭据 CLI。
 */
fun selectProductionParasitePort(
    mode: WorkMode?,
    litePort: () -> ParasiteSdkPort,
    unavailablePort: () -> ParasiteSdkPort = { UnavailableParasiteSdkPort(UltraAvailability.RUNNING) },
): ParasiteSdkPort = if (mode == WorkMode.LITE) litePort() else unavailablePort()
