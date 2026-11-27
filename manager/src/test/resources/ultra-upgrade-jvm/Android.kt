package android.content

open class Context {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext: Context get() = this
    val filesDir get() = java.io.File(System.getProperty("java.io.tmpdir"), "maplesu-upgrade-fixture")
    private val stores = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) { SharedPreferences() }
}

class SharedPreferences {
    val values = mutableMapOf<String, Any>()
    var commits = true
    fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    fun edit(): Editor = Editor()
    inner class Editor {
        private val edits = mutableMapOf<String, Any?>()
        fun putString(key: String, value: String?) = apply { edits[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { edits[key] = value }
        fun remove(key: String) = apply { edits[key] = null }
        fun commit(): Boolean {
            if (!commits) return false
            edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        fun apply() { commit() }
    }
}
