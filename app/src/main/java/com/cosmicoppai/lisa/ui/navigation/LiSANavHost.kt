package com.cosmicoppai.lisa.ui.navigation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.screens.AnimeDetailsScreen
import com.cosmicoppai.lisa.ui.screens.AnimePlayerScreen
import com.cosmicoppai.lisa.ui.screens.DownloadsScreen
import com.cosmicoppai.lisa.ui.screens.ExploreScreen
import com.cosmicoppai.lisa.ui.screens.LocalMangaReaderScreen
import com.cosmicoppai.lisa.ui.screens.LocalVideoPlayerScreen
import com.cosmicoppai.lisa.ui.screens.MangaDetailsScreen
import com.cosmicoppai.lisa.ui.screens.MangaReaderScreen
import com.cosmicoppai.lisa.ui.screens.MyListScreen
import com.cosmicoppai.lisa.ui.screens.SearchScreen
import com.cosmicoppai.lisa.ui.screens.SettingsScreen
import com.cosmicoppai.lisa.ui.theme.LisaBackground
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

sealed class TopTab(val route: String, val label: String, val icon: ImageVector) {
    data object Search : TopTab("search", "Search", Icons.Filled.Search)
    data object Explore : TopTab("explore", "Explore", Icons.Filled.Explore)
    data object MyList : TopTab("mylist", "My List", Icons.Filled.Bookmark)
    data object Downloads : TopTab("downloads", "Downloads", Icons.Filled.Download)
    data object Settings : TopTab("settings", "Settings", Icons.Filled.Settings)
}

private val topTabs = listOf(
    TopTab.Search,
    TopTab.Explore,
    TopTab.MyList,
    TopTab.Downloads,
    TopTab.Settings
)

