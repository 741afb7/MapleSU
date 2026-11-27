package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.BootImageInspector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/** Host entry to the same keyless generator and builder selected by the Manager. */
fun main(args: Array<String>) {
    try {
        require(args.size % 2 == 0) { "ULTRA_HOST_ARGUMENTS" }
        val options = args.toList().chunked(2).associate { it[0] to it[1] }
        val boot = options["--boot"]?.let(::File)
        val rawFile = options["--kernel"]?.let(::File)
        require((boot == null) != (rawFile == null)) { "ULTRA_HOST_INPUT_REQUIRED" }
        val outputs = listOfNotNull("--out", "--receipt", "--patched-kernel", "--patched-boot")
            .mapNotNull { key -> options[key]?.let { key to File(it).canonicalFile } }.toMap()
        require(outputs.containsKey("--out")) { "ULTRA_HOST_OUTPUT_REQUIRED" }
        require(outputs.values.toSet().size == outputs.size) { "ULTRA_HOST_OUTPUT_COLLISION" }
        require(outputs.values.none { it.exists() }) { "ULTRA_HOST_OUTPUT_EXISTS" }
        require(boot != null || !outputs.containsKey("--patched-boot")) { "ULTRA_HOST_BOOT_REQUIRED" }
        val kernel = if (boot != null) UltraKernelPlan.readKernel(boot) else rawFile!!.readBytes()
        val operation = options["--operation"] ?: "generate"
        if (operation == "describe-subject" || operation == "describe-boot-credentials") {
            require(outputs.keys == setOf("--out")) { "ULTRA_HOST_SUBJECT_OUTPUT" }
            val symbols = UltraKernelKallsymsTable.decode(kernel)
            val members = if (operation == "describe-subject") setOf(
                    UltraKernelStructOffsets.CRED_UID, UltraKernelStructOffsets.CRED_EUID,
                    UltraKernelStructOffsets.CRED_GROUP_INFO, UltraKernelStructOffsets.GROUP_USAGE,
                    UltraKernelStructOffsets.CRED_USAGE) else setOf(
                    UltraKernelStructOffsets.CRED_UID, UltraKernelStructOffsets.CRED_EUID,
                    UltraKernelStructOffsets.SUID, UltraKernelStructOffsets.SGID,
                    UltraKernelStructOffsets.CRED_USAGE, UltraKernelStructOffsets.CRED_SECUREBITS)
            val layout = UltraKernelStructOffsets.describe(symbols, kernel,
                setOf(UltraKernelStructOffsets.CRED), emptyMap(), members)
            val resultCode = if (operation == "describe-subject") "PRO_LAYOUT_SUBJECT_DESCRIBE"
                else "PRO_LAYOUT_BOOT_CREDENTIALS_DESCRIBE"
            val description = buildJsonObject {
                put("schemaVersion", 1)
                put("operation", resultCode)
                put("implementation", "manager/ultrakernel")
                put("fields", buildJsonObject {
                    layout.fields.toSortedMap().forEach { (name, field) ->
                        put(name, buildJsonObject {
                            put("offsetBytes", field.offsetBytes)
                            put("widthBytes", field.widthBytes)
                            if (field.ownerSizeBytes == null) put("ownerSizeBytes", JsonNull)
                            else put("ownerSizeBytes", field.ownerSizeBytes)
                            put("source", field.source)
                            put("parserVersion", field.parserVersion)
                        })
                    }
                })
            }
            outputs.getValue("--out").let { out ->
                out.parentFile.mkdirs()
                out.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), description) + "\n")
            }
            println("${resultCode}_OK fields=${layout.fields.size}")
            return
        }
        require(operation == "generate") { "ULTRA_HOST_OPERATION" }
        val assetsRoot = File(options["--assets"] ?: "manager/src/main/assets").canonicalFile
        require(assetsRoot.isDirectory) { "ULTRA_HOST_MANAGER_CLAIM_ASSETS_MISSING" }
        val release = UltraKernelSpec.releaseOf(kernel)
        options["--require-release"]?.let { require(it == release) { "ULTRA_KERNEL_RELEASE_MISMATCH" } }
        val plan = UltraKernelSpecGenerator(managerClaimAssets = UltraKernelFileAssets(assetsRoot))
            .generate(kernel)
        val applied = plan.apply(kernel)
        val text = plan.specText()
        val spec = UltraKernelSpec.parse(text)
        check(spec.apply(kernel).kernel.contentEquals(applied.kernel)) { "ULTRA_HOST_SPEC_REPLAY_MISMATCH" }

        val parent = requireNotNull(outputs.getValue("--out").parentFile) { "ULTRA_HOST_OUTPUT_PARENT" }
            .also { it.mkdirs() }
        val staging = Files.createTempDirectory(parent.toPath(), ".maplesu-spec-").toFile()
        try {
            val candidate = if (outputs.containsKey("--patched-boot")) {
                val report = BootImageInspector.inspect(boot!!)
                val asset = UltraKernelGeneratedAsset.of(plan, report.fileSize, report.ramdiskSize, text)
                UltraKernelBuilder(File(staging, "builder")).generate(boot, asset, spec)
            } else null
            val receipt = buildJsonObject {
                put("schemaVersion", 2)
                put("operation", "ULTRA_SPEC_GENERATE")
                put("implementation", "manager/ultrakernel")
                put("managerClaimAssetsRoot", assetsRoot.path)
                put("origin", plan.origin.name)
                put("verification", "experimental")
                put("kernelRelease", plan.kernelRelease)
                put("sites", plan.records.size)
                put("changedBytes", applied.derived.changedBytes)
                put("baseKernelSha256", UltraKernelSpec.sha256(kernel))
                put("patchedKernelSha256", applied.derived.patchedKernelSha256)
                put("outsideSites", applied.derived.outsideSites)
                put("deviceBootTestPerformed", false)
                put("partitionWrites", false)
                put("layout", buildJsonObject {
                    put("fields", buildJsonObject {
                        plan.layout.fields.toSortedMap().forEach { (name, field) ->
                            put(name, buildJsonObject {
                                put("offsetBytes", field.offsetBytes)
                                put("widthBytes", field.widthBytes)
                                if (field.ownerSizeBytes == null) put("ownerSizeBytes", JsonNull)
                                else put("ownerSizeBytes", field.ownerSizeBytes)
                                put("source", field.source)
                                put("parserVersion", field.parserVersion)
                            })
                        }
                    })
                })
                put("caves", buildJsonArray {
                    // Written by the same placement pass that encoded the instructions.
                    text.lineSequence().filter { it.startsWith("#cave=") }.forEach { add(it.removePrefix("#")) }
                })
                put("records", buildJsonArray {
                    plan.records.forEach { record -> add(buildJsonObject {
                        put("role", record.role)
                        put("offset", record.offset)
                        put("length", record.length)
                    }) }
                })
                candidate?.let { put("patchedBootSha256", it.bootSha256) }
            }
            val staged = linkedMapOf<String, File>()
            staged["--out"] = File(staging, "patch.spec").also { it.writeText(text) }
            if (outputs.containsKey("--receipt")) staged["--receipt"] = File(staging, "receipt.json").also {
                it.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt) + "\n")
            }
            if (outputs.containsKey("--patched-kernel")) staged["--patched-kernel"] =
                File(staging, "kernel.raw").also { it.writeBytes(applied.kernel) }
            candidate?.let { staged["--patched-boot"] = it.image }
            staged.forEach { (key, source) ->
                val destination = outputs.getValue(key)
                destination.parentFile.mkdirs()
                Files.move(source.toPath(), destination.toPath())
            }
            println("ULTRA_SPEC_GENERATE_OK release=${plan.kernelRelease} sites=${plan.records.size}")
            println("kernelSha256=${applied.derived.patchedKernelSha256}")
            candidate?.let { println("bootSha256=${it.bootSha256}") }
        } finally { staging.deleteRecursively() }
    } catch (error: UltraKernelGenerateException) {
        System.err.println("${error.code}:${error.detail}")
        exitProcess(3)
    } catch (error: Exception) {
        // Keep arbitrary input and command lines out of failure output.
        val code = error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,120}")) }
        System.err.println(code ?: "ULTRA_HOST_FAILED:${error.javaClass.simpleName}")
        exitProcess(3)
    }
}
