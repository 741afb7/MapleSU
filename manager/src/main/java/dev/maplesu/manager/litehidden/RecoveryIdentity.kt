package dev.maplesu.manager.litehidden

/**
 * 「撤销 su 授权所需的身份材料」= **App 作用域**的 `ANDROID_ID` + 管理器包名 + uid。
 *
 * <p>激活时由 App 把当前包名与 fullUID 写到 root-only 文件（[ParasitePaths.IDENTITY_FILE]，
 * `0600 root:root`），供恢复流程核对撤销目标。应用作用域 androidId 保持既有记录格式，
 * 不参与当前 Root/Owner 通道授权。
 *
 * <p>**安全边界（硬约束）**：
 *  - 本文件只放 `androidId` / `package` / `uid` 三个字段，**绝不放 rootKey 明文、密文或 token**；
 *  - `androidId` 不是凭据，仍按 root-only 落盘，避免被别的应用读到；
 *  - 解析是**严格白名单**：整行正则不匹配即 [`parse`] 返回 null（不做宽松 JSON 解析、不猜字段），
 *    任何「看起来像」的值都不会被当成身份材料使用。
 *
 * <p>线程/IO：本类不碰文件、不执行 root（脚本由 [RecoveryIdentityScripts] 生成，执行仍走既有
 * root 通道），因此纯主机可测。
 */
data class RecoveryIdentity(
    val androidId: String,
    val packageName: String,
    val uid: Int,
) {

    /** 只有三个字段都合法才允许落盘（不合法 → 调用方按「没有身份材料」处理，不写垃圾文件）。 */
    val isValid: Boolean
        get() = ANDROID_ID.matches(androidId) && PACKAGE.matches(packageName) && uid in 0..MAX_UID

    /**
     * 单行 JSON（字段顺序固定，便于脚本 `printf %s` 与严格解析；不含空白/换行）。
     *
     * 三个字段的字符集都已白名单校验，因此不需要 JSON 转义；非法身份材料返回空串，
     * 让调用方在写之前就失败（而不是写一个解析不回来的文件）。
     */
    fun toJson(): String {
        if (!isValid) return ""
        return buildString {
            append("{\"v\":").append(VERSION)
            append(",\"androidId\":\"").append(androidId).append('"')
            append(",\"package\":\"").append(packageName).append('"')
            append(",\"uid\":").append(uid)
            append('}')
        }
    }

    /** 身份材料的来源标签，只用于回执/日志里区分「用了文件」还是「回退到系统值」。 */
    fun sourceLabel(): String = SOURCE_APP

    companion object {
        const val VERSION = 1
        const val SOURCE_APP = "identity"

        private const val MAX_UID = 999_999

        /** 只允许字母数字：`Settings.Secure.ANDROID_ID` 的真实取值是 16 位十六进制。 */
        private val ANDROID_ID = Regex("^[A-Za-z0-9]{1,64}$")

        /** 包名白名单（与 `ParasitePolicy` / daemon 侧同形；不做通配）。 */
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /** 严格整行解析：`{"v":1,"androidId":"<hex>","package":"<pkg>","uid":<n>}`。 */
        private val LINE = Regex(
            "^\\{\"v\":1,\"androidId\":\"([A-Za-z0-9]{1,64})\",\"package\":\"" +
                "([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)\",\"uid\":([0-9]{1,6})\\}$",
        )

        /** 合法身份材料；任一字段非法 → null（绝不拼一个用不了的材料出来）。 */
        fun of(androidId: String?, packageName: String?, uid: Int?): RecoveryIdentity? {
            val id = androidId?.trim().orEmpty()
            val pkg = packageName?.trim().orEmpty()
            val user = uid ?: return null
            val identity = RecoveryIdentity(id, pkg, user)
            return identity.takeIf { it.isValid }
        }

        /**
         * 解析 `identity.json`；任何不符合严格格式的输入（缺字段、字段乱序、多余字符、
         * 损坏/被截断、字段值非法）都返回 null —— 调用方据此**回退**，不当成「身份材料为空」。
         */
        fun parse(text: String?): RecoveryIdentity? {
            val line = text?.trim().orEmpty()
            if (line.isEmpty() || line.length > MAX_LINE_CHARS) return null
            val match = LINE.matchEntire(line) ?: return null
            val androidId = match.groupValues[1]
            val pkg = match.groupValues[2]
            val uid = match.groupValues[3].toIntOrNull() ?: return null
            if (uid !in 0..MAX_UID) return null
            return RecoveryIdentity(androidId, pkg, uid).takeIf { it.isValid }
        }

        private const val MAX_LINE_CHARS = 512
    }
}

