package com.yll682.houche.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.time.Instant

class BusRepository(private val xmbus: XmbusClient, private val zsgj: ZsgjClient = ZsgjClient(), private val nanoTime: () -> Long = System::nanoTime) {
    private data class Entry(val created: Long, val value: Any)
    private val cache = LinkedHashMap<List<Any?>, Entry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<List<Any?>, CompletableDeferred<Any>>()
    // 首页与详情共用请求预算；重试也占用同一个许可。
    private val stationRequests = Semaphore(3)
    fun clearCache() = synchronized(cache) { cache.clear() }
    private suspend fun <T : Any> cached(vararg key: Any?, ttlNanos: Long = 15_000_000_000L, query: suspend () -> T): T = withContext(Dispatchers.IO) {
        val cacheKey = key.toList()
        var owner = false
        val pending = synchronized(cache) {
            cache[cacheKey]?.takeIf { nanoTime() - it.created < ttlNanos }?.let {
                @Suppress("UNCHECKED_CAST")
                return@withContext it.value as T
            }
            cache.remove(cacheKey)
            inFlight[cacheKey] ?: CompletableDeferred<Any>().also {
                inFlight[cacheKey] = it
                owner = true
            }
        }
        if (!owner) {
            try {
                @Suppress("UNCHECKED_CAST")
                return@withContext pending.await() as T
            } catch (error: CancellationException) {
                // 发起页取消不等于等待页取消；仍活跃的页面重新接手请求。
                currentCoroutineContext().ensureActive()
                return@withContext cached(*key, ttlNanos = ttlNanos, query = query)
            }
        }
        try {
            query().also { result -> synchronized(cache) {
                cache[cacheKey] = Entry(nanoTime(), result)
                while (cache.size > 512) cache.remove(cache.keys.first())
            }; pending.complete(result) }
        } catch (error: Exception) {
            val wrapped = if (error is CancellationException) error else failure(error, "公交资料解析失败，请稍后重试")
            synchronized(cache) { if (inFlight[cacheKey] === pending) inFlight.remove(cacheKey) }
            pending.completeExceptionally(wrapped)
            throw wrapped
        } finally { synchronized(cache) { if (inFlight[cacheKey] === pending) inFlight.remove(cacheKey) } }
    }
    private suspend fun xm(path: String, vararg params: Pair<String, Any?>): JsonElement = xmbus.query(path, mapOf(*params)).field("data")
    private suspend fun pub(city: City, command: String, vararg params: Pair<String, Any?>): JsonElement = zsgj.publicQuery(command, mapOf("CITYNAME" to city.name, *params))
    private suspend fun publicLines(city: City, station: Station, lat: Double? = station.lat, lng: Double? = station.lng): List<JsonElement> =
        cached("publicLines", city.key, station.name, lat, lng) {
            stationRequests.withPermit {
                stationLineArray(pub(city, "115", "STATIONNAME" to station.name, "MYLAT" to lat, "MYLNG" to lng, "ALL" to 1).field("data"), "upperOrDown")
            }
        }
    private fun line(item: JsonElement, native: Boolean): LineSummary {
        val dir = item.s(if (native) "upperOrDownInLine" else "upperOrDown")
        return LineSummary(item.s("lineName"), dir, item.s(if (native) if (dir == "1") "suStation" else "sdStation" else "from"),
            item.s(if (native) if (dir == "1") "euStation" else "edStation" else "to"), item.i(if (native) "stationOrderInLine" else "stationOrder"))
    }
    private fun station(item: JsonElement, native: Boolean): Station = if (native) Station(
        item.s("name"), item.field("stationNo").text()?.takeIf { it.isNotBlank() }, item.d("gcjLat"), item.d("gcjLng"), item.d("distance"), "GCJ02",
        (listOfNotNull(item.field("stationNo").text()) + item.list("relativeStationNoList").mapNotNull { it.text() }).filter { it.isNotBlank() }.distinct(),
        item.list("lineList").map { line(it, true) }
    ) else Station(item.s("name"), lat = item.d("lat"), lng = item.d("lon"), distance = item.d("dis")?.takeIf { it >= 0 }, sameNum = item.i("sameNum"))

