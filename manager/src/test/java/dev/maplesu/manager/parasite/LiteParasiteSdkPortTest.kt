package dev.maplesu.manager.parasite

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiteParasiteSdkPortTest {
    private val lite = object : ParasiteSdkPort {
        override fun precheck(targetPidCmdline: String) = ParasiteSdkResult.Ok()
        override fun implantApp(targetPidCmdline: String, soPath: String) = ParasiteSdkResult.Ok()
        override fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String) = ParasiteSdkResult.Ok()
    }

    @Test fun liteSelectsTheCurrentIdentitySdkFactoryOnce() {
        var calls = 0
        assertSame(lite, selectProductionParasitePort(WorkMode.LITE, { calls++; lite }))
        assertEquals(1, calls)
    }

    @Test fun ksuAndUnresolvedDoNotOpenTheLiteSdk() {
        for (mode in listOf(WorkMode.KSU, null)) {
            val selected = selectProductionParasitePort(mode, { error("unexpected Lite SDK factory") })
            assertEquals(ParasiteCodes.ULTRA_UNAVAILABLE,
                (selected.precheck("dev.fixture.host") as ParasiteSdkResult.Failed).code)
        }
    }

    @Test fun explicitUnavailableFactoryIsPreserved() {
        val unavailable = UnavailableParasiteSdkPort(UltraAvailability.UNLINKED)
        assertSame(unavailable, selectProductionParasitePort(null, { lite }, { unavailable }))
    }
}

