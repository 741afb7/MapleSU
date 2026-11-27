package com.maplesu.core.theme

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Detect the container by its bytes, including imports from a generic file picker. */
internal object ThemePackageReader {
    private val magic = byteArrayOf(83, 75, 84, 72)
    // Public theme file-format key from SukiSU-Nova ThemePackageCrypto, not a store credential.
    private val key = SecretKeySpec(byteArrayOf(
        0x41, 0xdc.toByte(), 0x17, 0x4e, 0x50, 0x96.toByte(), 0xb4.toByte(), 0xee.toByte(),
        0x93.toByte(), 0x31, 0x1c, 0xc8.toByte(), 0x92.toByte(), 0xb1.toByte(), 0x32, 0xe2.toByte(),
        0x19, 0x61, 0x01, 0x2b, 0x14, 0xcf.toByte(), 0x21, 0x79,
        0x54, 0x0f, 0x20, 0x3c, 0xa7.toByte(), 0x2a, 0xb9.toByte(), 0xc1.toByte(),
    ), "AES")

    fun read(input: InputStream, directory: File): Pair<JSONObject, Map<String, File>> {
        val source = PushbackInputStream(input, magic.size)
        val header = ByteArray(magic.size)
        var count = 0
        while (count < header.size) {
            val read = source.read(header, count, header.size - count)
            require(read > 0) { "Incomplete theme container" }
            count += read
        }
        if (!header.contentEquals(magic)) {
            source.unread(header)
            return FptContainer.read(source, directory)
        }
        val bounded = BoundedInputStream(source, 128L * 1024 * 1024 - magic.size)
        val iv = ByteArray(12)
        count = 0
        while (count < iv.size) {
            val read = bounded.read(iv, count, iv.size - count)
            require(read > 0) { "Incomplete SukiSU theme header" }
            count += read
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val (raw, files) = FptContainer.readZip(ThemeDecryptedStream(bounded, cipher), directory,
            maxConfig = 64L * 1024, maxEntry = 64L * 1024 * 1024, maxTotal = 256L * 1024 * 1024)
        return SukisuThemeDocument.convert(raw, files) to files
    }
}
