package com.example.hellocompose.engine.autogen.corpus

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 汇总 + 落盘。
 *
 * 报告同时给两种人看：
 * - **改算法的人**：需要一个「哪一关掉了」的即时答案，所以控制台会打一张逐站矩阵；
 * - **回头查原因的人**：需要知道当时生成的是什么配置，所以 Markdown 里保留失败详情，
 *   JSON 里保留每站每阶段的原始数字。
 *
 * 单独存 JSON 的另一个原因是可以进 git（或 CI 产物），两次提交之间直接 diff
 * 就能看出「这次改动到底动了哪一关」，比人肉看日志可靠。
 */
object CorpusReport {

    data class StageTotals(
        val stage: Stage,
        val passed: Int = 0,
        val total: Int = 0,
        val hits: Int = 0,
        val denominator: Int = 0
    ) {
        val accuracy: Double get() = if (denominator == 0) 0.0 else hits.toDouble() / denominator
        val rate: String get() = if (total == 0) "—" else "$passed/$total"
    }

    data class Summary(
        val sites: Int,
        val scored: Int,
        val blocked: Int,
        val stages: List<StageTotals>,
        /** 每个「首个失败阶段」各卡了几个站 */
        val blockers: Map<Stage, Int>
    )

    fun summarize(runs: List<SiteRun>): Summary {
        val scored = runs.filter { !it.site.blocked }
        val totals = Stage.entries.map { stage ->
            scored.fold(StageTotals(stage)) { acc, run ->
                val result = run.stages.getValue(stage)
                acc.copy(
                    passed = acc.passed + if (result.passed) 1 else 0,
                    total = acc.total + 1,
                    hits = acc.hits + result.numerator,
                    denominator = acc.denominator + result.denominator
                )
            }
        }
        val blockers = scored.mapNotNull { it.blocker }
            .groupingBy { it }
            .eachCount()
        return Summary(
            sites = runs.size,
            scored = scored.size,
            blocked = runs.count { it.site.blocked },
            stages = totals,
            blockers = blockers
        )
    }

    fun markdown(runs: List<SiteRun>, summary: Summary): String = buildString {
        appendLine("# 自动识别准确率报告")
        appendLine()
        appendLine(
            "语料 ${summary.sites} 个样本：参与评分 ${summary.scored} 个，" +
                "反爬样本 ${summary.blocked} 个。判定标准是 `expected.json` 里人工确认的选择器，"
        )
        appendLine("比对按**效果**（抓出来的条目集合）而不是选择器字符串。")
        appendLine()

        appendLine("## 分阶段成功率")
        appendLine()
        appendLine("| 阶段 | 通过站点 | 条目级准确率 | 卡点站数 |")
        appendLine("| --- | --- | --- | --- |")
        for (t in summary.stages) {
            val blockedHere = summary.blockers[t.stage] ?: 0
            val blockerCell = if (t.stage.isData && blockedHere > 0) "$blockedHere" else ""
            appendLine(
                "| ${t.stage.label} | ${t.rate} | ${percent(t.accuracy)}（${t.hits}/${t.denominator}） | " +
                    "$blockerCell |"
            )
        }
        appendLine()

        appendLine("## 逐站结果")
        appendLine()
        appendLine("| 站点 | 类型 | " + Stage.entries.joinToString(" | ") { it.label } + " | 卡点 |")
        appendLine("| --- | --- |" + " --- |".repeat(Stage.entries.size) + " --- |")
        for (run in runs) {
            if (run.site.blocked) {
                // 反爬样本不参与准确率，只记录「有没有按预期拒绝」
                val verdict = if (run.blockedAsExpected) "按预期拒绝" else "拒绝理由不对"
                appendLine("| ${run.site.id} | 反爬 |" + (" — |".repeat(Stage.entries.size)) + " $verdict |")
                continue
            }
            val marks = Stage.entries.joinToString(" | ") { stage ->
                if (run.stages.getValue(stage).passed) "✅" else "❌"
            }
            appendLine("| ${run.site.id} | ${run.site.type} | $marks | ${run.blocker?.label ?: "—"} |")
        }
        appendLine()

        val failed = runs.filter { !it.site.blocked && !it.fullPassed }
        if (failed.isNotEmpty()) {
            appendLine("## 未通过的站点详情")
            appendLine()
            for (run in failed) {
                appendLine("### ${run.site.id}（${run.site.name}）")
                appendLine()
                for (stage in Stage.entries) {
                    val result = run.stages.getValue(stage)
                    val mark = if (result.passed) "✅" else "❌"
                    appendLine("- $mark **${stage.label}**：${result.detail}")
                }
                appendLine()
            }
        }

        appendLine("## 语料清单")
        appendLine()
        appendLine("| 站点 | 类型 | 覆盖的特殊情况 |")
        appendLine("| --- | --- | --- |")
        for (run in runs) {
            appendLine("| ${run.site.id} | ${run.site.type} | ${run.site.notes.replace("|", "\\|")} |")
        }
        appendLine()
    }

