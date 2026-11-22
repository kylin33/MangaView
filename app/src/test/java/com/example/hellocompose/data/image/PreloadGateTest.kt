package com.example.hellocompose.data.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可见优先」的闸门测试。
 *
 * 这条规则的价值全在时序上：可见页加载时预加载必须停住，可见页结束后必须立刻放行。
 * 只测状态谓词不够，所以有两个用真实协程跑时序的用例 —— 超时给得足够宽，
 * 210ms 的等待是等「超时」，不是等「调度」。
 */
class PreloadGateTest {

    @Test
    fun predicateBlocksWhenVisiblePageIsLoading() {
        assertFalse(PreloadGateState(visible = 1, activePreloads = 0, maxPreloads = 1).canStartPreload)
        assertTrue(PreloadGateState(visible = 0, activePreloads = 0, maxPreloads = 1).canStartPreload)
        assertFalse(PreloadGateState(visible = 0, activePreloads = 1, maxPreloads = 1).canStartPreload)
        assertTrue(PreloadGateState(visible = 0, activePreloads = 1, maxPreloads = 2).canStartPreload)
    }

    @Test
    fun visibleCounterNeverGoesNegative() {
        val gate = PreloadGate()
        gate.visibleFinished()
        gate.visibleFinished()

        assertEquals(0, gate.snapshot.visible)
    }

    @Test
    fun preloadWaitsUntilVisiblePageFinishes() = runBlocking {
        val gate = PreloadGate(maxPreloads = 1)
        gate.visibleStarted()

        val acquired = CompletableDeferred<Unit>()
        val job = launch {
            gate.awaitSlot()
            acquired.complete(Unit)
        }

        // 可见页还在加载：预加载不能开始
        assertNull(withTimeoutOrNull(200) { acquired.await() })

        gate.visibleFinished()
        withTimeout(2_000) { acquired.await() }

        gate.release()
        job.cancel()
    }

    @Test
    fun onlyOnePreloadRunsAtATime() = runBlocking {
        val gate = PreloadGate(maxPreloads = 1)
        gate.awaitSlot()

        val second = async {
            gate.awaitSlot()
            "acquired"
        }

        assertNull(withTimeoutOrNull(200) { second.await() })

        gate.release()
        assertEquals("acquired", withTimeout(2_000) { second.await() })

        gate.release()
        assertEquals(0, gate.snapshot.activePreloads)
    }
}
