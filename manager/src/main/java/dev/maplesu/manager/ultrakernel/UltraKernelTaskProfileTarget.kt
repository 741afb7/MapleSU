package dev.maplesu.manager.ultrakernel

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same-generation implementation facts and facts derived from the current input Image. */
internal object UltraKernelTaskProfileTarget {
    const val PROOF_SCHEMA = "maplesu-task-profile-target-proof/3"
    const val SLOT_CONTRACT = "fixed-kernelsu-bit63-layout-compatibility"
    val proofBits = linkedMapOf("slot63" to 1, "rcu" to 2, "bitops" to 4,
        "cfi" to 8, "syscallTracepoint" to 16)
    data class Table(val symbol: String, val section: String, val offset: Int, val fileOffset: Int,
        val words: List<Int>, val sha256: String, val writable: Boolean)
    data class Implementation(val present: Boolean, val entries: Map<String, UltraKernelManagerClaimPayload.CallbackPrefix>,
        val signatures: Map<String, String>, val sourceSha256: String, val trace: Table, val target: Table,
        val marker: Table, val nativeAbi: JsonObject, val normalizeIntegers: Boolean,
        val legacyCfi: JsonObject? = null, val nativeLayout: JsonObject? = null)
    data class Proof(val targetSha256: String, val configSha256: String, val proofSha256: String,
        val workMember: Int, val workBit: Int, val markerWords: List<Int>, val evidence: JsonObject,
        val missing: Map<String, String>) {
        val complete get() = missing.isEmpty()
    }
    private fun identity(value: JsonElement?): JsonObject = (value?.jsonObject
        ?: error("ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_IDENTITY")).also {
        require(!it["path"]?.jsonPrimitive?.content.isNullOrBlank() &&
            (it["bytes"]?.jsonPrimitive?.longOrNull ?: 0) > 0 &&
            it["sha256"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) == true) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_IDENTITY"
        }
    }
    private fun dependencyIdentity(value: JsonElement): JsonObject = value.jsonObject.also {
        if (it["bytes"]?.jsonPrimitive?.longOrNull != 0L) identity(it) else {
            val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            require(!it["path"]?.jsonPrimitive?.content.isNullOrBlank() &&
                it["sha256"]?.jsonPrimitive?.content == empty &&
                it["archivePath"]?.jsonPrimitive?.content == "build-dependencies/files/$empty.bin") {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_DEPENDENCY_IDENTITY"
            }
        }
    }
    private fun same(a: JsonObject, b: JsonObject) = a["bytes"] == b["bytes"] && a["sha256"] == b["sha256"]
    private fun words(values: JsonElement?) = values!!.jsonArray.map {
        val word = it.jsonPrimitive.long
        require(word in Int.MIN_VALUE.toLong()..0xffffffffL) { "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_WORD" }
        word.toInt()
    }
    private fun bytes(words: List<Int>) = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { words.forEach { putInt(it) } }.array()

    /** A real legacy module supplies its own implementation facts. A raw claim
     * with disabled LTO/CFI is neither its owner nor an execution prerequisite. */
    fun parseLegacyModule(report: JsonObject): Implementation {
        require(report["schema"]?.jsonPrimitive?.content == "maplesu-init-bridge-task-profile-abi/1" &&
            report["implementationOrigin"]?.jsonPrimitive?.content == "normal-module-legacy-cfi" &&
            listOf("originalModuleWritten", "targetQualified", "deviceQualified").all {
                report[it]?.jsonPrimitive?.booleanOrNull == false
            }) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_SCHEMA" }
        val policy = report.getValue("cfiPolicy").jsonObject
        require(policy["mode"]?.jsonPrimitive?.content == "legacy-lto-cfi") { "ULTRA_KERNEL_TASK_PROFILE_MODULE_MODE" }
        val successor = report.getValue("sourceSuccessor").jsonObject
        val source = identity(report["sourceSuccessorIdentity"]).getValue("sha256").jsonPrimitive.content
        val requiredSources = setOf("manager_task_state.c", "manager_task_state.h", "manager_su_runtime_target.c", "manager_su_runtime_factory.c")
            .map { "runtime/ultra/identity/kernel/$it" }.toSet()
        val rows = successor.getValue("files").jsonArray.map { it.jsonObject }
        val selected = report.getValue("sources").jsonObject
        require(successor["schema"]?.jsonPrimitive?.content == "maplesu-task-profile-source-successor/1" &&
            successor["sourceOwnerFrozen"]?.jsonPrimitive?.booleanOrNull == true &&
            successor["productionWritten"]?.jsonPrimitive?.booleanOrNull == false && rows.size == 4 &&
            rows.map { it.getValue("logicalPath").jsonPrimitive.content }.toSet() == requiredSources && selected.keys == requiredSources) {
            "ULTRA_KERNEL_TASK_PROFILE_MODULE_SOURCES"
        }
        for (row in rows) {
            val name = row.getValue("logicalPath").jsonPrimitive.content
            val actual = selected.getValue(name).jsonObject
            require(same(identity(row["after"]), actual) &&
                same(identity(row["after"]), identity(actual["selected"]))) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_SOURCE:$name" }
        }
        val commands = report.getValue("provenance").jsonObject.getValue("actualLegacyCommands").jsonArray
        require(commands.size >= 5 && commands.all { item -> item.jsonObject.let {
            identity(it["identity"])
            it["legacyEnabled"]?.jsonPrimitive?.booleanOrNull == true &&
                it["lto"]?.jsonPrimitive?.booleanOrNull == true && it["crossDso"]?.jsonPrimitive?.booleanOrNull == true
        } }) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_ACTUAL_CFI" }
        val functions = report.getValue("functions").jsonObject
        val required = setOf("msu_task_profile_trace_bind", "msu_task_profile_trace_open", "msu_task_profile_trace_close",
            "msu_task_profile_trace_ready", "msu_task_profile_sys_enter", "msu_task_profile_marker_entry", "msu_task_profile_marker_return")
        require(functions.keys == required) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_FUNCTIONS" }
        val signatures = functions.mapValues { (_, value) -> value.jsonObject.getValue("signature").jsonPrimitive.content.also {
            require(it.startsWith("*f") && it.length <= 65536) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_SIGNATURE" }
        }.removePrefix("*") }
        val entries = functions.mapValues { (_, value) -> UltraKernelManagerClaimPayload.CallbackPrefix(
            value.jsonObject.getValue("offset").jsonPrimitive.int, 0) }
        val tables = report.getValue("tables").jsonObject
        fun table(name: String, section: String, count: Int, writable: Boolean): Table {
            val item = tables.getValue(name).jsonObject
            val values = words(item["values"])
            require(item["section"]?.jsonPrimitive?.content == section && values.size == count &&
                item["bytes"]?.jsonPrimitive?.intOrNull == count * 4 &&
                item["writable"]?.jsonPrimitive?.booleanOrNull == writable &&
                item["sha256"]?.jsonPrimitive?.content == UltraKernelSpec.sha256(bytes(values))) {
                "ULTRA_KERNEL_TASK_PROFILE_MODULE_TABLE:$name"
            }
            if (writable) require(values.take(3) == listOf(if (count == 34) 0x4d535450 else 0x4d53544d, 1, count * 4) &&
                values.drop(3).all { it == 0 }) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_UNBOUND:$name" }
            return Table(name, section, item.getValue("offset").jsonPrimitive.int,
                item.getValue("fileOffset").jsonPrimitive.int, values, item.getValue("sha256").jsonPrimitive.content, writable)
        }
        val native = report.getValue("nativeAbi").jsonObject
        val alignment = report.getValue("nativeAlignment").jsonObject
        val values = listOf("listHeadBytes", "listHeadAlignment", "rwlockBytes", "rwlockAlignment")
            .map { native.getValue(it).jsonPrimitive.int }
        require(alignment["independentlyMeasuredFromBridgeBuild"]?.jsonPrimitive?.booleanOrNull == true &&
            words(alignment["values"]) == values && native.getValue("types").jsonObject.keys == setOf("list_head", "rwlock_t") &&
            report.getValue("nativeTypes").jsonObject == JsonObject(native.getValue("types").jsonObject.mapValues {
                it.value.jsonObject.getValue("signature")
            })) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_NATIVE_ABI" }
        return Implementation(true, entries, signatures, source,
            table("msu_task_profile_trace_compiled_layout", ".msu.task_profile.trace-layout", 12, false),
            table("msu_task_profile_target", ".msu.task_profile.target", 34, true),
            table("msu_task_profile_marker_target", ".msu.task_profile.marker-target", 28, true),
            native, policy.getValue("normalizeIntegers").jsonPrimitive.boolean, report.getValue("legacyCfi").jsonObject,
            report["nativeLayout"]?.jsonObject?.also { layout ->
                require(layout["schema"]?.jsonPrimitive?.content == "maplesu-task-profile-native-layout/1" &&
                    layout["origin"]?.jsonPrimitive?.content == "module-dwarf-derived-btf" &&
                    layout["targetImageBtf"]?.jsonPrimitive?.booleanOrNull == false) {
                    "ULTRA_KERNEL_TASK_PROFILE_MODULE_NATIVE_LAYOUT_SOURCE"
                }
                for (key in listOf("moduleDebugSource", "derivedObject")) identity(report.getValue("provenance").jsonObject[key])
                require(layout.getValue("fields").jsonObject.isNotEmpty()) { "ULTRA_KERNEL_TASK_PROFILE_MODULE_NATIVE_LAYOUT_EMPTY" }
                for ((name, value) in layout.getValue("fields").jsonObject) {
                    val field = value.jsonObject
                    val offset = field.getValue("offsetBytes").jsonPrimitive.int
                    val width = field.getValue("widthBytes").jsonPrimitive.int
                    val owner = field.getValue("ownerSizeBytes").jsonPrimitive.int
                    require(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*\\.[A-Za-z_][A-Za-z_0-9.]*")) &&
                        owner in 1..1048576 && width > 0 && offset >= 0 && offset.toLong() + width <= owner &&
                        (!name.endsWith(".__size") || offset == 0 && width == owner) &&
                        (field["signature"]?.jsonPrimitive?.content?.let { it.isNotEmpty() && it.length <= 65536 } ?: name.endsWith(".__size"))) {
                        "ULTRA_KERNEL_TASK_PROFILE_MODULE_NATIVE_LAYOUT_FIELD:$name"
                    }
                }
            })
    }
    fun parseImplementation(report: JsonObject, parent: JsonObject, reference: JsonObject,
        coreInputs: Map<String, JsonObject>, elf: ByteArray, mode: String, normalize: Boolean): Implementation {
        require(report["schema"]?.jsonPrimitive?.content == "maplesu-task-profile-implementation/1" &&
            report["metadataComplete"]?.jsonPrimitive?.booleanOrNull == true &&
            report["targetQualified"]?.jsonPrimitive?.booleanOrNull == false &&
            report["deviceQualified"]?.jsonPrimitive?.booleanOrNull == false &&
            report["qualificationStage"]?.jsonPrimitive?.content == "per-input-image-binding" &&
            report["targetProofSchema"]?.jsonPrimitive?.content == PROOF_SCHEMA &&
            report["slot63Contract"]?.jsonPrimitive?.content == SLOT_CONTRACT &&
            report["present"] == parent["implementationPresent"] &&
            report["sourceSelectionIdentity"] == parent["sourceSelectionIdentity"]) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_SCHEMA"
        }
        val successor = report.getValue("sourceSuccessor").jsonObject
        val currentSources = successor["schema"]?.jsonPrimitive?.content == "maplesu-claim-current-source-selection/1"
        if (currentSources) {
            val core = reference.getValue("coreLifecycleAbi").jsonObject.getValue("sourceSelection").jsonObject
            val freeze = reference.getValue("managementAbi").jsonObject.getValue("upstreamSourceFreeze").jsonObject
            require(successor.keys == setOf("schema", "identity", "targetQualified", "parentRuntimeLayoutSelection", "files") &&
                successor["targetQualified"]?.jsonPrimitive?.booleanOrNull == false &&
                core == freeze && core["schema"] == successor["schema"] &&
                core["sourceOwnerFrozen"]?.jsonPrimitive?.booleanOrNull == true &&
                core["targetQualified"]?.jsonPrimitive?.booleanOrNull == false &&
                core["rootKeyRequired"]?.jsonPrimitive?.booleanOrNull == false &&
                successor["identity"] == core["identity"] && successor["identity"] == parent["sourceSelectionIdentity"] &&
                successor["identity"] == report["sourceSuccessorIdentity"] &&
                successor["parentRuntimeLayoutSelection"] == core["identity"]) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_CURRENT_SELECTION"
            }
            identity(successor["identity"])
        } else {
            require(successor["schema"]?.jsonPrimitive?.content == "maplesu-task-profile-source-successor/1" &&
                successor["productionWritten"]?.jsonPrimitive?.booleanOrNull == false &&
                successor["sourceOwnerFrozen"]?.jsonPrimitive?.booleanOrNull == true &&
                same(identity(successor["identity"]), identity(report["sourceSuccessorIdentity"])) &&
                same(identity(successor["parentCoreSelection"]), identity(parent["sourceSelectionIdentity"]))) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_SUCCESSOR"
            }
            identity(successor["sourceFreeze"])
            val runtime = reference.getValue("managementAbi").jsonObject.getValue("upstreamSourceFreeze")
                .jsonObject.getValue("runtimeLayoutEvolution").jsonObject
            require(same(identity(successor["parentRuntimeLayoutSelection"]), identity(runtime["identity"]))) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_RUNTIME_PARENT"
            }
        }
        val rows = successor.getValue("files").jsonArray.map { it.jsonObject }
        val selected = rows.associateBy { it.getValue("logicalPath").jsonPrimitive.content }
        val prefix = "runtime/ultra/identity/kernel/"
        require(rows.size == selected.size && selected.keys == setOf("manager_task_state.c", "manager_task_state.h",
            "manager_su_runtime_target.c", "manager_su_runtime_factory.c").map { prefix + it }.toSet()) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_SOURCE_SET"
        }
        for ((logical, row) in selected) {
            if (currentSources) {
                val after = identity(row["after"])
                val core = coreInputs.getValue(logical)
                require(row.keys == setOf("role", "logicalPath", "before", "after", "snapshotIdentity") &&
                    row["role"]?.jsonPrimitive?.content == "native" && row["before"] is JsonNull &&
                    row["after"] == row["snapshotIdentity"] &&
                    after.getValue("path").jsonPrimitive.content.replace('\\', '/').endsWith("/$logical") &&
                    same(after, identity(core["after"])) && core["role"]?.jsonPrimitive?.content == "native") {
                    "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_CURRENT_SOURCE:$logical"
                }
            } else {
                val before = identity(row["before"]); val after = identity(row["after"])
                require(after.getValue("path").jsonPrimitive.content.replace('\\', '/').endsWith("/" + logical.substringAfterLast('/'))) {
                    "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_SOURCE_PATH:$logical"
                }
                coreInputs[logical]?.let { core ->
                    require(same(after, identity(core["nativeCopyIdentity"] ?: core["after"])) &&
                        same(before, identity(core["previousTaskProfileInput"]))) {
                        "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_CORE_CHAIN:$logical"
                    }
                }
            }
        }

        val objects = report.getValue("objects").jsonObject
        require(objects.keys == setOf("manager_task_state", "manager_su_runtime_target", "manager_su_runtime_factory")) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_OBJECT_SET"
        }
        for ((name, value) in objects) {
            val item = value.jsonObject; val logical = prefix + name + ".c"
            require(item["logicalPath"]?.jsonPrimitive?.content == logical &&
                same(identity(item["sourceIdentity"]), identity(selected.getValue(logical)["after"])) &&
                same(identity(item["configIdentity"]), identity(parent["configIdentity"]))) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_OBJECT_SOURCE:$name"
            }
            for (key in listOf("objectIdentity", "commandIdentity", "dependencyReceiptIdentity")) identity(item[key])
            val dependencies = item.getValue("dependencies").jsonArray.map(::dependencyIdentity)
            require(listOf(item["sourceIdentity"], parent["headerIdentity"], parent["configIdentity"]).all { dep ->
                dependencies.any { same(it, identity(dep)) } }) { "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_DEPENDENCIES:$name" }
            val policy = item.getValue("cfiPolicy").jsonObject
            require(policy["kcfiEnabled"]?.jsonPrimitive?.booleanOrNull == (mode == "kcfi") &&
                policy["normalizeIntegers"]?.jsonPrimitive?.booleanOrNull == normalize) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_CFI:$name"
            }
        }
        fun declaration(item: JsonObject, name: String) {
            require(item["method"]?.jsonPrimitive?.content == "same-object-BTF-VAR-ELF-relocation" &&
                item["bytes"]?.jsonPrimitive?.intOrNull == 8 &&
                (item["offset"]?.jsonPrimitive?.intOrNull ?: -1) >= 0 &&
                (item["btfTypeId"]?.jsonPrimitive?.intOrNull ?: 0) > 0) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_DECLARATION:$name"
            }
            for (key in listOf("sourceIdentity", "objectIdentity", "commandIdentity", "dependencyReceiptIdentity")) identity(item[key])
            val reloc = item.getValue("relocations").jsonArray.single().jsonObject
            require(reloc["symbol"]?.jsonPrimitive?.content == name && reloc["type"]?.jsonPrimitive?.intOrNull == 257 &&
                reloc["addend"]?.jsonPrimitive?.longOrNull == 0L) { "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_DECLARATION_RELOCATION:$name" }
        }
        val functions = report.getValue("functions").jsonObject
        val required = setOf("msu_task_profile_trace_bind", "msu_task_profile_trace_open", "msu_task_profile_trace_close",
            "msu_task_profile_trace_ready", "msu_task_profile_sys_enter", "msu_task_profile_marker_entry", "msu_task_profile_marker_return")
        require(functions.keys == required) { "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_FUNCTION_SET" }
        val signatures = linkedMapOf<String, String>()
        val entries = functions.mapValues { (name, value) ->
            val item = value.jsonObject; val signature = item.getValue("signature").jsonPrimitive.content
            require(signature.startsWith("*f") && signature.length <= 65536 &&
                item["signature"] == item["declarationSignature"] &&
                objects.getValue("manager_task_state").jsonObject.all { (key, expected) -> item[key] == expected }) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_FUNCTION_TYPE:$name"
            }
            declaration(item.getValue("declaration").jsonObject, name)
            require(item.getValue("declaration").jsonObject["signature"] == item["signature"]) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_DECLARED_TYPE:$name"
            }
            val typeid = if (mode != "kcfi") 0 else item.getValue("typeid").jsonPrimitive.long.also {
                require(it in 0..0xffffffffL && item["typeid"] == item["declarationTypeid"]) {
                    "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_FUNCTION_CFI:$name"
                }
            }.toInt()
            signatures[name] = signature.removePrefix("*")
            UltraKernelManagerClaimPayload.CallbackPrefix(item.getValue("entryOffset").jsonPrimitive.int, typeid)
        }
        declaration(report.getValue("callbackRegistrationReference").jsonObject, "msu_task_profile_sys_enter")
        val registrations = report.getValue("callbackRegistrationReferences").jsonObject
        require(registrations.keys == setOf("msu_task_profile_sys_enter", "msu_task_profile_marker_entry", "msu_task_profile_marker_return")) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_CALLBACK_SET"
        }
        for ((name, value) in registrations) {
            declaration(value.jsonObject, name)
            require(value.jsonObject["signature"]?.jsonPrimitive?.content == "*" + signatures.getValue(name)) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_CALLBACK_TYPE:$name"
            }
        }
        val contracts = report.getValue("sourceContracts").jsonObject
        val requiredContracts = mapOf(
            "msu_task_profile_trace_bind" to setOf("lookup(\"__tracepoint_sys_enter\")", "mutex_lock", "mutex_unlock"),
            "msu_task_profile_trace_open" to setOf("msu_task_profile_target_ready()", "tracepoint_probe_register_prio", "msu_task_profile_sys_enter"),
            "msu_task_profile_trace_close" to setOf("smp_store_release", "tracepoint_probe_unregister", "tracepoint_synchronize_unregister"),
            "msu_task_profile_sys_enter" to setOf("msu_task_profile_trace_ready()", "test_bit(MSU_TASK_PROFILE_DISABLE_ROOT_BIT"),
            "msu_task_profile_apply_current" to setOf("test_and_set_bit", "rcu_read_lock()", "rcu_read_unlock()", "thread_head", "thread_node"))
        for ((name, expressions) in requiredContracts) {
            val contract = contracts.getValue(name).jsonObject
            require(contract["function"]?.jsonPrimitive?.content == name &&
                (contract["sourceBytes"]?.jsonPrimitive?.intOrNull ?: 0) > 0 &&
                contract["sourceSha256"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) == true &&
                contract.getValue("requiredExpressions").jsonArray.map { it.jsonPrimitive.content }.containsAll(expressions)) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_SOURCE_CONTRACT:$name"
            }
        }
        val nativeAbi = report.getValue("nativeAbi").jsonObject
        require(nativeAbi["schema"]?.jsonPrimitive?.content == "maplesu-task-profile-native-abi/1" &&
            nativeAbi["targetQualified"]?.jsonPrimitive?.booleanOrNull == false &&
            nativeAbi["deviceQualified"]?.jsonPrimitive?.booleanOrNull == false &&
            same(identity(nativeAbi["providerObjectIdentity"]), identity(parent["objectIdentity"])) &&
            same(identity(nativeAbi["providerCommandIdentity"]), identity(parent["commandIdentity"])) &&
            same(identity(nativeAbi["providerDependencyReceiptIdentity"]), identity(parent["dependencyReceiptIdentity"]))) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_NATIVE_ABI_SOURCE"
        }
        val alignment = nativeAbi.getValue("alignmentFacts").jsonObject
        val alignmentValues = words(alignment["values"])
        require(alignment["symbol"]?.jsonPrimitive?.content == "msu_task_profile_native_type_facts" &&
            alignmentValues == listOf(nativeAbi.getValue("listHeadBytes").jsonPrimitive.int,
                nativeAbi.getValue("listHeadAlignment").jsonPrimitive.int, nativeAbi.getValue("rwlockBytes").jsonPrimitive.int,
                nativeAbi.getValue("rwlockAlignment").jsonPrimitive.int) &&
            nativeAbi["listHeadType"]?.jsonPrimitive?.content == "list_head" &&
            nativeAbi["rwlockType"]?.jsonPrimitive?.content == "rwlock_t") {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_NATIVE_ALIGNMENT"
        }
        for (key in listOf("sourceIdentity", "objectIdentity", "commandIdentity", "dependencyReceiptIdentity")) identity(alignment[key])
        val nativeTypes = nativeAbi.getValue("types").jsonObject
        require(nativeTypes.keys.containsAll(setOf("list_head", "rwlock_t")) &&
            alignment.getValue("typeSignatures").jsonObject.all { (name, value) -> nativeTypes[name]?.jsonObject?.get("signature") == value } &&
            nativeAbi.getValue("lockConfig").jsonObject.keys == setOf("CONFIG_SMP", "CONFIG_QUEUED_RWLOCKS", "CONFIG_PARAVIRT_SPINLOCKS",
                "CONFIG_DEBUG_SPINLOCK", "CONFIG_DEBUG_LOCK_ALLOC", "CONFIG_PREEMPT_RT") &&
            nativeAbi.getValue("lockConfig").jsonObject.values.all { it.jsonPrimitive.content in setOf("y", "n") }) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_NATIVE_TYPES"
        }
        val lifecycle = report.getValue("relocations").jsonObject.getValue("lifecycle").jsonObject
        for ((name, callers) in mapOf("msu_task_profile_trace_open" to setOf("msu_su_runtime_post_fs_data"),
            "msu_task_profile_trace_close" to setOf("msu_su_runtime_post_fs_data", "msu_su_runtime_close",
                "msu_su_runtime_revoke_install", "msu_su_runtime_discarded"))) {
            require(lifecycle.getValue(name).jsonArray.flatMap { it.jsonObject.getValue("callers").jsonArray }
                .map { it.jsonPrimitive.content }.containsAll(callers)) { "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_LIFECYCLE:$name" }
        }
        require(report.getValue("relocations").jsonObject.getValue("targetBind").jsonObject
            .getValue("msu_task_profile_trace_bind").jsonArray.isNotEmpty()) { "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_TARGET_BIND" }
        fun table(key: String, name: String, section: String, count: Int, writable: Boolean): Table {
            val item = report.getValue(key).jsonObject; val data = words(item["values"])
            val at = item.getValue("fileOffset").jsonPrimitive.int
            require(item["symbol"]?.jsonPrimitive?.content == name && item["section"]?.jsonPrimitive?.content == section &&
                item["bytes"]?.jsonPrimitive?.intOrNull == count * 4 && data.size == count &&
                item["writable"]?.jsonPrimitive?.booleanOrNull == writable && at >= 0 &&
                at.toLong() + count * 4 <= elf.size && elf.copyOfRange(at, at + count * 4).contentEquals(bytes(data)) &&
                item["sha256"]?.jsonPrimitive?.content == UltraKernelSpec.sha256(bytes(data))) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_TABLE:$name"
            }
            if (writable) require(data.take(3) == listOf(if (count == 34) 0x4d535450 else 0x4d53544d, 1, count * 4) &&
                data.drop(3).all { it == 0 } && item["initiallyUnbound"]?.jsonPrimitive?.booleanOrNull == true) {
                "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_UNBOUND:$name"
            }
            return Table(name, section, item.getValue("offset").jsonPrimitive.int, at, data,
                item.getValue("sha256").jsonPrimitive.content, writable)
        }
        val trace = table("traceLayout", "msu_task_profile_trace_compiled_layout", ".msu.task_profile.trace-layout", 12, false)
        val target = table("targetBinding", "msu_task_profile_target", ".msu.task_profile.target", 34, true)
        val marker = table("markerTargetBinding", "msu_task_profile_marker_target", ".msu.task_profile.marker-target", 28, true)
        val targetReport = report.getValue("targetBinding").jsonObject
        require(targetReport["requiredProofMask"]?.jsonPrimitive?.intOrNull == 31 &&
            targetReport["targetProofSchema"]?.jsonPrimitive?.content == PROOF_SCHEMA &&
            targetReport["slot63Contract"]?.jsonPrimitive?.content == SLOT_CONTRACT &&
            targetReport.getValue("proofBits").jsonObject.mapValues { it.value.jsonPrimitive.int } == proofBits) {
            "ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_PROOF_SCHEMA"
        }
        return Implementation(report.getValue("present").jsonPrimitive.boolean, entries, signatures,
            identity(report["sourceSuccessorIdentity"]).getValue("sha256").jsonPrimitive.content,
            trace, target, marker, nativeAbi, normalize)
    }

    fun targetBytes(proof: Proof): ByteArray {
        require(proof.complete && proof.workMember in 0..1 && proof.workBit in 0..62) {
            "ULTRA_KERNEL_TASK_PROFILE_TARGET_INCOMPLETE"
        }
        fun digest(value: String): ByteArray {
            require(value.matches(Regex("[0-9a-f]{64}"))) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_DIGEST" }
            return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        return ByteBuffer.allocate(136).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x4d535450); putInt(1); putInt(136); putInt(0); putInt(31); putInt(0)
            putInt(63); putInt(proof.workMember); putInt(proof.workBit); putInt(0)
            put(digest(proof.targetSha256)); put(digest(proof.configSha256)); put(digest(proof.proofSha256))
            putInt(12, 1)
        }.array()
    }

    fun markerBytes(proof: Proof): ByteArray {
        require(proof.complete && proof.markerWords.size == 28 &&
            proof.markerWords.take(4) == listOf(0x4d53544d, 1, 112, 1)) {
            "ULTRA_KERNEL_TASK_PROFILE_MARKER_INCOMPLETE"
        }
        return bytes(proof.markerWords)
    }

    private fun targetConfig(kernel: ByteArray): Pair<ByteArray, Map<String, String>> {
        fun marker(text: String): Int {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            return (0..kernel.size - bytes.size).filter { at -> bytes.indices.all { kernel[at + it] == bytes[it] } }
                .singleOrNull() ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_CONFIG_MARKER:$text")
        }
        val start = marker("IKCFG_ST") + 8; val end = marker("IKCFG_ED")
        require(end > start && end - start <= 1048576) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_CONFIG_RANGE" }
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(kernel, start, end - start)).use { input ->
            val block = ByteArray(8192)
            while (true) {
                val size = input.read(block)
                if (size < 0) break
                require(output.size() + size <= 4 * 1048576) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_CONFIG_SIZE" }
                output.write(block, 0, size)
            }
        }
        val bytes = output.toByteArray()
        val config = bytes.toString(Charsets.US_ASCII).lineSequence().filter { it.startsWith("CONFIG_") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return bytes to config
    }

    /** Unchecked trace transport has its own proof, separate from CFI membership. */
    fun legacyUncheckedTrace(impl: Implementation, name: String, member: JsonObject): JsonObject? {
        if (member["cfiMode"]?.jsonPrimitive?.content != "unchecked-native-callsite") return null
        val legacy = requireNotNull(impl.legacyCfi)
        val calls = legacy.getValue("callbackCallsites").jsonObject.getValue(name).jsonArray
        require(name == "msu_task_profile_sys_enter" && calls.size == 1 &&
            member["symbol"]?.jsonPrimitive?.content == name && "typeIdHex" !in member &&
            member["runtimeOwnerQualified"]?.jsonPrimitive?.booleanOrNull == false) {
            "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED:$name"
        }
        val call = calls.single().jsonObject
        require(call["caller"]?.jsonPrimitive?.content == "__traceiter_sys_enter" &&
            call["cfiMode"]?.jsonPrimitive?.content == "unchecked-native-callsite" && "typeIdHex" !in call &&
            legacy.getValue("callerTypeSets").jsonObject.getValue("__traceiter_sys_enter").jsonArray.isEmpty() &&
            listOf("runtimeOwnerQualified", "deviceQualified").all { call[it]?.jsonPrimitive?.booleanOrNull == false }) {
            "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED_CALLSITE:$name"
        }
        val signature = "*" + impl.signatures.getValue(name)
        when (member["proof"]?.jsonPrimitive?.content) {
            "actual-native-unchecked-trace-callsite-not-cfi-membership" -> require(
                member["callbackSignature"]?.jsonPrimitive?.content == signature &&
                    call["callbackSignature"]?.jsonPrimitive?.content == signature &&
                    call["proof"]?.jsonPrimitive?.content == "actual-symbol-bounded-uninstrumented-trace-loop-and-btf") {
                "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED_SIGNATURE:$name"
            }
            "actual-native-unchecked-trace-machine-abi-not-semantic-or-cfi-membership" -> {
                val arguments = signature.removePrefix("*fv(").removeSuffix(")").split(';')
                val abi = buildJsonObject {
                    put("pcs", "aapcs64"); put("argumentRegisters", JsonArray(listOf("x0", "x1", "x2").map(::JsonPrimitive)))
                    put("argumentBytes", JsonArray(listOf(8, 8, 8).map(::JsonPrimitive)))
                    put("argumentSources", JsonArray(listOf("record-data", "iterator-x1", "iterator-x2").map(::JsonPrimitive)))
                    put("returnValueUsed", false)
                }
                require(member["moduleCallbackSignature"]?.jsonPrimitive?.content == signature &&
                    signature.startsWith("*fv(") && signature.endsWith(";)") && arguments.size == 4 && arguments.last().isEmpty() &&
                    arguments.dropLast(1).all { it.matches(Regex("(?:\\*+(?:v|s[A-Za-z_][A-Za-z_0-9]*|i[1-8]:[0-9]+)|i8:[0-9]+)")) } &&
                    member["callbackMachineAbi"] == abi && call["callbackMachineAbi"] == abi &&
                    listOf(member, call).all { fact -> fact["machineAbiQualified"]?.jsonPrimitive?.booleanOrNull == true &&
                        listOf("targetSemanticSignatureQualified", "targetCfiTypeIdQualified").all {
                            fact[it]?.jsonPrimitive?.booleanOrNull == false
                        } } && call["btfAvailable"]?.jsonPrimitive?.booleanOrNull == false &&
                    call["registrationWriter"] is JsonObject &&
                    call["proof"]?.jsonPrimitive?.content == "actual-symbol-bounded-uninstrumented-trace-loop-and-registration-writer") {
                    "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED_MACHINE_ABI:$name"
                }
            }
            else -> error("ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED_PROOF:$name")
        }
        return call
    }

    /** Replay the current Image's bounded native iterator, including both loop edges.
     * Layout may come from its BTF or the module actually accepted by the normal loader. */
    private fun verifyUncheckedTrace(kernel: ByteArray, symbols: UltraKernelSymbols, layout: UltraKernelLayout): JsonObject {
        fun need(ok: Boolean, reason: String) = require(ok) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_UNCHECKED_TRACE:$reason" }
        fun unique(name: String) = symbols.offsetsOf(name).distinct().singleOrNull()
            ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_UNCHECKED_TRACE:SYMBOL:$name")
        fun field(name: String) = layout.fields[name]
            ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_UNCHECKED_TRACE:FIELD:$name")
        fun word(at: Int) = UltraKernelA64.le32Bits(kernel, at)
        fun move(to: Int, from: Int) = 0xaa0003e0.toInt() or (from shl 16) or to
        fun branch(at: Int) = at + UltraKernelA64.signExtend(((word(at) ushr 5) and 0x7ffff).toLong(), 19).toInt() * 4
        fun load(at: Int, to: Int, from: Int, offset: Int): Boolean {
            val raw = word(at)
            return (offset == 0 && (raw == (0xc8dffc00.toInt() or (from shl 5) or to) ||
                raw == (0xf8bfc000.toInt() or (from shl 5) or to))) ||
                offset >= 0 && offset % 8 == 0 && offset / 8 <= 4095 &&
                raw == (0xf9400000.toInt() or ((offset / 8) shl 10) or (from shl 5) or to)
        }
        val start = unique("__traceiter_sys_enter"); val end = symbols.nextOffset(start) ?: kernel.size
        val point = unique("__tracepoint_sys_enter"); val pointEnd = symbols.nextOffset(point) ?: kernel.size
        val raw = symbols as? UltraKernelKallsymsTable
            ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_UNCHECKED_TRACE:SYMBOL_SOURCE")
        need(start >= 0 && start % 4 == 0 && end <= kernel.size && end > start && end - start <= 65536 &&
            point >= 0 && point % 8 == 0 && point.toLong() + field("tracepoint.__size").widthBytes <= pointEnd &&
            pointEnd <= kernel.size, "BOUNDS")
        val iterator = field("tracepoint.iterator"); val funcs = field("tracepoint.funcs")
        need(iterator.widthBytes == 8 && funcs.widthBytes == 8 && iterator.offsetBytes != funcs.offsetBytes, "POINTER_FIELDS")
        val entry = ByteBuffer.wrap(kernel, point + iterator.offsetBytes, 8).order(ByteOrder.LITTLE_ENDIAN).long - raw.textBase
        need(entry == start.toLong() || entry == UltraKernelLegacyCfi.canonicalEntry(kernel, symbols, "__traceiter_sys_enter").canonicalOffset.toLong(), "ITERATOR")
        val address = point + funcs.offsetBytes
        val bases = (start until end - 4 step 4).filter { at ->
            val a = word(at); val b = word(at + 4); val register = a and 31
            if (!UltraKernelA64.isAdrp(a) || b and 0xffc00000.toInt() != 0x91000000.toInt() ||
                b and 31 != register || (b ushr 5) and 31 != register) false else {
                val immediate = (((a ushr 5) and 0x7ffff).toLong() shl 2) or ((a ushr 29) and 3).toLong()
                (at.toLong() and -4096L) + (UltraKernelA64.signExtend(immediate, 21) shl 12) +
                    ((b ushr 10) and 4095) == address.toLong()
            }
        }
        need(bases.size == 1, "FUNCS_ADDRESS")
        val first = bases.single(); val base = word(first) and 31
        need(first + 13 * 4 <= end, "LOOP_BOUNDS")
        val node = word(first + 8) and 31
        need(base in 3..17 && node in 19..28 && load(first + 8, node, base, 0) &&
            word(first + 12) and 0xff00001f.toInt() == (0xb4000000.toInt() or node), "LIST_LOAD")
        val saves = listOf(first + 16, first + 20).associate { at -> ((word(at) ushr 16) and 31) to (word(at) and 31) }
        need(saves.keys == setOf(1, 2) && (saves.values + node).distinct().size == 3 && saves.values.all { it in 19..28 } &&
            listOf(first + 16, first + 20).all { word(it) == move(word(it) and 31, (word(it) ushr 16) and 31) }, "ARGUMENT_SAVE")
        val callback = field("tracepoint_func.func"); val data = field("tracepoint_func.data")
        val stride = field("tracepoint_func.__size").widthBytes
        val callLoad = first + 24; val fn = word(callLoad) and 31
        need(fn in 3..17 && callback.widthBytes == 8 && data.widthBytes == 8 && stride in 16..256 && stride % 8 == 0, "RECORD")
        val pair = word(callLoad) and 0xffc00000.toInt() == 0xa9400000.toInt()
        val call: Int
        if (pair) {
            need((word(callLoad) ushr 5) and 31 == node && (word(callLoad) ushr 10) and 31 == 0 &&
                UltraKernelA64.signExtend(((word(callLoad) ushr 15) and 127).toLong(), 7) * 8 == callback.offsetBytes.toLong() &&
                data.offsetBytes == callback.offsetBytes + 8 && word(first + 28) == move(1, saves.getValue(1)) &&
                word(first + 32) == move(2, saves.getValue(2)), "PAIR_ARGUMENTS")
            call = first + 36
        } else {
            need(load(callLoad, fn, node, callback.offsetBytes) && word(first + 28) == move(1, saves.getValue(1)) &&
                load(first + 32, 0, node, data.offsetBytes) && word(first + 36) == move(2, saves.getValue(2)), "CALL_ARGUMENTS")
            call = first + 40
        }
        val advance = call + 4; val again = call + 8; val tail = call + 12
        need(tail < end && word(call) == (0xd63f0000.toInt() or (fn shl 5)) &&
            word(advance) == (0xf8400c00.toInt() or (stride shl 12) or (node shl 5) or fn) &&
            word(again) and 0xff00001f.toInt() == (0xb5000000.toInt() or fn) && branch(again) == callLoad &&
            branch(first + 12) == tail, "LOOP_EDGES")
        fun stackInstruction(value: Int, loading: Boolean): Boolean {
            val rn = (value ushr 5) and 31; val rt = value and 31; val rt2 = (value ushr 10) and 31
            val pairMemory = value and 0x3a000000 == 0x28000000 && value ushr 30 == 2 &&
                value and (1 shl 22) != 0 == loading && rn == 31 && rt in 19..30 && rt2 in 19..30
            val singleMemory = value and 0x3a000000 == 0x38000000 && value ushr 30 == 3 &&
                value and (1 shl 22) != 0 == loading && rn in setOf(18, 31) && rt in 19..30
            return pairMemory || singleMemory
        }
        for (at in start until first step 4) need(word(at) in setOf(0xd503233f.toInt(), 0xd503237f.toInt(),
            0xd503245f.toInt(), 0xd50324df.toInt(), UltraKernelA64.NOP, 0x910003fd.toInt()) ||
            stackInstruction(word(at), false), "PROLOGUE")
        var finish = tail
        while (finish < end && word(finish) != UltraKernelA64.RET && word(finish) !in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())) {
            need(word(finish) in setOf(0x2a1f03e0, 0xd50323bf.toInt(), 0xd50323ff.toInt(), UltraKernelA64.NOP) ||
                stackInstruction(word(finish), true), "EPILOGUE")
            finish += 4
        }
        need(finish < end, "RETURN")
        return buildJsonObject { put("caller", "__traceiter_sys_enter"); put("start", start); put("bytes", end - start)
            put("sha256", UltraKernelSpec.sha256(kernel.copyOfRange(start, end))); put("callOffset", call)
            put("funcsPointerOffset", address); put("recordBytes", stride); put("funcOffset", callback.offsetBytes)
            put("dataOffset", data.offsetBytes); put("machineAbiQualified", true); put("targetCfiTypeIdQualified", false) }
    }

    fun derive(kernel: ByteArray, symbols: UltraKernelSymbols,
        facts: UltraKernelManagerClaimPayload.TaskProfileFacts): Proof =
        derive(kernel, symbols, requireNotNull(facts.implementation), facts.cfiMode)

    /** Claim ELF and init_boot module consume the same five qualifications.
     * The verified normal loader may supply native module layout when Image BTF is absent. */
    fun derive(kernel: ByteArray, symbols: UltraKernelSymbols, impl: Implementation,
        cfiMode: String, normalModuleLoader: Boolean = false): Proof {
        require(cfiMode in setOf("none", "kcfi", "legacy-lto-cfi")) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_CFI_MODE" }
        require(symbols.imageSize == kernel.size && impl.present) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_IMPLEMENTATION" }
        val missing = linkedMapOf<String, String>()
        val evidence = linkedMapOf<String, JsonElement>()
        val sha = UltraKernelSpec.sha256(kernel)
        val configInput = targetConfig(kernel)
        val configSha = UltraKernelSpec.sha256(configInput.first)
        val config = configInput.second
        fun enabled(key: String) = config[key] == "y"
        fun proof(name: String, action: () -> JsonObject) {
            try { evidence[name] = action() }
            catch (error: Exception) { missing[name] = error.message ?: error.javaClass.simpleName }
        }
        fun json(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
        val needed = setOf("task_struct.__size", "task_struct.thread_info", "task_struct.tasks", "task_struct.thread_node",
            "thread_info.flags", "thread_info.syscall_work", "signal_struct.thread_head", "pt_regs.__size", "pt_regs.regs",
            "pt_regs.syscallno", "tracepoint.__size", "tracepoint.funcs", "tracepoint_func.__size", "tracepoint_func.func",
            "tracepoint_func.data", "kretprobe.__size", "kretprobe.handler", "kretprobe.entry_handler", "kretprobe.kp.addr",
            "kretprobe.maxactive", "kretprobe.nmissed", "tracepoint.iterator", "list_head.__size", "list_head.next", "list_head.prev")
        val imageBtf = symbols.offsetsOf("__start_BTF").isNotEmpty() || symbols.offsetsOf("__stop_BTF").isNotEmpty()
        val nativeLayout = if (!imageBtf && normalModuleLoader && cfiMode == "legacy-lto-cfi") impl.nativeLayout else null
        val layout = if (nativeLayout != null) UltraKernelLayout(nativeLayout.getValue("fields").jsonObject.mapValues { (_, value) ->
            val item = value.jsonObject
            UltraKernelLayoutField(item.getValue("offsetBytes").jsonPrimitive.int,
                item.getValue("widthBytes").jsonPrimitive.int, item.getValue("ownerSizeBytes").jsonPrimitive.int,
                "module-dwarf-derived-btf")
        }) else UltraKernelBtf.abi(kernel, symbols, needed, setOf("thread_info.syscall_work"))
            ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_BTF")
        fun field(name: String) = layout.fields[name] ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_FIELD:$name")
        fun signature(name: String): String? = if (nativeLayout != null)
            nativeLayout.getValue("fields").jsonObject[name]?.jsonObject?.get("signature")?.jsonPrimitive?.content
            else UltraKernelBtf.memberSignature(kernel, symbols, name.substringBefore('.'), name.substringAfter('.'))
        val taskInfo = field("task_struct.thread_info")
        val flags = field("thread_info.flags")
        val taskFlags = taskInfo.offsetBytes + flags.offsetBytes
        var workMember = -1; var workBit = -1
        var markerWords = emptyList<Int>()
        proof("slot63") {
            require(listOf("CONFIG_ARM64", "CONFIG_64BIT", "CONFIG_THREAD_INFO_IN_TASK").all(::enabled) &&
                flags.widthBytes == 8 && flags.offsetBytes % 8 == 0 && taskFlags % 8 == 0 &&
                taskInfo.widthBytes == flags.ownerSizeBytes &&
                signature("thread_info.flags") == "i8:64") {
                "ULTRA_KERNEL_TASK_PROFILE_TARGET_FLAGS64"
            }
            val explicit = UltraKernelBtf.enumValue(kernel, symbols, "TIF_KSU_DISABLE_ESCAPE_WITH_ROOT")
                ?: UltraKernelBtf.integerVariable(kernel, symbols, "TIF_KSU_DISABLE_ESCAPE_WITH_ROOT")
            require(explicit == null || explicit == 63L) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_KSU_BIT" }
            buildJsonObject { put("contract", SLOT_CONTRACT); put("rootBit", 63); put("flagsOffset", taskFlags)
                put("flagsBytes", flags.widthBytes); put("globalUnusedBitProof", false)
                explicit?.let { put("explicitTargetBit", it) } }
        }
        proof("rcu") {
            val head = field("signal_struct.thread_head"); val node = field("task_struct.thread_node")
            require(taskInfo.widthBytes == flags.ownerSizeBytes && head.widthBytes == 16 && node.widthBytes == 16 &&
                field("list_head.__size").widthBytes == 16 && field("list_head.next").offsetBytes == 0 &&
                field("list_head.prev").offsetBytes == 8 && listOf("list_head.next", "list_head.prev").all { field(it).widthBytes == 8 } &&
                head.offsetBytes % 8 == 0 && node.offsetBytes % 8 == 0) {
                "ULTRA_KERNEL_TASK_PROFILE_TARGET_RCU_EMBEDDED_LIST"
            }
            buildJsonObject { put("embeddedThreadInfo", true); put("threadHeadOffset", head.offsetBytes)
                put("threadNodeOffset", node.offsetBytes); put("implementationSourceSha256", impl.sourceSha256) }
        }
        proof("bitops") {
            val abi = impl.nativeAbi
            val types = abi.getValue("types").jsonObject
            val expected = types.mapValues { it.value.jsonObject.getValue("signature").jsonPrimitive.content }
            require(types.keys.containsAll(setOf("list_head", "rwlock_t")) &&
                (nativeLayout != null || UltraKernelBtf.typeSignatures(kernel, symbols, expected.keys) == expected) &&
                abi["listHeadBytes"]?.jsonPrimitive?.intOrNull == 16 &&
                abi["listHeadAlignment"]?.jsonPrimitive?.intOrNull == 8 &&
                abi["rwlockBytes"] == types.getValue("rwlock_t").jsonObject["bytes"]) {
                "ULTRA_KERNEL_TASK_PROFILE_TARGET_NATIVE_LOCK_ABI"
            }
            val alignment = abi.getValue("rwlockAlignment").jsonPrimitive.int
            require(alignment in 1..4096 && alignment and (alignment - 1) == 0 &&
                (symbols.memoryOffsetOf("tasklist_lock") ?: -1).let { it >= 0 && it % alignment == 0 } &&
                abi.getValue("lockConfig").jsonObject.all { (key, value) -> value.jsonPrimitive.content == (config[key] ?: "n") } &&
                !enabled("CONFIG_PREEMPT_RT")) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_NATIVE_LOCK_CONFIG" }
            val work = field(if (enabled("CONFIG_GENERIC_ENTRY")) "thread_info.syscall_work" else "thread_info.flags")
            require(flags.widthBytes == 8 && work.widthBytes == 8 && work.offsetBytes % 8 == 0) {
                "ULTRA_KERNEL_TASK_PROFILE_TARGET_ATOMIC_WORD"
            }
            buildJsonObject { put("wordBits", 64); put("nativeAbiSha256", UltraKernelSpec.sha256(abi.toString().toByteArray()))
                put("implementationSourceSha256", impl.sourceSha256) }
        }
        proof("cfi") {
            var uncheckedTrace: JsonObject? = null
            if (cfiMode == "legacy-lto-cfi") {
                require(UltraKernelLegacyModuleRoute.required(kernel) &&
                    impl.normalizeIntegers == enabled("CONFIG_CFI_ICALL_NORMALIZE_INTEGERS")) {
                    "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_MODE"
                }
                val legacy = requireNotNull(impl.legacyCfi) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_PROOF" }
                require(legacy["schema"]?.jsonPrimitive?.content == "maplesu-task-profile-legacy-cfi/1" &&
                    legacy["relocationOwner"]?.jsonPrimitive?.content == "normal-kernel-module-loader" &&
                    listOf("moduleLoaded", "runtimeOwnerQualified", "deviceQualified").all {
                        legacy[it]?.jsonPrimitive?.booleanOrNull == false
                    }) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_OWNER" }
                val callers = legacy.getValue("callerTypeSets").jsonObject
                require(callers.isNotEmpty()) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_CALLERS" }
                val members = legacy.getValue("members").jsonObject
                val callsites = legacy.getValue("callbackCallsites").jsonObject
                val required = setOf("msu_task_profile_sys_enter", "msu_task_profile_marker_entry", "msu_task_profile_marker_return")
                require(members.keys == required && callsites.keys == required) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_CALLBACKS" }
                val unchecked = legacyUncheckedTrace(impl, "msu_task_profile_sys_enter", members.getValue("msu_task_profile_sys_enter").jsonObject)
                for ((caller, types) in callers) {
                    val ids = types.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    require((ids.isNotEmpty() || caller == "__traceiter_sys_enter" && unchecked != null) &&
                        ids.all { it.matches(Regex("[0-9a-f]{16}")) } &&
                        UltraKernelLegacyCfi.slowpathCallsites(kernel, symbols, caller).map { it.typeIdHex }.toSet() == ids) {
                        "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_CALLER:$caller"
                    }
                }
                for ((name, element) in members) {
                    val member = element.jsonObject
                    if (legacyUncheckedTrace(impl, name, member) != null) {
                        uncheckedTrace = verifyUncheckedTrace(kernel, symbols, layout)
                        if (imageBtf) require(listOf("__bpf_trace_sys_enter", "trace_event_raw_event_sys_enter").all {
                            UltraKernelBtf.functionSignature(kernel, symbols, it) == impl.signatures.getValue(name)
                        }) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_TRACE_SIGNATURE" }
                        continue
                    }
                    val id = member.getValue("typeIdHex").jsonPrimitive.content
                    require(id.matches(Regex("[0-9a-f]{16}")) && member["symbol"]?.jsonPrimitive?.content == name &&
                        member["proof"]?.jsonPrimitive?.content == "actual-relocated-module-checker-path" &&
                        member["runtimeOwnerQualified"]?.jsonPrimitive?.booleanOrNull == false &&
                        callsites.getValue(name).jsonArray.any { call -> call.jsonObject.let {
                            it["typeIdHex"]?.jsonPrimitive?.content == id &&
                                id in callers.getValue(it.getValue("caller").jsonPrimitive.content).jsonArray.map { value -> value.jsonPrimitive.content }
                        } }) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_TYPE64:$name" }
                }
                for ((member, callback) in mapOf("handler" to "msu_task_profile_marker_return", "entry_handler" to "msu_task_profile_marker_entry"))
                    require(signature("kretprobe.$member") == "*" + impl.signatures.getValue(callback)) {
                        "ULTRA_KERNEL_TASK_PROFILE_TARGET_LEGACY_MARKER_TYPE:$member"
                    }
            } else UltraKernelResidentAbi.verifyCfi(kernel, cfiMode, impl.normalizeIntegers)
            if (cfiMode == "kcfi") {
                val required = impl.entries.filterKeys { it in setOf("msu_task_profile_sys_enter",
                    "msu_task_profile_marker_entry", "msu_task_profile_marker_return") }
                val target = UltraKernelBtf.functionTypeids(kernel, symbols, required.keys.map { impl.signatures.getValue(it) }.toSet())
                require(required.all { (name, entry) -> target[impl.signatures.getValue(name)] == setOf(entry.typeid) }) {
                    "ULTRA_KERNEL_TASK_PROFILE_TARGET_CALLBACK_KCFI"
                }
            }
            buildJsonObject { put("mode", cfiMode); put("normalizeIntegers", impl.normalizeIntegers)
                put("callbackCount", 3)
                uncheckedTrace?.let { put("uncheckedTrace", it) }
                impl.legacyCfi?.let { put("legacyModuleChecker", it) } }
        }
        proof("syscallTracepoint") {
            require(enabled("CONFIG_HAVE_SYSCALL_TRACEPOINTS") && enabled("CONFIG_KRETPROBES") &&
                !enabled("CONFIG_PREEMPT_RT")) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_TRACE_CONFIG" }
            val trace = impl.trace.words
            val regs = field("pt_regs.__size"); val args = field("pt_regs.regs"); val number = field("pt_regs.syscallno")
            require(trace.size == 12 && trace[0] == 1 && trace[2] == 1 && trace[3] == regs.widthBytes &&
                trace[5] == args.offsetBytes && trace[6] == 8 && trace[7] == number.offsetBytes && trace[8] == number.widthBytes &&
                trace[11] == 0) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_REGS_LAYOUT" }
            val rawSymbols = symbols as? UltraKernelKallsymsTable
                ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_SYSCALL_SYMBOL_SOURCE")
            val syscallTable = symbols.offsetsOf("sys_call_table").singleOrNull()
                ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_SYSCALL_TABLE")
            val syscallEnd = symbols.nextOffset(syscallTable)
                ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_SYSCALL_TABLE_END")
            require(syscallTable >= 0 && syscallTable % 8 == 0 && syscallEnd <= kernel.size &&
                syscallEnd > syscallTable && syscallEnd - syscallTable <= 8192 &&
                trace[9] in 0 until (syscallEnd - syscallTable) / 8 && trace[10] in 0 until (syscallEnd - syscallTable) / 8) {
                "ULTRA_KERNEL_TASK_PROFILE_TARGET_SYSCALL_TABLE_RANGE"
            }
            for ((nr, function) in listOf(trace[9] to "__arm64_sys_execve", trace[10] to "__arm64_sys_execveat")) {
                val entry = if (cfiMode == "legacy-lto-cfi")
                    UltraKernelLegacyCfi.canonicalEntry(kernel, symbols, function).canonicalOffset
                    else symbols.offsetsOf(function).singleOrNull()
                        ?: error("ULTRA_KERNEL_TASK_PROFILE_TARGET_SYSCALL_FUNCTION:$function")
                val pointer = ByteBuffer.wrap(kernel, syscallTable + nr * 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
                require(pointer == rawSymbols.textBase + entry) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_SYSCALL_NUMBER:$function" }
            }
            val generic = enabled("CONFIG_GENERIC_ENTRY")
            val member = field(if (generic) "thread_info.syscall_work" else "thread_info.flags")
            val branch = UltraKernelTaskProfileCode.workBit(kernel, symbols, taskInfo.offsetBytes + member.offsetBytes,
                generic, field("tracepoint.funcs").offsetBytes)
            workMember = branch.member; workBit = branch.bit
            val abi = impl.nativeAbi; val rwBytes = abi.getValue("rwlockBytes").jsonPrimitive.int
            val rwAlign = abi.getValue("rwlockAlignment").jsonPrimitive.int
            val tasks = field("task_struct.tasks"); val funcs = field("tracepoint.funcs")
            val callback = field("tracepoint_func.func"); val data = field("tracepoint_func.data")
            val handler = field("kretprobe.handler"); val entry = field("kretprobe.entry_handler"); val address = field("kretprobe.kp.addr")
            val maxactive = field("kretprobe.maxactive"); val missed = field("kretprobe.nmissed")
            require(tasks.widthBytes == 16 && listOf(funcs, callback, data, handler, entry, address).all { it.widthBytes == 8 && it.offsetBytes % 8 == 0 } &&
                maxactive.widthBytes == 4 && missed.widthBytes == 4) { "ULTRA_KERNEL_TASK_PROFILE_TARGET_MARKER_FIELDS" }
            markerWords = listOf(0x4d53544d, 1, 112, 1, field("task_struct.__size").widthBytes, tasks.offsetBytes, tasks.widthBytes,
                field("tracepoint.__size").widthBytes, funcs.offsetBytes, funcs.widthBytes,
                field("tracepoint_func.__size").widthBytes, callback.offsetBytes, callback.widthBytes, data.offsetBytes, data.widthBytes,
                field("kretprobe.__size").widthBytes, handler.offsetBytes, handler.widthBytes, address.offsetBytes, address.widthBytes,
                maxactive.offsetBytes, maxactive.widthBytes, missed.offsetBytes, missed.widthBytes,
                rwBytes, rwAlign, entry.offsetBytes, entry.widthBytes)
            buildJsonObject { put("workMember", if (generic) "syscall_work" else "flags"); put("workBit", workBit)
                put("dispatcher", branch.function); put("branchOffset", branch.branch); put("traceReference", branch.traceReference)
                put("functionSha256", branch.functionSha256); put("registrationPendingRuntimeOpen", true) }
        }
        // Clone/exec keep the fixed KernelSU native lifecycle; no extra Image CFG qualification.
        val document = buildJsonObject { put("schema", PROOF_SCHEMA); put("slot63Contract", SLOT_CONTRACT)
            put("targetSha256", sha); put("configSha256", configSha); put("implementationSourceSha256", impl.sourceSha256)
            put("layoutOrigin", if (nativeLayout != null) "module-dwarf-derived-btf" else "target-image-btf")
            put("targetImageBtf", imageBtf); put("normalModuleLoader", normalModuleLoader)
            nativeLayout?.let { put("nativeLayoutSha256", UltraKernelSpec.sha256(it.toString().toByteArray(Charsets.UTF_8))) }
            put("proofs", JsonObject(evidence)); put("missing", json(missing)) }
        return Proof(sha, configSha, UltraKernelSpec.sha256(document.toString().toByteArray(Charsets.UTF_8)),
            workMember, workBit, markerWords, document, missing)
    }
}
