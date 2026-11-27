package dev.maplesu.manager.parasite

import dev.maplesu.manager.compat.StoredInputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ultra bridge 回执的**纯解析**（H4 P1）。与 `NativeReceipt` 的 org.json 版本并行：
 * 本包不依赖 Android，因此主机测试可直接喂 JSON 字符串验证解析与错误码映射。
 *
 * 回执形状（`runtime/ultra/bridge/native_lib_ultra.cpp` 统一约定）：
 * `{schemaVersion, backend:"maplesu_ultra", bridgeVersion, operation, ok, sdkError,
 *   sdkErrorCode, reason?, data?}`；成功由 `ok && sdkError=="OK" && sdkErrorCode==0` 判定。
 */
data class ParasiteBridgeReceipt(
    val schemaVersion: Int,
    val backend: String,
    val operation: String,
    val ok: Boolean,
    val sdkError: String,
    val sdkErrorCode: Int,
    val reason: String?,
    val data: JsonObject?,
    val raw: String,
) {
    val succeeded: Boolean get() = ok && sdkError == "OK" && sdkErrorCode == 0

    val dynlibs: List<ParasiteDynlibReceipt>
        get() = data?.get("dynlibs")?.jsonArray?.map { element ->
            val item = element.jsonObject
            ParasiteDynlibReceipt(
                path = item["path"]?.jsonPrimitive?.content.orEmpty(),
                status = item["status"]?.jsonPrimitive?.content.orEmpty(),
                statusKnown = (item["statusKnown"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.booleanOrNull ?: false,
                statusCode = (item["statusCode"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.intOrNull ?: -1,
            )
        }.orEmpty()
}

data class ParasiteDynlibReceipt(
    val path: String,
    val status: String,
    val statusKnown: Boolean,
    val statusCode: Int,
)

/** bridge 错误码常量（与 native_lib_ultra.cpp 一致）。 */
object ParasiteBridgeCodes {
    const val INVALID_ARGUMENT = "MAPLESU_BRIDGE_INVALID_ARGUMENT"
    const val SDK_SYMBOL_MISSING = "MAPLESU_BRIDGE_SDK_SYMBOL_MISSING"
    const val UNAVAILABLE = "MAPLESU_BRIDGE_UNAVAILABLE"
    const val UNPARSEABLE = "MAPLESU_BRIDGE_UNPARSEABLE"
    const val EXCEPTION = "MAPLESU_BRIDGE_EXCEPTION"
}

/** native 绑定异常（端口把 code 映射为 `ULTRA_*` 四码）。 */
class ParasiteUltraApiException(val code: String, val detail: String? = null) :
    RuntimeException(if (detail.isNullOrEmpty()) code else "$code: $detail")

object ParasiteBridgeReceiptParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): ParasiteBridgeReceipt? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val backend = StoredInputs.receiptBackend(root["backend"]?.jsonPrimitive?.content ?: return null)
        if (backend.isEmpty()) return null
        return ParasiteBridgeReceipt(
            schemaVersion = (root["schemaVersion"]?.jsonPrimitive)?.intOrNull ?: 0,
            backend = backend,
            operation = root["operation"]?.jsonPrimitive?.content.orEmpty(),
            ok = (root["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false,
            sdkError = StoredInputs.error(root["sdkError"]?.jsonPrimitive?.content ?: ParasiteBridgeCodes.UNPARSEABLE),
            sdkErrorCode = (root["sdkErrorCode"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.intOrNull ?: Int.MIN_VALUE,
            reason = root["reason"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
            data = root["data"] as? JsonObject,
            raw = raw,
        )
    }
}

/** 三 JNI 调用的可注入替身（主机测试不加载 native 库）。 */
interface ParasiteNativeCall {
    fun precheck(targetPidCmdline: String): String?
    fun implantApp(targetPidCmdline: String, soPath: String): String?
    fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String): String?
}

/**
 * 把 bridge 回执适配为 [ParasiteUltraApi]：
 * - 回执缺失/不可解析/`ok=false` → 抛 [ParasiteUltraApiException]（不伪造成功）；
 * - `data.dynlibs` 的 `unknown` 状态 → 抛错（不默认 not_running）；
 * - 授权由原生当前任务通道核验。
 */
class ParasiteUltraApiAdapter(
    private val native: ParasiteNativeCall,
) : ParasiteUltraApi {

    override fun precheckApp(targetPidCmdline: String): Map<String, AppDynlibStatus> {
        val receipt = requireReceipt(
            native.precheck(targetPidCmdline), "parasite_precheck_app",
        )
        val result = linkedMapOf<String, AppDynlibStatus>()
        for (dynlib in receipt.dynlibs) {
            if (dynlib.path.isBlank()) {
                throw ParasiteUltraApiException(ParasiteBridgeCodes.UNPARSEABLE, "dynlib path missing")
            }
            val status = if (!dynlib.statusKnown || dynlib.status == AppDynlibStatus.UNKNOWN.wire) {
                throw ParasiteUltraApiException(
                    ParasiteCodes.STATUS_UNKNOWN,
                    "status=${dynlib.status} code=${dynlib.statusCode}",
                )
            } else {
                AppDynlibStatus.fromWire(dynlib.status)
                    ?: throw ParasiteUltraApiException(
                        ParasiteCodes.STATUS_UNKNOWN,
                        "status=${dynlib.status} code=${dynlib.statusCode}",
                    )
            }
            result[dynlib.path] = status
        }
        return result
    }

    override fun implantApp(targetPidCmdline: String, soPath: String): Int {
        requireReceipt(
            native.implantApp(targetPidCmdline, soPath), "parasite_implant_app",
        )
        return 0
    }

    override fun implantSuEnv(
        targetPidCmdline: String,
        soPath: String,
        suDir: String,
    ): Int {
        requireReceipt(
            native.implantSuEnv(targetPidCmdline, soPath, suDir),
            "parasite_implant_su_env",
        )
        return 0
    }

    private fun requireReceipt(raw: String?, operation: String): ParasiteBridgeReceipt {
        val receipt = ParasiteBridgeReceiptParser.parse(raw)
            ?: throw ParasiteUltraApiException(ParasiteBridgeCodes.UNPARSEABLE, operation)
        if (!receipt.succeeded) {
            throw ParasiteUltraApiException(receipt.sdkError, receipt.reason)
        }
        return receipt
    }
}
