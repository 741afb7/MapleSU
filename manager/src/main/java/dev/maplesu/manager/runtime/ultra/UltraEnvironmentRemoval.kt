package dev.maplesu.manager.runtime.ultra

import android.content.Context
import android.util.Xml
import dev.maplesu.manager.workmode.storage.MoveSet
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import org.xmlpull.v1.XmlPullParser

/** Existing explicit removal marker; independent of installation credentials. */
internal object UltraEnvironmentRemoval {
    const val DISABLED = "${MoveSet.ULTRA_IDENTITY_ROOT}/disabled"

    /** Read the committed uninstall intent afresh; the root process has a separate preferences cache. */
    fun isSuspended(context: Context): Boolean {
        val disabled = File(DISABLED)
        if (exists(disabled)) return true
        val preferences = File(context.applicationInfo.dataDir, "shared_prefs/maplesu-ultra.xml")
        // SharedPreferences restores its backup when a writer has not committed the new file yet.
        val backup = File(preferences.path + ".bak")
        var source = source(preferences, backup)
        repeat(2) { attempt ->
            val result = try {
                Result.success(read(source))
            } catch (error: Exception) {
                Result.failure<Boolean>(error)
            }
            val current = source(preferences, backup)
            if (source == current) return result.getOrThrow()
            // A completed write can rename or delete the selected file before it is opened.
            if (attempt != 0) throw IllegalStateException("ULTRA_REMOVAL_STATE_CHANGED", result.exceptionOrNull())
            source = current
        }
        error("ULTRA_REMOVAL_STATE_CHANGED")
    }

    private data class Source(val file: File, val key: Any?, val modified: FileTime, val size: Long)

    private fun source(preferences: File, backup: File): Source? = state(backup) ?: state(preferences)

    private fun state(file: File): Source? = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        Source(file, attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
    } catch (_: NoSuchFileException) {
        null
    }

    private fun read(source: Source?): Boolean {
        if (source == null) return false
        return source.file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG &&
                    parser.getAttributeValue(null, "name") == "environmentRemoval") return@use true
            }
            check(parser.depth == 0) { "ULTRA_REMOVAL_STATE_INVALID" }
            false
        }
    }

    private fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    }
}
