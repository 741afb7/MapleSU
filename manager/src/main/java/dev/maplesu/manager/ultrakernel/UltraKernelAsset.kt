package dev.maplesu.manager.ultrakernel

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * One verified kernel-patch asset: the immutable facts the installer is allowed to rely on.
 *
 * Both the legacy `manifest.json` variants ([UltraKernelVariant]) and the release-indexed assets
 * ([UltraKernelReleaseAsset]) implement this interface, so the builder/flow gates are written once
 * and the two catalogues cannot drift apart.
 *
 * `baseKernelSha256` is optional: an empty string means "the asset publisher did not pin a
 * pristine base kernel". In that case the gates still require every spec record's original bytes
 * to match and the rebuilt kernel to hash to [expectedKernelSha256]; the editable base digest is
 * never used as a substitute for that check.
 */
internal interface UltraKernelPatchAsset {
    val id: String
    val label: String
    val summary: String
    val status: String
    val ready: Boolean
    val debugOnly: Boolean
    val debugReason: String

    /** Asset path relative to the APK's `assets/` root. */
    val specAsset: String
    val specSha256: String
    val patches: Int
    val kernelRelease: String

    /** Pinned pristine kernel digest, or "" when the publisher did not pin one. */
    val baseKernelSha256: String
    val expectedKernelSha256: String

    /** Optional stock boot digests; empty means "this asset does not pin a stock image". */
    val knownBaseImageSha256: String
    val knownBaseImageBootSha256: String

    val imageSizeBytes: Long
    val ramdiskSize: Long

    /** `sample` (derived from a real boot image) or `model` (derived from a model/fixture). */
    val source: String

    /** `verified` or `experimental`. Experimental assets are usable but the UI is warned. */
    val verification: String

    /**
     * True for the catalogue's recommended asset (`isDefault`) of its release.
     *
     * Used only to order the candidates of one kernel **series** ([UltraKernelAssetOrder]: default
     * first, then the newest release). It is not a support level and it never authorises a write.
     */
    val isDefault: Boolean get() = false

    /**
     * Non-null only for an asset backed by a locally generated [UltraKernelPlan].
     *
     * The builder uses it as a *cross-check*, never as a substitute for its own gates: the plan
     * derived these digests with `UltraKernelPlan.apply` and the builder re-derives them with
     * `UltraKernelSpec.apply`, so a disagreement between the two implementations - or a record set
     * that was mutated after the plan was made - fails the build instead of shipping.
     */
    val generated: UltraKernelPlan.Derived? get() = null

    val experimental: Boolean get() = verification == VERIFICATION_EXPERIMENTAL

    /** Non-null only for experimental assets; surfaced verbatim to the install UI. */
    val warning: String? get() = if (experimental) "$WARNING_PREFIX:$id" else null

    companion object {
        const val VERIFICATION_VERIFIED = "verified"
        const val VERIFICATION_EXPERIMENTAL = "experimental"
        const val SOURCE_SAMPLE = "sample"
        const val SOURCE_MODEL = "model"
        const val WARNING_PREFIX = "ULTRA_KERNEL_ASSET_EXPERIMENTAL"

        fun validVerification(value: String): Boolean =
            value == VERIFICATION_VERIFIED || value == VERIFICATION_EXPERIMENTAL

        fun validSource(value: String): Boolean = value == SOURCE_SAMPLE || value == SOURCE_MODEL
    }
}

/**
 * Read-only view over the APK asset tree. Kept Android-free so the catalogue, gates and the
 * release resolver run on a plain JVM (unit tests use [UltraKernelMemoryAssets]).
 */
internal interface UltraKernelAssetSource {
    /** Immediate child names of [directory], or an empty list when it does not exist. */
    fun list(directory: String): List<String>

    /** Bytes of one asset path; throws when the asset is absent or unreadable. */
    fun read(path: String): ByteArray
}

/** In-memory source used by host tests and by catalogue fixtures. */
internal class UltraKernelMemoryAssets(
    files: Map<String, ByteArray>,
) : UltraKernelAssetSource {
    private val files = files.mapValues { it.value.copyOf() }

    override fun list(directory: String): List<String> {
        val prefix = if (directory.endsWith("/")) directory else "$directory/"
        val names = LinkedHashSet<String>()
        for (path in files.keys) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            if (rest.isEmpty()) continue
            names += rest.substringBefore('/')
        }
        return names.toList()
    }

    override fun read(path: String): ByteArray =
        files[path]?.copyOf() ?: throw java.io.FileNotFoundException(path)
}

/** Bounded read; never allocates more than [limit] + 1 bytes for a hostile asset. */
internal fun readBoundedAsset(stream: InputStream, limit: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        total += count
        check(total <= limit) { "ULTRA_KERNEL_ASSET_TOO_LARGE" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/**
 * Optional host filesystem source. It exists so a generated release can be validated from the
 * command line/host tests before it is packaged; the app itself always uses the APK asset tree.
 */
internal class UltraKernelFileAssets(private val root: File) : UltraKernelAssetSource {
    override fun list(directory: String): List<String> {
        val dir = File(root, directory)
        if (!dir.isDirectory) return emptyList()
        return dir.list().orEmpty().sorted()
    }

    override fun read(path: String): ByteArray {
        val file = File(root, path)
        if (!file.isFile) throw java.io.FileNotFoundException(path)
        return file.inputStream().use { readBoundedAsset(it, UltraKernelCatalog.MAX_ASSET_BYTES) }
    }
}

/**
 * `MANIFEST`-style stable failure for a release that cannot be built as-is.
 *
 * When no shipped asset matches, the generator resolves the actual kernel layout.
 * Unsupported layouts retain this stable diagnostic and never produce a partial candidate.
 */
internal open class UltraKernelReleaseUnsupportedException(
    val kernelRelease: String,
    val knownReleases: List<String>,
    val code: String = CODE_UNSUPPORTED,
    detail: String = "",
) : IllegalStateException(
    "$code:$kernelRelease:known=[${knownReleases.joinToString(",")}]$detail",
) {
    companion object {
        const val CODE_UNSUPPORTED = "ULTRA_KERNEL_RELEASE_UNSUPPORTED"
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
