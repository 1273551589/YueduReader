package com.ayanami.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ayanami.reader.ui.screens.BookDetailScreen
import com.ayanami.reader.ui.screens.BookstoreScreen
import com.ayanami.reader.ui.screens.ModsScreen
import com.ayanami.reader.ui.screens.PixivArtistScreen
import com.ayanami.reader.ui.screens.PixivScreen
import com.ayanami.reader.ui.screens.PixivViewerScreen
import com.ayanami.reader.ui.savePixivImage
import com.ayanami.reader.ui.screens.ReadScreen
import com.ayanami.reader.ui.screens.SearchScreen
import com.ayanami.reader.ui.screens.SettingsScreen
import com.ayanami.reader.ui.screens.ShelvesScreen
import com.ayanami.reader.ui.screens.SourcesScreen
import com.ayanami.reader.ui.theme.LocalReaderTokens

/**
 * 底部 5 Tab 的主框架。
 *
 * ### 为什么要自建 Tab 而不是用 `NavigationBar` + 深层 NavHost
 *
 * 5 个 Tab 里「书架 / 书城 / Pixiv / 搜索 / 设置」是**平级**的，
 * 但「详情 / 阅读 / 书源管理 / MOD」是**从某个 Tab 推出去的**。
 * 用「每个 Tab 一个 NavHost」会让状态管理炸开；
 * 用「一个 NavHost + 条件显示底栏」最简单，也是这里的选择。
 *
 * `home` = 带底栏的容器（内嵌一个子 NavHost 会引入双重返回栈问题），
 * 所以底栏只在 `home` 路由显示，其余路由全屏。
 */
@Composable
fun ReaderApp(vm: ReaderViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val tokens = LocalReaderTokens.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(tokens.background)) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeWithTabs(vm, state, nav)
            }
            composable("search") {
                SearchScreen(
                    vm, state,
                    onBack = { nav.popBackStack() },
                    onOpen = { hit ->
                        vm.openHit(hit)
                        nav.navigate("book")
                    },
                    onOpenViewer = { nav.navigate("pixivViewer") },
                    onOpenNovel = { nav.navigate("read") },
                    onOpenArtist = { nav.navigate("pixivArtist") }
                )
            }
            composable("book") {
                BookDetailScreen(
                    vm, state,
                    onBack = { nav.popBackStack() },
                    onRead = { chapter, index ->
                        vm.loadChapter(chapter, index)
                        nav.navigate("read")
                    }
                )
            }
            composable("read") {
                ReadScreen(
                    vm, state,
                    onBack = { nav.popBackStack() },
                    // Pixiv 小说的正文是一次性拉回全部再本地切章的，
                    // 所以切章走内存而不是书源网络层
                    onPixivChapter = { index -> vm.loadPixivChapter(index) }
                )
            }
            composable("pixivArtist") {
                PixivArtistScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenViewer = { nav.navigate("pixivViewer") }
                )
            }
            composable("pixivViewer") {
                PixivViewerScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onSave = { url -> vm.savePixivImage(url) }
                )
            }
            composable("sources") {
                SourcesScreen(vm, state, onBack = { nav.popBackStack() })
            }
            composable("mods") {
                ModsScreen(vm, state, onBack = { nav.popBackStack() })
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
    }
}

/** 底栏 5 Tab 的名称与图标。顺序即展示顺序。 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    SHELF("tab_shelf", "书架", Icons.Filled.Home),
    STORE("tab_store", "书城", Icons.Filled.Star),
    PIXIV("tab_pixiv", "Pixiv", Icons.Filled.Build),
    SEARCH("tab_search", "搜索", Icons.Filled.Search),
    SETTINGS("tab_settings", "设置", Icons.Filled.Settings)
}

/**
 * 带底栏的容器。
 *
 * 用 [rememberSaveable] 存当前 Tab —— 转到详情页再返回时，
 * 用户应该回到原来那个 Tab，而不是跳回书架。
 */
@Composable
private fun HomeWithTabs(vm: ReaderViewModel, state: ReaderUiState, nav: NavHostController) {
    val t = LocalReaderTokens.current
    var current by rememberSaveable { mutableStateOf(Tab.SHELF.route) }

    Scaffold(
        containerColor = t.background,
        bottomBar = {
            NavigationBar(containerColor = t.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { current = tab.route },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.primary,
                            selectedTextColor = t.primary,
                            // 指示器用浅紫实底（和全 App 的胶囊体系一致）
                            indicatorColor = com.ayanami.reader.ui.theme.PillSelectedBg,
                            unselectedIconColor = t.textSecondary,
                            unselectedTextColor = t.textSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                Tab.SHELF.route -> ShelvesScreen(
                    vm, state,
                    onSearch = { nav.navigate("search") },
                    onSources = { nav.navigate("sources") },
                    onMods = { nav.navigate("mods") },
                    onOpenRecent = { item ->
                        vm.openRecent(item)
                        nav.navigate("book")
                    }
                )
                Tab.STORE.route -> BookstoreScreen(
                    vm, state,
                    onOpen = { storeBook ->
                        // 书城的书直接转成 SearchBook 打开，复用同一条详情页链路
                        vm.openHitFromBookstore(storeBook)
                        nav.navigate("book")
                    }
                )
                Tab.PIXIV.route -> PixivScreen(
                    vm, state,
                    onOpenNovel = { nav.navigate("read") },
                    onOpenViewer = { nav.navigate("pixivViewer") },
                    onOpenSettings = { current = Tab.SETTINGS.route },
                    // 点 Tag → 切到搜索页并带上关键词
                    // 点 Tag：切到搜索页的「Pixiv插画」Tab + 填关键词 + 立即搜索。
                    // 三件事必须在 openPixivSearch 里一起做，否则会出现
                    // 「跳到搜索页但停在书源 Tab、关键词是空的」。
                    onSearchTag = { tag ->
                        vm.openPixivSearch(
                            tag,
                            com.ayanami.reader.ui.PixivSearchMode.ILLUST
                        )
                        current = Tab.SEARCH.route
                    },
                    onOpenArtist = { nav.navigate("pixivArtist") }
                )
                Tab.SEARCH.route -> SearchScreen(
                    vm, state,
                    onBack = null,
                    onOpen = { hit ->
                        vm.openHit(hit)
                        nav.navigate("book")
                    },
                    onOpenViewer = { nav.navigate("pixivViewer") },
                    onOpenNovel = { nav.navigate("read") },
                    onOpenArtist = { nav.navigate("pixivArtist") }
                )
                Tab.SETTINGS.route -> SettingsScreen(
                    vm, state,
                    onSources = { nav.navigate("sources") },
                    onMods = { nav.navigate("mods") }
                )
            }
        }
    }
}
