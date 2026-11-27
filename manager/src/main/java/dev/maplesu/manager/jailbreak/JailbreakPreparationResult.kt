package dev.maplesu.manager.jailbreak

/** Automatic selection may record its selected backend; explicit targets must match. */
internal fun matchingJailbreakStatus(request: JailbreakRequest, observed: JailbreakStatus?): JailbreakStatus? =
    observed?.takeIf {
        it.attemptId == request.attemptId && it.bootId == request.bootId &&
            (request.rootBackend == null || it.rootBackend == request.rootBackend)
    }

/** The preparation reply is authoritative, including failures not persisted by the provider. */
internal fun preparedJailbreakOutcome(request: JailbreakRequest, observed: JailbreakStatus?): JailbreakController.Outcome {
    val status = matchingJailbreakStatus(request, observed) ?: return JailbreakController.Outcome(
        JailbreakStatus.STAGE_FAILED, "attempt produced no matching status record",
        "JAILBREAK_ATTEMPT_NOT_CONFIRMED")
    if (status.failure != null || status.stage != JailbreakStatus.STAGE_DAEMON_READY) {
        return JailbreakController.Outcome(
            if (status.stage == JailbreakStatus.STAGE_CANCELLED) JailbreakStatus.STAGE_CANCELLED else JailbreakStatus.STAGE_FAILED,
            status.message, status.failure ?: "JAILBREAK_ATTEMPT_NOT_CONFIRMED:" + status.stage,
        )
    }
    return JailbreakController.Outcome(status.stage, status.message, null, request.attemptId)
}

/** Reuse the bounded startup detector; a successful operation is not an implicit mode activation. */
internal fun refreshJailbreakEnvironment(
    outcome: JailbreakController.Outcome,
    invalidate: () -> Unit,
    detect: () -> Unit,
    prepare: () -> Unit = {},
) {
    if (outcome.stage != JailbreakStatus.STAGE_DAEMON_READY || outcome.failure != null || outcome.attemptId.isNullOrBlank()) return
    try {
        prepare()
    } finally {
        invalidate()
        detect()
    }
}
