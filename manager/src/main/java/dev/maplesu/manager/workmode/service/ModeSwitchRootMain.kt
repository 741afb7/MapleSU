package dev.maplesu.manager.workmode.service

import android.net.LocalServerSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dev.maplesu.manager.liteweb.daemon.UltraSdkLibrary
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent app_process entry. No HTTP surface, root key file, or runtime activation. */
object ModeSwitchRootMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != 0 || args.size != 4) return
        val managerUid = args[1].toIntOrNull() ?: return
        val configuration = ModeSwitchRootLaunch(managerUid, System.getenv("CLASSPATH").orEmpty(), args[3], args[2])
        if (runCatching { configuration.validate() }.isFailure || args[0] != configuration.socketName) return
        val server = runCatching { LocalServerSocket(configuration.socketName) }.getOrNull() ?: return
        val commands = ModeSwitchRootCommandRunner()
        val lease = ModeSwitchRootLease()
        val requests = ModeSwitchRootRequests(
            execute = commands::execute,
            uninstall = { uninstall(configuration.nativeDirectory) },
            acquire = lease::acquire,
            release = lease::release,
            isHeld = lease::isHeld,
        )
        server.use {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return
                socket.use { client ->
                    // Rejected UIDs receive no protocol data and cannot make us read a key.
                    if (client.peerCredentials.uid != managerUid) return@use
                    client.soTimeout = 10_000
                    val token = runCatching { readToken(configuration.tokenFile, managerUid) }.getOrNull()
                        ?: return@use
                    try {
                        // A disconnected manager does not cancel a request already accepted here.
                        // A fresh connection may inspect/recover the same persistent helper later.
                        ModeSwitchRootProtocol.serve(client.peerCredentials.uid, managerUid,
                            client.inputStream, client.outputStream, token, requests::handle)
                    } catch (_: Exception) {
                        // Broken/malformed connections are closed; credentials are never logged.
                    } finally { token.fill(0) }
                }
            }
        }
    }

    private fun readToken(path: String, managerUid: Int): ByteArray {
        val descriptor = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        return FileInputStream(descriptor).use { input ->
            val stat = Os.fstat(descriptor)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == managerUid && stat.st_nlink == 1L &&
                stat.st_mode and 0x3f == 0 && stat.st_size == ModeSwitchRootProtocol.TOKEN_BYTES.toLong())
            ByteArray(ModeSwitchRootProtocol.TOKEN_BYTES).also { bytes ->
                java.io.DataInputStream(input).readFully(bytes)
            }
        }
    }

    internal fun uninstall(nativeDirectory: String): ModeSwitchRootProtocol.Reply {
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "maplesu-mode-switch-sdk").apply { isDaemon = true }
        }
        val work = worker.submit(Callable {
            val library = File(nativeDirectory, "libmaplesu_ultra_bridge.so")
            if (!UltraSdkLibrary.load(library.absolutePath)) {
                ModeSwitchRootProtocol.Reply(-1, detail = "MODE_SWITCH_ULTRA_SDK_UNAVAILABLE")
            } else {
                ModeSwitchRootProtocol.uninstallAndReboot(
                    remove = {
                        val receipt = UltraRuntimeClient().uninstallEnvironment()
                        if (receipt.succeeded) ModeSwitchRootProtocol.Reply(0)
                        else ModeSwitchRootProtocol.Reply(-1, detail = "MODE_SWITCH_ULTRA_UNINSTALL_FAILED")
                    },
                    reboot = { ModeSwitchRootCommandRunner().execute("/system/bin/svc power reboot") },
                )
            }
        })
        return try { work.get(90, TimeUnit.SECONDS) }
        catch (_: Exception) {
            // JNI may still be running. No further mutation is allowed by this helper.
            ModeSwitchRootProtocol.Reply(-1, detail = ModeSwitchRootProtocol.UNCERTAIN)
        } finally { worker.shutdown() }
    }
}
