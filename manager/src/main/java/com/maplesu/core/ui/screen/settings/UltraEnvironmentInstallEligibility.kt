package com.maplesu.core.ui.screen.settings

import dev.maplesu.manager.workmode.WorkMode

/** Manual SDK recovery is available without prior runtime or installation information. */
internal fun environmentInstallEligible(
    mode: WorkMode?,
    activeMode: WorkMode?,
    hidden: Boolean,
    transitionPending: Boolean,
): Boolean = mode != WorkMode.KSU && activeMode != WorkMode.KSU && !hidden && !transitionPending

/** The Home default asks for current identity; explicit SDK recovery remains available. */
internal fun environmentInstallNeedsConfirmation(mode: WorkMode?, confirmedAbsent: Boolean): Boolean =
    mode == null && !confirmedAbsent
