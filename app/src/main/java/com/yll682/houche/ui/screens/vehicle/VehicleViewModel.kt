package com.yll682.houche.ui.screens.vehicle

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.Arrival
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.data.TrackedVehicle
import com.yll682.houche.data.VehicleAlightSelection
import com.yll682.houche.data.VehicleTracker
import com.yll682.houche.data.remainingStopsToTarget
import com.yll682.houche.ui.StateViewModel
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class VehicleData(
    val route: Route,
    val plate: String,
    val boarding: RouteStation?,
    val vehicle: Vehicle? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val tracking: Boolean = false,
    val updatedAt: String? = null,
    val offline: Boolean = false,
    val arrival: Arrival? = null,
    /** 下车提醒站：用户指定的到站提醒目标。 */
    val alight: RouteStation? = null,
    /** 后台跟踪服务是否正在运行。 */
    val backgroundActive: Boolean = false
)

class VehicleViewModel(
    private val graph: AppGraph,
    val city: City,
    route: Route,
    plate: String,
    boarding: RouteStation? = null
) : StateViewModel<VehicleData>(VehicleData(route, plate, boarding)) {
    private val mutex = Mutex()
    private val trackingCommands = Mutex()
    // 只存 applicationContext（见 bindContext），不持有 Activity，因此不构成泄漏；
    // lint 只看字段类型看不出这一点。
    @SuppressLint("StaticFieldLeak")
    private var notificationContext: Context? = null
    private var selectedAlight: VehicleAlightSelection? = null
    private var reloadAfterQuery = false

    init {
        viewModelScope.launch {
            attempt {
                combine(
                    VehicleTracker.active,
                    VehicleTracker.backgroundActive,
                    graph.prefs.vehicleAlightSelection
                ) { tracked, running, selection -> Triple(tracked, running, selection) }.collect { (tracked, running, selection) ->
                    selectedAlight = selection
                    syncTracking(tracked, running)
                }
            }
        }
        load()
    }

    // 只持有应用上下文，不保留页面或 Activity。
    fun bindContext(context: Context) {
        if (notificationContext != null) return
        notificationContext = context.applicationContext
        VehicleTracker.restore(context.applicationContext)
    }

    fun load() = action { queryLive(showLoading = true) }

    suspend fun refreshLive() {
        queryLive(showLoading = false)
    }

    private suspend fun queryLive(showLoading: Boolean) {
        if (!mutex.tryLock()) return
        try {
            attempt {
                if (showLoading) mutable.value = UiState.Loading(data)
                val snapshot = data
                val repo = graph.repository()
                coroutineScope {
                    val liveQuery = async { repo.vehicles(city, snapshot.route, snapshot.boarding) }
                    val arrivalQuery = async {
                        val order = snapshot.boarding?.order
                        if (city.isXiamen && order != null && order > 0) {
                            repo.arrivals(city, snapshot.route.name, snapshot.route.direction, order)
                        } else emptyList()
                    }
                    val live = liveQuery.await()
                    val arrivals = if (city.isXiamen) arrivalQuery.await() else live.arrivals
                    val current = live.vehicles.firstOrNull { it.plate == snapshot.plate }
                    val arrival = arrivals.firstOrNull { it.plate == snapshot.plate && !it.waiting }
                    // 切车期间到达的旧响应不能覆盖新车位置；解锁后补查当前车辆。
                    if (snapshot.plate != data.plate) {
                        reloadAfterQuery = true
                        return@coroutineScope
                    }
                    // 离线时保留最后位置，提醒只使用本次确实查到的车辆。
                    update(data.copy(
                        vehicle = current ?: data.vehicle,
                        vehicles = live.vehicles,
                        offline = current == null,
                        arrival = if (current != null) arrival else null,
                        updatedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    ))
                    // 页面只展示实时数据；提醒唯一由后台服务发送。
                }
            }
        } finally {
            mutex.unlock()
            if (reloadAfterQuery) {
                reloadAfterQuery = false
                load()
            }
        }
    }

    fun refresh() = action { refreshLive() }

    fun selectVehicle(plate: String) {
        if (plate == data.plate) return
        update(data.copy(plate = plate, vehicle = null, arrival = null, offline = false))
        syncTracking(VehicleTracker.active.value, VehicleTracker.backgroundActive.value)
        if (mutex.isLocked) reloadAfterQuery = true
        load()
    }

    fun toggleTracking() { changeTracking(null) }

    /** 权限弹窗返回时使用幂等开启；恢复已完成也不会把“开启”翻转成关闭。 */
    fun startTracking() { changeTracking(true) }

    fun stop() { changeTracking(false) }

    private fun changeTracking(enabled: Boolean?) {
        val context = notificationContext ?: return
        val target = trackingTarget()
        action {
            trackingCommands.withLock {
                val active = VehicleTracker.active.value
                val tracking = active?.matches(target.city, target.route, target.plate, target.boarding) == true
                val shouldTrack = enabled ?: !tracking
                if (shouldTrack && !tracking) {
                    // 用户连续点选站和开启时，等待前一个保存完成后再读取当前选站。
                    val startTarget = if (target.matches(city, data.route, data.plate, data.boarding)) {
                        target.copy(alightName = data.alight?.name, alightOrder = data.alight?.order)
                    } else target
                    VehicleTracker.start(context, startTarget)
                } else if (!shouldTrack && tracking) {
                    VehicleTracker.stop(context, active)
                }
                syncTracking(VehicleTracker.active.value, VehicleTracker.backgroundActive.value)
            }
        }
    }

    fun selectAlight(station: RouteStation) {
        val context = notificationContext ?: return
        val target = trackingTarget()
        // 接收用户明确选择的站；未知站序可保存，但服务不会猜站序或发送提醒。
        val selected = station.order?.let { order -> data.route.stations.firstOrNull { it.order == order } } ?: station
        action {
            trackingCommands.withLock {
                VehicleTracker.selectAlight(context, target, selected)
                if (target.matches(city, data.route, data.plate, data.boarding)) {
                    preserveState(data.copy(alight = selected))
                }
            }
        }
    }

    fun clearAlight() {
        val context = notificationContext ?: return
        val target = trackingTarget()
        action {
            trackingCommands.withLock {
                VehicleTracker.selectAlight(context, target, null)
                if (target.matches(city, data.route, data.plate, data.boarding)) {
                    preserveState(data.copy(alight = null, tracking = false, backgroundActive = false))
                }
            }
        }
    }

    private fun trackingTarget(): TrackedVehicle = TrackedVehicle(
        city = city,
        lineName = data.route.name,
        plate = data.plate,
        boardingName = data.boarding?.name,
        route = data.route,
        boarding = data.boarding,
        alightName = data.alight?.name,
        alightOrder = data.alight?.order
    )

    private fun syncTracking(tracked: TrackedVehicle?, running: Boolean) {
        val tracking = matches(tracked)
        val selection = selectedAlight?.takeIf {
            it.cityKey == city.key && it.routeId == data.route.id &&
                it.direction == data.route.direction && it.plate == data.plate
        }
        preserveState(data.copy(
            tracking = tracking,
            backgroundActive = tracking && running,
            alight = if (tracking) tracked?.alight else selection?.station
        ))
    }

    private fun preserveState(value: VehicleData) {
        // 跟踪选择变化不抹掉查询错误或加载状态。
        mutable.value = when (val old = mutable.value) {
            is UiState.Loading -> old.copy(data = value)
            is UiState.Error -> old.copy(data = value)
            is UiState.Empty -> old.copy(data = value)
            is UiState.Success -> old.copy(data = value)
        }
    }

    private fun matches(tracked: TrackedVehicle?): Boolean =
        tracked?.matches(city, data.route, data.plate, data.boarding) == true
}

/** 不用坐标、站名或列表下标推测站序，未知位置也不计算站数。 */
internal fun remainingStops(boarding: RouteStation?, vehicle: Vehicle?): Int? {
    return remainingStopsToTarget(boarding, vehicle)
}
