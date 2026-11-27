package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.runtime.authorization.*
import org.junit.Assert.*
import org.junit.Test

/** Signed installation facts constrain the full-UID authority; a signer alone never renews a grant. */
class UltraManagerSignatureAuthorizationTest {
    private val name = "dev.maplesu.manager"
    private fun identity(uid: Int = 10999, signer: String = "a", installed: Long = 10) =
        ApplicationIdentity(uid, name, listOf(name), setOf(signer.repeat(64)), installed, installed + 1, "/data/app/new/base.apk")
    private fun authority(record: ApplicationIdentity = identity()) = AuthorizationSnapshot(
        true, mapOf(record.uid to record), rev = 4, uidRev = mapOf(record.uid to 4L))

    private class Port : UltraAllowlistPort {
        override val supportsUidProfiles = true
        var current: ApplicationIdentity? = null
        var profiles: Map<Int, SuUidProfile>? = emptyMap()
        var writeError: String? = null
        val writes = mutableListOf<SuUidProfile>()
        override fun uidProfiles() = profiles
        override fun identity(uid: Int) = current?.takeIf { it.uid == uid }
        override fun packages(): Set<String> = error("Full UID observation required")
        override fun setAllowed(packageName: String, allowed: Boolean): UltraAuthorizationAttempt = error("Full UID mutation required")
        override fun setAllowed(uid: Int, packageName: String, allowed: Boolean): UltraAuthorizationAttempt {
            val profile = SuUidProfile(uid, allowed, packageName)
            writes += profile
            writeError?.let { return UltraAuthorizationAttempt.local(it) }
            profiles = profiles.orEmpty() + (uid to profile)
            return UltraAuthorizationAttempt.local().withReadback(true)
        }
    }
    private fun port(current: ApplicationIdentity? = identity()) = Port().apply {
        this.current = current
        profiles = mapOf(10999 to SuUidProfile(10999, true, name),
            2000 to SuUidProfile(2000, true, "com.android.shell"))
    }

    @Test fun sameSignerReinstallRequiresACurrentExplicitGrant() {
        val current = identity(uid = 11001, installed = 20)
        val port = port(current)
        assertTrue(UltraAllowlistMirror(port).apply(authority()).ok)
        assertFalse(port.profiles!![10999]!!.allow)
        assertTrue(port.profiles!![2000]!!.allow)
        assertTrue(port.writes.none { it.allow })
        assertTrue(UltraAllowlistMirror(port).apply(authority(current)).ok)
        assertTrue(port.profiles!![11001]!!.allow)
    }

    @Test fun differentlySignedOrOtherUserManagerIsRevokedWithoutNameRemapping() {
        for (current in listOf(identity(signer = "b"), identity(uid = 110999))) {
            val port = port(current)
            assertTrue(UltraAllowlistMirror(port).apply(authority()).ok)
            assertEquals(listOf(SuUidProfile(10999, false, name)), port.writes)
        }
    }

    @Test fun unknownIdentityRevokesOnceAndNeverRebuildsByPackageName() {
        val port = port(null)
        val mirror = UltraAllowlistMirror(port)
        repeat(4) { assertTrue(mirror.apply(authority()).ok) }
        assertEquals(listOf(SuUidProfile(10999, false, name)), port.writes)
        assertFalse(ManagerSuperuserPolicy.decide(authority(), 10999, null).allowed)
    }

    @Test fun unknownIdentityWithoutAManagerGrantDoesNotWrite() {
        val port = port(null).apply { profiles = emptyMap() }
        repeat(20) { assertTrue(UltraAllowlistMirror(port).apply(AuthorizationSnapshot.empty()).ok) }
        assertTrue(port.writes.isEmpty())
    }

    @Test fun absentManagerGrantNeverGetsReaddedBySignerOrName() {
        val port = port(identity()).apply { profiles = emptyMap() }
        assertTrue(UltraAllowlistMirror(port).apply(AuthorizationSnapshot(true, emptyMap())).ok)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun failedRevocationRetainsItsFirstErrorAndNoGrantIsSubmitted() {
        val port = port(identity(signer = "b")).apply { writeError = "WRITE_FAILED" }
        val result = UltraAllowlistMirror(port).apply(authority())
        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.contains("WRITE_FAILED"))
        assertEquals(listOf(SuUidProfile(10999, false, name)), port.writes)
        assertTrue(port.profiles!![10999]!!.allow)
    }

    @Test fun unreadableListAndFailedGrantAreNotAcceptedAsRestored() {
        val unreadable = port().apply { profiles = null }
        assertFalse(UltraAllowlistMirror(unreadable).apply(authority()).ok)
        assertTrue(unreadable.writes.isEmpty())
        val denied = port().apply { writeError = "ADD_FAILED" }
        val result = UltraAllowlistMirror(denied).apply(authority())
        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.contains("ADD_FAILED"))
        assertEquals(1, denied.writes.size)
    }

    @Test fun replacementInvalidatesTheIdentityReadBeforeAnotherMutation() {
        val scope = Any()
        val old = identity()
        val observation = mapOf(old.uid to old)
        var live = true
        assertTrue(currentIdentityReadMatches(scope, scope, 4, 4, observation, old.uid, name, true) { live })
        live = false
        assertFalse(currentIdentityReadMatches(scope, scope, 4, 4, observation, old.uid, name, true) { live })
        val port = port(identity(signer = "b"))
        assertTrue(UltraAllowlistMirror(port).apply(authority()).ok)
        assertFalse(port.profiles!![old.uid]!!.allow)
    }

    @Test fun ownerTransportNeverOverridesDisabledOrRevokedStandardSu() {
        val record = identity()
        for (snapshot in listOf(authority().copy(enabled = false), authority().copy(
                tombs = listOf(SuperuserTomb(record.uid, name, 5, 100))))) {
            assertFalse(ManagerSuperuserPolicy.decide(snapshot, record.uid, record).allowed)
            assertTrue(SuperuserOps.currentIdentityObservation(snapshot) { record }.grants.isEmpty())
        }
    }

    @Test fun installationChangingDuringObservationDoesNotKeepTheStoredGrant() {
        val record = identity()
        val changed = identity(installed = 20)
        assertTrue(SuperuserOps.currentIdentityObservation(authority(record)) { changed }.grants.isEmpty())
        assertEquals(SuCodes.AUTH_IDENTITY_CHANGED, ManagerSuperuserPolicy.decide(authority(record), record.uid, changed).reason)
    }

    @Test fun authorityRequiresTheCompleteSigningAndPackageMemberSet() {
        val record = identity()
        assertTrue(record.matches(identity()))
        assertFalse(record.matches(identity(signer = "b")))
        val shared = ApplicationIdentity(record.uid, listOf(record.members.getValue(name),
            ApplicationIdentity.Installation("other.app", record.signers, 10, 11, "/data/app/other/base.apk")))
        assertFalse(record.matches(shared))
        assertThrows(IllegalArgumentException::class.java) {
            ApplicationIdentity(record.uid, name, listOf(name), emptySet(), 10, 11, "/data/app/base.apk")
        }
    }
}
