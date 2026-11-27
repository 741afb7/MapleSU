package dev.maplesu.manager.ultrakernel

import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Replays the real packaged module and its task-profile proof; no synthetic CFI checker. */
class UltraInitBootLegacyNormalRouteTest {
    @Test fun actual510ModuleUsesOnlyTheNativeInitConsumer() = verify(fixture(false))

    @Test fun actual515ModuleUsesOnlyTheNativeInitConsumer() = verify(fixture(true))

    @Test fun normalRouteRejectsAnUnrelatedCallback() = forEachFixture { fixture ->
        val normal = fixture.proof.getValue("normalModule").jsonObject
        val members = normal.getValue("members").jsonArray
        val extra = JsonObject(members.single().jsonObject + ("symbol" to JsonPrimitive("msu_legacy_kernel_probe_work")))
        rejected(fixture, normal(fixture, "members", JsonArray(members + extra)), "ULTRA_INIT_TASK_PROFILE_LEGACY_NORMAL_ROUTE")
    }

    @Test fun normalRouteRequiresInitAndTheLegacyMode() = forEachFixture { fixture ->
        rejected(fixture, normal(fixture, "members", JsonArray(emptyList())), "ULTRA_INIT_TASK_PROFILE_LEGACY_NORMAL_ROUTE")
        rejected(fixture, normal(fixture, "mode", JsonPrimitive("none")), "ULTRA_INIT_TASK_PROFILE_LEGACY_NORMAL_ROUTE")
    }

    @Test fun normalRouteKeepsRuntimeQualificationSeparate() = forEachFixture { fixture ->
        for (field in listOf("moduleLoaded", "runtimeOwnerQualified", "deviceQualified")) {
            rejected(fixture, normal(fixture, field, JsonPrimitive(true)), "ULTRA_INIT_TASK_PROFILE_LEGACY_NORMAL_ROUTE")
        }
    }

    @Test fun ownerAndCheckerProofsRemainRequired() = forEachFixture { fixture ->
        rejected(fixture, JsonObject(fixture.proof + ("moduleLoaded" to JsonPrimitive(true))), "ULTRA_INIT_TASK_PROFILE_LEGACY_OWNER")
        val checker = fixture.proof.getValue("checker").jsonObject
        rejected(fixture, JsonObject(fixture.proof + ("checker" to JsonObject(checker + ("sha256" to JsonPrimitive("0".repeat(64)))))),
            "ULTRA_INIT_TASK_PROFILE_LEGACY_CHECKER_CHANGED")
    }

    @Test fun taskCallbacksRemainIndependentlyRequired() = forEachFixture { fixture ->
        val members = fixture.proof.getValue("members").jsonObject
        rejected(fixture, JsonObject(fixture.proof + ("members" to JsonObject(members - "msu_task_profile_marker_return"))),
            "ULTRA_INIT_TASK_PROFILE_LEGACY_MEMBERS")
    }

    @Test fun uncheckedTraceDoesNotInventACfiTypeId() = forEachFixture { fixture ->
        val member = fixture.proof.getValue("members").jsonObject.getValue("msu_task_profile_sys_enter").jsonObject
        rejected(fixture, member(fixture, member + ("typeIdHex" to JsonPrimitive("0".repeat(16)))),
            "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED:msu_task_profile_sys_enter", argument = true)
    }

    @Test fun uncheckedTraceRequiresTheRecordedProofAndSignature() = forEachFixture { fixture ->
        val member = fixture.proof.getValue("members").jsonObject.getValue("msu_task_profile_sys_enter").jsonObject
        rejected(fixture, member(fixture, member + ("proof" to JsonPrimitive("actual-relocated-module-checker-path"))),
            "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED_PROOF:msu_task_profile_sys_enter")
        val signature = if ("moduleCallbackSignature" in member) "moduleCallbackSignature" else "callbackSignature"
        val expected = if ("moduleCallbackSignature" in member) "MACHINE_ABI" else "SIGNATURE"
        rejected(fixture, member(fixture, member + (signature to JsonPrimitive("*fv(*v;)"))),
            "ULTRA_KERNEL_TASK_PROFILE_LEGACY_UNCHECKED_$expected:msu_task_profile_sys_enter", argument = true)
    }

