package dev.maplesu.manager.runtime.ultra

import com.maplesu.core.BuildConfig

object UltraSdkBuild {
    const val BUILD_ID = BuildConfig.ULTRA_SDK_BUILD_ID
    const val SDK_VERSION = BuildConfig.ULTRA_SDK_VERSION
    // Legacy facade field retained for callers; source history is kept in provenance evidence.
    const val UPSTREAM_VERSION = SDK_VERSION
    const val HELPER_SIZE = BuildConfig.ULTRA_SDK_HELPER_SIZE
    const val SDK_SHA256 = BuildConfig.ULTRA_SDK_SHA256
    const val HELPER_SHA256 = BuildConfig.ULTRA_SDK_HELPER_SHA256
    const val HELPER_BUILD_ID = BuildConfig.ULTRA_SDK_HELPER_BUILD_ID
    const val APP_VERSION = BuildConfig.VERSION_NAME
    val known: Boolean get() = BUILD_ID != "unknown" && HELPER_SHA256.length == 64
}
