package dev.maplesu.manager.ultrakernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Portable inverse of one final, bound plan. Only original bytes and digests of the final write
 * ranges are retained: replacement bytes, credentials and release/fingerprint lookup tables are
 * absent. The producer ABI values are diagnostic; the recovery format/action ABI defines parsing.
 * This codec neither reads images nor publishes a patch. Its caller must verify the plan against
 * the actual baseline using the existing builder before retaining this record.
 */
internal class UltraKernelRecoveryRecord private constructor(
    val kernelBytes: Int,
    val producerSdkAbi: Int,
    val producerRuntimeAbi: Int,
    private val ranges: List<Range>,
    private val deployment: Deployment? = null,
) {
    private class Range(val offset: Int, val original: ByteArray, val replacementSha256: ByteArray)
    private class Deployment(val offset: Int, val sha256: ByteArray)

    val rangeCount: Int get() = ranges.size
    val originalBytes: Int get() = ranges.sumOf { it.original.size }

    fun encode(): ByteArray {
        val headerBytes = if (deployment == null) HEADER_BYTES else DEPLOYMENT_HEADER_BYTES
        val size = encodedSize(ranges.size, originalBytes, headerBytes)
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC).putInt(if (deployment == null) FORMAT_VERSION else DEPLOYMENT_FORMAT_VERSION)
            .putInt(size).putInt(headerBytes)
            .putInt(ACTION_ABI).putInt(producerSdkAbi).putInt(producerRuntimeAbi)
            .putLong(kernelBytes.toLong()).putInt(ranges.size).putInt(originalBytes)
        deployment?.let {
            out.putLong(it.offset.toLong()).putInt(UltraKernelDeploymentNamespace.RECORD_BYTES).putInt(0).put(it.sha256)
        }
        for (range in ranges) {
            out.putLong(range.offset.toLong()).putInt(range.original.size).putInt(0)
                .put(range.replacementSha256).put(range.original)
        }
        out.put(digest(out.array(), 0, size - SHA_BYTES))
        return out.array()
    }

    /** Checks every current range before restoring any byte; the supplied [current] stays intact. */
    fun restore(current: ByteArray): ByteArray {
        require(current.size == kernelBytes) { "ULTRA_KERNEL_RECOVERY_CURRENT_LENGTH" }
        verifyCurrent(current)
        val restored = current.copyOf()
        for (range in ranges) range.original.copyInto(restored, range.offset)
        return restored
    }

    /** Resolve the typed locator, or the public init path in verified legacy write ranges. */
    fun deploymentNamespace(current: ByteArray): UltraKernelDeploymentNamespace? {
        verifyCurrent(current)
        val selected = deployment ?: run {
            return UltraKernelDeploymentNamespace.fromLegacyInitPaths(ranges.asSequence().map { range ->
                current.copyOfRange(range.offset, range.offset + range.original.size)
            })
        }
        val record = current.copyOfRange(selected.offset, selected.offset + UltraKernelDeploymentNamespace.RECORD_BYTES)
        require(MessageDigest.isEqual(selected.sha256, digest(record, 0, record.size))) {
            "ULTRA_KERNEL_RECOVERY_DEPLOYMENT_MISMATCH"
        }
        return UltraKernelDeploymentNamespace.decode(record)
    }

    private fun verifyCurrent(current: ByteArray) {
        require(current.size == kernelBytes) { "ULTRA_KERNEL_RECOVERY_CURRENT_LENGTH" }
        for (range in ranges) require(MessageDigest.isEqual(range.replacementSha256,
            digest(current, range.offset, range.original.size))) { "ULTRA_KERNEL_RECOVERY_CURRENT_MISMATCH" }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val DEPLOYMENT_FORMAT_VERSION = 2
        const val ACTION_ABI = 1 // Restore exact original bytes into an equal-length raw kernel.
        const val MAX_RANGES = 512
        const val MAX_BYTES = 1024 * 1024
        private const val HEADER_BYTES = 48
        private const val DEPLOYMENT_HEADER_BYTES = 96
        private const val SHA_BYTES = 32
        private const val RANGE_BYTES = 16 + SHA_BYTES
        private val MAGIC = byteArrayOf(77, 83, 85, 82, 67, 86, 82, 0) // MSURCVR\0

        /** Normalize overlapping writes with the same input-before-any-write semantics as apply. */
        fun fromPlan(plan: UltraKernelPlan, kernelBytes: Int,
                     producerSdkAbi: Int, producerRuntimeAbi: Int): UltraKernelRecoveryRecord {
            require(kernelBytes > 0) { "ULTRA_KERNEL_RECOVERY_KERNEL_LENGTH" }
            require(producerSdkAbi >= 0 && producerRuntimeAbi >= 0) {
                "ULTRA_KERNEL_RECOVERY_PRODUCER_ABI"
            }
            require(plan.records.size in 1..MAX_RANGES) { "ULTRA_KERNEL_RECOVERY_RANGE_COUNT" }
            var originalBudget = 0L
            var replacementBudget = 0L
            for (record in plan.records) {
                require(record.offset >= 0 && record.original.isNotEmpty() && record.replacement.isNotEmpty() &&
                    record.offset <= kernelBytes.toLong() &&
                    maxOf(record.original.size, record.replacement.size).toLong() <= kernelBytes - record.offset) {
                    "ULTRA_KERNEL_RECOVERY_PLAN_RANGE"
                }
                originalBudget += record.original.size
                replacementBudget += record.replacement.size
                require(originalBudget <= MAX_BYTES && replacementBudget <= MAX_BYTES) {
                    "ULTRA_KERNEL_RECOVERY_PLAN_SIZE"
                }
            }
            val records = plan.records.map { it.copy() }
            val namespaces = records.filter { it.role == "manager_claim:deployment_namespace" }
            require(namespaces.size <= 1) { "ULTRA_KERNEL_RECOVERY_DEPLOYMENT_COUNT" }
            val deployment = namespaces.singleOrNull()?.let { record ->
                UltraKernelDeploymentNamespace.decode(record.replacement)
                require(records.none { it !== record && it.offset < record.offset + record.replacement.size &&
                    record.offset < it.offset + it.replacement.size }) { "ULTRA_KERNEL_RECOVERY_DEPLOYMENT_OVERLAP" }
                Deployment(record.offset.toInt(), digest(record.replacement, 0, record.replacement.size))
            }
            // All originals refer to one pristine input, including overlaps outside write ranges.
            for (i in records.indices) for (j in 0 until i) {
                val left = records[i]; val right = records[j]
                val start = maxOf(left.offset, right.offset)
                val end = minOf(left.offset + left.original.size, right.offset + right.original.size)
                for (at in start until end) require(
                    left.original[(at - left.offset).toInt()] == right.original[(at - right.offset).toInt()]) {
                    "ULTRA_KERNEL_RECOVERY_ORIGINAL_CONFLICT"
                }
            }
            val spans = ArrayList<IntArray>()
            for (record in records.sortedBy { it.offset }) {
                val start = record.offset.toInt()
                val end = start + record.replacement.size
                val previous = spans.lastOrNull()
                if (previous != null && start <= previous[1]) previous[1] = maxOf(previous[1], end)
                else spans += intArrayOf(start, end)
            }
            val originalSize = spans.sumOf { it[1] - it[0] }
            encodedSize(spans.size, originalSize, if (deployment == null) HEADER_BYTES else DEPLOYMENT_HEADER_BYTES)
            val normalized = spans.map { span ->
                val start = span[0]; val length = span[1] - start
                val original = ByteArray(length)
                val known = BooleanArray(length)
                val replacement = ByteArray(length)
                for (record in records) {
                    val at = record.offset.toInt()
                    val originalStart = maxOf(start, at)
                    val originalEnd = minOf(span[1], at + record.original.size)
                    if (originalStart < originalEnd) {
                        record.original.copyInto(original, originalStart - start,
                            originalStart - at, originalEnd - at)
                        known.fill(true, originalStart - start, originalEnd - start)
                    }
                    val writeStart = maxOf(start, at)
                    val writeEnd = minOf(span[1], at + record.replacement.size)
                    if (writeStart < writeEnd) record.replacement.copyInto(replacement, writeStart - start,
                        writeStart - at, writeEnd - at)
                }
                require(known.all { it }) { "ULTRA_KERNEL_RECOVERY_PREIMAGE_INCOMPLETE" }
                Range(start, original, digest(replacement, 0, length))
            }
            return UltraKernelRecoveryRecord(kernelBytes, producerSdkAbi, producerRuntimeAbi, normalized, deployment)
        }

        fun decode(bytes: ByteArray): UltraKernelRecoveryRecord {
            require(bytes.size in (HEADER_BYTES + RANGE_BYTES + 1 + SHA_BYTES)..MAX_BYTES) {
                "ULTRA_KERNEL_RECOVERY_RECORD_SIZE"
            }
            val snapshot = bytes.copyOf()
            require(MessageDigest.isEqual(digest(snapshot, 0, snapshot.size - SHA_BYTES),
                snapshot.copyOfRange(snapshot.size - SHA_BYTES, snapshot.size))) {
                "ULTRA_KERNEL_RECOVERY_CHECKSUM"
            }
            val input = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also { input.get(it) }
            require(magic.contentEquals(MAGIC)) { "ULTRA_KERNEL_RECOVERY_MAGIC" }
            val format = input.int
            require(format == FORMAT_VERSION || format == DEPLOYMENT_FORMAT_VERSION) { "ULTRA_KERNEL_RECOVERY_FORMAT" }
            val headerBytes = if (format == FORMAT_VERSION) HEADER_BYTES else DEPLOYMENT_HEADER_BYTES
            require(input.int == bytes.size && input.int == headerBytes) { "ULTRA_KERNEL_RECOVERY_HEADER_LENGTH" }
            require(input.int == ACTION_ABI) { "ULTRA_KERNEL_RECOVERY_ACTION_ABI" }
            val sdkAbi = input.int; val runtimeAbi = input.int
            require(sdkAbi >= 0 && runtimeAbi >= 0) { "ULTRA_KERNEL_RECOVERY_PRODUCER_ABI" }
            val kernelBytes = input.long
            require(kernelBytes in 1..Int.MAX_VALUE.toLong()) { "ULTRA_KERNEL_RECOVERY_KERNEL_LENGTH" }
            val count = input.int; val originalBytes = input.int
            require(count in 1..MAX_RANGES) { "ULTRA_KERNEL_RECOVERY_RANGE_COUNT" }
            require(originalBytes >= count && encodedSize(count, originalBytes, headerBytes) == bytes.size) {
                "ULTRA_KERNEL_RECOVERY_RECORD_LENGTH"
            }
            val deployment = if (format == DEPLOYMENT_FORMAT_VERSION) {
                val offset = input.long
                require(input.int == UltraKernelDeploymentNamespace.RECORD_BYTES && input.int == 0 &&
                    offset >= 0 && offset <= kernelBytes - UltraKernelDeploymentNamespace.RECORD_BYTES) {
                    "ULTRA_KERNEL_RECOVERY_DEPLOYMENT_RANGE"
                }
                Deployment(offset.toInt(), ByteArray(SHA_BYTES).also { input.get(it) })
            } else null
            val ranges = ArrayList<Range>(count)
            var end = -1L
            var remainingOriginal = originalBytes
            repeat(count) { index ->
                require(input.remaining() - SHA_BYTES >= RANGE_BYTES + 1) {
                    "ULTRA_KERNEL_RECOVERY_RANGE_LENGTH"
                }
                val offset = input.long; val length = input.int
                require(input.int == 0) { "ULTRA_KERNEL_RECOVERY_RESERVED" }
                require(offset >= 0 && length > 0 && offset <= kernelBytes &&
                    length.toLong() <= kernelBytes - offset && offset > end) {
                    "ULTRA_KERNEL_RECOVERY_RANGE"
                }
                val minimumRemaining = SHA_BYTES.toLong() + length +
                    (count - index - 1).toLong() * (RANGE_BYTES + 1) + SHA_BYTES
                require(length <= remainingOriginal && minimumRemaining <= input.remaining()) {
                    "ULTRA_KERNEL_RECOVERY_RANGE_LENGTH"
                }
                val expected = ByteArray(SHA_BYTES).also { input.get(it) }
                val original = ByteArray(length).also { input.get(it) }
                ranges += Range(offset.toInt(), original, expected)
                remainingOriginal -= length
                end = offset + length
            }
            require(remainingOriginal == 0 && input.remaining() == SHA_BYTES) {
                "ULTRA_KERNEL_RECOVERY_TRAILING_BYTES"
            }
            deployment?.let { selected ->
                require(ranges.any { it.offset <= selected.offset &&
                    selected.offset.toLong() + UltraKernelDeploymentNamespace.RECORD_BYTES <= it.offset.toLong() + it.original.size }) {
                    "ULTRA_KERNEL_RECOVERY_DEPLOYMENT_UNBOUND"
                }
            }
            return UltraKernelRecoveryRecord(kernelBytes.toInt(), sdkAbi, runtimeAbi, ranges, deployment)
        }

        private fun encodedSize(count: Int, originalBytes: Int, headerBytes: Int = HEADER_BYTES): Int {
            val total = headerBytes.toLong() + count.toLong() * RANGE_BYTES + originalBytes + SHA_BYTES
            require(count in 1..MAX_RANGES && originalBytes >= count && total <= MAX_BYTES) {
                "ULTRA_KERNEL_RECOVERY_RECORD_SIZE"
            }
            return total.toInt()
        }

        private fun digest(bytes: ByteArray, offset: Int, length: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").run { update(bytes, offset, length); digest() }
    }
}