    suspend fun nearby(city: City, lat: Double, lng: Double): List<Station> = cached("nearby", city, lat, lng) {
        coordinates(lat, lng)
        val stations = if (city.isXiamen) xm("nearestExactStation", "lat" to lat, "lng" to lng, "num" to "8").items().map { station(it, true) }
        else coroutineScope {
            pub(city, "106", "LAT" to lat, "LNG" to lng).list("data").mapIndexed { index, item -> async {
                val stop = station(item, false)
                if (index >= 3) stop else try { stop.copy(lines = publicLines(city, stop).map { line(it, false) }) }
                catch (error: Exception) { val wrapped = failure(error, "站点线路查询失败"); stop.copy(linesError = wrapped.message) }
            } }.awaitAll()
        }
        stations.sortedWith(compareBy<Station> { it.distance == null }.thenBy { it.distance })
    }
    suspend fun search(city: City, keyword: String): SearchResults = cached("search", city, keyword) {
        coroutineScope {
            val lines = async { searchPart(city, keyword, true) }
            val stations = async { searchPart(city, keyword, false) }
            SearchResults(lines.await().map {
                LineSummary(it.s("lineName"), it.s(if (city.isXiamen) "direction" else "upperOrDown"), it.s("from"), it.s("to"))
            }, stations.await().map { Station(it.s("stationName")) })
        }
    }
    private suspend fun searchPart(city: City, keyword: String, line: Boolean): List<JsonElement> = if (city.isXiamen)
        zsgj.snapshot("searchLinesOrStation", mapOf("cityName" to city.name, "keyword" to keyword, "searchType" to if (line) "searchLine" else "searchStation")).list("data")
    else pub(city, if (line) "114" else "110", "KEYWORD" to keyword).list(if (line) "buslines" else "busstations")

    suspend fun stationDetail(city: City, name: String, number: String? = null, lat: Double? = null, lng: Double? = null): Station = cached(
        "stationDetail", city.key, if (city.isXiamen && !number.isNullOrBlank()) "number" else "name",
        if (city.isXiamen) number?.takeIf { it.isNotBlank() } ?: name else name,
        if (city.isXiamen) null else lat, if (city.isXiamen) null else lng,
        ttlNanos = if (city.isXiamen) 300_000_000_000L else 15_000_000_000L
    ) {
        if (city.isXiamen) {
            val raw = stationRequests.withPermit {
                if (!number.isNullOrBlank()) xm("exactStationDetail", "stationNo" to number, "reverseStationNo" to "", "amapStationId" to "")
                else xm("stationDetail", "busStationName" to name)
            }
            requireData(raw, "上游没有返回这个站点的资料")
            station(raw, true).let { parsed ->
                val id = parsed.number ?: number?.takeIf { it.isNotBlank() }
                parsed.copy(name = parsed.name.ifBlank { name }, number = id, platformNumbers = (listOfNotNull(id) + parsed.platformNumbers).distinct())
            }
        } else {
            val platforms = stationRequests.withPermit {
                stationArray(pub(city, "209", "STATIONNAME" to name, "MYLAT" to lat, "MYLNG" to lng, "LAT" to lat, "LNG" to lng).field("info")).map { station(it, false) }
            }
            if (platforms.isEmpty()) throw BusException(BusException.Kind.UPSTREAM, "上游没有返回这个站点的资料")
            val selected = if (lat != null && lng != null) platforms.minBy { p ->
                if (p.lat == null || p.lng == null) Double.POSITIVE_INFINITY else (p.lat-lat)*(p.lat-lat)+(p.lng-lng)*(p.lng-lng)
            } else platforms.first()
            // 线路补充失败不能让已经取得的站台资料消失；实时查询会单独重试。
            try { selected.copy(platforms = platforms, lines = publicLines(city, selected).map { line(it, false) }) }
            catch (error: Exception) { selected.copy(platforms = platforms, linesError = failure(error, "站点线路查询失败").message) }
        }
    }