    @Test fun uncheckedTraceRequiresRealModuleRegistration() = forEachFixture { fixture ->
        val member = fixture.proof.getValue("members").jsonObject.getValue("msu_task_profile_sys_enter").jsonObject
        rejected(fixture, member(fixture, member + ("registration" to JsonArray(emptyList()))),
            "ULTRA_INIT_TASK_PROFILE_LEGACY_REGISTRATION:msu_task_profile_sys_enter")
        val rows = member.getValue("registration").jsonArray
        val changed = JsonObject(rows.first().jsonObject + ("imported" to JsonPrimitive("unrelated_import")))
        rejected(fixture, member(fixture, member + ("registration" to JsonArray(listOf(changed, rows.last())))),
            "ULTRA_INIT_TASK_PROFILE_LEGACY_REGISTRATION:msu_task_profile_sys_enter")
        val at = rows.first().jsonObject.getValue("call").jsonObject.getValue("fileOffset").jsonPrimitive.int
        val changedModule = fixture.module.clone()
        UltraKernelA64.writeLe32(changedModule, at, UltraKernelA64.NOP.toLong())
        rejected(fixture.copy(module = changedModule), fixture.proof,
            "ULTRA_INIT_TASK_PROFILE_LEGACY_REGISTRATION:msu_task_profile_sys_enter")
    }

    private fun member(fixture: Fixture, value: Map<String, JsonElement>): JsonObject = JsonObject(fixture.proof +
        ("members" to JsonObject(fixture.proof.getValue("members").jsonObject +
            ("msu_task_profile_sys_enter" to JsonObject(value)))))

    private data class Fixture(val module: ByteArray, val implementation: UltraKernelTaskProfileTarget.Implementation) {
        val proof get() = requireNotNull(implementation.legacyCfi)
    }

    private fun fixture(variant: Boolean): Fixture {
        val property = System.getProperty("maplesu.legacyInitBridgeAssets")
        assumeTrue("Set maplesu.legacyInitBridgeAssets to real packaged init-bridge assets", !property.isNullOrBlank())
        val root = File(requireNotNull(property))
        val directory = if (variant) File(root, "variants").listFiles().orEmpty()
            .single { File(it, "task-profile-abi.json").isFile } else root
        val report = Json.parseToJsonElement(File(directory, "task-profile-abi.json").readText()).jsonObject
        val module = File(directory, "bridge.ko").readBytes()
        assertEquals(report.getValue("bundledModuleSha256").jsonPrimitive.content, UltraKernelSpec.sha256(module))
        assertEquals(setOf("init_module"), report.getValue("legacyCfi").jsonObject.getValue("normalModule").jsonObject
            .getValue("members").jsonArray.map { it.jsonObject.getValue("symbol").jsonPrimitive.content }.toSet())
        return Fixture(module, UltraKernelTaskProfileTarget.parseLegacyModule(report))
    }

    private fun forEachFixture(action: (Fixture) -> Unit) = listOf(false, true).forEach { action(fixture(it)) }

    private fun normal(fixture: Fixture, field: String, value: JsonElement): JsonObject = JsonObject(fixture.proof +
        ("normalModule" to JsonObject(fixture.proof.getValue("normalModule").jsonObject + (field to value))))

    private fun rejected(fixture: Fixture, proof: JsonObject, code: String, argument: Boolean = false) {
        try {
            verify(fixture.copy(implementation = fixture.implementation.copy(legacyCfi = proof)))
            fail("Expected $code")
        } catch (error: RuntimeException) {
            assertEquals(if (argument) IllegalArgumentException::class.java else IllegalStateException::class.java, error.javaClass)
            assertEquals(code, error.message)
        }
    }

    private fun verify(fixture: Fixture) {
        val type = Class.forName("dev.maplesu.manager.ultrakernel.UltraInitBootTargetResolver\$Module")
        val module = type.getDeclaredConstructor(ByteArray::class.java).apply { isAccessible = true }.newInstance(fixture.module)
        val verify = type.getDeclaredMethod("verifyLegacyTaskProfile", UltraKernelTaskProfileTarget.Implementation::class.java)
            .apply { isAccessible = true }
        try {
            verify.invoke(module, fixture.implementation)
        } catch (error: InvocationTargetException) {
            throw requireNotNull(error.cause)
        }
    }
}
