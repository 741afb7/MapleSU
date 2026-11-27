package dev.maplesu.manager.installer

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.maplesu.image.LenovoAvbSigner
import dev.maplesu.manager.workmode.Hashes
import java.io.File
import java.security.PrivateKey

/** One install/export attempt; finalization runs after every kernel/ramdisk patch. */
internal class LenovoSigningSession(
    private val context: Context,
    private val options: LenovoSigningOptions,
    private val target: InstallTargetMode,
    private val taskId: String,
    private val fromFile: Boolean,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val directory = File(context.cacheDir, "lenovo-avb-$taskId")
    private val keys = mutableMapOf<Int, PrivateKey>()
    var outputs: Map<String, File> = emptyMap()
        private set

    fun finish(candidate: InstallCandidateImage, source: File): InstallCandidateImage {
        if (!options.enabled) return candidate
        try {
            check(directory.mkdirs()) { "LENOVO_AVB_WORKSPACE_EXISTS" }
            val names = if (fromFile) options.requiredImages(candidate.partition) else
                (options.requiredImages(candidate.partition) + "vbmeta").distinct()
            fun acquire(name: String): File {
                val image = File(directory, "$name.img")
                if (fromFile) {
                    val uri = requireNotNull(options.uri(name)) { "LENOVO_AVB_IMAGE_REQUIRED_$name" }
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        image.outputStream().use { output -> copyBounded(input, output) }
                    }
                } else {
                    val metadata = Bundle()
                    val descriptor = InstallerServiceClient.withService(context, persistent = true, rootTarget = target) {
                        it.openNativeInstallImage(taskId, name, metadata)
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        image.outputStream().use { output -> copyBounded(input, output) }
                    }
                    check(image.length() == metadata.getLong("size") && Hashes.sha256(image) == metadata.getString("sha256")) {
                        "LENOVO_AVB_SOURCE_CHANGED_$name"
                    }
                    log("AVB 提取当前槽分区：$name，${image.length()} bytes")
                }
                return image
            }
            val companions = names.associateWith(::acquire)
            val restored = if (fromFile) emptyMap() else {
                val status = InstallerServiceClient.withService(context, persistent = true, rootTarget = target) { it.getBootInstall(taskId) }
                status.getStringArrayList("originalPartitions").orEmpty().associateWith(::acquire)
            }
            log("联想 AVB 签名开始：${options.mode(candidate.partition)}，目标分区：${candidate.partition}")
            outputs = LenovoAvbSigner.sign(options.mode(candidate.partition), candidate.partition, candidate.image, source,
                companions, restored + candidate.compatibilityImages.mapValues { it.value.image }, File(directory, "signed"), { bits ->
                    keys.getOrPut(bits) {
                        context.assets.open("maplesu/avb/testkey_rsa$bits.pem").use { LenovoAvbSigner.decodeKey(it.readBytes()) }
                    }
                }, log)
            val image = outputs.getValue(candidate.partition)
            val hash = Hashes.sha256(image)
            // FlashScreen offers exporting the verified boot after installation. Its candidate
            // must outlive this session; companions remain private until committed/published.
            val retained = File(context.filesDir, "images/lenovo-signed/$hash.img")
            check(retained.parentFile!!.isDirectory || retained.parentFile!!.mkdirs()) { "LENOVO_AVB_CANDIDATE_DIRECTORY" }
            if (!retained.exists()) image.copyTo(retained)
            check(retained.length() == image.length() && Hashes.sha256(retained) == hash) { "LENOVO_AVB_CANDIDATE_CHANGED" }
            outputs = outputs + (candidate.partition to retained)
            val signed = candidate.copy(image = retained, sha256 = hash, size = retained.length())
            if (target == InstallTargetMode.ULTRA) {
                dev.maplesu.manager.runtime.ultra.UltraInstallTransactions.retainSignedIdentity(context,
                    candidate.image, candidate.sha256, signed, if (fromFile)
                        dev.maplesu.manager.runtime.ultra.UltraInstallTransactions.IMAGE_EXPORT_OPERATION else taskId)
            }
            log("联想 AVB 签名成功（RSA / hash / 验证链回读通过）")
            outputs.forEach { (name, file) -> log("签名输出路径 [$name]：${file.absolutePath}") }
            return signed
        } catch (error: Exception) {
            log("联想 AVB 签名失败：${error.message.orEmpty()}")
            log("签名失败，未输出完整镜像，未开始刷写。")
            throw error
        }
    }

    fun companions(partition: String) = outputs.filterKeys { it != partition }

    fun commit(service: IInstallerService, candidate: InstallCandidateImage): Bundle {
        if (!options.enabled && candidate.compatibilityImages.isEmpty()) return ParcelFileDescriptor.open(candidate.image, ParcelFileDescriptor.MODE_READ_ONLY).use {
            service.commitNativeInstall(taskId, it, candidate.sha256)
        }
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            val images = Bundle()
            companions(candidate.partition).forEach { (name, file) ->
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptors += descriptor
                images.putParcelable(name, descriptor)
                images.putString("$name.sha256", Hashes.sha256(file))
            }
            candidate.compatibilityImages.forEach { (name, companion) ->
                check(name !in images.keySet()) { "INSTALLER_COMPANION_INVALID" }
                val descriptor = ParcelFileDescriptor.open(companion.image, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptors += descriptor
                images.putParcelable(name, descriptor)
                images.putString("$name.sha256", companion.sha256)
                images.putString("$name.sourceSha256", companion.sourceSha256)
            }
            return ParcelFileDescriptor.open(candidate.image, ParcelFileDescriptor.MODE_READ_ONLY).use {
                service.commitNativeSignedInstall(taskId, it, candidate.sha256, images)
            }
        } finally { descriptors.forEach { it.close() } }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "LENOVO_AVB_CANCELLED" }
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0 && size + count <= dev.maplesu.manager.ImageRepository.MAX_IMAGE_BYTES) { "LENOVO_AVB_IMAGE_SIZE" }
            output.write(buffer, 0, count)
            size += count
        }
        check(size > 0) { "LENOVO_AVB_IMAGE_EMPTY" }
    }

    override fun close() { directory.deleteRecursively() }
}
