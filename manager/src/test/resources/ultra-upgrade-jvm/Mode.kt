package dev.maplesu.manager.workmode
enum class WorkMode { LITE, KSU }
sealed interface ModeActivation { data class Active(val mode: WorkMode) : ModeActivation }
object ModeRuntime {
    var activation: ModeActivation = ModeActivation.Active(WorkMode.LITE)
    val active: WorkMode? get() = (activation as? ModeActivation.Active)?.mode
}
