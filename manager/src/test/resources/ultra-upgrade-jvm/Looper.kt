package android.os
object Looper {
    private val main = Any()
    fun myLooper(): Any? = null
    fun getMainLooper(): Any = main
}