    /** 首页等调用者获取全站聚合；站点详情可单独请求所选站台。 */
    suspend fun stationArrivals(city: City, station: Station, lat: Double? = station.lat, lng: Double? = station.lng): StationArrivals =
        stationArrivals(city, station, lat, lng, selectedOnly = false)

    suspend fun stationPlatformArrivals(city: City, station: Station): StationArrivals =
        stationArrivals(city, station, station.lat, station.lng, selectedOnly = true)

    private suspend fun stationArrivals(city: City, station: Station, lat: Double?, lng: Double?, selectedOnly: Boolean): StationArrivals = coroutineScope {
        val errors = mutableListOf<String>()
        val seed = station.copy(lat = lat, lng = lng)
        val resolved = try {
            stationDetail(city, seed.name, seed.number, seed.lat, seed.lng).let { detail ->
                detail.copy(distance = detail.distance ?: seed.distance,
                    platformNumbers = (detail.platformNumbers + seed.platformNumbers).distinct())
            }
        } catch (error: Exception) {
            errors += failure(error, "站台资料查询失败").message
            // 输入可能已经是全站聚合结果；降级时不能把所有线路都归到主站台。
            val known = seed.platforms.firstOrNull {
                if (city.isXiamen) !seed.number.isNullOrBlank() && it.number == seed.number
                else seed.lat != null && seed.lng != null && it.lat == seed.lat && it.lng == seed.lng
            }
            known?.copy(distance = seed.distance, platformNumbers = (known.platformNumbers + seed.platformNumbers).distinct(), platforms = seed.platforms) ?: seed
        }
        val platforms = if (selectedOnly) {
            listOf(resolved.copy(platforms = emptyList()))
        } else if (city.isXiamen) {
            // relativeStationNoList 可能含自身；只按明确编号扩展，绝不按同名合并站台。
            val byNumber = linkedMapOf<String, Station>()
            val queue = ArrayDeque<String>()
            fun enqueue(stop: Station) {
                (listOfNotNull(stop.number) + stop.platformNumbers).filter { it.isNotBlank() }.forEach {
                    if (it !in byNumber && it !in queue) queue.addLast(it)
                }
            }
            resolved.number?.takeIf { it.isNotBlank() }?.let { byNumber[it] = resolved.copy(platforms = emptyList()) }
            enqueue(resolved)
            seed.platforms.forEach(::enqueue)
            while (queue.isNotEmpty()) {
                val number = queue.removeFirst()
                val known = seed.platforms.firstOrNull { it.number == number }
                    ?: Station(resolved.name, number = number, coordinateSystem = resolved.coordinateSystem)
                val detail = try { stationDetail(city, known.name, number, known.lat, known.lng) }
                catch (error: Exception) {
                    errors += failure(error, "部分站台资料查询失败").message
                    known
                }
                byNumber[number] = detail.copy(platforms = emptyList())
                enqueue(detail)
            }
            byNumber.values.toList().distinctBy { it.number }
        } else {
            (listOf(resolved.copy(platforms = emptyList())) + resolved.platforms).distinctBy { Triple(it.name, it.lat, it.lng) }
        }
        if (platforms.isEmpty()) return@coroutineScope StationArrivals(resolved, emptyList(), StationArrivalsStatus.UNAVAILABLE,
            (errors + "这个站点尚未取得实时车辆接口").distinct())

        val results = platforms.map { platform -> async { platformArrivals(city, platform) } }.awaitAll()
        // 站点聚合结果给站点页与首页「附近站点」共用，按「到该站还剩几站」从近到远排。
        val arrivals = orderArrivalsByNear(results.flatMap { it.arrivals })
        errors += results.flatMap { it.errors }
        val gaps = results.flatMap { result ->
            result.station.lines.filter { summary -> result.arrivals.none { it.name == summary.name && it.direction == summary.direction } }
                .map { StationArrivalGap(it, result.station.number, result.station.lat, result.station.lng) }
        }
        if (gaps.isNotEmpty() && results.any { it.succeeded }) errors += "部分线路暂未返回到站资料"
        val completePlatforms = results.map { result ->
            result.station.copy(lines = (result.station.lines + result.arrivals.map {
                LineSummary(it.name, it.direction, to = it.to, stationOrder = it.stationOrder)
            }).distinctBy { it.name to it.direction }, platforms = emptyList())
        }
        // 聚合模式保留各站台线路来源；单站台模式只返回所选站台线路。
        val detail = resolved.copy(platforms = if (selectedOnly) seed.platforms.ifEmpty { resolved.platforms } else completePlatforms,
            platformNumbers = (resolved.platformNumbers + completePlatforms.mapNotNull { it.number }).distinct(),
            lines = completePlatforms.flatMap { it.lines }.distinctBy { it.name to it.direction }, linesError = null)
        val status = when {
            results.none { it.succeeded } -> StationArrivalsStatus.UNAVAILABLE
            errors.isNotEmpty() -> StationArrivalsStatus.PARTIAL
            arrivals.isEmpty() -> StationArrivalsStatus.EMPTY
            else -> StationArrivalsStatus.READY
        }
        StationArrivals(detail, arrivals, status, errors.distinct(), gaps)
    }

