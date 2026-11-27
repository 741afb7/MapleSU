package dev.maplesu.manager.jailbreak

import me.yuki.fina.adbroot.core.KernelCatalog
import me.yuki.fina.adbroot.core.KernelEngine
import me.yuki.fina.adbroot.core.KernelMap
import me.yuki.fina.adbroot.core.KernelProfiles
import me.yuki.fina.adbroot.core.KernelResolution
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The packaged 98260 catalog is data, so these checks keep the data honest:
 * the default profile must agree with the compiled one, every declared build
 * must stay non-runnable, and a tampered or incomplete entry must be dropped
 * instead of completing itself with defaults.
 */
class KernelCatalogTest {
    private fun document(): JSONObject {
        val file = listOf(
            File("../jailbreak-providers/maplesu-93260/profiles.json"),
            File("jailbreak-providers/maplesu-93260/profiles.json"),
            File("../../jailbreak-providers/maplesu-93260/profiles.json"),
        ).firstOrNull { it.isFile } ?: error("profiles.json not found from " + File(".").absolutePath)
        return JSONObject(file.readText())
    }

    private fun catalog(): KernelCatalog = KernelCatalog.parse(document())

    @Test fun packagedCatalogKeepsTheCompiledDefault() {
        val catalog = catalog()
        assertTrue("unexpected rejected entries: " + catalog.rejected, catalog.rejected.isEmpty())
        val default = catalog.profile(KernelProfiles.DEFAULT_ID)!!
        assertEquals(KernelEngine.RETAINED, default.engine)
        assertTrue(KernelCatalog.same(default, KernelProfiles.DEFAULT_PROFILE))
        assertEquals(KernelProfiles.DEFAULT_ADDRESSES, default.addresses)
        assertEquals(KernelProfiles.DEFAULT_KMI, default.kmi)
        assertEquals(6, default.kernelUapi)
    }

    @Test fun packagedDefaultResolvesToItsOwnProfile() {
        val resolution = KernelProfiles.resolve(KernelProfiles.DEFAULT_RELEASE, catalog())
        assertTrue(resolution is KernelResolution.Packaged)
        resolution as KernelResolution.Packaged
        assertEquals(KernelProfiles.DEFAULT_ID, resolution.profile.id)
        assertEquals("android16-6.12", resolution.release.kmi)
        assertNull(resolution.failureToken)
    }

    @Test fun everyPlantProfileIsRunnableAndCarriesNoAddressTable() {
        val catalog = catalog()
        val plants = catalog.profiles.filter { it.engine == KernelEngine.PLANT }
        assertEquals(13, plants.size)
        for (profile in plants) {
            assertNotNull(profile.runner)
            assertNull(profile.addresses)
            assertTrue(profile.runner!!.matches(Regex("""plant-[0-9]{5}""")))
            val resolution = KernelProfiles.resolve(profile.releases.first(), catalog)
            assertTrue("plant profile must resolve: " + profile.id,
                resolution is KernelResolution.Packaged)
            assertEquals(profile.id, resolution.profileId)
            assertEquals(profile.variant, resolution.variant)
            assertNull(resolution.failureToken)
        }
        assertTrue("declared entries are now real profiles", catalog.declared.isEmpty())
    }

    @Test fun honorSixSixBuildResolvesToItsPackagedPlant() {
        val release = "6.6.118-android15-8-ga812a1946391-abogki576098434-4k"
        val resolution = KernelProfiles.resolve(release, catalog())
        assertTrue(resolution is KernelResolution.Packaged)
        val profile = (resolution as KernelResolution.Packaged).profile
        assertEquals("plant-66118-a812a1946391", profile.id)
        assertEquals("plant-66118", profile.runner)
        assertEquals(KernelEngine.PLANT, profile.engine)
        assertEquals("android15-6.6", profile.kmi)
        assertNull(profile.addresses)
    }

    @Test fun honorPlantRequiresTheExactCommitBuildAndPageSize() {
        val release = "6.6.118-android15-8-ga812a1946391-abogki576098434-4k"
        val catalog = catalog()
        val changed = listOf(
            release.replace("a812a1946391", "a812a1946392"),
            release.replace("576098434", "576098435"),
            release.replace("-4k", "-16k"),
        )
        for (candidate in changed) {
            assertTrue("unexpected profile for " + candidate,
                KernelProfiles.resolve(candidate, catalog) is KernelResolution.Unsupported)
        }
    }

