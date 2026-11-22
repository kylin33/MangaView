package com.example.hellocompose.data.image

/**
 * 解码并发的建议值。
 *
 * Coil 的并发上限在构造 `ImageLoader` 时就定死了，运行中改不了，所以只能按设备给一个
 * 合适的初始值：真正吃内存的是「同时解几张」，而同一张长图的解码耗时和 CPU 核数关系不大，
 * 加到 4 路以上基本只会更快 OOM。
 *
 * 依据是 ART 的堆上限（`ActivityManager.memoryClass`）而不是物理内存：
 * 物理 8GB 的机器给 App 的堆也可能只有 256MB，按物理内存算会直接把低端机算成高端机。
 */
object ImageConcurrency {

    /** 低于这个堆上限就别并发了，一张长图就可能把堆打满 */
    private const val LOW_HEAP_MB = 192

    /** 中等堆：两路，常见的 4GB 机器 */
    private const val MID_HEAP_MB = 384

    fun recommend(memoryClassMb: Int, isLowRamDevice: Boolean): Int = when {
        isLowRamDevice -> 1
        memoryClassMb <= LOW_HEAP_MB -> 1
        memoryClassMb <= MID_HEAP_MB -> 2
        else -> 3
    }

    /** 面板上显示的一句解释，省得用户以为并发是随手写的常数 */
    fun reason(memoryClassMb: Int, isLowRamDevice: Boolean): String = when (recommend(memoryClassMb, isLowRamDevice)) {
        1 -> if (isLowRamDevice) "低内存设备，单路解码" else "堆上限 ${memoryClassMb}MB，单路解码"
        2 -> "堆上限 ${memoryClassMb}MB，两路解码"
        else -> "堆上限 ${memoryClassMb}MB，三路解码"
    }
}
