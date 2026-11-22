package com.example.hellocompose.data.image

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 闸门当前的状态。[canStartPreload] 是「预加载能不能开始」的唯一判据，
 * 抽成纯数据是为了能不依赖协程把它测清楚。
 */
data class PreloadGateState(
    val visible: Int,
    val activePreloads: Int,
    val maxPreloads: Int
) {
    val canStartPreload: Boolean get() = visible == 0 && activePreloads < maxPreloads
}

/**
 * 可见页优先的预加载闸门。
 *
 * Coil 2 的 `ImageRequest` 没有优先级字段（那是 Coil 3 才有的），所以优先级只能在这一层做：
 *
 * - 用户正在看的页（[visibleStarted] 到 [visibleFinished] 之间）不跑任何预加载 ——
 *   带宽和解码名额全给可见页，翻页时的等待时间就是最直接的用户体验；
 * - 一份预加载占一个名额，重试也算在同一个名额里，不会因为「失败了再试」变成两路；
 * - 预加载并发固定为 1：它是投机行为，多开一路只会让可见页更慢。
 *
 * 取消是安全的：预加载等闸门时停在 `first {}` 上，协程被取消就直接退出，不会留下占用。
 */
class PreloadGate(private val maxPreloads: Int = 1) {

    private val state = MutableStateFlow(PreloadGateState(0, 0, maxPreloads))

    /**
     * 等一个预加载名额，等到即占用，调用方必须在 finally 里 [release]。
     *
     * 用 `Mutex` 串起来：两个预加载同时看到「有空位」再各自加一，就会一起越过上限。
     * 持锁期间等待是故意的 —— 后面的预加载本来就该排队。
     */
    private val gate = Mutex()

    suspend fun awaitSlot() {
        gate.withLock {
            state.first { it.canStartPreload }
            state.update { it.copy(activePreloads = it.activePreloads + 1) }
        }
    }

    fun release() {
        state.update { it.copy(activePreloads = (it.activePreloads - 1).coerceAtLeast(0)) }
    }

    fun visibleStarted() {
        state.update { it.copy(visible = it.visible + 1) }
    }

    fun visibleFinished() {
        state.update { it.copy(visible = (it.visible - 1).coerceAtLeast(0)) }
    }

    val snapshot: PreloadGateState get() = state.value
}
