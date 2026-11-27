package android.content

import java.io.File

open class Context(val libraryDirectory: String) {
    val applicationContext: Context get() = this
    val applicationInfo = ApplicationInfo(libraryDirectory)
    val contentResolver = Any()
    private val preferences = HashMap<String, Preferences>()
    fun getSharedPreferences(name: String, mode: Int) = preferences.getOrPut(name) { Preferences() }
    fun <T> getSystemService(type: Class<T>): T? = if (type == android.os.UserManager::class.java)
        type.cast(android.os.UserManager()) else null
    companion object { const val MODE_PRIVATE = 0 }
}

class ApplicationInfo(val nativeLibraryDir: String) {
    val sourceDir = File(nativeLibraryDir, "manager.apk").path
    val uid = 10123
}

class Preferences {
    private val values = HashMap<String, String>()
    fun getString(key: String, fallback: String?): String? = values[key] ?: fallback
    fun edit() = Editor(values)
    class Editor(private val values: MutableMap<String, String>) {
        fun putString(key: String, value: String): Editor = apply { values[key] = value }
        fun commit() = true
    }
}
