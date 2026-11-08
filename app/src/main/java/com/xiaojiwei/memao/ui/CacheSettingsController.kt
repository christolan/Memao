package com.xiaojiwei.memao.ui

import com.xiaojiwei.memao.model.CacheReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class CacheSettingsState(
    val report: CacheReport? = null,
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val readError: String? = null,
    val clearError: String? = null,
)

/** Main-thread confined; cache operations cover all accounts, while feedback belongs to a session. */
internal class CacheSettingsController(
    private val scope: CoroutineScope,
    private val readCache: suspend () -> CacheReport,
    private val clearCache: suspend () -> CacheReport,
    private val onStateChanged: (CacheSettingsState) -> Unit,
    private val onCleared: (notifyCurrentSession: Boolean) -> Unit,
) {
    var state = CacheSettingsState()
        private set
    private var requestId = 0L
    private var sessionId = 0L
    private var request: Job? = null
    private var closed = false

    fun load() {
        if (closed || !scope.isActive || state.loading || state.clearing) return
        start(clearing = false)
    }

    fun clear() {
        if (closed || !scope.isActive || state.clearing || (state.report?.ordinaryBytes ?: 0L) <= 0L) return
        start(clearing = true)
    }

    private fun start(clearing: Boolean) {
        val id = ++requestId
        val ownerSession = sessionId
        request?.cancel()
        // Reserve the operation before launching so repeated taps cannot enqueue more work.
        publish(state.copy(loading = !clearing, clearing = clearing, readError = null, clearError = null))
        request = scope.launch {
            try {
                val report = if (clearing) clearCache() else readCache()
                if (isCurrent(id)) {
                    publish(state.copy(report = report, loading = false, clearing = false))
                    if (clearing) onCleared(ownerSession == sessionId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrent(id)) publish(
                    if (clearing) state.copy(
                        clearError = "清理失败，请重试。",
                        readError = if (state.report == null) "缓存占用未知，请重新读取。" else null,
                    )
                    else state.copy(readError = "无法读取缓存占用，请重试。")
                )
            } finally {
                if (isCurrent(id) && (state.loading || state.clearing)) {
                    publish(state.copy(loading = false, clearing = false))
                }
            }
        }
    }

    /** A global clear finishes across account changes; only its previous-session toast is dropped. */
    fun reset() {
        if (closed) return
        sessionId++
        if (state.clearing) {
            publish(state.copy(report = null, readError = null, clearError = null))
            return
        }
        // Invalidate before cancelling so a non-cooperative read cannot restore an old report.
        requestId++
        request?.cancel()
        request = null
        publish(CacheSettingsState())
    }

    fun close() {
        closed = true
        requestId++
        request?.cancel()
        request = null
    }

    private fun isCurrent(id: Long) = !closed && scope.isActive && requestId == id

    private fun publish(value: CacheSettingsState) {
        state = value
        onStateChanged(value)
    }
}
