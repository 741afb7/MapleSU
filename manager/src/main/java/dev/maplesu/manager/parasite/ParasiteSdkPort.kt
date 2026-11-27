package dev.maplesu.manager.parasite

/**
 * 寄生模式 SDK 端口 —— 对同代 Ultra SDK
 * `parasite_precheck_app` / `parasite_implant_app` / `parasite_implant_su_env`
 * （`runtime/maplesu-sdk/include/maplesu_parasite.h`）的唯一封装点。
 *
 * 生产使用当前调用任务身份；旧库与不可用环境保留结构化失败语义。
 */

/** 目标进程动态库的可寄生状态（头文件 `AppDynlibStatus` 的 wire 词表）。 */
enum class AppDynlibStatus(val wire: String) {
    /** header: Unknown = 0；未知必须保持未知，不得默认成 NOT_RUNNING。 */
    UNKNOWN("unknown"),
    RUNNING("running"),
    NOT_RUNNING("not_running"),
    ;

    companion object {
        fun fromWire(value: String?): AppDynlibStatus? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class AppDynlibInfo(val path: String, val status: AppDynlibStatus)

/** 结构化错误码（跨 UI/日志/测试复用；不得靠 detail 文本判别）。 */
object ParasiteCodes {
    /** Ultra 环境整体不可用（未 Running / 未链接）。 */
    const val ULTRA_UNAVAILABLE = "ULTRA_UNAVAILABLE"
    /** Ultra 环境存在但未 Running。 */
    const val ULTRA_NOT_RUNNING = "ULTRA_NOT_RUNNING"
    /** 旧 Ultra SDK 缺少寄生符号/绑定。 */
    const val ULTRA_API_UNLINKED = "ULTRA_API_UNLINKED"
    const val INVALID_ARGUMENT = "PARASITE_INVALID_ARGUMENT"
    const val PRECHECK_FAILED = "PARASITE_PRECHECK_FAILED"
    const val IMPLANT_FAILED = "PARASITE_IMPLANT_FAILED"
    const val SU_ENV_FAILED = "PARASITE_SU_ENV_FAILED"
    /** 预检查成功但没有任何可寄生 dynlib。 */
    const val NO_ELIGIBLE_DYNLIB = "PARASITE_NO_ELIGIBLE_DYNLIB"

    /** 模式切换写者静默期：拒绝新的寄生动作。 */
    const val QUIESCING = "PARASITE_QUIESCING"

    /** 预检查输出里出现未定义的 dynlib 状态整数（不默认成 not_running）。 */
    const val STATUS_UNKNOWN = "PARASITE_DYNLIB_STATUS_UNKNOWN"
}

/** Ultra 可用性分类；[UnavailableParasiteSdkPort] 据此返回不同的失败码。 */
enum class UltraAvailability {
    RUNNING,
    NOT_RUNNING,
    UNLINKED,
    ;

    fun unavailableCode(): String = when (this) {
        RUNNING -> ParasiteCodes.ULTRA_UNAVAILABLE
        NOT_RUNNING -> ParasiteCodes.ULTRA_NOT_RUNNING
        UNLINKED -> ParasiteCodes.ULTRA_API_UNLINKED
    }
}

sealed interface ParasiteSdkResult {
    data class Ok(val dynlibs: List<AppDynlibInfo> = emptyList()) : ParasiteSdkResult

    data class Failed(val code: String, val detail: String = "") : ParasiteSdkResult

    val isOk: Boolean get() = this is Ok
}

/** 三个原生调用的唯一端口（设备执行只可能经此；主机测试用 fake）。 */
interface ParasiteSdkPort {
    /** `parasite_precheck_app`：目标进程 cmdline → (so 绝对路径 → 可寄生状态)。 */
    fun precheck(targetPidCmdline: String): ParasiteSdkResult

    /** `parasite_implant_app`：植入普通 App 寄生 so。 */
    fun implantApp(targetPidCmdline: String, soPath: String): ParasiteSdkResult

    /** `parasite_implant_su_env`：植入 su 环境。 */
    fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String): ParasiteSdkResult
}

/** Ultra 不可用时的端口：不 crash、不造数据，可用性分类给出结构化错误码。 */
class UnavailableParasiteSdkPort(
    private val availability: UltraAvailability = UltraAvailability.UNLINKED,
) : ParasiteSdkPort {
    private fun unavailable(): ParasiteSdkResult.Failed =
        ParasiteSdkResult.Failed(availability.unavailableCode(), "parasite sdk unavailable")

    override fun precheck(targetPidCmdline: String): ParasiteSdkResult = unavailable()

    override fun implantApp(targetPidCmdline: String, soPath: String): ParasiteSdkResult = unavailable()

    override fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String): ParasiteSdkResult =
        unavailable()

    companion object {
        /** 旧 SDK 未链接时的兼容端口。 */
        val CURRENT: UnavailableParasiteSdkPort = UnavailableParasiteSdkPort(UltraAvailability.UNLINKED)
    }
}

/**
 * Ultra 侧原生调用的集中点（反射/JNI 都只能出现在实现里；端口本身纯 Kotlin）。
 *
 * 生产实现为 ParasiteUltraApiAdapter，保留 0=OK、旧库缺符号和真实错误回执。
 */
