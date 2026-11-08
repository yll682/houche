package com.yll682.houche.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * 地铁资料的内置兜底。站表来自官方接口抓取快照，线路色来自官方 App。
 * 地铁页由 MetroLoader 优先请求在线站表；本类也为离线搜索与站点换乘提供资料。
 */
@Serializable data class MetroStation(
    val order: Int,
    val name: String,
    val transfer: List<String> = emptyList(),
    val opened: Boolean = true,
    /** 官方站码，形如 01010000；接口与线路图共用同一套编号。 */
    val code: String = "",
    /** 往本方向的首末班时间；末班可能为「次日00:00」。 */
    val first: String = "",
    val last: String = "",
    val lng: String = "",
    val lat: String = "",
    /** 官方跨线路共站标识；不能用它代替各线路实时接口所需的 code。 */
    val stationUniqueCode: String = "",
    /** null 表示旧资料未提供，不能将缺失字段当作官方可换乘标记。 */
    val changeFlag: Boolean? = null,
    /** 官方 allStation 坐标为 BD09；未确认来源的旧资料保留 unconfirmed。 */
    val coordinateSystem: String = "unconfirmed",
    /** 每次由当前整份站表重算，避免序列化快照中的旧目标污染在线替换。 */
    @kotlinx.serialization.Transient val transferTargets: List<MetroTransferTarget> = emptyList()
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
    val stations: List<MetroStation> = emptyList(),
    /** 官方八位线路码；旧快照缺失时由首站站码推导。 */
    val apiCode: String = ""
) {
    /**
     * 全部站点按站序排好。
     * 官方接口用 showFlag 标记站点当前是否在运营图上显示（4、6 号线整条都还没正式运营），
     * 仍保留这些站序供查看，只在界面上区分样式。
     */
    val liveStations: List<MetroStation> get() = stations.sortedBy { it.order }

    /** 尚未正式运营的站点数量，用于界面上的状态提示。 */
    val pendingCount: Int get() = stations.count { !it.opened }
}

/** 内置快照没有线路码时，站码前两位仍可确定实时接口所需的线路。 */
fun MetroLine.realtimeCode(): String = apiCode.takeIf { it.isNotEmpty() }
    ?: stations.firstOrNull()?.code?.takeIf { it.isNotEmpty() }?.take(2)?.plus("000000").orEmpty()

@Serializable data class MetroNetwork(
    val city: String = "",
    val cityKey: String = "",
    val updated: String = "",
    /** 站序出处，例如「厦门地铁官方接口 pub/station/allStation」。 */
    val source: String = "",
    val lines: List<MetroLine> = emptyList()
) {
    /** 展示的线路：只要有站点就展示，未正式运营的线路由界面另行标注。 */
    val openedLines: List<MetroLine> get() = lines.filter { it.liveStations.isNotEmpty() }
}

/** 某个地铁站经过的线路，用于在站点页标注换乘。 */
data class MetroCalling(val line: MetroLine, val station: MetroStation)

/** 城市索引与站表分开存放，判断覆盖范围时无需读取完整站序。 */
@Serializable internal data class MetroCity(val city: String, val cityKey: String) {
    fun matches(candidate: City): Boolean =
        (cityKey.isNotBlank() && (cityKey.equals(candidate.key, true) || cityKey.equals(candidate.pinyin, true))) ||
            (city.isNotBlank() && city == candidate.name)
}

class Metro(private val openIndex: () -> InputStream, private val openAsset: () -> InputStream) {
    private val mutex = Mutex()
    private var cached: MetroNetwork? = null
    private var cachedCities: List<MetroCity>? = null

    /** 只查随内置资料提供的城市索引，不依赖电话区号，也不提前读取完整站表。 */
    suspend fun supports(city: City): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            citiesLocked().any { it.matches(city) }
        }
    }

    suspend fun network(): MetroNetwork = withContext(Dispatchers.IO) {
        mutex.withLock {
            networkLocked()
        }
    }

    // 只在持锁期间调用；读取、解析和换乘补齐全部成功后才写入缓存，失败可重新读取。
    private fun citiesLocked(): List<MetroCity> = cachedCities ?: try {
        BusNetwork.json.decodeFromString<List<MetroCity>>(openIndex().bufferedReader(Charsets.UTF_8).use { it.readText() })
            .also { cachedCities = it }
    } catch (error: Exception) {
        throw failure(error, "地铁城市索引加载失败")
    }

    private fun networkLocked(): MetroNetwork = cached ?: try {
        val network = BusNetwork.json.decodeFromString<MetroNetwork>(openAsset().bufferedReader(Charsets.UTF_8).use { it.readText() })
        network.copy(lines = metroTransfers(network.lines)).also { cached = it }
    } catch (error: Exception) {
        throw failure(error, "地铁资料加载失败")
    }

    /**
     * 只返回当前城市有地铁资料时的那份网络，没有就返回 null，界面按「没有地铁」处理。
     * key / 拼音 / 市名任一命中即可：cities.json 的 key 是内部编号（厦门市是 11），
     * 资料里的 cityKey 一旦跟它错位，整条地铁就会静默消失，这里多认两种写法兜住。
     */
    suspend fun networkFor(city: City): MetroNetwork? = withContext(Dispatchers.IO) {
        // 城市判断与完整站表读取属于同一次查询，不能在两次独立加锁之间让出。
        mutex.withLock {
            if (!citiesLocked().any { it.matches(city) }) return@withLock null
            val all = networkLocked()
            val hit = MetroCity(all.city, all.cityKey).matches(city)
            all.takeIf { hit && it.openedLines.isNotEmpty() }
        }
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
