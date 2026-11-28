package com.maplesu.core.ui.viewmodel

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Claim before launch so repeated refreshes never queue another mode wait or probe. */
internal class HomeRefreshSingleFlight {
    private val active = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job? {
        val completion = CompletableDeferred<Unit>()
        if (!active.compareAndSet(null, completion)) return null
        return try {
            scope.launch { block() }.also { job ->
                job.invokeOnCompletion { release(completion) }
            }
        } catch (error: Throwable) {
            release(completion)
            throw error
        }
    }

    /** A manual retry waits for the current series, then owns its complete series. */
    suspend fun runAfterCurrent(block: suspend () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        while (!active.compareAndSet(null, completion)) active.get()?.await()
        try { block() } finally { release(completion) }
    }

    private fun release(completion: CompletableDeferred<Unit>) {
        active.compareAndSet(completion, null)
        completion.complete(Unit)
    }
}
