package dev.maplesu.manager.ultrakernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.CRC32

/** Immutable public path name. It is not a Key, installation generation or grant. */
internal class UltraKernelDeploymentNamespace private constructor(private val name: ByteArray) {
    fun name(): String = String(name, Charsets.US_ASCII)

    fun record(): ByteArray = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(MAGIC).putShort(1).putShort(RECORD_BYTES.toShort()).put(name).array()

    companion object {
        const val RECORD_BYTES = 24
        const val BINDING_BYTES = 56
        const val ANCHOR_VERSION = 4
        private const val MAGIC = 0x4e44534d
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun create(): UltraKernelDeploymentNamespace {
            val random = SecureRandom()
            return UltraKernelDeploymentNamespace(ByteArray(16) { ALPHABET[random.nextInt(ALPHABET.length)].code.toByte() })
        }

        /** Retain an existing public directory name, without retaining its former credential. */
        fun fromDirectoryName(name: String): UltraKernelDeploymentNamespace {
            require(name.matches(Regex("[A-Za-z0-9]{16}"))) { "ULTRA_KERNEL_DEPLOYMENT_NAMESPACE" }
            return UltraKernelDeploymentNamespace(name.toByteArray(Charsets.US_ASCII))
        }

        /** Input ranges have already been verified against the installed image. */
        fun fromLegacyInitPaths(ranges: Sequence<ByteArray>): UltraKernelDeploymentNamespace? {
            val path = Regex("/data/([A-Za-z0-9]{16})/init\\x00")
            val names = ranges.flatMap { bytes ->
                path.findAll(String(bytes, Charsets.ISO_8859_1)).map { it.groupValues[1] }
            }.toSet()
            require(names.size <= 1) { "ULTRA_KERNEL_RECOVERY_DEPLOYMENT_CONFLICT" }
            return names.singleOrNull()?.let(::fromDirectoryName)
        }

        fun decode(record: ByteArray): UltraKernelDeploymentNamespace {
            require(record.size == RECORD_BYTES) { "ULTRA_KERNEL_DEPLOYMENT_RECORD_BYTES" }
            val input = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            require(input.int == MAGIC && input.short.toInt() == 1 && input.short.toInt() == RECORD_BYTES) {
                "ULTRA_KERNEL_DEPLOYMENT_RECORD_HEADER"
            }
            val name = ByteArray(16).also { input.get(it) }
            require(name.all { it.toInt() in 'A'.code..'Z'.code || it.toInt() in 'a'.code..'z'.code ||
                it.toInt() in '0'.code..'9'.code }) { "ULTRA_KERNEL_DEPLOYMENT_NAMESPACE" }
            return UltraKernelDeploymentNamespace(name)
        }

        fun bindingCrc(binding: ByteArray, record: ByteArray): Int {
            require(binding.size == BINDING_BYTES) { "ULTRA_KERNEL_DEPLOYMENT_BINDING_BYTES" }
            decode(record)
            val bytes = binding.copyOf().apply { fill(0, 48, 52) }
            return CRC32().apply { update(bytes); update(record) }.value.toInt()
        }
    }
}