    /** 兼容旧调用者；全失败继续抛错，部分成功返回已取得的记录。 */
    suspend fun stationBuses(city: City, station: Station, lat: Double? = station.lat, lng: Double? = station.lng): List<Arrival> {
        val result = stationArrivals(city, station, lat, lng)
        if (result.status == StationArrivalsStatus.UNAVAILABLE) throw BusException(BusException.Kind.UPSTREAM, result.errors.firstOrNull() ?: "站点实时查询失败")
        return result.arrivals
    }

    private data class PlatformResult(val station: Station, val arrivals: List<Arrival>, val succeeded: Boolean, val errors: List<String> = emptyList())
    private suspend fun platformArrivals(city: City, platform: Station): PlatformResult {
        val records = try {
            if (city.isXiamen) cached("nativeStationArrivals", city.key, platform.number, platform.name, platform.lat, platform.lng, maxOf(20, platform.lines.size)) {
                stationRequests.withPermit {
                    stationLineArray(xm("stationLineBus", "stationNo" to platform.number, "stationName" to platform.name,
                        "stationLon" to platform.lng, "stationLat" to platform.lat, "lineNum" to maxOf(20, platform.lines.size).toString()), "upDown").map {
                        val next = it.field("nextTime").text()?.trim()
                        val waiting = it.s("carNo").isEmpty() || !next.isNullOrEmpty()
                        Arrival(name = it.s("lineName"), direction = it.s("upDown"), to = it.s("endStationName"), plate = it.field("carNo").text(), waiting = waiting,
                            remainingStations = if (waiting) null else it.i("remainNum"), distance = if (waiting) null else it.d("remainDist"), nextDeparture = next,
                            platformNumber = platform.number, platformLat = platform.lat, platformLng = platform.lng)
                    }
                }
            } else publicLines(city, platform).map {
                Arrival(name = it.s("lineName"), direction = it.s("upperOrDown"), to = it.s("to"), statusText = it.s("neartext"), timeText = it.s("neardis"),
                    stationOrder = it.i("stationOrder"), platformNumber = platform.number, platformLat = platform.lat, platformLng = platform.lng)
            }
        } catch (error: Exception) {
            return PlatformResult(platform, emptyList(), false, listOf(failure(error, "站台实时查询失败").message))
        }
        if (!city.isXiamen || records.isEmpty()) return PlatformResult(platform, records, true)
        // CMD 115 只补时间：它失败时原生车辆记录仍然可用，下次刷新可立即重试。
        return try {
            val times = publicLines(city, platform).associate { (it.s("lineName") to it.s("upperOrDown")) to it.s("neardis") }
            PlatformResult(platform, records.map { it.copy(timeText = times[it.name to it.direction].orEmpty()) }, true)
        } catch (error: Exception) { PlatformResult(platform, records, true, listOf(failure(error, "到站时间补充失败").message)) }
    }
    suspend fun lineDetail(city: City, name: String, direction: String): Route = cached("lineDetail", city, name, direction) {
        direction(direction)
        if (city.isXiamen) {
            val raw = xm("lineDetail", "busLineName" to name)
            requireData(raw, "上游没有返回这条线路的资料")
            val prefix = if (direction == "1") "u" else "d"
            Route(raw.s("name"), raw.s("id"), direction, raw.s("s${prefix}Station"), raw.s("e${prefix}Station"), raw.s("s${prefix}Time"), raw.s("e${prefix}Time"), raw.s("comments"),
                raw.list("stationList").filter { it.s("upperOrDown") == direction }.map { RouteStation(it.s("name"), it.i("stationOrder"), it.field("id").text(), it.d("gcjLat"), it.d("gcjLng")) }.sortedBy { it.order ?: Int.MAX_VALUE }, true, "厦门公交", "GCJ02")
        } else {
            val raw = pub(city, "103", "LINENAME" to name, "DIRECTION" to direction)
            val stations = raw.list("data").map { RouteStation(it.s("stationName"), it.i("stationOrder"), lat = it.d("station_lat"), lng = it.d("station_lon")) }
            val firstLast = raw.list("firstLast").firstOrNull()
            Route(raw.s("routeName"), raw.s("routeId"), raw.s("upperOrDown"), stations.firstOrNull()?.name.orEmpty(), stations.lastOrNull()?.name.orEmpty(),
                firstLast?.s("first").orEmpty(), firstLast?.s("last").orEmpty(), raw.s("commonts"), stations.sortedBy { it.order ?: Int.MAX_VALUE }, raw.i("showDepart") == 1, "掌上公交 H5",
                operatingPeriods = raw.field("firstLast"), path = raw.field("nihelist"), companies = raw.field("companys"), relatedRoutes = raw.field("StationRelatedRouteName"))
        }
    }
    // 未选乘车站时以终点方向最后一站为参照：上游按参照站序号返回整条线路的车辆，
    // 乘客进线路页就能看到车在哪，不必先选站。
    suspend fun vehicles(city: City, line: Route, station: RouteStation? = null): VehicleResults = cached("vehicles", city, line, station) {
        val anchor = station ?: line.stations.lastOrNull() ?: throw BusException(BusException.Kind.ARGUMENT, "这条线路没有站点资料")
        val order = anchor.order ?: throw BusException(BusException.Kind.ARGUMENT, "上游没有返回可查询车辆的站点序号")
        argument(order > 0, "上游没有返回可查询车辆的站点序号")
        val records: List<Vehicle>
        if (city.isXiamen) {
            argument(line.id.isNotEmpty() && !anchor.id.isNullOrEmpty(), "厦门车辆查询需要线路和站点编号")
            val raw = xm("busList", "busLineId" to line.id, "direction" to line.direction, "stationSeq" to order, "stationId" to anchor.id, "stationName" to anchor.name)
            records = raw.items().map { parseXiamenVehicle(it, line.stations) }
            VehicleResults(sortVehicles(records, order), if (raw is JsonArray)
                "车辆位置来自上游；仅提供剩余站数，不估算分钟到站时间。"
                else "上游车辆位置资料格式无效，位置暂不可用。")
        } else {
            val raw = realtime(city, line.name, line.direction, order)
            records = raw.list("list").map {
                // index + 1 和状态码含义仍待同方向 CMD 103 / 104 真实响应交叉验证。
                Vehicle(it.s("busNumber"), it.i("index")?.plus(1), when(it.s("statusType")) { "0" -> "at-stop"; "2" -> "between"; else -> "unknown" },
                    currentStation = it.field("stationName").text(), lat = it.d("bus_lat"), lng = it.d("bus_lng"), statusType = it.field("statusType").text(),
                    segmentDistance = it.d("busToStationNiheDistance"), dataTime = it.d("_recTime")?.let { time -> Instant.ofEpochMilli(time.toLong()).toString() })
            }
            VehicleResults(sortVehicles(records, order), when { raw.i("hasReal") == 0 -> "这条线路未开通实时数据查询。"; raw.i("runState") == 1 -> "这条线路已停运。"; else -> "到站站数、时间及距离由掌上公交提供，请提前候车。" },
                publicArrivals(raw), raw.i("hasReal"), raw.i("runState"), raw.field("planTime").text(), raw.field("speedlist"))
        }
    }
    private suspend fun realtime(city: City, name: String, direction: String, order: Int) = pub(city, "104", "LINENAME" to name, "DIRECTION" to direction, "STATIONORDER" to order)
    suspend fun arrivals(city: City, lineName: String, direction: String, stationOrder: Int): List<Arrival> = cached("arrivals", city, lineName, direction, stationOrder) { publicArrivals(realtime(city, lineName, direction, stationOrder)) }
    suspend fun timetable(city: City, line: Route, direction: String = line.direction): Timetable = cached("timetable", city, line, direction) {
        direction(direction)
        if (city.isXiamen) {
            val raw = xm("timeTable", "busLineId" to line.id, "busLineName" to line.name)
            Timetable(raw.list(if (direction == "1") "upPlan" else "downPlan").map { it.s("time") }, raw.s("tips"))
        } else {
            val raw = pub(city, "207", "ROUTEID" to line.id, "DIRECTION" to direction)
            Timetable(raw.list("list").flatMap { it.list("times") }.filter { it.i("isDelete") != 1 }.map { it.s("time") },
                if (raw.s("special") == "1" && city.name != "泉州市") "计划发车时间可能临时调整，请提前候车。" else "时刻表根据历史数据计算，仅供参考，请提前候车。",
                raw.field("list"), raw.field("nextTime").text(), raw.field("special").text())
        }
    }
    // 只识别厦门覆盖区；其余城市交由上次选择或默认值兜底，不猜测全国城市。
    suspend fun resolveCity(cities: Cities, lat: Double, lng: Double, previousKey: String? = null): CityResolution {
        val fallback = cities.defaultCity(previousKey)
        val xiamen = cities.all().first { it.isXiamen }
        return try {
            val nearest = nearby(xiamen, lat, lng).firstOrNull()?.distance
            if (nearest != null && nearest <= 30_000) CityResolution(xiamen, true, "已按定位城市查询") else CityResolution(fallback, false)
        } catch (error: Exception) { CityResolution(fallback, false, error = failure(error, "定位城市查询失败").message) }
    }
}
/**
 * 掌上公交（非厦门）路线车辆列表用「距离」排序：那里的 [Arrival.distance] 是线路级到站距离，
 * 与站点的「还剩几站」不是同一语义，线路页 `data.arrivals.firstOrNull()` 依赖它，
 * 因此保持不变。站点页 / 首页「从近到远」用 `ArrivalOrder.nearArrivalComparator`。
 */
