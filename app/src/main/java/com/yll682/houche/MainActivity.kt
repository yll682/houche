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
    internal var initialVehicleIntentHandled = false
    private var vehicleLinkJob: Job? = null
    var cityVisible by mutableStateOf(false)
    // 四个 Tab 共用首页条目，切换时保留各自的 ViewModel。
    var segment by mutableIntStateOf(HomeTab.BUS)
    var pendingMetroLine by mutableStateOf<String?>(null)
    // 收藏站点可能来自别城，地铁保留来源城市，不改公交当前城市。
    var metroCity by mutableStateOf<City?>(null)
    fun push(screen: Screen) { stack.add(PageEntry(screen)) }
    fun pop() { if (stack.size > 1) { val entry = stack.removeAt(stack.lastIndex); retired.add(entry) } }
    fun release(entry: PageEntry) { if (entry !in stack) { entry.viewModelStore.clear(); retired.remove(entry) } }
    fun home() { val visible = stack.last(); while (stack.size > 1) pop(); segment = HomeTab.BUS; pendingMetroLine = null; metroCity = null; retired.filter { it != visible }.toList().forEach { release(it) } }
    // 站点也可能从搜索页进入，换乘时要把中间页一并退掉，才能露出地铁 Tab。
    fun metro(line: String, city: City? = null) { home(); segment = HomeTab.METRO; metroCity = city; pendingMetroLine = line }
    fun openVehicleLink(graph: AppGraph, uri: String, notify: (Int) -> Unit) {
        vehicleLinkJob?.cancel()
        val target = VehicleShareLink.parse(uri)
        if (target == null || target.direction.isEmpty()) {
            notify(R.string.vehicle_link_incomplete)
            return
        }
        notify(R.string.vehicle_link_loading)
        vehicleLinkJob = viewModelScope.launch {
            try {
                val screen = withTimeout(20_000) {
                    val cities = graph.cities.all().filter { it.code == target.cityCode }
                    if (cities.isEmpty()) {
                        notify(R.string.vehicle_link_city_not_found)
                        return@withTimeout null
                    }
                    if (cities.size != 1) {
                        notify(R.string.vehicle_link_incomplete)
                        return@withTimeout null
                    }
                    val city = cities.single()
                    // 优先按 ID 匹配已有完整线路；跨城线路绝不混用。
                    val known = stack.mapNotNull { entry ->
                        when (val page = entry.screen) {
                            is Screen.Vehicle -> page.route.takeIf { page.city.key == city.key }
                            is Screen.Times -> page.route.takeIf { page.city.key == city.key }
                            else -> null
                        }
                    }.toMutableList()
                    if (target.lineId != null && known.none { it.id == target.lineId && it.direction == target.direction }) {
                        graph.prefs.trackedVehicle.first()?.takeIf { it.city.key == city.key }?.let { known.add(it.route) }
                    }
                    val route = resolveVehicleShareRoute(target, known) { name, direction ->
                        graph.repository().lineDetail(city, name, direction)
                    }
                    // 厦门 Route.direction 是请求值；实际方向必须由该方向站表证实。
                    if (route == null || (city.isXiamen && route.stations.isEmpty())) {
                        notify(R.string.vehicle_link_line_not_found)
                        null
                    } else Screen.Vehicle(city, route, target.plate, boarding = null)
                }
                ensureActive()
                if (screen != null) {
                    cityVisible = false
                    push(screen)
                }
            } catch (timeout: TimeoutCancellationException) {
                // 被新链接取消的旧请求不能再提示超时或推入页面。
                ensureActive()
                notify(R.string.vehicle_link_failed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ensureActive()
                android.util.Log.w("Houche", "分享车辆线路查询失败", error)
                notify(if (error is BusException && error.message == "上游没有返回这条线路的资料")
                    R.string.vehicle_link_line_not_found else R.string.vehicle_link_failed)
            }
        }
    }
    override fun onCleared() { (stack + retired).forEach { it.viewModelStore.clear() } }
}

