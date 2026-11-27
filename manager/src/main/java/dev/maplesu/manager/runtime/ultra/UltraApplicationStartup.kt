package dev.maplesu.manager.runtime.ultra

/** Only the main manager process schedules automatic Ultra startup. */
internal object UltraApplicationStartup {
    fun start(
        processName: String,
        packageName: String,
        warmup: () -> Unit,
    ) {
        if (processName != packageName) return
        warmup()
    }
}