internal val arrivalComparator = compareBy<Arrival> { it.waiting }.thenBy { it.distance == null }.thenBy { it.distance ?: Double.POSITIVE_INFINITY }.thenBy { it.remainingStations ?: Int.MAX_VALUE }
internal fun publicArrivals(raw: JsonElement): List<Arrival> = raw.list("routeOnStationRTimeInfoList").map {
    val waiting = it.s("busNumber").isEmpty() || (it.i("busToStationCount") ?: -1) < 0
    Arrival(if (waiting) null else it.i("busToStationCount"), if (waiting) null else it.d("busToStationDistance"), waiting,
        if (waiting) it.field("planTime").text() else null, it.s("toStationName"), it.field("busNumber").text(), it.s("busToStationTips"), it.s("busToStationTimeTips"), if (waiting) "" else it.s("busToStationDistanceTips"))
}.sortedWith(arrivalComparator)
/**
 * 进场车辆从近到远：离参照站还剩几站就是排序键，正在进站的那辆排第一。
 * 已经驶过的车不参与「谁先到」的比较，统一压到后面，否则会顶掉真正要进站的车。
 * 站差排序也用当前站锚点，避免同车停走切换导致绘图 order 前移/回退时跳动。
 */
internal fun sortVehicles(records: List<Vehicle>, stationOrder: Int): List<Vehicle> =
    records.sortedWith(
        compareBy<Vehicle> {
            val order = it.currentStationOrder
            when {
                order == null || it.positionState == "unknown" -> Int.MAX_VALUE
                order <= stationOrder -> stationOrder - order
                else -> 10_000 + (order - stationOrder)
            }
        }
            .thenBy { it.positionState != "at-stop" }
            .thenBy { it.segmentDistance?.takeIf { distance -> distance >= 0 } ?: Double.POSITIVE_INFINITY }
            .thenBy { it.plate }
    )
