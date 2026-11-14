package com.example.hellocompose.data.source

import kotlin.math.roundToInt

/**
 * 识别置信度的粗粒度等级。
 *
 * 三档而不是百分比颜色渐变：用户要判断的是「能不能直接用」，
 * 92% 和 89% 对他的决策没有区别。
 */
enum class ConfidenceLevel {
    /** 可以放心用 */
    HIGH,

    /** 能用，但建议试抓一次确认 */
    MEDIUM,

    /** 大概率有问题，多半得手动改选择器 */
    LOW
}

/**
 * 一套配置的置信度，随 JSON 一起保存。
 *
 * 只在「自动识别」时产生，手写或导入的配置没有这个字段（为 null）。
 * 含义是「识别器对这套选择器的把握」，不是精确概率 —— 真正决定能不能用的
 * 永远是拿正式解析器试抓一遍。它解决的是另一个问题：
 * 同一站点可能推出好几套都「勉强能抓」的规则，得有一个数说明哪套更可信，
 * 以及哪一步最可疑（首页很稳、正文只有 0.4，就知道该去改 reader）。
 */
data class SourceConfidence(
    val overall: Double = 0.0,
    val home: Double = 0.0,
    val detail: Double = 0.0,
    val reader: Double = 0.0
) {
    /** 0~100，给 UI 显示 */
    val percent: Int get() = (overall.coerceIn(0.0, 1.0) * 100).roundToInt()

    val level: ConfidenceLevel
        get() = when {
            overall >= 0.80 -> ConfidenceLevel.HIGH
            overall >= 0.55 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
}
