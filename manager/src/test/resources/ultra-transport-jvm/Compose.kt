package androidx.compose.runtime
import kotlin.reflect.KProperty
class State<T>(var value: T)
fun <T> mutableStateOf(value: T) = State(value)
operator fun <T> State<T>.getValue(owner: Any?, property: KProperty<*>) = value
operator fun <T> State<T>.setValue(owner: Any?, property: KProperty<*>, value: T) { this.value = value }