/**
 * stationOrder / reportedOrder 为 1 基「车当前站段起点」，nextStationName 是下一站。
 * order 仅为绘图锚点：speed > 0 时右移到前方站，供 UI 左移半格后画在两站之间；
 * speed == 0 时按本站停靠，锚点就是 reportedOrder。站差另用 currentStationOrder。
 * inOrOut 两值均有正速度/零速，motionLessTime 也与正速度共存，均不作停站依据。
 *
 * 只有速度、原始站序及同方向站表校验通过才定位；缺失速度/站序、越界或冲突均未知。
 * 缺失下一站名沿用唯一相邻站补全；环线仅在首末同名且明确报首站为下一站时回绕。
 * 未知车辆清空绘图用 order，保留 reportedOrder / inOrOut / 下一站，避免旧 UI 将未知画成途中。
 * 不累积轮询状态、不设未经验证的静止时长阈值，也不把零值距离/时间拿来算 ETA。
 */
internal fun parseXiamenVehicle(raw: JsonElement, stations: List<RouteStation>): Vehicle {
    val reported = raw.i("stationOrder")
    val nextName = raw.field("nextStationName").text()?.trim()?.takeIf { it.isNotEmpty() }
    val vehicle = Vehicle(
        plate = raw.s("plateNo"), order = null, positionState = "unknown",
        reportedOrder = reported, inOrOut = raw.field("inOrOut").text(), nextStation = nextName,
        dataTime = raw.field("dataTime").text(), lat = raw.d("lat"), lng = raw.d("lng")
    )
    if (raw.field("nextStationName") !is JsonPrimitive) return vehicle
    // 原始站序缺失/非法时不从下一站名字倒推，保留 unknown。
    if (reported == null || reported <= 0 || reported == Int.MAX_VALUE) return vehicle
    val current = stations.singleOrNull { it.order == reported } ?: return vehicle
    if (current.name.isBlank()) return vehicle
    val speed = raw.d("speed")?.takeIf { it >= 0.0 } ?: return vehicle
    val next = if (reported == stations.mapNotNull { it.order }.maxOrNull()) {
        resolveXiamenLoopStart(reported, nextName, stations) ?: return vehicle
    } else {
        resolveVehicleStation(
            vehicle.copy(order = reported + 1, positionState = "between"), stations
        ) ?: return vehicle
    }
    if (stations.count { it.order == next.order } != 1) return vehicle
    val withNext = vehicle.copy(nextStation = nextName ?: next.name)
    return if (speed > 0.0)
        withNext.copy(order = next.order, positionState = "between")
    // 站名来自已唯一校验的本站；保留 nextStation 的下一站角色，避免停靠文字误用下一站名。
    else withNext.copy(order = reported, positionState = "at-stop", currentStation = current.name)
}

