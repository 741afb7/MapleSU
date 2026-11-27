package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.AvbFooter
import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.RamdiskInspector
import kotlinx.serialization.json.*
import java.io.File
import kotlin.system.exitProcess

/** Host regression of the production file builder; no Android service, SDK session or partition I/O. */
fun main(args: Array<String>) {
    require(args.size % 2 == 0) { "IMAGE_VERIFY_ARGUMENTS" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    val output = File(requireNotNull(options["--out"]))
    check(!output.exists() && output.mkdirs()) { "IMAGE_VERIFY_OUTPUT_EXISTS" }
    var stage = "INPUT"
    var status = "FAIL"
    var failure: Throwable? = null
    val evidence = linkedMapOf<String, JsonElement>()
    fun record(name: String, file: File) {
        val report = BootImageInspector.inspect(file)
        val footer = AvbFooter.parse(file)
        check(footer.valid(file.length()) && (!footer.present || footer.reservedZero)) {
            "IMAGE_VERIFY_AVB_GEOMETRY"
        }
        if (report.ramdiskSize > 0) check(!RamdiskInspector.inspect(file).malformed) {
            "IMAGE_VERIFY_RAMDISK_INVALID"
        }
        evidence[name] = buildJsonObject {
            put("path", file.canonicalPath)
            put("container", Json.parseToJsonElement(report.toJson()))
            put("avbFooter", Json.parseToJsonElement(footer.toJson()))
            put("avbGeometryValid", true)
            put("avbSignatureVerified", false)
        }
    }
    try {
        val input = File(requireNotNull(options["--input"])).canonicalFile
        val expected = requireNotNull(options["--input-sha256"])
        check(expected.matches(Regex("[0-9a-f]{64}")) && UltraKernelSpec.sha256(input) == expected) {
            "IMAGE_VERIFY_INPUT_CHANGED"
        }
        val magic = input.inputStream().use { it.readNBytes(64) }
        if (!magic.take(8).toByteArray().contentEquals("ANDROID!".toByteArray())) {
            evidence["inputKind"] = JsonPrimitive(if (magic.size >= 60 &&
                magic.copyOfRange(56, 60).contentEquals("ARMd".toByteArray())) "raw" else "unknown")
            status = "UNSUPPORTED"
            error("IMAGE_VERIFY_BOOT_CONTAINER_REQUIRED")
        }
        val layout = BootImageInspector.inspect(input)
        val kind = if (layout.kernelSize == 0L) "init_boot" else "boot"
        evidence["inputKind"] = JsonPrimitive(kind)
        record("input", input)
        stage = "ASSETS"
        val assetsRoot = File(requireNotNull(options["--assets"])).canonicalFile
        check(assetsRoot.isDirectory) { "IMAGE_VERIFY_ASSETS_MISSING" }
        val assets = UltraKernelFileAssets(assetsRoot)
        val open: (String) -> java.io.InputStream = { File(assetsRoot, it).inputStream() }
        val kmi = options["--kmi"]
        if (kind == "init_boot" && kmi == null) {
            status = "UNSUPPORTED"
            error("IMAGE_VERIFY_TARGET_KMI_UNPROVEN")
        }
        val artifact = if (kind == "init_boot") {
            stage = "COMPONENT_SELECTION"
            UltraInitBootCatalog.resolveKmi(File(output, "component-assets"), open, requireNotNull(kmi))
                ?: run { status = "UNSUPPORTED"; error("IMAGE_VERIFY_COMPONENT_ABSENT") }
        } else null
        fun flow(name: String) = UltraKernelFlow(File(output, name), assets,
            managerClaimAssets = assets, moduleArtifact = { source ->
                UltraInitBootCatalog.resolve(File(output, "$name-exact-assets"), open, source, assets)
            })
        stage = "PATCH"
        val firstFlow = flow("first-build")
        val firstPreparation = firstFlow.prepareTarget(input, null, artifact)
        val first = firstFlow.candidateForInstall(input, firstPreparation)
        check(first.partition == kind && first.baseBootSha256 == expected &&
            first.bootSha256 == UltraKernelSpec.sha256(first.image) && first.bootSha256 != expected) {
            "IMAGE_VERIFY_FIRST_CANDIDATE"
        }
        evidence["productionRoute"] = JsonPrimitive(firstPreparation.planOrigin)
        record("patched", first.image)
        val emptyReceipts = File(output, "empty-receipts").apply { check(mkdir()) }
        fun restore(name: String, candidate: File): UltraKernelOriginalRecovery.Recovered {
            val restored = requireNotNull(UltraKernelOriginalRecovery(assets).recover(candidate,
                emptyReceipts, emptyList(), File(output, name), generatedOnly = true)) {
                "IMAGE_VERIFY_IMAGE_BOUND_RECOVERY_MISSING"
            }
            check(restored.image.length() == input.length() && UltraKernelSpec.sha256(restored.image) == expected) {
                "IMAGE_VERIFY_EXACT_RESTORE_MISMATCH"
            }
            record(name, restored.image)
            return restored
        }
        stage = "FIRST_RESTORE"
        val restoredFirst = restore("restored-first", first.image)
        val namespace = requireNotNull(restoredFirst.deploymentNamespace) { "IMAGE_VERIFY_NAMESPACE_MISSING" }
        check(UltraKernelDeploymentNamespace.decode(namespace.record()).record().contentEquals(namespace.record())) {
            "IMAGE_VERIFY_NAMESPACE_INVALID"
        }
        stage = "REPATCH"
        // A new Flow and empty receipt directory prove image-owned identity, not retained process state.
        val repeatFlow = flow("repeat-build")
        val repeatPreparation = repeatFlow.prepareTarget(first.image, null, artifact)
        val repeated = repeatFlow.candidateForInstall(first.image, repeatPreparation)
        check(repeated.partition == kind && repeated.bootSha256 == first.bootSha256 &&
            UltraKernelSpec.sha256(repeated.image) == first.bootSha256 &&
            UltraKernelSpec.sha256(first.image) == first.bootSha256) { "IMAGE_VERIFY_REPATCH_NOT_IDEMPOTENT" }
        record("repatched", repeated.image)
        stage = "REPATCH_RESTORE"
        val restoredRepeat = restore("restored-repeat", repeated.image)
        check(restoredRepeat.deploymentNamespace?.record()?.contentEquals(namespace.record()) == true) {
            "IMAGE_VERIFY_NAMESPACE_CHANGED"
        }
        stage = "FINAL_INPUT_CHECK"
        check(UltraKernelSpec.sha256(input) == expected) { "IMAGE_VERIFY_INPUT_CHANGED" }
        evidence["namespaceSha256"] = JsonPrimitive(UltraKernelSpec.sha256(namespace.record()))
        evidence["repatchByteIdentical"] = JsonPrimitive(true)
        evidence["imageBoundExactRecovery"] = JsonPrimitive(true)
        evidence["sourceUnchanged"] = JsonPrimitive(true)
        status = "PASS"
        stage = "COMPLETE"
    } catch (error: Throwable) {
        failure = error
    }
    val result = buildJsonObject {
        put("schema", "maplesu-production-image-regression/1")
        put("scope", "HOST_PRODUCTION_FILE_ALGORITHMS")
        put("status", status)
        put("stage", stage)
        failure?.let {
            put("errorType", it.javaClass.name)
            put("error", it.message.orEmpty().take(2048))
        }
        put("evidence", JsonObject(evidence))
        put("sdkExecuted", false)
        put("partitionWrites", false)
        put("deviceExecuted", false)
        put("deviceBootVerified", false)
    }
    File(output, "RESULT.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result) + "\n")
    println("IMAGE_VERIFY_$status stage=$stage")
    exitProcess(if (status == "PASS") 0 else if (status == "UNSUPPORTED") 2 else 3)
}
