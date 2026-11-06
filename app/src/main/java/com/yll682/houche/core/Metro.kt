package com.yll682.houche.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * 地铁线路资料。厦门地铁官方 App 的接口域名（app.i-amtr.com）已经下线，
 * 高德的老地铁图接口也已 404，所以线路与站序改为内置资源，和 cities.json 同一套做法。
 * 站序、换乘、首末班都极少变动，内置比依赖一个随时会挂的网关更稳。
 */
@Serializable data class MetroStation(
    val order: Int,
    val name: String,
    val transfer: List<String> = emptyList(),
    val opened: Boolean = true
)

@Serializable data class MetroLine(
    val id: String,
    val name: String,
    val color: String = "",
    val from: String = "",
    val to: String = "",
    val opened: Boolean = true,
    val first: String = "",
    val last: String = "",
    val stations: List<MetroStation> = emptyList()
) {
    /** 已开通的站点，按站序排好；未开通段不参与展示。 */
    val liveStations: List<MetroStation> get() = stations.filter { it.opened }.sortedBy { it.order }
}

@Serializable data class MetroNetwork(
    val city: String = "",
    val cityKey: String = "",
    val updated: String = "",
    val lines: List<MetroLine> = emptyList()
) {
    val openedLines: List<MetroLine> get() = lines.filter { it.opened && it.liveStations.isNotEmpty() }
}

/** 某个地铁站经过的线路，用于在站点页标注换乘。 */
data class MetroCalling(val line: MetroLine, val station: MetroStation)

class Metro(private val openAsset: () -> InputStream) {
    private val mutex = Mutex()
    private var cached: MetroNetwork? = null

    suspend fun network(): MetroNetwork = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached ?: try {
                BusNetwork.json.decodeFromString<MetroNetwork>(openAsset().bufferedReader(Charsets.UTF_8).use { it.readText() }).also { cached = it }
            } catch (error: Exception) {
                throw failure(error, "地铁资料加载失败")
            }
        }
    }

    /** 只返回当前城市有地铁资料时的那份网络，没有就返回 null，界面按「没有地铁」处理。 */
    suspend fun networkFor(city: City): MetroNetwork? {
        val all = network()
        return all.takeIf { it.cityKey == city.key && it.openedLines.isNotEmpty() }
    }

    suspend fun lines(city: City, keyword: String = ""): List<MetroLine> {
        val list = networkFor(city)?.openedLines.orEmpty()
        val text = keyword.trim()
        return if (text.isEmpty()) list else list.filter { line ->
            line.name.contains(text, true) || line.id == text || line.from.contains(text, true) ||
                line.to.contains(text, true) || line.liveStations.any { it.name.contains(text, true) }
        }
    }

    /** 站名命中的地铁站，跨线路返回，供搜索与站点页复用。 */
    suspend fun callingAt(city: City, stationName: String): List<MetroCalling> {
        val network = networkFor(city) ?: return emptyList()
        val key = stationName.trim().removeSuffix("站")
        if (key.isEmpty()) return emptyList()
        return network.openedLines.flatMap { line ->
            line.liveStations.filter { it.name.removeSuffix("站") == key }.map { MetroCalling(line, it) }
        }
    }

    suspend fun line(city: City, id: String): MetroLine? =
        networkFor(city)?.openedLines?.firstOrNull { it.id == id }
}