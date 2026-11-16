package com.example.hellocompose.engine.autogen.corpus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * 自动识别的**准确率**基线。
 *
 * 和 `AutoGeneratorFixtureTest` 的分工：那边断言「机制对不对」（懒加载有没有取到真地址、
 * JS 跳转有没有被认出来、搜索地址会不会假装生效），一次只盯一件事，改一处加一条；
 * 这边断言「识别得有多准」，每个样本都有完整的人工标准答案，跑完给出一张分阶段成绩单。
 *
 * 为什么要单独一张成绩单：只统计「整站生成成功」是看不出问题的。首页选错了列表，
 * 章节和图片必然跟着错，成功率会同时掉三格，看起来像「这个站很烂」，实际上只有一处要改。
 * 拆成六关之后，同一个数字会直接指向责任环节。
 *
 * 回归策略是**棘轮**（只许更好）：`corpus/baseline.json` 记着每个阶段的通过站数和
 * 条目级准确率，任何一项掉下来就红。这样已知的短板可以暂不修（有记录、不阻塞提交），
 * 但谁把它改得更差了，立刻会被拦下。
 *
 * 更新基线（确认是变好/样本变更导致）：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*AutoGeneratorCorpusTest*" -Dautogen.corpus.update=true
 * ```
 */
@RunWith(Parameterized::class)
class AutoGeneratorCorpusTest(private val site: CorpusSite) {

    @Test
    fun scoresEveryStage() {
        val run = CorpusScoring.run(site)
        synchronized(RESULTS) { RESULTS += run }

        println("=== ${site.id}（${site.name}）===")
        run.steps.forEach { println("  · $it") }

        if (site.blocked) {
            // 反爬样本只有一个观测点：有没有如实拒绝。打六行 ✗ 只会误导
            println("  ${if (run.blockedAsExpected) "✓" else "✗"} ${run.stages.getValue(Stage.CONFIG).detail}")
            assertTrue(
                "反爬样本必须如实拒绝，并说清是人机验证/403，实际：${run.stages.getValue(Stage.CONFIG).detail}",
                run.blockedAsExpected
            )
            return
        }

        for (stage in Stage.entries) {
            val result = run.stages.getValue(stage)
            println("  ${if (result.passed) "✓" else "✗"} ${stage.label}：${result.detail}")
        }

        // 生成出来的配置必须能被正式解析器读回去
        run.configJson?.let { json ->
            assertTrue("生成的配置里应该有 home 节点", json.contains("\"home\""))
        }
    }

    companion object {

        private val RESULTS = mutableListOf<SiteRun>()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = CorpusSite.loadAll().map { arrayOf<Any>(it) }

        @JvmStatic
        @org.junit.AfterClass
        fun reportAndGuardBaseline() {
            val runs = synchronized(RESULTS) {
                val copy = RESULTS.sortedBy { it.site.id }
                RESULTS.clear()
                copy
            }
            if (runs.isEmpty()) return

            val summary = CorpusReport.summarize(runs)
            CorpusReport.write(runs, summary, reportDir())

            val update = System.getProperty("autogen.corpus.update").orEmpty().toBoolean()
            if (update) {
                val file = baselineFile()
                file.parentFile?.mkdirs()
                file.writeText(CorpusReport.baseline(summary))
                println("已更新基线：${file.absolutePath}")
                return
            }

            val baseline = readBaseline() ?: run {
                println("还没有基线文件，按当前结果视为基线（提交 corpus/baseline.json 之后才会真正拦退化）")
                return
            }

            val problems = mutableListOf<String>()
            val stages = baseline.optJSONObject("stages") ?: JSONObject()
            for (total in summary.stages) {
                val was = stages.optJSONObject(total.stage.name) ?: continue
                // 样本增减会让 total 变化，用条目级准确率比较，避免「加了 3 个样本」直接判红
                val beforeAccuracy = if (was.getInt("denominator") == 0) {
                    0.0
                } else {
                    was.getInt("hits").toDouble() / was.getInt("denominator")
                }
                if (total.passed < was.getInt("passed")) {
                    problems += "${total.stage.label}通过站点从 ${was.getInt("passed")} 掉到 ${total.passed}"
                }
                if (total.accuracy + 1e-9 < beforeAccuracy) {
                    problems += "${total.stage.label}条目准确率从 " +
                        "${(beforeAccuracy * 100).toInt()}% 掉到 ${(total.accuracy * 100).toInt()}%"
                }
            }

            assertTrue(
                "识别能力退化：\n  " + problems.joinToString("\n  ") +
                    "\n（确认是预期变化就加 -Dautogen.corpus.update=true 重跑并提交新基线）",
                problems.isEmpty()
            )
            assertEquals("评分样本数不该比基线少", baseline.optInt("scored", 0), summary.scored)
        }

        private fun reportDir(): File = File(
            System.getProperty("autogen.corpus.reportDir").orEmpty()
                .ifBlank { "build/reports/autogen-corpus" }
        )

        private fun baselineFile(): File = File(
            System.getProperty("autogen.corpus.baseline").orEmpty()
                .ifBlank { "src/test/resources/corpus/baseline.json" }
        )

        private fun readBaseline(): JSONObject? {
            // 明确给了路径就用它（本地临时对比用），否则读打进 classpath 的那份
            val override = System.getProperty("autogen.corpus.baseline").orEmpty()
            val file = if (override.isNotBlank()) {
                File(override)
            } else {
                CorpusSite::class.java.classLoader
                    ?.getResource("corpus/baseline.json")
                    ?.let { runCatching { File(it.toURI()) }.getOrNull() }
            }
            if (file == null || !file.exists()) return null
            return runCatching { JSONObject(file.readText()) }.getOrNull()
        }
    }
}
