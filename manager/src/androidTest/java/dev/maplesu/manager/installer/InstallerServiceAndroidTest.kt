package dev.maplesu.manager.installer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.Natives
import com.maplesu.core.ui.util.rootAvailable
import dev.maplesu.manager.runtime.IdentityProbeService
import dev.maplesu.manager.ImageRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Selected-device service tests. Never writes a partition and never changes SELinux. */
@RunWith(AndroidJUnit4::class)
class InstallerServiceAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun requireSelectedDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingRootInstaller") == "true")
    }

    private fun startActivity(component: String) {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $component").use { descriptor ->
            val output = FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            assertTrue(output, output.contains("Status: ok"))
        }
    }

    @Before fun keepManagerInForeground() {
        requireSelectedDevice()
        startActivity("${context.packageName}/com.maplesu.core.ui.MainActivity")
    }

    /** Opt in with existingRootInstaller=true and installerStatusDiagnostics=true. */
    @Test fun persistedInstallationStatusMatchesOrdinaryAndDaemonBindings() {
        requireSelectedDevice()
        assumeTrue(InstrumentationRegistry.getArguments().getString("installerStatusDiagnostics") == "true")
        val taskId = InstallerServiceClient.lastTaskId(context)
        val failures = mutableListOf<Pair<String, Exception>>()
        val observations = linkedMapOf<String, Bundle>()
        fun probe(route: String, read: () -> Bundle) {
            val report = Bundle().apply {
                putString("installerStatusRoute", route)
                putString("installerStatusRequestedTaskId", taskId ?: "")
            }
            try {
                val status = read()
                observations[route] = status
                report.putString("installerStatusResult", "OK")
                report.putString("installerStatusBundle", status.toString())
            } catch (error: Exception) {
                failures += route to error
                report.putString("installerStatusResult", "FAILED")
                report.putString("installerStatusCauseChain", error.stackTraceToString())
            }
            instrumentation.sendStatus(2, report)
        }
        probe("installationStatus") { InstallerServiceClient.installationStatus(context, taskId) }
        probe("ordinaryBinding") {
            InstallerServiceClient.withService(context) { it.getBootInstall(taskId ?: "") }
        }
        probe("daemonBinding") {
            InstallerServiceClient.withService(context, persistent = true) { it.getBootInstall(taskId ?: "") }
        }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("installerStatusComparison", observations.entries.joinToString("; ") { (route, status) ->
                "$route: task=${status.getString("taskId")}, state=${status.getString("state")}, " +
                    "active=${status.getBoolean("operationActive")}, error=${status.getString("error")}"
            })
        })
        if (failures.isNotEmpty()) {
            throw AssertionError("INSTALLER_STATUS_DIAGNOSTICS_FAILED: ${failures.joinToString { it.first }}").also {
                failures.forEach { (_, cause) -> it.addSuppressed(cause) }
            }
        }
        if (taskId != null) {
            observations.forEach { (route, status) ->
                assertEquals("$route returned a different durable task", taskId, status.getString("taskId"))
            }
        }
    }

    @Test fun heldReadOnlyDevicesMatchTheExistingBootChain() {
        requireSelectedDevice()
        val wasManager = Natives.isManager
        val managerRoot = rootAvailable()
        val expected = InstallerRootSession.open(context).use { it.snapshotCurrentBoot() }
        InstallerServiceClient.withService(context) { service ->
            val response = InstallerServiceClient.observe(service)
            assertEquals(0, response.getInt("serviceUid", -1))
            assertNotEquals(Process.myPid(), response.getInt("servicePid", -1))
            assertEquals(Process.myUid(), response.getInt("callerUid", -1))
            assertFalse(response.hasFileDescriptors())
            val actual = InstallerServiceClient.decode(response)
            assertEquals(expected.kernelRelease, actual.kernelRelease)
            assertEquals(expected.firmware, actual.firmware)
            assertEquals(expected.slotSuffix, actual.slotSuffix)
            assertEquals(expected.selinux, actual.selinux)
            // Ordinary observation excludes partitions reserved for explicit signing sessions.
            val expectedBootChain = expected.partitions.filterKeys { it !in setOf("recovery", "vbmeta_system") }
            assertEquals(expectedBootChain, actual.partitions.mapValues { it.value.copy(identity = null) })
            assertEquals(actual.partitions, InstallerServiceClient.decode(InstallerServiceClient.observe(service)).partitions)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("installerServiceUid", response.getInt("serviceUid").toString())
                putString("installerServicePid", response.getInt("servicePid").toString())
                putString("installerServiceCallerUid", response.getInt("callerUid").toString())
            })
            for ((name, partition) in actual.partitions) {
                val row = requireNotNull(response.getBundle(name))
                assertTrue(row.getLong("deviceNumber") > 0)
                assertTrue(row.getLong("inode") > 0)
                val identity = requireNotNull(partition.identity)
                assertEquals(row.getLong("deviceNumber"), identity.deviceNumber)
                assertEquals(row.getLong("inode"), identity.inode)
                assertEquals(row.getLong("filesystem"), identity.filesystem)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("installerServicePartition", partition.path)
                    putString("installerServiceSha256", partition.sha256)
                    putString("installerServiceSize", partition.size.toString())
                    putString("installerServiceDeviceNumber", row.getLong("deviceNumber").toString())
                    putString("installerServiceInode", row.getLong("inode").toString())
                    putString("installerServiceFilesystem", row.getLong("filesystem").toString())
                })
            }
        }
        assertEquals(wasManager, Natives.isManager)
        assertEquals(managerRoot, rootAvailable())
    }

    @Test fun rejectsInvalidServiceResponses() {
        requireSelectedDevice()
        InstallerServiceClient.withService(context) { service ->
            val valid = InstallerServiceClient.observe(service)
            val mutations: List<Pair<String, (Bundle) -> Unit>> = listOf(
                "service UID" to { it.putInt("serviceUid", 2000) },
                "service PID" to { it.putInt("servicePid", Process.myPid()) },
                "caller full UID" to { it.putInt("callerUid", Process.myUid() + 100_000) },
                "duplicate partition" to { it.putStringArrayList("partitions", arrayListOf("boot", "boot", "vbmeta")) },
                "unsupported partition" to { it.putStringArrayList("partitions", arrayListOf("boot", "system", "vbmeta")) },
                "missing boot" to { it.remove("boot") },
                "zero device number" to { it.getBundle("boot")!!.putLong("deviceNumber", 0) },
                "missing device number" to { it.getBundle("boot")!!.remove("deviceNumber") },
                "missing inode" to { it.getBundle("boot")!!.remove("inode") },
                "missing filesystem" to { it.getBundle("boot")!!.remove("filesystem") },
                "negative filesystem" to { it.getBundle("boot")!!.putLong("filesystem", -1) },
                "non-device path" to { it.getBundle("boot")!!.putString("resolvedPath", "/data/boot.img") },
                "invalid slot" to { it.putString("slotSuffix", "_c") },
            )
            for ((name, mutate) in mutations) {
                val invalid = valid.deepCopy()
                mutate(invalid)
                assertThrows("Response mutation: $name", RuntimeException::class.java) {
                    InstallerServiceClient.decode(invalid)
                }
            }
            // SELinux is an observed fact; identity and transaction checks control qualification.
            for (status in listOf("Enforcing", "Permissive", "Unknown")) {
                val observed = valid.deepCopy().apply { putString("selinux", status) }
                assertEquals(status, InstallerServiceClient.decode(observed).selinux)
            }
        }
    }

    @Test fun decodedSnapshotsRetainEachObservedPartitionIdentity() {
        requireSelectedDevice()
        InstallerServiceClient.withService(context) { service ->
            val response = InstallerServiceClient.observe(service)
            val original = InstallerServiceClient.decode(response).partitions
            for (field in listOf("deviceNumber", "inode", "filesystem")) {
                val changed = response.deepCopy()
                val row = requireNotNull(changed.getBundle("boot"))
                row.putLong(field, row.getLong(field) + 1)
                assertNotEquals("Changed $field must remain visible in the decoded snapshot",
                    original, InstallerServiceClient.decode(changed).partitions)
            }
        }
    }

    @Test fun ordinaryAppProcessRejectsPrivilegedPartitionReader() {
        requireSelectedDevice()
        assertNotEquals(0, Process.myUid())
        val error = assertThrows(IllegalStateException::class.java) { InstallerPartitionReader.observe() }
        assertEquals("INSTALLER_ROOT_REQUIRED", error.message)
        assertThrows(IllegalStateException::class.java) { InstallerPartitionReader.openBootImage(context.cacheDir) }
    }

    @Test fun currentBootImageReachesTheProductionImagePipeline() {
        requireSelectedDevice()
        InstallerServiceClient.withService(context) { service ->
            val response = Bundle()
            val image = service.openBootImage(response)
            ParcelFileDescriptor.AutoCloseInputStream(image).use { input ->
                val observed = InstallerServiceClient.decode(response)
                val boot = observed.partitions.getValue("boot")
                assertFalse(response.hasFileDescriptors())
                val stat = Os.fstat(image.fileDescriptor)
                assertTrue("Only a regular snapshot may cross Binder", OsConstants.S_ISREG(stat.st_mode))
                assertEquals("The snapshot must be unlinked before returning", 0L, stat.st_nlink)
                assertEquals(boot.size, image.statSize)
                val error = assertThrows(ErrnoException::class.java) {
                    Os.pwrite(image.fileDescriptor, byteArrayOf(0), 0, 1, 0)
                }
                assertEquals(OsConstants.EBADF, error.errno)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    total += count
                }
                assertEquals(boot.size, total)
                assertEquals(boot.sha256, digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
        val snapshot = InstallerServiceClient.analyzeCurrentBoot(context)
        val result = requireNotNull(ImageRepository(context).restore())
        val boot = snapshot.partitions.getValue("boot")
        assertEquals(boot.sha256, result.report.sha256)
        assertEquals(boot.size, result.report.fileSize)
        val roundtrip = JSONObject(requireNotNull(result.roundTripJson))
        assertEquals(snapshot.kernelRelease, roundtrip.getString("kernelRelease"))
        assertTrue(roundtrip.getBoolean("containerByteIdentical"))
        assertFalse(roundtrip.getBoolean("flashableCandidate"))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("installerImageSha256", result.report.sha256)
            putString("installerImageSize", result.report.fileSize.toString())
            putString("installerImageKernelRelease", roundtrip.getString("kernelRelease"))
            putString("installerImageReadOnlySnapshot", "true")
        })
    }

    @Test fun currentBootKernelRebuildRemainsAComponentArtifact() {
        requireSelectedDevice()
        val snapshot = InstallerServiceClient.analyzeCurrentBoot(context)
        val repository = ImageRepository(context)
        val imported = requireNotNull(repository.restore()).report
        val kernel = File.createTempFile("current-kernel-component-", ".bin", context.cacheDir)
        try {
            RandomAccessFile(File(context.filesDir, "images/imported.img"), "r").use { input ->
                input.seek(imported.kernelOffset)
                kernel.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = imported.kernelSize
                    while (remaining > 0) {
                        val count = minOf(buffer.size.toLong(), remaining).toInt()
                        input.readFully(buffer, 0, count)
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    output.fd.sync()
                }
            }
            repository.withRebuiltKernel(Uri.fromFile(kernel)) { file, report ->
                assertEquals(snapshot.partitions.getValue("boot").sha256, report.source.sha256)
                assertFalse(report.kernelChanged)
                assertEquals(imported.kernelSize, report.image.kernelSize)
                assertEquals(imported.ramdiskSize, report.image.ramdiskSize)
                assertEquals(0L, report.image.signatureSize)
                assertEquals(report.image.sha256, dev.maplesu.image.BootImageInspector.inspect(file).sha256)
                assertFalse(JSONObject(report.toJson()).getBoolean("flashableCandidate"))
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("componentRebuildSourceSha256", report.source.sha256)
                    putString("componentRebuildSha256", report.image.sha256)
                    putString("componentRebuildKernelChanged", report.kernelChanged.toString())
                    putString("componentRebuildFlashableCandidate", "false")
                })
            }
            assertEquals(imported.sha256, requireNotNull(repository.restore()).report.sha256)
        } finally { kernel.delete() }
    }

    @Test fun installationServiceRejectsAnActualForeignBinderUid() {
        requireSelectedDevice()
        val component = instrumentation.context.packageName +
            "/androidx.test.core.app.InstrumentationActivityInvoker\$EmptyActivity"
        startActivity(component)
        // Opening the peer package must not leave the instrumented Manager in the background:
        // the selected ROM freezes it, including Binder callbacks, after a few seconds.
        keepManagerInForeground()
        val connected = CountDownLatch(1)
        var peer: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                peer = service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent().setComponent(ComponentName(instrumentation.context.packageName,
            IdentityProbeService::class.java.name)).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Test UID service did not bind", connected.await(10, TimeUnit.SECONDS))
            InstallerServiceClient.withService(context) { service ->
                for (code in 1..2) {
                    val request = Parcel.obtain()
                    val response = Parcel.obtain()
                    try {
                        request.writeInterfaceToken(IdentityProbeService.DESCRIPTOR)
                        request.writeStrongBinder(service.asBinder())
                        assertTrue(requireNotNull(peer).transact(IBinder.FIRST_CALL_TRANSACTION + code, request, response, 0))
                        response.readException()
                        val peerUid = response.readInt()
                        assertNotEquals(Process.myUid(), peerUid)
                        assertEquals("Real production request $code must reject the foreign UID", 0, response.readInt())
                    } finally {
                        request.recycle()
                        response.recycle()
                    }
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
