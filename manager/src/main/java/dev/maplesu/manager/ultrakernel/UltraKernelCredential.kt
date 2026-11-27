package dev.maplesu.manager.ultrakernel

import java.security.SecureRandom

/** Historical 48-byte credential for explicit legacy calls and older image restoration.
 * Standard owner-v3 uses verified installation identity and DeploymentNamespace.
 */
internal class UltraKernelCredential private constructor(private val secret: ByteArray) {

    /** Historical base62 encoding: signed delta from the payload's BLR to its metadata. */
    val metadataDisplacement: Int?
        get() {
            val count = (alphabetIndex(secret[1]) - alphabetIndex(secret[0]) + 62) % 62
            if (count !in 3..(LENGTH + 2)) return null
            val extended = secret + byteArrayOf(secret[15], secret[2])
            val start = extended.size - count
            val seed = alphabetIndex(extended[start])
            val decoded = IntArray(count - 1) { index ->
                (alphabetIndex(extended[start + 1 + index]) - seed - index + 124) % 62
            }
            val negative = decoded.last() == 0
            val limit = Int.MAX_VALUE.toLong() + if (negative) 1 else 0
            var magnitude = 0L
            for (index in 0 until decoded.lastIndex) {
                if (magnitude > (limit - decoded[index]) / 62) return null
                magnitude = magnitude * 62 + decoded[index]
            }
            return (if (negative) -magnitude else magnitude).toInt()
        }

    /** Validate the historical locator before using it to identify an older image. */
    val sdkMetadataValid: Boolean
        get() = metadataDisplacement?.let {
            it != 0 && it % 4 == 0 && it.toLong() in -MAX_METADATA_DELTA..MAX_METADATA_DELTA
        } == true

    /** Bind the legacy SDK locator to this generated image's actual metadata. */
    fun withMetadataDisplacement(displacement: Int): UltraKernelCredential {
        require(displacement != 0 && displacement % 4 == 0 &&
            displacement.toLong() in -MAX_METADATA_DELTA..MAX_METADATA_DELTA) {
            "ULTRA_KERNEL_CREDENTIAL_METADATA_RANGE"
        }
        if (metadataDisplacement == displacement) return this
        // Base62 uses digits, lowercase, uppercase; java's radix conversion stops at 36.
        var magnitude = kotlin.math.abs(displacement.toLong())
        val digits = StringBuilder()
        do {
            digits.append(ALPHABET[(magnitude % 62).toInt()])
            magnitude /= 62
        } while (magnitude != 0L)
        val decoded = digits.reverse().toString() + if (displacement < 0) "0" else "1"
        val tailSeed = alphabetIndex(secret[16])
        val tail = byteArrayOf(ALPHABET[tailSeed].code.toByte()) +
            ByteArray(decoded.length) { index ->
                ALPHABET[(ALPHABET.indexOf(decoded[index]) + tailSeed + index) % 62].code.toByte()
            }
        val bound = secret.copyOf()
        try {
            tail.copyInto(bound, 50 - tail.size, 0, tail.size - 2)
            bound[15] = tail[tail.size - 2]
            bound[2] = tail.last()
            bound[1] = ALPHABET[(alphabetIndex(bound[0]) + tail.size) % 62].code.toByte()
            return of(bound).also {
                check(it.metadataDisplacement == displacement) { "ULTRA_KERNEL_CREDENTIAL_METADATA_ENCODING" }
            }
        } finally {
            bound.fill(0)
            tail.fill(0)
        }
    }

    /** Length in bytes; always [LENGTH]. */
    val length: Int get() = secret.size

    /** First [KEY16_LENGTH] characters, i.e. the `/data/<key16>/` directory name. */
    val key16: String = String(secret, 0, KEY16_LENGTH, Charsets.US_ASCII)

    /** The path the Ultra envelope execs: `/data/<key16>/init`. */
    val initPath: String = initPathFor(key16)

    /** Defensive copy; callers must clear it when they are done. */
    fun copySecret(): ByteArray = secret.copyOf()

    fun matches(other: ByteArray): Boolean = secret.contentEquals(other)

    /**
     * The `init_path` field as the envelopes store it: ASCII, NUL terminated, zero padded to
     * [fieldLength]. Refuses a field that cannot hold the path instead of truncating it.
     */
    fun initPathField(fieldLength: Int): ByteArray {
        val encoded = initPath.toByteArray(Charsets.US_ASCII)
        check(encoded.size + 1 <= fieldLength) { "ULTRA_KERNEL_CREDENTIAL_PATH_TOO_LONG" }
        return encoded.copyOf(fieldLength)
    }

    /** Never print the secret; diagnostics only need to know it is present. */
    override fun toString(): String = "UltraKernelCredential(length=$length, key16=<redacted>)"

    override fun equals(other: Any?): Boolean =
        other is UltraKernelCredential && secret.contentEquals(other.secret)

    override fun hashCode(): Int = secret.contentHashCode()

    companion object {
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val MAX_METADATA_DELTA = 128L * 1024 * 1024
        private fun alphabetIndex(value: Byte): Int = ALPHABET.indexOf(value.toInt().toChar())
        /** Credential length in bytes; the Ultra kernel literal and the Lite stdin slot agree on it. */
        const val LENGTH = 48

        /** Characters of the credential that name the device directory. */
        const val KEY16_LENGTH = 16

        /** The envelope's `init_path` field width (27 printable bytes + NUL + padding). */
        const val INIT_PATH_FIELD = 28

        fun initPathFor(key16: String): String = "/data/$key16/init"

        fun create(): UltraKernelCredential {
            val random = SecureRandom()
            val bytes = ByteArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)].code.toByte() }
            return try { of(bytes) } finally { bytes.fill(0) }
        }

        fun isValid(credential: ByteArray): Boolean =
            credential.size == LENGTH && credential.all { isAlphanumeric(it) }

        fun isValid(text: String): Boolean {
            if (text.length != LENGTH) return false
            return text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        }

        /** @throws IllegalArgumentException `ULTRA_KERNEL_CREDENTIAL_INVALID` when malformed. */
        fun of(credential: ByteArray): UltraKernelCredential {
            require(isValid(credential)) { "ULTRA_KERNEL_CREDENTIAL_INVALID" }
            return UltraKernelCredential(credential.copyOf())
        }

        /** @throws IllegalArgumentException `ULTRA_KERNEL_CREDENTIAL_INVALID` when malformed. */
        fun of(text: String): UltraKernelCredential {
            require(isValid(text)) { "ULTRA_KERNEL_CREDENTIAL_INVALID" }
            return UltraKernelCredential(text.toByteArray(Charsets.US_ASCII))
        }

        /**
         * True when [value] is exactly a credential-shaped run: [LENGTH] alphanumerics. Used to
         * detect an undeclared credential baked into a spec (a hard failure, see [UltraKernelPlan]).
         */
        fun isCredentialRun(value: ByteArray): Boolean = isValid(value)

        private fun isAlphanumeric(byte: Byte): Boolean =
            byte in 'A'.code.toByte()..'Z'.code.toByte() ||
                byte in 'a'.code.toByte()..'z'.code.toByte() ||
                byte in '0'.code.toByte()..'9'.code.toByte()
    }
}