/** 复用线路页的加载入口；有 ID 时只接受相同 ID，防止同名或旧链接打开别的线路。 */
internal suspend fun resolveVehicleShareRoute(
    target: VehicleShareTarget,
    knownRoutes: List<Route>,
    lineDetail: suspend (String, String) -> Route
): Route? {
    if (target.direction != "1" && target.direction != "2") return null
    target.lineId?.let { id ->
        knownRoutes.firstOrNull { it.id == id && it.direction == target.direction && it.name.isNotBlank() }?.let { return it }
    }
    // BusRepository 目前只有 name + direction 入口，不把 ID 冒充 busLineName。
    if (target.lineName.isBlank()) return null
    return lineDetail(target.lineName, target.direction).takeIf { route ->
        route.name.isNotBlank() && route.direction == target.direction &&
            if (target.lineId != null) route.id == target.lineId else route.name == target.lineName
    }
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
    private val navigation: NavigationState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as HoucheApp).graph
        if (!navigation.initialVehicleIntentHandled) {
            navigation.initialVehicleIntentHandled = true
            handleVehicleIntent(intent)
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVehicleIntent(intent)
    }

    private fun handleVehicleIntent(intent: Intent?) {
        // 从桌面图标重新进来时回到首页：singleTask 下系统只发 onNewIntent，
        // 不重置的话会停在离开时的那一页。
        if (intent?.action == Intent.ACTION_MAIN) {
            navigation.home()
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.dataString ?: return
        val context = applicationContext
        navigation.openVehicleLink((application as HoucheApp).graph, uri) { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
    // 返回位移只属于发起手势的那一页；栈顶一换，新页面不能带着旧位移出现。
    var backOwner by remember { mutableStateOf<Any?>(null) }
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
        backOwner = backEntry
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
        else if (navigation.segment != HomeTab.BUS) navigation.segment = HomeTab.BUS
        else {
            val now = SystemClock.elapsedRealtime()
            if (lastBack != 0L && now - lastBack < 2000) exit()
            else { lastBack = now; Toast.makeText(context, exitMessage, Toast.LENGTH_SHORT).show() }
        }
    }
    val sheetHost = remember { SheetHost() }
    CompositionLocalProvider(LocalBulletinCenter provides bulletin, LocalSheetHost provides sheetHost) {
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
                Modifier.fillMaxSize().testTag("page-container").graphicsLayer { translationX = if (navigation.stack.last() === backOwner) backX.value else 0f }.drawWithContent {
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                        // 仍录一份图层给「返回时露出的上一页」和导航栏取景，但屏幕上画的是实时内容，不是缓存帧。
                        pageLayer.record { this@drawWithContent.drawContent() }; drawContent()
                    } else drawContent()
                }, transitionSpec = {
                    when {
                        gesturePop -> EnterTransition.None togetherWith ExitTransition.None
                        targetState.order > initialState.order ->
                            slideInHorizontally(tween(200, easing = TelegramQuint)) { it } togetherWith
                                slideOutHorizontally(tween(200, easing = TelegramQuint)) { -it / 3 }
                        else -> slideInHorizontally(tween(200, easing = TelegramQuint)) { -it / 3 } togetherWith
                            slideOutHorizontally(tween(200, easing = TelegramQuint)) { it }
                    }
                }, label = "页面切换") { entry ->
                DisposableEffect(entry) { onDispose { navigation.release(entry) } }
                pageStates.SaveableStateProvider(entry.stateKey) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    when (val screen = entry.screen) {
                        Screen.Home -> {
                            // 按城市留在首页的 Store 中；第一次进地铁前不加载站序。
                            val metroCity = navigation.metroCity ?: city
                            val metro: MetroViewModel? = if (navigation.segment == HomeTab.METRO || navigation.pendingMetroLine != null) {
                                model(key = "metro-${metroCity.key}") { MetroViewModel(graph, metroCity) }
                            } else null
                            val pending = navigation.pendingMetroLine
                            LaunchedEffect(metro, pending) {
                                if (metro != null && pending != null) {
                                    metro.selectLine(pending)
                                    navigation.pendingMetroLine = null
                                }
                            }
                            HomeRoute(
                                model(key = city.key) { HomeViewModel(graph, city) },
                                model(key = "settings-${city.key}") { com.yll682.houche.ui.screens.settings.SettingsViewModel(graph, city) },
                                navigation.segment, { navigation.segment = it },
                                { open(Screen.Search(city)) }, { navigation.cityVisible = true },
                                { c, l, s -> open(Screen.Line(c, l, s)) }, { c, s -> open(Screen.Stop(c, s)) },
                                metroContent = { if (metro != null) MetroRoute(metro) }
                            )
                        }
                        is Screen.Search -> SearchRoute(
                            model { SearchViewModel(graph, screen.city) }, navigation::pop,
                            { open(Screen.Line(screen.city, it)) },
                            { open(Screen.Stop(screen.city, it)) },
                            { lineId -> navigation.metro(lineId, screen.city) }
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
                            { lineId -> navigation.metro(lineId, screen.city) }
                        )
                        is Screen.Times -> TimetableRoute(model { TimetableViewModel(graph, screen.city, screen.route) }, navigation::pop)
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
        SheetHostLayer(sheetHost)
        BulletinHost(bulletin, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 12.dp))
      }
    }
    }
}
