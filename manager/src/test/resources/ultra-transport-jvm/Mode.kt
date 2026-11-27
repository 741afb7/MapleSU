package dev.maplesu.manager.workmode
sealed interface ModeActivation { class Active : ModeActivation }
object ModeRuntime {
    var active: WorkMode? = WorkMode.LITE
    var activation: ModeActivation = ModeActivation.Active()
}
