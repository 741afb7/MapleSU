package dev.maplesu.manager.modules
import android.content.Context
object UltraRootKeyStore {
    fun isSuspended(context: Context) = context.getSharedPreferences("maplesu-ultra", 0).getString("environmentRemoval", null) != null
    fun captureOwnerMarker(context: Context): () -> Boolean {
        val prefs = context.getSharedPreferences("maplesu-ultra", 0)
        val observed = prefs.values.toMap()
        return { observed == prefs.values }
    }
}
