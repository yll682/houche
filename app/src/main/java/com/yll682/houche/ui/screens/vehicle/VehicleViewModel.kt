package com.yll682.houche.ui.screens.vehicle

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.R
import com.yll682.houche.core.Arrival
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.data.TrackedVehicle
import com.yll682.houche.data.VehicleTracker
import com.yll682.houche.ui.StateViewModel
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    val arrival: Arrival? = null
)

class VehicleViewModel(
    private val graph: AppGraph,
    val city: City,
    route: Route,
    plate: String,
    boarding: RouteStation? = null
) : StateViewModel<VehicleData>(VehicleData(route, plate, boarding)) {
    private val mutex = Mutex()
    // 只存 applicationContext（见 bindContext），不持有 Activity，因此不构成泄漏；
    // lint 只看字段类型看不出这一点。
    @SuppressLint("StaticFieldLeak")
    private var notificationContext: Context? = null
    private var notifiedStop: Int? = null

    init {
        viewModelScope.launch {
            attempt {
                VehicleTracker.active.collect { tracked ->
                    val tracking = matches(tracked)
                    if (!tracking) notifiedStop = null
                    if (data.tracking != tracking) setTracking(tracking)
                }
            }
        }
        load()
    }

    // 只持有应用上下文，不保留页面或 Activity。
    fun bindContext(context: Context) {
        notificationContext = context.applicationContext
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
                    // 离线时保留最后位置，提醒只使用本次确实查到的车辆。
                    update(data.copy(
                        vehicle = current ?: data.vehicle,
                        vehicles = live.vehicles,
                        offline = current == null,
                        arrival = if (current != null) arrival else null,
                        updatedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    ))
                    notifyIfNeeded(current)
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    fun refresh() = action { refreshLive() }

    fun selectVehicle(plate: String) {
        if (plate == data.plate) return
        update(data.copy(plate = plate, vehicle = null, arrival = null, offline = false))
        load()
    }

    // 跟踪器的选择由 Route 同步，界面状态在点击时立即变化。
    fun toggleTracking() {
        setTracking(!data.tracking)
        if (!data.tracking) notifiedStop = null
    }

    fun stop() {
        if (matches(VehicleTracker.active.value)) VehicleTracker.stop()
        notifiedStop = null
        setTracking(false)
    }

    private fun setTracking(tracking: Boolean) {
        val value = data.copy(tracking = tracking)
        // 跟踪选择变化不抹掉查询错误或加载状态。
        mutable.value = when (val old = mutable.value) {
            is UiState.Loading -> old.copy(data = value)
            is UiState.Error -> old.copy(data = value)
            is UiState.Empty -> old.copy(data = value)
            is UiState.Success -> old.copy(data = value)
        }
    }

    private fun matches(tracked: TrackedVehicle?): Boolean = tracked != null &&
        tracked.city.key == city.key && tracked.lineName == data.route.name &&
        tracked.plate == data.plate && tracked.boardingName == data.boarding?.name

    private fun notifyIfNeeded(vehicle: Vehicle?) {
        val remaining = remainingStops(data.boarding, vehicle)
        // 只在还差 0 或 1 站时提醒；已经开过去（负数）、站数未知、还差得远都只重置。
        if (remaining == null || remaining < 0 || remaining > 1) {
            notifiedStop = null
            return
        }
        val stop = data.boarding ?: return
        val context = notificationContext ?: return
        if (!data.tracking || !matches(VehicleTracker.active.value) || notifiedStop == stop.order) return
        VehicleTracker.notify(
            context,
            context.getString(R.string.vehicle_notify_arrival),
            context.getString(R.string.vehicle_notify_stop, data.plate, stop.name)
        )
        notifiedStop = stop.order
    }
}

/** 不用坐标、站名或列表下标推测站序，未知位置也不计算站数。 */
internal fun remainingStops(boarding: RouteStation?, vehicle: Vehicle?): Int? {
    val destination = boarding?.order?.takeIf { it > 0 } ?: return null
    val current = vehicle?.order?.takeIf { it > 0 } ?: return null
    if (vehicle.positionState != "at-stop" && vehicle.positionState != "between") return null
    return destination - current
}
