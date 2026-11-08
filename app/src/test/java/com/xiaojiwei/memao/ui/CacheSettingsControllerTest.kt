package com.xiaojiwei.memao.ui

import com.xiaojiwei.memao.model.CacheReport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CacheSettingsControllerTest {
    @Test fun initialReadStaysUnknownAndDuplicateLoadsAreIgnored() = runTest {
        val pending = CompletableDeferred<CacheReport>()
        var reads = 0
        val changes = mutableListOf<CacheSettingsState>()
        val controller = CacheSettingsController(this, { reads++; pending.await() }, { error("Unexpected clear") }, changes::add, {})

        controller.load()
        controller.load()
        assertTrue(controller.state.loading)
        assertNull(controller.state.report)
        runCurrent()
        assertEquals(1, reads)
        val report = CacheReport(128, 64)
        pending.complete(report)
        runCurrent()
        assertEquals(report, controller.state.report)
        assertFalse(controller.state.loading)
        assertNull(controller.state.readError)
        assertTrue(changes.filter { it.loading }.all { it.report == null })
    }

    @Test fun failedReadIsExplicitAndRetryReplacesItWithRealUsage() = runTest {
        var failing = true
        val controller = CacheSettingsController(this,
            { if (failing) throw IOException("local file unavailable") else CacheReport(0, 90) },
            { error("Unexpected clear") }, {}, {})

        controller.load()
        runCurrent()
        assertNull(controller.state.report)
        assertFalse(controller.state.loading)
        assertEquals("无法读取缓存占用，请重试。", controller.state.readError)
        failing = false
        controller.load()
        assertNull(controller.state.readError)
        assertTrue(controller.state.loading)
        runCurrent()
        assertEquals(CacheReport(0, 90), controller.state.report)
        assertNull(controller.state.readError)
    }

    @Test fun clearWinsOverOldReadAndRepeatedTapsDoNotQueueAnotherClear() = runTest {
        val oldRead = CompletableDeferred<CacheReport>()
        val clearResult = CompletableDeferred<CacheReport>()
        var reads = 0
        var clears = 0
        val feedback = mutableListOf<Boolean>()
        val controller = CacheSettingsController(this,
            { if (reads++ == 0) CacheReport(200, 75) else withContext(NonCancellable) { oldRead.await() } },
            { clears++; clearResult.await() }, {}, feedback::add)
        controller.load()
        runCurrent()
        controller.load()
        runCurrent()

        controller.clear()
        controller.clear()
        controller.load()
        assertTrue(controller.state.clearing)
        assertFalse(controller.state.loading)
        runCurrent()
        assertEquals(1, clears)
        assertEquals(2, reads)
        // Some ordinary data can remain; the controller must not invent a zero-byte result.
        val actualResult = CacheReport(7, 75, 193)
        clearResult.complete(actualResult)
        runCurrent()
        assertEquals(actualResult, controller.state.report)
        oldRead.complete(CacheReport(999, 75))
        runCurrent()
        assertEquals(actualResult, controller.state.report)
        assertEquals(listOf(true), feedback)
        assertFalse(controller.state.clearing)
    }

    @Test fun clearFailureKeepsUsageAndAllowsRetry() = runTest {
        val original = CacheReport(400, 81)
        val after = CacheReport(9, 81, 391)
        var failing = true
        val feedback = mutableListOf<Boolean>()
        val controller = CacheSettingsController(this, { original },
            { if (failing) throw IOException("disk unavailable") else after }, {}, feedback::add)
        controller.load()
        runCurrent()
        controller.clear()
        runCurrent()
        assertEquals(original, controller.state.report)
        assertEquals("清理失败，请重试。", controller.state.clearError)
        assertNull(controller.state.readError)
        assertFalse(controller.state.clearing)
        assertTrue(feedback.isEmpty())

        failing = false
        controller.clear()
        assertNull(controller.state.clearError)
        runCurrent()
        assertEquals(after, controller.state.report)
        assertEquals(listOf(true), feedback)
    }

    @Test fun accountChangeInvalidatesOldReadWithoutOverwritingNewSession() = runTest {
        val oldRead = CompletableDeferred<CacheReport>()
        var reads = 0
        val current = CacheReport(22, 33)
        val controller = CacheSettingsController(this,
            { if (reads++ == 0) withContext(NonCancellable) { oldRead.await() } else current },
            { error("Unexpected clear") }, {}, {})
        controller.load()
        runCurrent()
        controller.reset()
        assertNull(controller.state.report)
        assertFalse(controller.state.loading)
        controller.load()
        runCurrent()
        oldRead.complete(CacheReport(999, 999))
        runCurrent()
        assertEquals(current, controller.state.report)
        assertFalse(controller.state.loading)
        assertNull(controller.state.readError)
    }

    @Test fun globalClearFinishesAcrossAccountChangeWithoutOldSuccessToast() = runTest {
        val clearResult = CompletableDeferred<CacheReport>()
        var reads = 0
        var clears = 0
        val feedback = mutableListOf<Boolean>()
        val controller = CacheSettingsController(this, { reads++; CacheReport(60, 40) },
            { clears++; clearResult.await() }, {}, feedback::add)
        controller.load()
        runCurrent()
        controller.clear()
        runCurrent()
        controller.reset()
        assertTrue(controller.state.clearing)
        assertFalse(clearResult.isCancelled)
        controller.load()
        controller.clear()
        runCurrent()
        assertEquals(1, clears)
        assertEquals(1, reads)

        clearResult.complete(CacheReport(0, 40, 60))
        runCurrent()
        assertEquals(CacheReport(0, 40, 60), controller.state.report)
        assertFalse(controller.state.clearing)
        assertEquals(listOf(false), feedback)
    }

    @Test fun cancelledReadIsNotReportedAsFailureAndCanBeRetried() = runTest {
        var cancelRead = true
        val controller = CacheSettingsController(this,
            { if (cancelRead) throw CancellationException() else CacheReport(8, 5) },
            { error("Unexpected clear") }, {}, {})
        controller.load()
        runCurrent()
        assertFalse(controller.state.loading)
        assertNull(controller.state.readError)
        cancelRead = false
        controller.load()
        runCurrent()
        assertEquals(CacheReport(8, 5), controller.state.report)
    }

    @Test fun clearFailureAfterAccountChangeLeavesAnAvailableReadRetry() = runTest {
        val clearResult = CompletableDeferred<CacheReport>()
        val controller = CacheSettingsController(this, { CacheReport(60, 40) },
            { clearResult.await() }, {}, {})
        controller.load()
        runCurrent()
        controller.clear()
        runCurrent()
        controller.reset()
        controller.load() // Re-entering settings during the global clear must not start a competing read.
        clearResult.completeExceptionally(IOException("disk unavailable"))
        runCurrent()
        assertNull(controller.state.report)
        assertFalse(controller.state.loading)
        assertFalse(controller.state.clearing)
        assertNotNull(controller.state.clearError)
        assertEquals("缓存占用未知，请重新读取。", controller.state.readError)

        controller.load()
        runCurrent()
        assertEquals(CacheReport(60, 40), controller.state.report)
        assertNull(controller.state.readError)
        assertNull(controller.state.clearError)
    }

    @Test fun unknownOrEmptyOrdinaryCacheCannotBeCleared() = runTest {
        var clears = 0
        val controller = CacheSettingsController(this, { CacheReport(0, 1024) },
            { clears++; CacheReport(0, 1024) }, {}, {})
        controller.clear()
        controller.load()
        runCurrent()
        controller.clear()
        runCurrent()
        assertEquals(0, clears)
        assertEquals(CacheReport(0, 1024), controller.state.report)
    }

    @Test fun closeDropsLateResultAndPreventsAnyFurtherOperation() = runTest {
        val pending = CompletableDeferred<CacheReport>()
        val changes = mutableListOf<CacheSettingsState>()
        var reads = 0
        val controller = CacheSettingsController(this,
            { reads++; withContext(NonCancellable) { pending.await() } },
            { error("Unexpected clear") }, changes::add, { error("Unexpected feedback") })
        controller.load()
        runCurrent()
        controller.close()
        val previousChanges = changes.toList()
        controller.load()
        controller.clear()
        controller.reset()
        pending.complete(CacheReport(40, 30))
        runCurrent()
        assertEquals(previousChanges, changes)
        assertEquals(1, reads)
    }
}