internal val navJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun encodeNavArg(raw: String): String {
    if (raw.isEmpty()) return ""
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

internal fun decodeNavArg(encoded: String): String? {
    if (encoded.isBlank()) return ""
    return runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()
}

internal inline fun <reified T> encodeNavJson(value: T): String {
    return encodeNavArg(navJson.encodeToString(value))
}

internal inline fun <reified T> decodeNavJson(encoded: String): T? {
    val decoded = decodeNavArg(encoded)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { navJson.decodeFromString<T>(decoded) }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiSANavHost(activeDownloadsCount: Int) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isTopTab = topTabs.any { tab -> currentDest?.hierarchy?.any { it.route == tab.route } == true }
    val useRail = LocalConfiguration.current.screenWidthDp >= 600

    fun navigateAnimeDetails(item: AnimeSearchItem) {
        val encoded = encodeNavJson(item)
        navController.navigate("anime_details?item=$encoded")
    }

    fun navigateMangaDetails(item: MangaSearchItem) {
        val encoded = encodeNavJson(item)
        navController.navigate("manga_details?item=$encoded")
    }

    fun navigateAnimePlayer(item: AnimeSearchItem, ep: AnimeEpisode, page: Int) {
        val a = encodeNavJson(item)
        val e = encodeNavJson(ep)
        navController.navigate("anime_player?anime=$a&ep=$e&page=$page")
    }

    fun navigateMangaReader(item: MangaSearchItem, chapterSession: String? = null) {
        val encoded = encodeNavJson(item)
        val encodedChp = encodeNavArg(chapterSession.orEmpty())
        navController.navigate("manga_reader?item=$encoded&chapter=$encodedChp")
    }

    val navContent: @Composable (Modifier) -> Unit = { mod ->
        NavHost(
            navController = navController,
            startDestination = TopTab.Search.route,
            modifier = mod
        ) {
            composable(TopTab.Search.route) {
                SearchScreen(
                    onOpenAnime = ::navigateAnimeDetails,
                    onOpenManga = ::navigateMangaDetails
                )
            }
            composable(TopTab.Explore.route) {
                ExploreScreen(
                    onOpenAnime = ::navigateAnimeDetails,
                    onOpenManga = ::navigateMangaDetails
                )
            }
            composable(TopTab.MyList.route) {
                MyListScreen(
                    onOpenAnime = ::navigateAnimeDetails,
                    onOpenManga = ::navigateMangaDetails
                )
            }
            composable(TopTab.Downloads.route) {
                DownloadsScreen(
                    onPlayLocalVideo = { id -> navController.navigate("local_player/$id") },
                    onReadLocalManga = { id -> navController.navigate("local_manga/$id") }
                )
            }
            composable(TopTab.Settings.route) {
                SettingsScreen()
            }
            composable(
                route = "anime_details?item={item}",
                arguments = listOf(navArgument("item") { type = NavType.StringType })
            ) { entry ->
                val raw = entry.arguments?.getString("item").orEmpty()
                val anime = decodeNavJson<AnimeSearchItem>(raw)
                if (anime == null) {
                    LaunchedEffect(raw) { navController.popBackStack() }
                } else {
                    AnimeDetailsScreen(
                        anime = anime,
                        onBack = { navController.popBackStack() },
                        onPlayEpisode = ::navigateAnimePlayer,
                        onOpenRecommendation = ::navigateAnimeDetails
                    )
                }
            }
            composable(
                route = "manga_details?item={item}",
                arguments = listOf(navArgument("item") { type = NavType.StringType })
            ) { entry ->
                val raw = entry.arguments?.getString("item").orEmpty()
                val manga = decodeNavJson<MangaSearchItem>(raw)
                if (manga == null) {
                    LaunchedEffect(raw) { navController.popBackStack() }
                } else {
                    MangaDetailsScreen(
                        manga = manga,
                        onBack = { navController.popBackStack() },
                        onRead = ::navigateMangaReader,
                        onOpenRecommendation = ::navigateMangaDetails
                    )
                }
            }
            composable(
                route = "anime_player?anime={anime}&ep={ep}&page={page}",
                arguments = listOf(
                    navArgument("anime") { type = NavType.StringType },
                    navArgument("ep") { type = NavType.StringType },
                    navArgument("page") { type = NavType.IntType; defaultValue = 1 }
                )
            ) { entry ->
                val animeRaw = entry.arguments?.getString("anime").orEmpty()
                val epRaw = entry.arguments?.getString("ep").orEmpty()
                val page = entry.arguments?.getInt("page") ?: 1
                val anime = decodeNavJson<AnimeSearchItem>(animeRaw)
                val ep = decodeNavJson<AnimeEpisode>(epRaw)
                if (anime == null || ep == null) {
                    LaunchedEffect(animeRaw, epRaw) { navController.popBackStack() }
                } else {
                    AnimePlayerScreen(
                        anime = anime,
                        initialEpisode = ep,
                        initialPage = page,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                route = "manga_reader?item={item}&chapter={chapter}",
                arguments = listOf(
                    navArgument("item") { type = NavType.StringType },
                    navArgument("chapter") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                val raw = entry.arguments?.getString("item").orEmpty()
                val chp = decodeNavArg(entry.arguments?.getString("chapter").orEmpty())?.takeIf { it.isNotBlank() }
                val manga = decodeNavJson<MangaSearchItem>(raw)
                if (manga == null) {
                    LaunchedEffect(raw) { navController.popBackStack() }
                } else {
                    MangaReaderScreen(
                        manga = manga,
                        initialChapterSession = chp,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                route = "local_player/{taskId}",
                arguments = listOf(navArgument("taskId") { type = NavType.LongType })
            ) { entry ->
                val taskId = entry.arguments?.getLong("taskId") ?: 0L
                LocalVideoPlayerScreen(
                    taskId = taskId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "local_manga/{taskId}",
                arguments = listOf(navArgument("taskId") { type = NavType.LongType })
            ) { entry ->
                val taskId = entry.arguments?.getLong("taskId") ?: 0L
                LocalMangaReaderScreen(
                    taskId = taskId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    Scaffold(
        containerColor = LisaBackground,
        bottomBar = {
            if (isTopTab && !useRail) {
                NavigationBar(containerColor = LisaSurface) {
                    topTabs.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                TabIconWithBadge(tab, activeDownloadsCount)
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = LisaPrimary,
                                selectedTextColor = LisaPrimary,
                                unselectedIconColor = LisaTextSecondary,
                                unselectedTextColor = LisaTextSecondary,
                                indicatorColor = LisaPrimary.copy(alpha = 0.16f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (isTopTab && useRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NavigationRail(containerColor = LisaSurface) {
                    topTabs.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { TabIconWithBadge(tab, activeDownloadsCount) },
                            label = { Text(tab.label) }
                        )
                    }
                }
                navContent(Modifier.weight(1f))
            }
        } else {
            navContent(Modifier.padding(padding))
        }
    }
}

@Composable
private fun TabIconWithBadge(tab: TopTab, activeDownloadsCount: Int) {
    if (tab == TopTab.Downloads && activeDownloadsCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(if (activeDownloadsCount > 9) "9+" else activeDownloadsCount.toString())
                }
            }
        ) {
            Icon(tab.icon, contentDescription = tab.label)
        }
    } else {
        Icon(tab.icon, contentDescription = tab.label)
    }
}
