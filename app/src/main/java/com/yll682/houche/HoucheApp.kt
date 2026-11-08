package com.yll682.houche

import android.app.Application
import com.yll682.houche.core.*
import com.yll682.houche.data.*
import kotlinx.coroutines.*

class HoucheApp : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(
    app: Application,
    // 地铁页优先请求在线站表；内置快照供等待时展示、断网兜底与离线搜索。
    val metro: Metro = Metro(openIndex = { app.assets.open("metro-cities.json") }) { app.assets.open("metro.json") }
) {
    private val located = kotlinx.coroutines.flow.MutableStateFlow<City?>(null)
    val locatedCity: kotlinx.coroutines.flow.StateFlow<City?> = located
    fun setLocatedCity(city: City) { located.value = city }
    val cities = Cities { app.assets.open("cities.json") }
    val metroRemote = MetroRemote()
    val metroRealtime = MetroRealtimeRemote()
    val prefs = Prefs(app)
    val location = LocationProvider(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 进程级初始化，不在主线程读资源或 DataStore。
    private var pending = initialize()
    private fun initialize() = scope.async {
        cities.all()
        BusRepository(XmbusClient(prefs.deviceId()))
    }
    suspend fun repository(): BusRepository = pending.await()
    fun retryInitialization() { if (pending.isCancelled) pending = initialize() }
}
