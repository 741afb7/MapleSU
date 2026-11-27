package dev.maplesu.manager.runtime.ultra

/** UI observation only; explicit installation is authenticated by the SDK operation itself. */
enum class UltraEnvironmentInstallReadiness {
    NotChecked, Ready, NeedsImage, Unavailable;

    val needsImage: Boolean get() = this == NeedsImage

    companion object {
        fun from(channel: NativeReceipt): UltraEnvironmentInstallReadiness = when {
            channel.succeeded -> Ready
            channel.sdkError == "ERR_MODULE_NO_BOOT" || channel.sdkError == "ERR_MODULE_TEST_CHANNEL" -> NeedsImage
            else -> Unavailable
        }
    }
}