interface ParasiteUltraApi {
    /** 返回 dynlib 绝对路径 → 状态；失败抛异常或返回空 map（由端口映射）。 */
    fun precheckApp(targetPidCmdline: String): Map<String, AppDynlibStatus>

    /** `KRootErr`：0 = OK。 */
    fun implantApp(targetPidCmdline: String, soPath: String): Int

    /** `KRootErr`：0 = OK。 */
    fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String): Int
}

/** 未链接替身：任何调用都抛 `UnsupportedOperationException`（端口会映射成结构化失败）。 */
object UnlinkedParasiteUltraApi : ParasiteUltraApi {
    override fun precheckApp(targetPidCmdline: String): Map<String, AppDynlibStatus> =
        throw UnsupportedOperationException("parasite native binding TODO")

    override fun implantApp(targetPidCmdline: String, soPath: String): Int =
        throw UnsupportedOperationException("parasite native binding TODO")

    override fun implantSuEnv(
        targetPidCmdline: String,
        soPath: String,
        suDir: String,
    ): Int = throw UnsupportedOperationException("parasite native binding TODO")
}

/**
 * 已链接端口：参数校验 → 静默期 → 可用性检查 → 当前任务原生调用 → 错误码映射。
 * 原生绑定缺失时行为与 [UnavailableParasiteSdkPort] 一致（结构化失败，不崩）。
 */
class UltraLinkedParasiteSdkPort(
    private val api: ParasiteUltraApi = UnlinkedParasiteUltraApi,
    private val availabilityProvider: () -> UltraAvailability = { UltraAvailability.UNLINKED },
    private val quiescing: () -> Boolean = { false },
) : ParasiteSdkPort {

    override fun precheck(targetPidCmdline: String): ParasiteSdkResult {
        validate(targetPidCmdline)?.let { return it }
        blocked()?.let { return it }
        unavailable()?.let { return it }
        return runCatching {
            val dynlibs = api.precheckApp(targetPidCmdline)
            if (dynlibs.isEmpty()) {
                ParasiteSdkResult.Failed(ParasiteCodes.NO_ELIGIBLE_DYNLIB, "no eligible dynlib")
            } else {
                ParasiteSdkResult.Ok(dynlibs.entries.sortedBy { it.key }.map { AppDynlibInfo(it.key, it.value) })
            }
        }.getOrElse { failure(it) }
    }

    override fun implantApp(targetPidCmdline: String, soPath: String): ParasiteSdkResult {
        validate(targetPidCmdline)?.let { return it }
        validate(soPath, "soPath")?.let { return it }
        blocked()?.let { return it }
        unavailable()?.let { return it }
        return runCatching {
            if (api.implantApp(targetPidCmdline, soPath) == 0) ParasiteSdkResult.Ok()
            else ParasiteSdkResult.Failed(ParasiteCodes.IMPLANT_FAILED, "implantApp rejected")
        }.getOrElse { failure(it) }
    }

    override fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String): ParasiteSdkResult {
        validate(targetPidCmdline)?.let { return it }
        validate(soPath, "soPath")?.let { return it }
        validate(suDir, "suDir")?.let { return it }
        blocked()?.let { return it }
        unavailable()?.let { return it }
        return runCatching {
            if (api.implantSuEnv(targetPidCmdline, soPath, suDir) == 0) ParasiteSdkResult.Ok()
            else ParasiteSdkResult.Failed(ParasiteCodes.SU_ENV_FAILED, "implantSuEnv rejected")
        }.getOrElse { failure(it) }
    }

    private fun unavailable(): ParasiteSdkResult.Failed? {
        val availability = availabilityProvider()
        return if (availability == UltraAvailability.RUNNING) null
        else ParasiteSdkResult.Failed(availability.unavailableCode(), "ultra unavailable")
    }

    private fun failure(error: Throwable): ParasiteSdkResult.Failed {
        val availability = runCatching { availabilityProvider() }.getOrNull() ?: UltraAvailability.UNLINKED
        val code = when (error) {
            is ParasiteUltraApiException -> when (error.code) {
                // 原生权限与业务失败保持真实错误，旧库缺符号独立映射。
                ParasiteBridgeCodes.SDK_SYMBOL_MISSING -> ParasiteCodes.ULTRA_API_UNLINKED
                else -> if (availability == UltraAvailability.RUNNING) {
                    ParasiteCodes.ULTRA_UNAVAILABLE
                } else {
                    availability.unavailableCode()
                }
            }
            is UnsupportedOperationException -> ParasiteCodes.ULTRA_API_UNLINKED
            else -> if (availability == UltraAvailability.RUNNING) {
                ParasiteCodes.ULTRA_UNAVAILABLE
            } else {
                availability.unavailableCode()
            }
        }
        val detail = (error as? ParasiteUltraApiException)?.code ?: error.javaClass.simpleName
        return ParasiteSdkResult.Failed(code, detail)
    }

    private fun validate(value: String, field: String = "targetPidCmdline"): ParasiteSdkResult.Failed? =
        if (value.isBlank()) {
            ParasiteSdkResult.Failed(ParasiteCodes.INVALID_ARGUMENT, "$field blank")
        } else {
            null
        }

    private fun blocked(): ParasiteSdkResult.Failed? =
        if (quiescing()) ParasiteSdkResult.Failed(ParasiteCodes.QUIESCING, "writes quiesced") else null
}
