package dev.maplesu.manager.runtime.authorization

import dev.maplesu.manager.runtime.ultra.UltraAuthorizationAttempt
import dev.maplesu.manager.runtime.ultra.SuUidProfile
import org.junit.Assert.*
import org.junit.Test

class UltraCurrentIdentityObservationTest {
    private fun identity(uid: Int, signer: Char = 'a', installedAt: Long = 1000,
                         updatedAt: Long = 2000, path: String = "/data/app/example/base.apk") =
        ApplicationIdentity(uid, "example.app", listOf("example.app"), setOf(signer.toString().repeat(64)),
            installedAt, updatedAt, path)

    private fun snapshot(vararg identities: ApplicationIdentity) = AuthorizationSnapshot(
        true, identities.associateBy { it.uid }, rev = 4,
        uidRev = identities.associate { it.uid to 4L },
    )

    @Test fun samePackageAcrossUsersKeepsEachFreshFullUidIdentity() {
        val primary = identity(10123)
        val otherUser = identity(110123)
        val current = mapOf(primary.uid to primary, otherUser.uid to otherUser)
        val reads = arrayListOf<Int>()
        val observed = SuperuserOps.currentIdentityObservation(snapshot(primary, otherUser)) { uid ->
            reads += uid
            current[uid]
        }
        assertEquals(listOf(10123, 110123), reads)
        assertEquals(current, observed.grants)
        assertEquals(4L, observed.rev)
        assertFalse("Observation is never a new authority", observed.enabled)
    }

    @Test fun legitimateUpdateReturnsCurrentFactsRatherThanTheStoredGrant() {
        val recorded = identity(10123)
        val updated = identity(10123, updatedAt = 3000, path = "/data/app/updated/base.apk")
        val observed = SuperuserOps.currentIdentityObservation(snapshot(recorded)) { updated }
        assertEquals(updated, observed.grants[10123])
        assertNotEquals(recorded, observed.grants[10123])
    }

    @Test fun missingReinstalledResignedOrDifferentUidIdentityIsOmitted() {
        val recorded = identity(10123)
        for (current in listOf(null, identity(10123, signer = 'b'),
                identity(10123, installedAt = 1500), identity(110123))) {
            assertTrue(SuperuserOps.currentIdentityObservation(snapshot(recorded)) { current }.grants.isEmpty())
        }
    }

    @Test fun disabledRevokedAndPendingSwitchNeverPublishAnAllowedIdentity() {
        val current = identity(10123)
        val authority = snapshot(current)
        val states = listOf(authority.copy(enabled = false),
            authority.copy(tombs = listOf(SuperuserTomb(current.uid, current.packageName, 5, 3000))),
            authority.copy(switchPending = true))
        for (state in states) {
            assertTrue(SuperuserOps.currentIdentityObservation(state) { current }.grants.isEmpty())
        }
    }

    @Test(expected = IllegalStateException::class)
    fun corruptAuthorityDoesNotBecomeAnEmptyObservation() {
        SuperuserOps.currentIdentityObservation(AuthorizationSnapshot.unreadable()) { null }
    }

    @Test fun identityReadTokenRejectsAnOldScopeRevisionOrFullUidMember() {
        val owner = Any()
        val current = identity(110123)
        val identities = mapOf(current.uid to current)
        fun matches(scope: Any = owner, revision: Long = 4, uid: Int = current.uid,
                    name: String = current.packageName, live: Boolean = true, allow: Boolean = true) =
            currentIdentityReadMatches(owner, scope, 4, revision, identities, uid, name, allow) { live }
        assertTrue(matches())
        assertFalse(matches(scope = Any()))
        assertFalse(matches(revision = 3))
        assertFalse(matches(revision = 5))
        assertFalse(matches(live = false))
        assertFalse(matches(uid = 10123))
        assertFalse(matches(name = "example.replacement"))
        assertTrue("A revoked UID may be absent from the allowed observation", matches(uid = 10123, allow = false))
        assertFalse(matches(scope = Any(), allow = false))
        assertFalse(matches(revision = 3, allow = false))
    }

    @Test fun brokerNeverRegrantsADisabledRevokedOrReinstalledManager() {
        fun manager(installedAt: Long = 1000) = ApplicationIdentity(10123, "dev.maplesu.manager",
            listOf("dev.maplesu.manager"), setOf("a".repeat(64)), installedAt, 2000, "/data/app/manager/base.apk")
        val recorded = manager()
        val authority = snapshot(recorded)
        val cases = listOf(authority.copy(enabled = false) to recorded,
            authority.copy(tombs = listOf(SuperuserTomb(recorded.uid, recorded.packageName, 5, 3000))) to recorded,
            authority to manager(1500))
        for ((state, installed) in cases) {
            var profile = SuUidProfile(recorded.uid, true, recorded.packageName)
            var grants = 0
            var revokes = 0
            val mirror = UltraAllowlistMirror(object : UltraAllowlistPort {
                override val supportsUidProfiles = true
                override fun uidProfiles() = mapOf(profile.uid to profile)
                override fun identity(uid: Int) = installed.takeIf { it.uid == uid }
                override fun packages(): Set<String> = error("Owner flow uses full UID profiles")
                override fun setAllowed(packageName: String, allowed: Boolean): UltraAuthorizationAttempt =
                    error("Owner flow uses full UID writes")
                override fun setAllowed(uid: Int, packageName: String, allowed: Boolean): UltraAuthorizationAttempt {
                    if (allowed) grants++ else revokes++
                    profile = SuUidProfile(uid, allowed, packageName)
                    return UltraAuthorizationAttempt.local().withReadback(true)
                }
            })
            val result = mirror.apply(state)
            assertEquals(0, grants)
            assertEquals(1, revokes)
            assertTrue(result.detail, result.ok)
            assertFalse(profile.allow)
            assertTrue(SuperuserOps.currentIdentityObservation(state) { installed }.grants.isEmpty())
        }
    }
}
