package dev.maplesu.manager.workmode.service

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Root-owned protocol endpoint, retained by the official persistent Installer RootService. */
internal class ModeSwitchRootBridge(
    private val managerUid: Int,
    nativeDirectory: String,
) : Closeable {
    private val commands = ModeSwitchRootCommandRunner()
    private val lease = ModeSwitchRootLease()
    private val requests = ModeSwitchRootRequests(
        execute = commands::execute,
        uninstall = { ModeSwitchRootMain.uninstall(nativeDirectory) },
        acquire = lease::acquire,
        release = lease::release,
        isHeld = lease::isHeld,
    )
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "maplesu-mode-switch-binder").apply { isDaemon = true }
    }
    private var managerLifetime: IBinder? = null

    fun <T> withInstallLock(id: String, action: () -> T): T = lease.withInstallLock(id, action)

    /** Runs on the command worker, so an accepted write finishes before its owner is released. */
    private fun claimManager(candidate: IBinder) {
        if (managerLifetime == candidate) return
        check(!lease.isHeld() || managerLifetime?.isBinderAlive != true) { ModeSwitchRootLease.BUSY }
        lease.close()
        candidate.linkToDeath({
            runCatching {
                worker.execute {
                    if (managerLifetime == candidate) {
                        lease.close()
                        managerLifetime = null
                    }
                }
            }
        }, 0)
        managerLifetime = candidate
    }

    /** callerUid comes from the authenticated Binder transaction, never from its payload. */
    fun open(callerUid: Int, tokenFile: ParcelFileDescriptor, timeoutMillis: Int,
             identity: Bundle): ParcelFileDescriptor {
        check(Process.myUid() == 0 && callerUid == managerUid) { ModeSwitchRootProtocol.UNAVAILABLE }
        require(timeoutMillis == 1_000 || timeoutMillis == 180_000) { ModeSwitchRootProtocol.BAD_REQUEST }
        val owner = requireNotNull(identity.getBinder("managerLifetime"))
        val stat = Os.fstat(tokenFile.fileDescriptor)
        ModeSwitchRootTokenMetadata.requireValid(OsConstants.S_ISREG(stat.st_mode), stat.st_uid,
            stat.st_mode, stat.st_nlink, stat.st_size, managerUid)
        val token = ByteArray(ModeSwitchRootProtocol.TOKEN_BYTES)
        var endpoints: Array<ParcelFileDescriptor>? = null
        try {
            check(Os.pread(tokenFile.fileDescriptor, token, 0, token.size, 0L) == token.size) {
                ModeSwitchRootProtocol.UNAVAILABLE
            }
            val pair = ParcelFileDescriptor.createSocketPair()
            endpoints = pair
            val timeout = StructTimeval.fromMillis(10_000)
            Os.setsockoptTimeval(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, timeout)
            Os.setsockoptTimeval(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, timeout)
            // The app may use this FD but SELinux denies setopt on a root-owned socket.
            // Configure its existing operation-specific timeout before the Binder handoff.
            val clientTimeout = StructTimeval.fromMillis(timeoutMillis.toLong())
            Os.setsockoptTimeval(pair[1].fileDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO, clientTimeout)
            Os.setsockoptTimeval(pair[1].fileDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_SNDTIMEO, clientTimeout)
            identity.clear()
            identity.putInt("serviceUid", Process.myUid())
            identity.putInt("servicePid", Process.myPid())
            identity.putInt("managerUid", managerUid)
            worker.execute {
                pair[0].use { endpoint ->
                    try {
                        claimManager(owner)
                        // The pair was created by root. Its owner is the verified Binder caller,
                        // not SO_PEERCRED (which describes the pair's root creator on both ends).
                        ModeSwitchRootProtocol.serve(callerUid, managerUid,
                            FileInputStream(endpoint.fileDescriptor), FileOutputStream(endpoint.fileDescriptor),
                            token, requests::handle)
                    } catch (error: Exception) {
                        logModeSwitchRootFailure("server-serve", error)
                        // Accepted operations finish independently of the manager's lifetime.
                    } finally { token.fill(0) }
                }
            }
            return pair[1]
        } catch (error: Exception) {
            logModeSwitchRootFailure("server-open", error)
            token.fill(0)
            endpoints?.forEach { runCatching { it.close() } }
            throw error
        }
    }

    override fun close() {
        // An accepted write retains the kernel lock until its worker has finished.
        worker.execute { lease.close() }
        worker.shutdown()
    }
}

/** The descriptor grants access without asking the root domain to traverse app-private data. */
internal object ModeSwitchRootTokenMetadata {
    fun requireValid(regular: Boolean, uid: Int, mode: Int, links: Long, size: Long, managerUid: Int) {
        check(regular && uid == managerUid && mode and 0x1ff == 0x180 && links == 1L &&
            size == ModeSwitchRootProtocol.TOKEN_BYTES.toLong()) { ModeSwitchRootProtocol.UNAVAILABLE }
    }
}
