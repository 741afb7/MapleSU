package dev.maplesu.manager.runtime

import android.content.Context
import android.util.Log
import dev.maplesu.manager.runtime.authorization.AndroidApplicationIdentities
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import java.io.File

/**
 * Publishes the shared SU forwarder inside the verified installation namespace.
 * The UID list and per-UID links locate delivered entries; the durable authority still
 * checks the real caller's fullUID and installation identity on every admission.
 */
object SuPublication {
    private const val RECORD = "published-directory"
    private val DIRECTORY = Regex("/data/[A-Za-z0-9]{16}/su-client")
    private val LEGACY_DIRECTORY = Regex("/data/[a-f0-9]{16}")

    /** A missing current readback is unknown, never a guessed deployment path. */
    fun directory(context: Context): String? =
        UltraInstalledIdentityProbe.installedHelperPath()?.let { helper ->
            File(File(helper).parentFile, "su-client").absolutePath.takeIf(DIRECTORY::matches)
        }

    fun suPath(context: Context): String? = directory(context)?.let { "$it/su" }
    fun suDirectory(context: Context): String? = directory(context)?.let { "$it/" }

    /** A display/probe record, never authority and never an automatic cleanup target. */
    fun previousDirectory(context: Context): String? =
        previousDirectory(File(context.applicationContext.filesDir, RECORD))

    internal fun previousDirectory(record: File): String? = runCatching {
        record.takeIf { it.isFile }?.readText()?.trim()?.takeIf {
            DIRECTORY.matches(it) || LEGACY_DIRECTORY.matches(it)
        }
    }.getOrNull()

    fun publish(context: Context): Boolean {
        val application = context.applicationContext
        val current = UltraSdkCredentials.captureOwnerMarker(application)
        if (!current()) return false
        val granted = DirectPolicy.grantedUids(application)?.sorted() ?: return false
        val observation = UltraInstalledIdentityProbe.readAndVerify(application,
            UltraSdkCredentials.client(application, current))
        if (!observation.verification.verified || !current()) return false
        val directory = directory(application) ?: return false
        val source = File(application.applicationInfo.nativeLibraryDir, "libmaplesu_su.so")
        if (!source.isFile) return false
        val entries = granted.mapNotNull { uid ->
            when (DirectPolicy.hasGrantStrict(application, uid)) {
                true -> Unit
                false -> return@mapNotNull null
                null -> return false
            }
            val name = AndroidApplicationIdentities(application).read(uid)?.packageName
                ?: return false
            val info = runCatching { application.packageManager.getApplicationInfo(name, 0) }
                .getOrNull()?.takeIf { it.uid == uid } ?: return false
            uid to info.nativeLibraryDir
        }
        val authorized = entries.joinToString("\n") { it.first.toString() }
        val staging = File(application.cacheDir, "publish-${System.nanoTime()}.img")
        val list = File(application.cacheDir, "authorized-${System.nanoTime()}.txt")
        val links = File(application.cacheDir, "links-${System.nanoTime()}.sh")
        return try {
            // This raw image forwards to u/<fullUID>/su, whose symlink resolves to the
            // installed Manager image before its existing Binder bootstrap runs.
            source.inputStream().use { input -> staging.outputStream().use { input.copyTo(it) } }
            list.writeText(if (authorized.isEmpty()) "" else "$authorized\n")
            links.writeText(entries.joinToString("\n") { (uid, target) ->
                "/system/bin/ln -sfn ${quote(target)} ${quote("$directory/u/$uid")}"
            })
            if (!current()) return false
            val script = """
                set -eu
                /system/bin/mkdir -p ${quote(directory)}
                /system/bin/chown 0:0 ${quote(directory)}
                /system/bin/chmod 0755 ${quote(directory)}
                /system/bin/chcon u:object_r:system_file:s0 ${quote(directory)}
                /system/bin/cp -f ${quote(staging.absolutePath)} ${quote("$directory/su.new")}
                /system/bin/chown 0:0 ${quote("$directory/su.new")}
                /system/bin/chmod 0711 ${quote("$directory/su.new")}
                /system/bin/chcon u:object_r:system_file:s0 ${quote("$directory/su.new")}
                /system/bin/mv -f ${quote("$directory/su.new")} ${quote("$directory/su")}
                /system/bin/cp -f ${quote(list.absolutePath)} ${quote("$directory/authorized.new")}
                /system/bin/chown 0:0 ${quote("$directory/authorized.new")}
                /system/bin/chmod 0644 ${quote("$directory/authorized.new")}
                /system/bin/chcon u:object_r:system_file:s0 ${quote("$directory/authorized.new")}
                /system/bin/mv -f ${quote("$directory/authorized.new")} ${quote("$directory/authorized")}
                /system/bin/mkdir -p ${quote("$directory/u")}
                /system/bin/chown 0:0 ${quote("$directory/u")}
                /system/bin/chmod 0711 ${quote("$directory/u")}
                /system/bin/chcon u:object_r:system_file:s0 ${quote("$directory/u")}
                /system/bin/rm -f ${quote("$directory/u")}/*
                /system/bin/sh ${quote(links.absolutePath)}
            """.trimIndent()
            val result = DirectRoot.run(application, script)
            if (result.code == 0 && current()) {
                File(application.filesDir, RECORD).writeText(directory)
                Log.i("MapleSU", "SU_PUBLISHED uids=${entries.size}")
                true
            } else {
                Log.w("MapleSU", "SU_PUBLISH_FAILED code=${result.code}")
                false
            }
        } finally {
            staging.delete()
            list.delete()
            links.delete()
        }
    }

    /** Explicit file removal is confined to the SU subtree, never the installed helper. */
    fun withdraw(context: Context): Boolean {
        val current = UltraSdkCredentials.captureOwnerMarker(context)
        val directory = directory(context) ?: return false
        if (!current()) return false
        val result = DirectRoot.run(context, "/system/bin/rm -rf ${quote(directory)}")
        if (result.code != 0 || !current()) return false
        File(context.applicationContext.filesDir, RECORD).delete()
        return true
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