    @Test fun generatedManifestKeepsPlantProfilesResolvable() {
        // The packaged manifest carries `runner` as the profile name and the
        // digest record as `runnerFile`. Parsing it must not drop the profile
        // (a former shape put the record in `runner`, which the catalog rejects).
        val plant = catalog().profiles.first { it.engine == KernelEngine.PLANT }
        val block = JSONObject().put("profiles", JSONArray().put(JSONObject()
            .put("id", plant.id)
            .put("engine", "plant")
            .put("releases", JSONArray(plant.releases))
            .put("variant", plant.variant)
            .put("kmi", plant.kmi)
            .put("kernelUapi", plant.kernelUapi)
            .put("pageSize", plant.pageSize ?: "4k")
            .put("models", JSONArray(plant.models))
            .put("payloadKey", plant.payloadKey)
            .put("runner", plant.runner)
            .put("runnerFile", JSONObject()
                .put("asset", "maplesu-93260/plant/" + plant.runner)
                .put("sha256", "0".repeat(64))
                .put("bytes", 1))))
        val parsed = KernelCatalog.parse(block)
        assertTrue(parsed.rejected.isEmpty())
        assertEquals(plant.id, parsed.profile(plant.id)?.id)
        val resolution = KernelProfiles.resolve(plant.releases.first(), parsed)
        assertTrue(resolution is KernelResolution.Packaged)
        assertEquals(plant.id, resolution.profileId)
    }

    @Test fun kernelMapNeverBindsAPlantProfile() {
        val plant = catalog().profiles.first { it.engine == KernelEngine.PLANT }
        assertFalse(KernelMap.bind(plant, plant.releases.first()))
        assertEquals(KernelProfiles.DEFAULT_PROFILE.id, KernelMap.profile.id)
        KernelMap.reset()
    }

    @Test fun sameKmiFamilyWithoutTheExactBuildIsUnsupported() {
        val changed = KernelProfiles.DEFAULT_RELEASE.replace("dd6fd5c98918", "376529f3e739")
        val resolution = KernelProfiles.resolve(changed, catalog())
        assertTrue(resolution is KernelResolution.Unsupported)
        assertEquals("MAPLESU_93260_KERNEL_UNSUPPORTED", resolution.failureToken)
        // The family is reported for the log, but was never used to pick a profile.
        assertEquals("android16-6.12", resolution.release.kmi)
    }

    @Test fun driftedDefaultIsRecordedAndTheCompiledOneKeepsServing() {
        val block = document()
        block.getJSONArray("profiles").getJSONObject(0)
            .getJSONObject("addresses").put("loadModule", "ffffffc080502689")
        val catalog = KernelCatalog.parse(block)
        assertTrue(catalog.rejected.contains(
            "profile:" + KernelProfiles.DEFAULT_ID + ":default-drift"))
        val resolution = KernelProfiles.resolve(KernelProfiles.DEFAULT_RELEASE, catalog)
        assertTrue(resolution is KernelResolution.Packaged)
        assertEquals(KernelProfiles.DEFAULT_ADDRESSES,
            (resolution as KernelResolution.Packaged).profile.addresses)
    }

    @Test fun aProfileWithoutAFullAddressTableIsRejected() {
        val block = document()
        val profile = block.getJSONArray("profiles").getJSONObject(0)
        profile.put("id", "maplesu-93260-test-missing")
        profile.put("payloadKey", "maplesu-93260-test-missing")
        profile.put("releases", JSONArray().put(
            "6.12.58-android16-6-gaaaaaaa11111-ab11111111-4k"))
        profile.getJSONObject("addresses").remove("traceSysExit")
        val catalog = KernelCatalog.parse(block)
        assertTrue(catalog.rejected.contains("profile:maplesu-93260-test-missing:invalid"))
        assertNull(catalog.profile("maplesu-93260-test-missing"))
        // The compiled default is still the only runnable profile.
        assertTrue(KernelProfiles.resolve(KernelProfiles.DEFAULT_RELEASE, catalog)
            is KernelResolution.Packaged)
    }

    @Test fun modelHintsDoNotChangeProfileIdentity() {
        assertTrue(KernelCatalog.same(KernelProfiles.DEFAULT_PROFILE.copy(models = emptyList()),
            KernelProfiles.DEFAULT_PROFILE))
        assertFalse(KernelCatalog.same(
            KernelProfiles.DEFAULT_PROFILE.copy(kernelUapi = 5), KernelProfiles.DEFAULT_PROFILE))
    }
}
