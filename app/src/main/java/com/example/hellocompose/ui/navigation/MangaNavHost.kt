package com.example.hellocompose.ui.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.components.SwitchSelection
import com.example.hellocompose.ui.detail.DetailScreen
import com.example.hellocompose.ui.history.HistoryScreen
import com.example.hellocompose.ui.home.HomeScreen
import com.example.hellocompose.ui.reader.ReaderScreen
import com.example.hellocompose.ui.search.SearchScreen
import com.example.hellocompose.ui.source.SourceEditorScreen
import com.example.hellocompose.ui.stats.ImageStatsScreen
import kotlinx.coroutines.launch

@Composable
fun MangaNavHost(
    navController: NavHostController = rememberNavController()
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * 从历史记录续读。
     *
     * 记录里存的是当时的站点 id：那个站点还在就先切回去再打开，
     * 这样「上次在 A 站看第 8 话第 20 页」能原样恢复。
     * 站点已经被删掉时不硬跳——地址是 A 站的，用 B 站的规则解不出来，
     * 直接告诉用户去搜同书，比开出一个空白页有用。
     */
    fun resumeRecord(record: ReadingRecord) {
        scope.launch {
            val activated = runCatching { container.sourceRepository.activate(record.sourceId) }
                .getOrDefault(false)
            if (activated) {
                navController.navigate(Destinations.resume(record))
            } else {
                Toast.makeText(
                    context,
                    "「${record.mangaTitle}」所在的站点已经不可用了，" +
                        "可以点历史条目右侧的搜索图标，用书名在当前站点再找一次",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 换源后的跳转。
     *
     * 阅读页换了源、又对上了同一章的话，直接落到那一章继续看；
     * 对不上就进目标站点的目录，让用户自己挑。
     *
     * `popUpTo(DETAIL_ROUTE)` 是有意为之：旧站点的目录页还在返回栈里，
     * 而它上面的章节地址只对旧站点有效 —— 站点已经切走了，
     * 用户退回去再点一次必然解析失败。索性连同它一起弹掉。
     */
    fun openSwitched(selection: SwitchSelection) {
        scope.launch {
            runCatching { container.sourceRepository.select(selection.source.id) }
            val route = selection.chapter
                ?.let { chapter ->
                    Destinations.reader(
                        chapter = chapter,
                        detailUrl = selection.manga.detailUrl,
                        mangaTitle = selection.manga.title,
                        coverUrl = selection.manga.coverUrl
                    )
                }
                ?: Destinations.detail(selection.manga)

            navController.navigate(route) {
                popUpTo(Destinations.DETAIL_ROUTE) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Destinations.HOME
    ) {        composable(Destinations.HOME) {
            HomeScreen(
                onMangaClick = { manga ->
                    navController.navigate(Destinations.detail(manga))
                },
                onSearchClick = {
                    navController.navigate(Destinations.search())
                },
                onHistoryClick = {
                    navController.navigate(Destinations.HISTORY)
                },
                onContinueReading = { record -> resumeRecord(record) },
                onAddSource = {
                    navController.navigate(Destinations.sourceEditor())
                },
                onEditSource = { source ->
                    navController.navigate(Destinations.sourceEditor(source.id))
                },
                onStatsClick = {
                    navController.navigate(Destinations.STATS)
                }
            )
        }

        composable(Destinations.STATS) {
            ImageStatsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Destinations.SEARCH_ROUTE,
            arguments = listOf(
                navArgument("q") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            SearchScreen(
                onBack = { navController.popBackStack() },
                onMangaClick = { manga ->
                    navController.navigate(Destinations.detail(manga))
                },
                initialKeyword = entry.arguments?.getString("q").orEmpty()
            )
        }

        composable(Destinations.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenRecord = { record -> resumeRecord(record) },
                onSearchTitle = { keyword ->
                    navController.navigate(Destinations.search(keyword))
                }
            )
        }

        composable(
            route = Destinations.SOURCE_EDITOR_ROUTE,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            SourceEditorScreen(
                editingId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Destinations.DETAIL_ROUTE,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("cover") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val detailUrl = entry.arguments?.getString("url").orEmpty()
            val mangaTitle = entry.arguments?.getString("title").orEmpty()
            val coverUrl = entry.arguments?.getString("cover").orEmpty()

            DetailScreen(
                title = mangaTitle,
                detailUrl = detailUrl,
                onBack = { navController.popBackStack() },
                onChapterClick = { chapter ->
                    // 把详情页地址和书名一并传给阅读器：阅读器要靠它们翻章、写历史
                    navController.navigate(
                        Destinations.reader(
                            chapter = chapter,
                            detailUrl = detailUrl,
                            mangaTitle = mangaTitle,
                            coverUrl = coverUrl
                        )
                    )
                },
                onSwitchSource = { source, manga ->
                    openSwitched(SwitchSelection(source, manga, null))
                }
            )
        }

        composable(
            route = Destinations.READER_ROUTE,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("chapterTitle") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("detail") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("cover") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            ReaderScreen(
                chapterTitle = entry.arguments?.getString("chapterTitle").orEmpty(),
                chapterUrl = entry.arguments?.getString("url").orEmpty(),
                detailUrl = entry.arguments?.getString("detail").orEmpty(),
                mangaTitle = entry.arguments?.getString("title").orEmpty(),
                coverUrl = entry.arguments?.getString("cover").orEmpty(),
                onBack = { navController.popBackStack() },
                onSwitchSource = { source, manga, chapter ->
                    openSwitched(SwitchSelection(source, manga, chapter))
                },
                onOpenStats = { navController.navigate(Destinations.STATS) }
            )
        }
    }
}