    fun json(runs: List<SiteRun>, summary: Summary): String = JSONObject().apply {
        put("sites", summary.sites)
        put("scored", summary.scored)
        put("blocked", summary.blocked)
        put("stages", JSONObject().apply {
            for (t in summary.stages) {
                put(t.stage.name, JSONObject().apply {
                    put("passed", t.passed)
                    put("total", t.total)
                    put("hits", t.hits)
                    put("denominator", t.denominator)
                    put("accuracy", round(t.accuracy))
                })
            }
        })
        put("runs", JSONArray().apply {
            for (run in runs) {
                put(JSONObject().apply {
                    put("id", run.site.id)
                    put("type", run.site.type)
                    put("blocked", run.site.blocked)
                    put("fullPassed", run.fullPassed)
                    put("blocker", run.blocker?.name ?: JSONObject.NULL)
                    put("stages", JSONObject().apply {
                        for ((stage, result) in run.stages) {
                            put(stage.name, JSONObject().apply {
                                put("passed", result.passed)
                                put("hits", result.numerator)
                                put("denominator", result.denominator)
                                put("detail", result.detail)
                            })
                        }
                    })
                })
            }
        })
    }.toString(2)

    /** 基线只存「计数 + 准确率」，不存站点名单：加样本时不用手工维护日期和条目 */
    fun baseline(summary: Summary): String = JSONObject().apply {
        put("scored", summary.scored)
        put("stages", JSONObject().apply {
            for (t in summary.stages) {
                put(t.stage.name, JSONObject().apply {
                    put("passed", t.passed)
                    put("total", t.total)
                    put("hits", t.hits)
                    put("denominator", t.denominator)
                })
            }
        })
    }.toString(2) + "\n"

    fun write(runs: List<SiteRun>, summary: Summary, dir: File) {
        dir.mkdirs()
        File(dir, "report.md").writeText(markdown(runs, summary))
        File(dir, "report.json").writeText(json(runs, summary))

        // 控制台也打一张：Gradle 的 testLogging 已经开了 showStandardStreams，
        // 改完算法直接看输出，不用再去找报告文件
        println(consoleTable(runs, summary))
        println("报告：${File(dir, "report.md").absolutePath}")
    }

    private fun consoleTable(runs: List<SiteRun>, summary: Summary): String = buildString {
        appendLine()
        appendLine("=== 自动识别分阶段准确率 ===")
        appendLine(row("站点" to 26, "类型" to 12, *stageCells(Stage.entries.map { it.label })))

        // 反爬样本没有「准确率」可言，混在矩阵里会让每一列都显示失败，误导读者
        for (run in runs.filterNot { it.site.blocked }) {
            val marks = Stage.entries.map { stage ->
                if (run.stages.getValue(stage).passed) "✓" else "✗"
            }
            appendLine(row(run.site.id to 26, run.site.type to 12, *stageCells(marks)))
        }

        appendLine("─".repeat(26 + 1 + 12 + 1 + 11 * Stage.entries.size))
        appendLine(row("通过站点" to 26, "" to 12, *stageCells(summary.stages.map { it.rate })))
        appendLine(row("条目准确率" to 26, "" to 12, *stageCells(summary.stages.map { percent(it.accuracy) })))
        summary.blockers.entries.sortedByDescending { it.value }.forEach { (stage, n) ->
            appendLine("  卡在${stage.label}：$n 站")
        }
        runs.filter { it.site.blocked }.forEach { run ->
            appendLine("  反爬样本 ${run.site.id}：${if (run.blockedAsExpected) "已正确拒绝" else "拒绝理由不对"}")
        }
    }

    private fun stageCells(values: List<String>): Array<Pair<String, Int>> =
        values.map { it to 11 }.toTypedArray()

    private fun row(vararg cells: Pair<String, Int>): String =
        cells.joinToString("") { (text, width) -> padTo(text, width) }

    /** 中文按两列算，否则表头和数据列会错开 */
    private fun padTo(text: String, width: Int): String {
        val display = text.sumOf { if (it.code > 0x2E7F) 2 else 1 }
        return text + " ".repeat((width - display).coerceAtLeast(0))
    }

    private fun percent(v: Double): String = "${(v * 100).roundToInt()}%"

    private fun round(v: Double): Double = (v * 10000).roundToInt() / 10000.0
}
