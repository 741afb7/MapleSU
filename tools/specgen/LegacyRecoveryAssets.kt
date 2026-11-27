package dev.maplesu.manager.ultrakernel

import java.io.File
import kotlinx.serialization.json.*

/** Host-only producer: explicit pristine/patched pairs, exact replay and credential-free inverses. */
fun main(args: Array<String>) {
    check(args.size >= 3 && args.size % 2 == 1) { "usage: OUT ORIGINAL PATCHED [ORIGINAL PATCHED ...]" }
    val output = File(args[0])
    check(output.isDirectory || output.mkdirs())
    val results = args.drop(1).chunked(2).map { (originalPath, patchedPath) ->
        val originalFile = File(originalPath)
        val patchedFile = File(patchedPath)
        val original = UltraKernelPlan.readKernel(originalFile)
        val patched = UltraKernelPlan.readKernel(patchedFile)
        val release = UltraKernelSpec.releaseOf(original)
        check(original.size == patched.size && release.isNotEmpty() &&
            release == UltraKernelSpec.releaseOf(patched) &&
            original.copyOfRange(0, 64).contentEquals(patched.copyOfRange(0, 64))) {
            "LEGACY_PAIR_HEADER_MISMATCH"
        }
        val symbols = UltraKernelKallsymsTable.decode(original)
        val spans = arrayListOf<IntArray>()
        for (at in original.indices) if (original[at] != patched[at]) {
            val start = at and -4
            val end = minOf(original.size, (at + 4) and -4)
            val previous = spans.lastOrNull()
            if (previous != null && start <= previous[1] + 16) previous[1] = maxOf(previous[1], end)
            else spans += intArrayOf(start, end)
        }
        check(spans.isNotEmpty() && spans.size <= UltraKernelRecoveryRecord.MAX_RANGES) { "LEGACY_PAIR_RANGES" }
        // These are the historical producer's write extents, resolved on the supplied original.
        // An unrelated difference must be reviewed before an inverse is added to the archive.
        val extents = listOf(
            Triple("die", 0, 228), Triple("arm64_notify_die", 0, 52), Triple("free_initmem", 0, 60),
            Triple("report_cfi_failure", 0, 8), Triple("free_reserved_area", 0, 8),
            Triple("do_execveat_common", 0, 4), Triple("avc_denied", 40, 4),
            Triple("__drm_puts_coredump", 0, 88), Triple("__drm_printfn_coredump", 0, 124),
            Triple("drm_dev_printk", 0, 108), Triple("__dev_printk", 0, 84), Triple("panic", 0, 48),
            Triple("__traceiter_kvm_userspace_exit", 112, 56),
        )
        fun metadata(start: Int, end: Int): Boolean = end - start == 56 &&
            UltraKernelA64.le32Bits(patched, start) == start &&
            UltraKernelA64.le32Bits(patched, start + 4) == symbols.offsetOf("kallsyms_lookup_name") &&
            UltraKernelA64.le32Bits(patched, start + 36) == symbols.offsetOf("do_execveat_common") &&
            UltraKernelA64.le32Bits(patched, start + 48) == symbols.offsetOf("filldir64")
        for ((start, end) in spans) check(extents.any { (name, delta, length) ->
            symbols.offsetOf(name)?.let { start >= it + delta && end <= it + delta + length } == true
        } || metadata(start, end)) { "LEGACY_PAIR_UNREVIEWED_RANGE:$start:${end - start}" }
        val records = spans.mapIndexed { index, (start, end) ->
            UltraKernelPlan.PatchRecord("legacy-$index", start.toLong(),
                original.copyOfRange(start, end), patched.copyOfRange(start, end))
        }
        val plan = UltraKernelPlan.fromRecords(release, records)
        check(plan.apply(original).kernel.contentEquals(patched)) { "LEGACY_PAIR_REPLAY_MISMATCH" }
        val inverse = UltraKernelRecoveryRecord.fromPlan(plan, original.size, 0, 0).encode()
        check(UltraKernelRecoveryRecord.decode(inverse).restore(patched).contentEquals(original)) {
            "LEGACY_PAIR_INVERSE_MISMATCH"
        }
        val digest = UltraKernelSpec.sha256(patched)
        val metadata = buildJsonObject {
            put("schemaVersion", 1); put("kernelRelease", release); put("kernelBytes", original.size)
            put("patchedKernelSha256", digest); put("originalKernelSha256", UltraKernelSpec.sha256(original))
            put("recordSha256", UltraKernelSpec.sha256(inverse))
        }
        for ((name, bytes) in listOf("$digest.bin" to inverse,
            "$digest.json" to (metadata.toString() + "\n").toByteArray(Charsets.UTF_8))) {
            val file = File(output, name)
            check(!file.exists() || file.readBytes().contentEquals(bytes)) { "LEGACY_OUTPUT_CONFLICT:$name" }
            file.writeBytes(bytes)
        }
        buildJsonObject {
            put("original", originalFile.canonicalPath); put("patched", patchedFile.canonicalPath)
            put("originalBootSha256", UltraKernelSpec.sha256(originalFile))
            put("patchedBootSha256", UltraKernelSpec.sha256(patchedFile))
            put("binding", metadata); put("ranges", records.size); put("recordBytes", inverse.size)
            put("completeKernelReplay", true); put("completeKernelRestoration", true)
            put("deviceTest", false)
        }
    }
    println(JsonArray(results))
}
