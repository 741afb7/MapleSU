package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.runtime.authorization.*
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraAuthorizationAttempt
import dev.maplesu.manager.runtime.ultra.UltraAuthorizationState
import dev.maplesu.manager.runtime.ultra.UltraSelfWhitelist
import dev.maplesu.manager.runtime.ultra.SuUidProfile
import java.io.File
import java.nio.file.Paths
import java.util.Base64

/** The independent daemon uses the same store and mirror as the installed boot-session manager. */
internal class DaemonBootSuperuserAuthority(
    private val execute: (String, List<String>) -> String,
    private val identityOf: (Int) -> ApplicationIdentity?,
    private val uidOfPackage: (String) -> Int?,
    private val mirror: SuperuserMirror,
) {
    private fun snapshot(): AuthorizationSnapshot? = runCatching {
        val wire = execute("snapshot", emptyList()).substringAfter("wire=", "").substringBefore(' ')
        SuperuserWire.decode(wire)?.takeUnless { it.corrupt }
    }.getOrNull()

    fun packages(): Set<String>? {
        val current = snapshot() ?: return null
        val last = current.appliedOf(mirror.backend)
        if (mirror !is NoSuperuserMirror &&
            (current.pendingApply() || last == null || !last.ok || last.rev != current.rev) && !sync(current)) return null
        return current.grants.values.filter { record ->
            ManagerSuperuserPolicy.decide(current, record.uid, identityOf(record.uid)).allowed
        }.flatMap { it.members.keys }.toSet()
    }

    fun setAllowed(packageName: String, allowed: Boolean): Boolean = runCatching {
        val before = snapshot() ?: return false
        val installedUid = uidOfPackage(packageName)
        val targets = if (allowed) {
            val uid = installedUid ?: return false
            if (identityOf(uid)?.members?.containsKey(packageName) != true) return false
            listOf(uid)
        } else {
            (listOfNotNull(installedUid) +
                before.grants.values.filter { packageName in it.members }.map { it.uid } +
                before.tombs.filter { it.packageName == packageName }.map { it.uid }).distinct()
        }
        var current = before
        for (uid in targets) {
            val result = SuperuserControl.parseOutcome(execute(if (allowed) "grant" else "revoke", listOf(uid.toString())))
            if (!result.ok) return false
            current = snapshot() ?: return false
            if (current.rev != result.rev) return false
            if (allowed) {
                if (!ManagerSuperuserPolicy.decide(current, uid, identityOf(uid)).allowed) return false
            } else if (current.grantOf(uid) != null && !current.revoked(uid)) return false
        }
        sync(current)
    }.getOrDefault(false)

    private fun sync(current: AuthorizationSnapshot): Boolean = runCatching {
        val outcome = runCatching { mirror.apply(current) }
            .getOrElse { MirrorOutcome.failure(it.javaClass.simpleName) }
        val reply = execute("mark-applied", listOf(mirror.backend, current.rev.toString(), if (outcome.ok) "1" else "0",
            Base64.getEncoder().encodeToString(outcome.detail.toByteArray(Charsets.UTF_8))))
        val recorded = SuperuserControl.parseOutcome(reply)
        outcome.ok && recorded.ok && !recorded.pendingApply && recorded.rev == current.rev
    }.getOrDefault(false)

    companion object {
        fun production(shell: DaemonShell, root: String, managerPackage: String, client: UltraRuntimeClient): DaemonBootSuperuserAuthority? {
            val identity = runCatching { RecoveryIdentity.parse(File(root, "identity.json").readText()) }.getOrNull()
                ?.takeIf { it.packageName == managerPackage } ?: return null
            val identities = PackageIdentityReader(SystemPackageSnapshotSource(identity.uid))
            val user = identity.uid / 100000
            fun execute(op: String, args: List<String>) = SuperuserOps.run(identity.uid,
                Paths.get("/data/user/$user/$managerPackage/no_backup/authorizations.bin"),
                Paths.get(root, "superuser-audit.log"), op, args)
            val catalog by lazy {
                val result = shell.run("pm list packages -U --user all")
                if (result.code == 0) DaemonSuPackageCatalog.parse(result.output) else null
            }
            val mirror = UltraAllowlistMirror(object : UltraAllowlistPort {
                override val supportsUidProfiles: Boolean = true
                private var observedIdentities: Map<Int, ApplicationIdentity>? = null
                override fun beginIdentityRead(revision: Long): Boolean {
                    val encoded = execute("current-identities", emptyList())
                        .substringAfter("identities=", "").substringBefore(' ')
                    val observed = SuperuserWire.decode(encoded)
                        ?.takeIf { !it.corrupt && !it.enabled && it.rev == revision } ?: return false
                    observedIdentities = observed.grants
                    return true
                }
                override fun uidProfiles(): Map<Int, SuUidProfile>? =
                    client.suUidProfiles().takeIf { it.ok }?.profiles?.associateBy { it.uid }
                override fun reconcileUidMapping(): Boolean {
                    if (client.suUidProfiles().receipt.sdkErrorCode != -21001) return false
                    return UltraAuthorizationAttempt.from(client.refreshSuAuthList()).succeeded
                }
                override fun setAllowed(uid: Int, packageName: String, allowed: Boolean): UltraAuthorizationAttempt =
                    UltraAuthorizationState().setUidGranted(UltraSelfWhitelist.clientPort(client), uid, packageName, allowed,
                        durableDecisionCommitted = true)
                override fun identity(uid: Int) = observedIdentities?.get(uid)
                override fun uniquelyMapped(packageName: String, uid: Int): Boolean =
                    catalog?.filter { it.packageName == packageName }?.singleOrNull()?.uid == uid
                override fun packages(): Set<String>? {
                    val result = client.suAuthSnapshot()
                    return if (result.receipt.succeeded && result.receipt.array("packages") != null)
                        result.packages.toSet() else null
                }
                override fun setAllowed(packageName: String, allowed: Boolean): UltraAuthorizationAttempt =
                    UltraAuthorizationAttempt.from(if (allowed) client.addSuAuth(packageName) else client.removeSuAuth(packageName))
            }, setOf(managerPackage, "android", "com.android.shell"))
            return DaemonBootSuperuserAuthority(
                execute = ::execute,
                identityOf = identities::read,
                uidOfPackage = { pkg ->
                    val result = shell.run("pm list packages -U --user $user")
                    if (result.code != 0) null else DaemonSuPackageCatalog.parse(result.output)
                        ?.filter { it.packageName == pkg }?.singleOrNull()?.uid
                },
                mirror = mirror,
            )
        }
    }
}
