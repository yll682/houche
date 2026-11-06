package com.yll682.houche.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * 地铁线路资料。站序、线路色与换乘全部取自厦门地铁官方 App 内置的运营线路图
 * （res/raw/xm_20260609_1.svg，元素 ID 自带线路与站序），由 analysis/extract_svg.py 提取。
 * 官方网关 app.i-amtr.com 的签名算法也已还原（见 MetroRemote），但它要求登录态，
 * 匿名拿不到数据，所以线网资料走内置资源，和 cities.json 同一套做法。
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
    /** 站序出处，例如「厦门地铁官方App线路图 xm_20260609_1.svg」。 */
    val source: String = "",
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

    /**
     * 只返回当前城市有地铁资料时的那份网络，没有就返回 null，界面按「没有地铁」处理。
     * key / 拼音 / 市名任一命中即可：cities.json 的 key 是内部编号（厦门市是 11），
     * 资料里的 cityKey 一旦跟它错位，整条地铁就会静默消失，这里多认两种写法兜住。
     */
    suspend fun networkFor(city: City): MetroNetwork? {
        val all = network()
        val hit = all.cityKey.equals(city.key, true) ||
            (all.cityKey.isNotBlank() && all.cityKey.equals(city.pinyin, true)) ||
            (all.city.isNotBlank() && all.city == city.name)
        return all.takeIf { hit && it.openedLines.isNotEmpty() }
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
