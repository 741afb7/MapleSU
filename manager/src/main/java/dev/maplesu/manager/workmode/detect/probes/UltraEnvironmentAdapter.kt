package dev.maplesu.manager.workmode.detect.probes

import android.content.Context
import dev.maplesu.manager.runtime.HookRootExecutor
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentUpgrade
import dev.maplesu.manager.workmode.detect.UltraEnvironmentPort
import dev.maplesu.manager.workmode.detect.UltraEnvironmentObservation
import dev.maplesu.manager.workmode.detect.UltraState

/**
 * Current identity reads are independent of optional health and Root observations.
 */
class UltraEnvironmentAdapter internal constructor(
    private val readSnapshot: () -> UltraEnvironmentProbe.Snapshot,
    private val refreshSnapshot: () -> UltraEnvironmentProbe.Snapshot,
    private val hookActive: () -> Boolean,
    private val readManagerOwnerIdentity: () -> UltraEnvironmentProbe.Snapshot = { UltraEnvironmentProbe.Snapshot() },
    private val readInstalledCarrier: () -> Boolean = { false },
) : UltraEnvironmentPort {
    constructor(context: Context) : this(
        readSnapshot = { UltraEnvironmentProbe.currentSnapshot(context.applicationContext) },
        refreshSnapshot = { UltraEnvironmentProbe.observeFresh(context.applicationContext) },
        hookActive = { HookRootExecutor.production(context.applicationContext).available() },
        readManagerOwnerIdentity = { UltraEnvironmentProbe.observeFreshIdentity(context.applicationContext) },
        readInstalledCarrier = { UltraEnvironmentUpgrade.installedKernelKnown(context.applicationContext) },
    )

    override fun state(): UltraState = if (hookActive()) UltraState.UNKNOWN else map(readSnapshot().state)

    override fun observe(allowRefresh: Boolean): UltraEnvironmentObservation = observe(allowRefresh, false)

    override fun observe(allowRefresh: Boolean, allowOwnerQuery: Boolean): UltraEnvironmentObservation {
        if (hookActive()) return UltraEnvironmentObservation(UltraState.UNKNOWN)
        val identity = if (allowOwnerQuery) readManagerOwnerIdentity() else UltraEnvironmentProbe.Snapshot()
        val observation = if (allowRefresh) refreshSnapshot() else UltraEnvironmentProbe.Snapshot()
        if (hookActive()) return UltraEnvironmentObservation(UltraState.UNKNOWN)
        val owner = identity.managerOwnerIdentity
        val healthOwner = observation.managerOwnerIdentity
        val ownerChanged = owner != null && healthOwner != null && owner != healthOwner
        val rootVerified = observation.kernelRootVerified && !ownerChanged &&
            (if (owner != null) healthOwner == owner else
                healthOwner != null || observation.legacyCredentialFingerprint != null)
        val ownerBound = allowOwnerQuery && (healthOwner != null || owner != null)
        return UltraEnvironmentObservation(if (rootVerified) map(observation.state) else UltraState.UNKNOWN,
            rootVerified, ownerBound,
            readInstalledCarrier())
    }

    private fun map(state: UltraEnvironmentProbe.State): UltraState = when (state) {
        UltraEnvironmentProbe.State.Running -> UltraState.RUNNING
        UltraEnvironmentProbe.State.NotInstalled -> UltraState.NOT_INSTALLED
        UltraEnvironmentProbe.State.NoKey -> UltraState.UNKNOWN
        UltraEnvironmentProbe.State.Fault -> UltraState.FAULT
        UltraEnvironmentProbe.State.Unknown -> UltraState.UNKNOWN
    }
}