/**
 * 身份材料的 root 脚本（纯字符串，主机可测；执行仍走既有 root 通道）。
 *
 * 写入纪律（对应真机要求）：
 *  - **原子**：先 `printf > identity.json.tmp`，`chmod 600`，再 `mv -f` 覆盖（同目录 rename，
 *    不会出现「只写了一半的 identity.json」）；
 *  - **0600 root:root**：`chmod 600` + `stat -c '%a %u'` **回读**，不是 `600 0` 就删掉并失败；
 *  - 恢复目录 0700，身份文件与现有恢复材料同级；
 *  - 失败**不留半成品**、不打印文件内容。
 */
object RecoveryIdentityScripts {

    fun read(): String = "cat " + quote(ParasitePaths.IDENTITY_FILE) + " 2>/dev/null"

    fun write(identity: RecoveryIdentity): String {
        val json = identity.toJson()
        // 非法身份材料：直接返回一条**必然失败**的脚本（不写文件、不进 shell 参数）。
        if (json.isEmpty()) return failScript("IDENTITY_INVALID")
        val file = quote(ParasitePaths.IDENTITY_FILE)
        val temp = quote(ParasitePaths.IDENTITY_TEMP)
        val dir = quote(ParasitePaths.RECOVERY)
        return buildString {
            appendLine("set -u")
            appendLine("rm -f $temp 2>/dev/null")
            appendLine("mkdir -p $dir 2>/dev/null || { echo ${ParasiteMarkers.FAIL} code=IDENTITY_MKDIR; exit 80; }")
            appendLine("chmod 700 $dir 2>/dev/null")
            // json 只含 [A-Za-z0-9_] 与 {}":, 因此单引号内无转义风险（toJson 已白名单校验）。
            appendLine("printf %s '$json' > $temp || { echo ${ParasiteMarkers.FAIL} code=IDENTITY_WRITE; exit 81; }")
            appendLine("chmod 600 $temp || { echo ${ParasiteMarkers.FAIL} code=IDENTITY_MODE; exit 82; }")
            appendLine("mv -f $temp $file || { echo ${ParasiteMarkers.FAIL} code=IDENTITY_RENAME; exit 83; }")
            appendLine("chmod 600 $file || { echo ${ParasiteMarkers.FAIL} code=IDENTITY_MODE; exit 84; }")
            appendLine("M=\$(stat -c '%a %u' $file 2>/dev/null)")
            appendLine("if [ \"\$M\" != \"600 0\" ]; then")
            appendLine("  rm -f $file 2>/dev/null")
            appendLine("  echo \"${ParasiteMarkers.FAIL} code=IDENTITY_MODE_READBACK\"")
            appendLine("  exit 85")
            appendLine("fi")
            appendLine("echo ${ParasiteMarkers.IDENTITY_OK}")
        }
    }

    /**
     * 解除成功后清理（失败保留以便重试：只有真的要删的时候才调用）。
     *
     * 判定来自文件事实（`[ ! -e ]`），不看 `rm` 的退出码 —— 删不掉时显式失败。
     */
    fun remove(): String =
        "rm -f " + quote(ParasitePaths.IDENTITY_FILE) + " " + quote(ParasitePaths.IDENTITY_TEMP) +
            " 2>/dev/null; " +
            "if [ -e " + quote(ParasitePaths.IDENTITY_FILE) + " ]; then " +
            "echo ${ParasiteMarkers.FAIL} code=IDENTITY_DELETE_FAILED; exit 86; fi; " +
            "echo ${ParasiteMarkers.IDENTITY_GONE}"

    /** 必然失败且**不动任何文件**的脚本（非法身份材料时的诚实出口）。 */
    private fun failScript(code: String): String =
        "echo ${ParasiteMarkers.FAIL} code=$code; exit 89"

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