/** 只有唯一首末站同名、且上游明确报首站为下一站时才回绕；解析和驶离判断共用此证据。 */
internal fun resolveXiamenLoopStart(
    reportedOrder: Int, nextStationName: String?, stations: List<RouteStation>
): RouteStation? {
    if (reportedOrder <= 1 || reportedOrder != stations.mapNotNull { it.order }.maxOrNull()) return null
    val last = stations.singleOrNull { it.order == reportedOrder } ?: return null
    val first = stations.singleOrNull { it.order == 1 } ?: return null
    val name = first.name.trim().takeIf { it.isNotEmpty() } ?: return null
    if (last.name.trim() != name || nextStationName?.trim() != name ||
        stations.count { it.name.trim() == name } != 2) return null
    return first
}
/**
 * 将已经规范化的车辆站序映射到调用方提供的同线路、同方向站表。
 * at-stop 对应停靠站，between 对应前方站；这不是上游站序换算函数。
 *
 * 序号优先，缺失时才允许同角色唯一站名兜底；已提供的无效站序不得被名称覆盖。
 * 停靠站名校验本站，下一站名校验后继；途中下一站名校验前方站，当前站名校验前驱。
 * 首末边界仅在原始站序、站表和下一站名共同证实回绕时允许相邻关系跨越末站。
 * 同时提供的序号和名称冲突、重复站序、重名歧义或未知状态均返回 null。
 * 厦门解析已接入，返回的 order 仅供车标使用，站差用 currentStationOrder；非厦门仍未校准。
 */
