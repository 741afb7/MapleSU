package dev.maplesu.manager.installer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.RamdiskInspector
import dev.maplesu.manager.BootCandidatePublisher
import dev.maplesu.manager.ultrakernel.UltraKernelContextAssets
import dev.maplesu.manager.ultrakernel.UltraKernelFlow
import dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real source -> production builder -> Downloads publish -> readback; no flash or SDK bootstrap.
 * Historical method selectors remain stable. Each test requires -e verifyUltraExport true,
 * -e exportSourceImage <app-readable pristine image> and -e exportSourceSha256 <independent SHA>.
 * init_boot also requires -e exportComponentKmi <packaged KMI>.
 */
class UltraImageExportIdentityAndroidTest {
    private fun isolated(block: (Context) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyUltraExport") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "export-identity-${UUID.randomUUID()}-"
        val stores = mutableSetOf<String>()
        val work = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = work
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                stores += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        try { block(context) } finally {
            stores.forEach(base::deleteSharedPreferences)
            work.deleteRecursively()
        }
    }

    private fun candidate(context: Context): Pair<File, File> {
        val args = InstrumentationRegistry.getArguments()
        val input = File(requireNotNull(args.getString("exportSourceImage")) { "EXPORT_SOURCE_REQUIRED" })
        val expected = requireNotNull(args.getString("exportSourceSha256")) { "EXPORT_SOURCE_SHA_REQUIRED" }
        assertTrue(expected.matches(Regex("[0-9a-f]{64}")))
        assertEquals(expected, UltraKernelSpec.sha256(input))
        val kmi = args.getString("exportComponentKmi")
        if (BootImageInspector.inspect(input).kernelSize == 0L) requireNotNull(kmi) { "EXPORT_COMPONENT_KMI_REQUIRED" }
        val artifact = kmi?.let { requireNotNull(UltraKernelFlow.initBootArtifact(context, it)) { "EXPORT_COMPONENT_ABSENT" } }
        val flow = UltraKernelFlow(context)
        val preparation = flow.prepareTarget(input, args.getString("exportVariantId"), artifact)
        val built = flow.candidateForInstall(input, preparation)
        assertEquals(expected, built.baseBootSha256)
        assertEquals(expected, UltraKernelSpec.sha256(input))
        assertEquals(built.bootSha256, UltraKernelSpec.sha256(built.image))
        assertNotEquals(expected, built.bootSha256)
        assertNull("File export must not create a flash transaction", InstallerServiceClient.lastTaskId(context))
        assertNoLegacySecrets(context)
        return input to built.image
    }

    private fun assertNoLegacySecrets(context: Context) {
        val prefs = context.getSharedPreferences("maplesu-ultra", Context.MODE_PRIVATE)
        val secretNames = setOf("rootKey", "rootKeySuspended", "pendingKernelCredential", "previousInstallCredential",
            "keyVerified", "keyVerifiedFor", "installCredentialTaskId", "installCredentialForKey",
            "installCredentialSourceBoot", "installCredentialProvenance", "installAuthorizationObservedForKey")
        assertTrue("File patch/export created historical secret fields", secretNames.none(prefs::contains))
        assertTrue(context.getSharedPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue("File export must not claim an active SDK session",
            context.getSharedPreferences("maplesu-boot-sdk", Context.MODE_PRIVATE).all.isEmpty())
    }

    private fun findPublished(context: Context, name: String): Uri? = context.contentResolver.query(
        MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI), arrayOf(MediaStore.Downloads._ID),
        "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null else Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)).toString())
    }

    private fun published(context: Context, image: File, block: (File) -> Unit) {
        val name = "maplesu-export-acceptance-${UUID.randomUUID()}.img"
        try {
            BootCandidatePublisher.publish(context, image, UltraKernelSpec.sha256(image), name)
            val uri = requireNotNull(findPublished(context, name))
            val copy = File(context.filesDir, "published-readback.img")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input).use { source -> copy.outputStream().use { target -> source.copyTo(target) } }
            }
            assertEquals(image.length(), copy.length())
            assertEquals(UltraKernelSpec.sha256(image), UltraKernelSpec.sha256(copy))
            block(copy)
            assertNoLegacySecrets(context)
        } finally {
            findPublished(context, name)?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun recover(context: Context, exported: File, original: File, receipts: File) {
        val restored = requireNotNull(UltraKernelOriginalRecovery(UltraKernelContextAssets(context)).recover(
            exported, receipts, emptyList(), File(context.filesDir, "recovered-${UUID.randomUUID()}"), generatedOnly = true,
        )) { "EXPORTED_IMAGE_RECOVERY_MISSING" }
        assertEquals(UltraKernelSpec.sha256(original), UltraKernelSpec.sha256(restored.image))
        val namespace = requireNotNull(restored.deploymentNamespace) { "EXPORTED_PUBLIC_NAMESPACE_MISSING" }
        assertTrue(namespace.name().matches(Regex("[A-Za-z0-9]{16}")))
        val ramdisk = RamdiskInspector.inspect(exported)
        assertFalse(ramdisk.malformed)
        assertFalse(ramdisk.entries.any { it.path == ".maplesu_ultra/credential" })
        assertNoLegacySecrets(context)
    }

    @Test fun exportedImageKeepsItsExactKeyAndCanInitializeWithoutAFlashTransaction() = isolated { context ->
        val (input, image) = candidate(context)
        published(context, image) { exported -> recover(context, exported, input, image.parentFile!!) }
        assertNull(InstallerServiceClient.lastTaskId(context))
    }

    @Test fun aChangedExportCannotPublishItsKeyOrEnableStartup() = isolated { context ->
        val (_, image) = candidate(context)
        val changed = File(context.filesDir, "changed.img")
        image.copyTo(changed)
        val expected = UltraKernelSpec.sha256(changed)
        changed.appendBytes(byteArrayOf(1))
        val name = "maplesu-export-rejected-${UUID.randomUUID()}.img"
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                BootCandidatePublisher.publish(context, changed, expected, name)
            }
            assertEquals("CANDIDATE_EXPORT_READBACK_MISMATCH", error.message)
            assertNull("Failed export must remove its Downloads row", findPublished(context, name))
            assertNull(InstallerServiceClient.lastTaskId(context))
            assertNoLegacySecrets(context)
        } finally {
            findPublished(context, name)?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun previousGeneratedExportsAlsoResumeWithoutATransaction() = isolated { context ->
        val (input, image) = candidate(context)
        val emptyReceipts = File(context.filesDir, "empty-receipts").apply { check(mkdirs()) }
        published(context, image) { exported -> recover(context, exported, input, emptyReceipts) }
        assertNull(InstallerServiceClient.lastTaskId(context))
    }
}
