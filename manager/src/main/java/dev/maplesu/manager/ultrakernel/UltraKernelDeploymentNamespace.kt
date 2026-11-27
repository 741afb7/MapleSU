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