internal fun resolveVehicleStation(vehicle: Vehicle, stations: List<RouteStation>): RouteStation? {
    val name = when (vehicle.positionState) {
        "at-stop" -> vehicle.currentStation
        "between" -> vehicle.nextStation
        else -> return null
    }?.trim()?.takeIf { it.isNotEmpty() }
    val candidates = stations.filter { (it.order ?: 0) > 0 && it.name.isNotBlank() }
    val stop = if (vehicle.order != null) {
        candidates.singleOrNull { it.order == vehicle.order } ?: return null
    } else {
        if (name == null) return null
        candidates.singleOrNull { it.name.trim() == name } ?: return null
    }
    if (candidates.count { it.order == stop.order } != 1 || (name != null && stop.name.trim() != name)) return null
    val otherName = (if (vehicle.positionState == "at-stop") vehicle.nextStation else vehicle.currentStation)
        ?.trim()?.takeIf { it.isNotEmpty() } ?: return stop
    val adjacentOrder = stop.order!!.toLong() + if (vehicle.positionState == "at-stop") 1 else -1
    val adjacent = candidates.singleOrNull { it.order?.toLong() == adjacentOrder } ?: run {
        val reported = vehicle.reportedOrder ?: return null
        val first = resolveXiamenLoopStart(reported, vehicle.nextStation, stations) ?: return null
        when {
            vehicle.positionState == "at-stop" && stop.order == reported -> first
            vehicle.positionState == "between" && stop.order == first.order ->
                stations.single { it.order == reported }
            else -> return null
        }
    }
    return stop.takeIf { adjacent.name.trim() == otherName }
}
private fun requireData(raw: JsonElement, message: String) { if (raw !is JsonObject || raw.isEmpty()) throw BusException(BusException.Kind.UPSTREAM, message) }
private fun stationArray(raw: JsonElement): List<JsonElement> {
    if (raw !is JsonArray || raw.any { it !is JsonObject || it.isEmpty() })
        throw BusException(BusException.Kind.UPSTREAM, "上游返回的站点资料格式无效")
    return raw.toList()
}
private fun stationLineArray(raw: JsonElement, directionKey: String): List<JsonElement> = stationArray(raw).also { lines ->
    if (lines.any { it.s("lineName").isBlank() || it.s(directionKey) !in setOf("1", "2") })
        throw BusException(BusException.Kind.UPSTREAM, "上游返回的站点线路资料格式无效")
}
private fun direction(value: String) = argument(value in setOf("1", "2"), "线路方向需要为 1 或 2")
private fun coordinates(lat: Double, lng: Double) = argument(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0, "请输入有效经纬度")
