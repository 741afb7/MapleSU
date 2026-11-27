package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.installer.InstalledUltraCarrier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local evidence from an explicit partition read; never an execution capability. */
internal class UltraInstalledKernelObservation {
    private data class Entry(val carrier: InstalledUltraCarrier, val current: () -> Boolean)
    private var entry: Entry? = null
    @Volatile var revision: Long = 0
        private set
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()

    @Synchronized
    fun invalidate() {
        revision++
        entry = null
        changes.value += 1
    }

    @Synchronized
    fun discard(expectedRevision: Long): Boolean {
        if (revision != expectedRevision) return false
        invalidate()
        return true
    }

    @Synchronized
    fun publish(expectedRevision: Long, carrier: InstalledUltraCarrier, current: () -> Boolean): Boolean {
        if (revision != expectedRevision || !current()) return false
        entry = Entry(carrier, current)
        changes.value += 1
        return true
    }

    @Synchronized
    fun current(): InstalledUltraCarrier? = entry?.takeIf { it.current() }?.carrier
}
