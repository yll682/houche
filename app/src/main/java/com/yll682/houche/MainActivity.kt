package com.yll682.houche

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.*
import androidx.activity.compose.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.*
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.screens.home.*
import com.yll682.houche.ui.screens.search.*
import com.yll682.houche.ui.screens.line.*
import com.yll682.houche.ui.screens.station.*
import com.yll682.houche.ui.screens.timetable.*
import com.yll682.houche.ui.screens.city.CityRoute
import com.yll682.houche.ui.screens.settings.SettingsScreen
import com.yll682.houche.ui.theme.HoucheTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface Screen {
    data object Home : Screen
    data class Search(val city: City) : Screen
    data class Line(val city: City, val line: LineSummary, val station: Station? = null) : Screen
    data class Stop(val city: City, val station: Station) : Screen
    data class Times(val city: City, val route: Route) : Screen
    data object Settings : Screen
}
class PageEntry(val screen: Screen) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
class NavigationState : ViewModel() {
    val stack = mutableStateListOf(PageEntry(Screen.Home))
    private val retired = mutableSetOf<PageEntry>()
    var cityVisible by mutableStateOf(false)
    fun push(screen: Screen) { stack.add(PageEntry(screen)) }
    fun pop() { if (stack.size > 1) { val entry = stack.removeAt(stack.lastIndex); retired.add(entry) } }
    fun release(entry: PageEntry) { if (entry !in stack) { entry.viewModelStore.clear(); retired.remove(entry) } }
    fun home() { val visible = stack.last(); while (stack.size > 1) pop(); retired.filter { it != visible }.toList().forEach { release(it) } }
    override fun onCleared() { (stack + retired).forEach { it.viewModelStore.clear() } }
}
data class RootData(val cities: List<City> = emptyList(), val city: City? = null)
class RootViewModel(val graph: AppGraph) : StateViewModel<RootData>(RootData()) {
    private var job: Job? = null
    init { load() }
    fun load() {
        job?.cancel()
        graph.retryInitialization()
        job = action {
            mutable.value = UiState.Loading(data)
            graph.repository()
            val all = graph.cities.all()
            graph.prefs.currentCityKey.collect { update(RootData(all, graph.cities.defaultCity(it))) }
        }
    }
    fun city(city: City, done: () -> Unit) = action { graph.prefs.setCurrentCity(city.key); done() }
}
@Composable inline fun <reified T : ViewModel> model(key: String? = null, crossinline create: () -> T): T = viewModel(key = key, factory = object : ViewModelProvider.Factory {
    override fun <M : ViewModel> create(modelClass: Class<M>): M { @Suppress("UNCHECKED_CAST") return create() as M }
})
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as HoucheApp).graph
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            HoucheTheme(dynamicColor = false) { HoucheRoot(graph) { finish() } }
        }
    }
}
@Composable private fun HoucheRoot(graph: AppGraph, exit: () -> Unit) {
    val root: RootViewModel = model { RootViewModel(graph) }
    val state by root.state.collectAsStateWithLifecycle()
    val located by graph.locatedCity.collectAsStateWithLifecycle()
    val data = state.data ?: RootData()
    val city = data.city
    val navigation: NavigationState = viewModel()
    val context = LocalContext.current
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val exitMessage = stringResource(R.string.exit_again)
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler(!navigation.cityVisible) {
        if (navigation.stack.size > 1) navigation.pop()
        else {
            val now = SystemClock.elapsedRealtime()
            if (lastBack != 0L && now - lastBack < 2000) exit()
            else { lastBack = now; Toast.makeText(context, exitMessage, Toast.LENGTH_SHORT).show() }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (city == null) Column(Modifier.safeDrawingPadding().padding(16.dp)) { StateFeedback(state) { root.load() } }
        else {
            AnimatedContent(navigation.stack.last(), transitionSpec = { (fadeIn(tween(180)) + slideInVertically(tween(180)) { shift }) togetherWith fadeOut(tween(180)) }, label = "页面切换") { entry ->
                DisposableEffect(entry) { onDispose { navigation.release(entry) } }
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    when (val screen = entry.screen) {
                        Screen.Home -> HomeRoute(model(key = city.key) { HomeViewModel(graph, city) }, { navigation.push(Screen.Search(city)) }, { navigation.cityVisible = true }, { navigation.push(Screen.Settings) }, { c, l, s -> navigation.push(Screen.Line(c, l, s)) }, { c, s -> navigation.push(Screen.Stop(c, s)) })
                        is Screen.Search -> SearchRoute(model { SearchViewModel(graph, screen.city) }, navigation::pop, { navigation.push(Screen.Line(screen.city, it)) }, { navigation.push(Screen.Stop(screen.city, it)) })
                        is Screen.Line -> LineRoute(model { LineViewModel(graph, screen.city, screen.line, screen.station) }, navigation::pop, { navigation.push(Screen.Times(screen.city, it)) })
                        is Screen.Stop -> StationRoute(model { StationViewModel(graph, screen.city, screen.station) }, navigation::pop, { line, stop -> navigation.push(Screen.Line(screen.city, line, stop)) })
                        is Screen.Times -> TimetableRoute(model { TimetableViewModel(graph, screen.city, screen.route) }, navigation::pop)
                        Screen.Settings -> SettingsScreen(model { com.yll682.houche.ui.screens.settings.SettingsViewModel(graph, city) }, navigation::pop) { navigation.cityVisible = true }
                    }
                }
            }
            if (state is UiState.Error && !navigation.cityVisible) Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { ErrorCard((state as UiState.Error).message) { root.load() } }
            if (navigation.cityVisible) CityRoute(data.cities, located, { navigation.cityVisible = false }, error = (state as? UiState.Error)?.message, failed = state is UiState.Error, retry = { root.load() }) { chosen -> root.city(chosen) { navigation.cityVisible = false; navigation.home() } }
        }
    }
}
