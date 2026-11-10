package com.yll682.houche

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.*
import androidx.activity.compose.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.yll682.houche.ui.screens.vehicle.*
import com.yll682.houche.ui.screens.metro.*
import com.yll682.houche.ui.screens.city.CityRoute
import com.yll682.houche.ui.theme.HoucheTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface Screen {
    data object Home : Screen
    data class Search(val city: City) : Screen
    data class Line(val city: City, val line: LineSummary, val station: Station? = null) : Screen
    data class Stop(val city: City, val station: Station) : Screen
    data class Times(val city: City, val route: Route) : Screen
    data class Vehicle(val city: City, val route: Route, val plate: String, val boarding: RouteStation?) : Screen
    data class Metro(val city: City, val line: String? = null) : Screen
}
class PageEntry(val screen: Screen) : ViewModelStoreOwner {
    val stateKey = java.util.UUID.randomUUID().toString()
    val order = System.nanoTime()
    var preview: ImageBitmap? = null
    override val viewModelStore = ViewModelStore()
}
class NavigationState : ViewModel() {
    val stack = mutableStateListOf(PageEntry(Screen.Home))
    private val retired = mutableSetOf<PageEntry>()
    var cityVisible by mutableStateOf(false)
    // 首页 / 收藏 / 设置是同一层的三个 Tab，设置不再是压栈页面。
    var segment by mutableIntStateOf(0)
    fun push(screen: Screen) { stack.add(PageEntry(screen)) }
    fun pop() { if (stack.size > 1) { val entry = stack.removeAt(stack.lastIndex); retired.add(entry) } }
    fun release(entry: PageEntry) { if (entry !in stack) { entry.viewModelStore.clear(); retired.remove(entry) } }
    fun home() { val visible = stack.last(); while (stack.size > 1) pop(); segment = 0; retired.filter { it != visible }.toList().forEach { release(it) } }
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
    val pageStates = rememberSaveableStateHolder()
    val context = LocalContext.current
    val density = LocalDensity.current
    val pageLayer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val bulletin = remember { BulletinCenter() }
    val backX = remember { Animatable(0f) }
    var pageWidth by remember { mutableIntStateOf(1) }
    var gesturePop by remember { mutableStateOf(false) }
    var backGeneration by remember { mutableLongStateOf(0L) }
    var opening by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf(true) }
    val pageLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) { onDispose { attached = false; backGeneration++ } }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    fun open(screen: Screen) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                navigation.stack.last().preview = if (pageLayer.size.width > 0 && pageLayer.size.height > 0) runCatching { pageLayer.toImageBitmap() }.getOrNull() else null
                navigation.push(screen)
            } finally { opening = false }
        }
    }
    PredictiveBackHandler(navigation.stack.size > 1 && !navigation.cityVisible && !imeVisible) { events ->
        val token = ++backGeneration
        val backEntry = navigation.stack.last()
        var hasProgress = false
        try {
            events.collect { event ->
                hasProgress = hasProgress || event.progress > 0f
                backX.snapTo(with(density) { 56.dp.toPx() } * CubicBezierEasing(0f,0f,0f,1f).transform(event.progress))
            }
            if (token != backGeneration || navigation.stack.last() != backEntry) return@PredictiveBackHandler
            if (hasProgress) {
                backX.animateTo(pageWidth + with(density) { 56.dp.toPx() }, tween(380, easing = TelegramQuint))
                gesturePop = true
            }
            if (token == backGeneration && navigation.stack.last() == backEntry) navigation.pop()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { if (token == backGeneration && attached && pageLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) backX.animateTo(0f, tween(320, easing = TelegramQuint)) }
        } finally {
            if (!gesturePop && token == backGeneration) withContext(NonCancellable) { backX.snapTo(0f) }
        }
    }
    LaunchedEffect(navigation.stack.last()) {
        backGeneration++
        if (gesturePop) { backX.snapTo(0f); gesturePop = false }
    }
    val exitMessage = stringResource(R.string.exit_again)
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler(!navigation.cityVisible && navigation.stack.size == 1) {
        if (navigation.stack.size > 1) navigation.pop()
        else if (navigation.segment != 0) navigation.segment = 0
        else {
            val now = SystemClock.elapsedRealtime()
            if (lastBack != 0L && now - lastBack < 2000) exit()
            else { lastBack = now; Toast.makeText(context, exitMessage, Toast.LENGTH_SHORT).show() }
        }
    }
    CompositionLocalProvider(LocalBulletinCenter provides bulletin) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize().onSizeChanged { pageWidth = it.width }) {
        if (city == null) Column(Modifier.safeDrawingPadding().padding(16.dp)) { StateFeedback(state) { root.load() } }
        else {
            if (backX.value > 0f && navigation.stack.size > 1) {
                navigation.stack[navigation.stack.lastIndex - 1].preview?.let { snapshot ->
                    Image(snapshot, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
            }
            AnimatedContent(navigation.stack.last(),
                Modifier.fillMaxSize().testTag("page-container").graphicsLayer { translationX = backX.value }.drawWithContent {
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                        pageLayer.record { this@drawWithContent.drawContent() }; drawLayer(pageLayer)
                    } else drawContent()
                }, transitionSpec = {
                    when {
                        gesturePop -> EnterTransition.None togetherWith ExitTransition.None
                        targetState.screen is Screen.Search || initialState.screen is Screen.Search ->
                            fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        targetState.order > initialState.order ->
                            slideInHorizontally(tween(200, easing = TelegramQuint)) { it } togetherWith
                                (slideOutHorizontally(tween(200, easing = TelegramQuint)) { -it / 3 } + fadeOut(tween(200), targetAlpha = .6f))
                        else -> (slideInHorizontally(tween(200, easing = TelegramQuint)) { -it / 3 } + fadeIn(tween(200))) togetherWith
                            slideOutHorizontally(tween(200, easing = TelegramQuint)) { it }
                    }
                }, label = "页面切换") { entry ->
                DisposableEffect(entry) { onDispose { navigation.release(entry) } }
                pageStates.SaveableStateProvider(entry.stateKey) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    when (val screen = entry.screen) {
                        Screen.Home -> HomeRoute(
                            model(key = city.key) { HomeViewModel(graph, city) },
                            model(key = "settings-${city.key}") { com.yll682.houche.ui.screens.settings.SettingsViewModel(graph, city) },
                            navigation.segment, { navigation.segment = it },
                            { open(Screen.Search(city)) }, { navigation.cityVisible = true },
                            { c, l, s -> open(Screen.Line(c, l, s)) }, { c, s -> open(Screen.Stop(c, s)) },
                            { line -> open(Screen.Metro(city, line)) }
                        )
                        is Screen.Search -> SearchRoute(
                            model { SearchViewModel(graph, screen.city) }, navigation::pop,
                            { open(Screen.Line(screen.city, it)) },
                            { open(Screen.Stop(screen.city, it)) },
                            { lineId -> open(Screen.Metro(screen.city, lineId)) }
                        )
                        is Screen.Line -> LineRoute(
                            model { LineViewModel(graph, screen.city, screen.line, screen.station) },
                            navigation::pop,
                            { open(Screen.Times(screen.city, it)) },
                            { route, plate, boarding -> open(Screen.Vehicle(screen.city, route, plate, boarding)) }
                        )
                        is Screen.Stop -> StationRoute(
                            model { StationViewModel(graph, screen.city, screen.station) },
                            navigation::pop,
                            { line, stop -> open(Screen.Line(screen.city, line, stop)) },
                            { lineId -> open(Screen.Metro(screen.city, lineId)) }
                        )
                        is Screen.Times -> TimetableRoute(model { TimetableViewModel(graph, screen.city, screen.route) }, navigation::pop)
                        is Screen.Metro -> MetroRoute(model { MetroViewModel(graph, screen.city, screen.line) }, navigation::pop)
                        is Screen.Vehicle -> VehicleRoute(
                            model { VehicleViewModel(graph, screen.city, screen.route, screen.plate, screen.boarding) },
                            navigation::pop,
                            { text, title -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, title)) }
                        )
                    }
                }
            }
            }
            if (state is UiState.Error && !navigation.cityVisible) Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { ErrorCard((state as UiState.Error).message) { root.load() } }
            if (navigation.cityVisible) CityRoute(data.cities, located, { navigation.cityVisible = false }, error = (state as? UiState.Error)?.message, failed = state is UiState.Error, retry = { root.load() }) { chosen -> root.city(chosen) { navigation.cityVisible = false; navigation.home() } }
        }
        BulletinHost(bulletin, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 12.dp))
      }
    }
    }
}
