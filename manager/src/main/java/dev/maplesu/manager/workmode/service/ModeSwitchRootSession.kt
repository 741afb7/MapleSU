package dev.maplesu.manager.workmode.service

import android.content.Context
import android.os.Bundle
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Log
import dev.maplesu.manager.installer.InstallerServiceClient
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.workmode.switch.HookResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * Narrow manager-to-root session for a mode switch. Call on an IO thread before uninstalling
 * Ultra. Only ensureReady may bind/start the official persistent root daemon;
 * EXEC and UNINSTALL keep its Binder and never relaunch/replay a mutation.
 */
class ModeSwitchRootSession(context: Context) {
    private val context = context.applicationContext
    private val configuration by lazy {
        ModeSwitchRootLaunch(
            managerUid = Process.myUid(),
            apk = this.context.applicationInfo.sourceDir,
            nativeDirectory = this.context.applicationInfo.nativeLibraryDir,
            tokenFile = File(this.context.filesDir, ModeSwitchRootLaunch.TOKEN_FILE).canonicalPath,
        ).also { it.validate() }
    }
    private val binderOwner = ModeSwitchRootBinderOwner(
        bind = { InstallerServiceClient.withService(this.context, persistent = true) { it } },
        alive = { it.asBinder().isBinderAlive },
    )
    private val channel by lazy {
        val launch = configuration
        ModeSwitchRootChannel(
            token = { loadOrCreateToken(File(launch.tokenFile)) },
            connect = { timeoutMillis ->
                val service = binderOwner.current()
                val identity = Bundle().apply { putBinder("managerLifetime", managerLifetime) }
                val descriptor = ParcelFileDescriptor.open(File(launch.tokenFile),
                    ParcelFileDescriptor.MODE_READ_ONLY).use {
                    service.openModeSwitchRoot(it, timeoutMillis, identity)
                }
                try {
                    check(identity.getInt("serviceUid", -1) == 0 &&
                        identity.getInt("servicePid", -1) > 0 && identity.getInt("servicePid") != Process.myPid() &&
                        identity.getInt("managerUid", -1) == Process.myUid()) { ModeSwitchRootProtocol.UNAVAILABLE }
                    object : ModeSwitchRootConnection {
                        // Identity belongs to the authenticated libsu daemon which created the FD.
                        override val peerUid = identity.getInt("serviceUid")
                        override val input = FileInputStream(descriptor.fileDescriptor)
                        override val output = FileOutputStream(descriptor.fileDescriptor)
                        override fun close() = descriptor.close()
                    }
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            },
            launch = {
                loadOrCreateToken(File(launch.tokenFile)).fill(0)
                binderOwner.prepare()
            },
            onFailure = { stage, error -> logModeSwitchRootFailure("client-$stage", error) },
        )
    }

    fun ensureReady(): HookResult {
        requireBackground()
        return runCatching { channel.ensureReady() }.getOrElse {
            logModeSwitchRootFailure("configuration", it)
            HookResult.failed(ModeSwitchRootProtocol.UNAVAILABLE)
        }
    }

    fun execute(script: String): RootExecResult {
        requireBackground()
        return channel.execute(script)
    }

    fun uninstallUltra(): HookResult {
        requireBackground()
        return channel.uninstallUltra()
    }

    fun acquire(switchId: String): HookResult {
        requireBackground()
        return channel.acquire(switchId)
    }

    fun release(switchId: String): HookResult {
        requireBackground()
        return channel.release(switchId)
    }

    private fun requireBackground() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MODE_SWITCH_BACKGROUND_THREAD_REQUIRED" }
    }

    private fun loadOrCreateToken(file: File): ByteArray {
        // The app-private persistent token survives a manager restart. No root key is stored here.
        // lstat before opening prevents accepting an existing link at the token path.
        if (file.exists()) check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
        return withLockedModeSwitchRootToken(file) { handle ->
            Os.chmod(file.path, 0x180) // 0600
            val stat = Os.lstat(file.path)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_nlink == 1L && stat.st_mode and 0x3f == 0) { ModeSwitchRootProtocol.UNAVAILABLE }
            if (handle.length() == ModeSwitchRootProtocol.TOKEN_BYTES.toLong()) {
                return@withLockedModeSwitchRootToken ByteArray(ModeSwitchRootProtocol.TOKEN_BYTES).also(handle::readFully)
            }
            val token = ByteArray(ModeSwitchRootProtocol.TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
            handle.setLength(0)
            handle.write(token)
            handle.fd.sync()
            token
        }
    }

    private companion object {
        val managerLifetime = Binder()
    }
}

/** Fixed phase, class, errno and code location: request bytes and exception messages stay private. */
internal fun logModeSwitchRootFailure(stage: String, error: Throwable) {
    val errno = generateSequence(error) { it.cause }.take(8).filterIsInstance<ErrnoException>()
        .firstOrNull()?.errno
    val frame = error.stackTrace.firstOrNull()
    val location = frame?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
    val caller = error.stackTrace.firstOrNull { it.className.startsWith("dev.maplesu.") }
    val callsite = caller?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
    Log.w("MapleSUModeRoot", "stage=$stage exception=${error.javaClass.simpleName} errno=${errno ?: 0} at=$location caller=$callsite")
}

