package com.yll682.houche.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant

class BusRepository(private val xmbus: XmbusClient, private val zsgj: ZsgjClient = ZsgjClient(), private val nanoTime: () -> Long = System::nanoTime) {
    private data class Entry(val created: Long, val value: Any)
    private val cache = LinkedHashMap<List<Any?>, Entry>(16, 0.75f, true)
    fun clearCache() = synchronized(cache) { cache.clear() }
    private suspend fun <T : Any> cached(vararg key: Any?, query: suspend () -> T): T = withContext(Dispatchers.IO) {
        val cacheKey = key.toList()
        synchronized(cache) {
            cache[cacheKey]?.takeIf { nanoTime() - it.created < 15_000_000_000L }?.let {
                @Suppress("UNCHECKED_CAST")
                return@withContext it.value as T
            }
            cache.remove(cacheKey)
        }
        try {
            query().also { result -> synchronized(cache) {
                cache[cacheKey] = Entry(nanoTime(), result)
                while (cache.size > 512) cache.remove(cache.keys.first())
            } }
        } catch (error: Exception) { throw failure(error, "公交资料解析失败，请稍后重试") }
    }
    private suspend fun xm(path: String, vararg params: Pair<String, Any?>): JsonElement = xmbus.query(path, mapOf(*params)).field("data")
    private suspend fun pub(city: City, command: String, vararg params: Pair<String, Any?>): JsonElement = zsgj.publicQuery(command, mapOf("CITYNAME" to city.name, *params))
    private suspend fun publicLines(city: City, station: Station, lat: Double? = station.lat, lng: Double? = station.lng): List<JsonElement> =
        pub(city, "115", "STATIONNAME" to station.name, "MYLAT" to lat, "MYLNG" to lng, "ALL" to 1).list("data")
    private fun line(item: JsonElement, native: Boolean): LineSummary {
        val dir = item.s(if (native) "upperOrDownInLine" else "upperOrDown")
        return LineSummary(item.s("lineName"), dir, item.s(if (native) if (dir == "1") "suStation" else "sdStation" else "from"),
            item.s(if (native) if (dir == "1") "euStation" else "edStation" else "to"), item.i(if (native) "stationOrderInLine" else "stationOrder"))
    }
    private fun station(item: JsonElement, native: Boolean): Station = if (native) Station(
        item.s("name"), item.field("stationNo").text(), item.d("gcjLat"), item.d("gcjLng"), item.d("distance"), "GCJ02",
        item.list("relativeStationNoList").mapNotNull { it.text() }, item.list("lineList").map { line(it, true) }
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

    suspend fun stationDetail(city: City, name: String, number: String? = null, lat: Double? = null, lng: Double? = null): Station = cached("stationDetail", city, name, number, lat, lng) {
        if (city.isXiamen) {
            val raw = if (!number.isNullOrEmpty()) xm("exactStationDetail", "stationNo" to number, "reverseStationNo" to "", "amapStationId" to "")
                else xm("stationDetail", "busStationName" to name)
            requireData(raw, "上游没有返回这个站点的资料")
            station(raw, true).let { if (!number.isNullOrEmpty()) it.copy(number = number) else it }
        } else {
            val platforms = pub(city, "209", "STATIONNAME" to name, "MYLAT" to lat, "MYLNG" to lng, "LAT" to lat, "LNG" to lng).list("info").map { station(it, false) }
            if (platforms.isEmpty()) throw BusException(BusException.Kind.UPSTREAM, "上游没有返回这个站点的资料")
            val selected = if (lat != null && lng != null) platforms.minBy { p ->
                if (p.lat == null || p.lng == null) Double.POSITIVE_INFINITY else (p.lat-lat)*(p.lat-lat)+(p.lng-lng)*(p.lng-lng)
            } else platforms.first()
            selected.copy(platforms = platforms, lines = publicLines(city, selected).map { line(it, false) })
        }
    }
    suspend fun stationBuses(city: City, station: Station, lat: Double? = station.lat, lng: Double? = station.lng): List<Arrival> = cached("stationBuses", city, station, lat, lng) {
        argument(!city.isXiamen || !station.number.isNullOrEmpty(), "这个站点尚未取得实时车辆接口")
        if (!city.isXiamen) publicLines(city, station, lat, lng).map {
            Arrival(name = it.s("lineName"), direction = it.s("upperOrDown"), to = it.s("to"), statusText = it.s("neartext"), timeText = it.s("neardis"), stationOrder = it.i("stationOrder"))
        }.sortedWith(arrivalComparator)
        else {
            val raw = xm("stationLineBus", "stationNo" to station.number, "stationName" to station.name, "stationLon" to lng, "stationLat" to lat, "lineNum" to "20").items()
            val times = publicLines(city, station, lat, lng).associate { (it.s("lineName") to it.s("upperOrDown")) to it.s("neardis") }
            raw.map {
                val next = it.field("nextTime").text()?.trim()
                val waiting = it.s("carNo").isEmpty() || !next.isNullOrEmpty()
                Arrival(name = it.s("lineName"), direction = it.s("upDown"), to = it.s("endStationName"), plate = it.field("carNo").text(), waiting = waiting,
                    remainingStations = if (waiting) null else it.i("remainNum"), distance = if (waiting) null else it.d("remainDist"), nextDeparture = next,
                    timeText = times[it.s("lineName") to it.s("upDown")].orEmpty())
            }.sortedWith(arrivalComparator)
        }
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
            records = xm("busList", "busLineId" to line.id, "direction" to line.direction, "stationSeq" to order, "stationId" to anchor.id, "stationName" to anchor.name).items().map {
                val reported = it.i("stationOrder")
                Vehicle(it.s("plateNo"), reported?.plus(if (it.s("inOrOut") == "1") 1 else 0), when(it.s("inOrOut")) { "0" -> "at-stop"; "1" -> "between"; else -> "unknown" },
                    reported, it.field("inOrOut").text(), it.field("nextStationName").text(), dataTime = it.field("dataTime").text(), lat = it.d("lat"), lng = it.d("lng"))
            }
            VehicleResults(sortVehicles(records, order), "车辆位置来自上游，站点序号依据厦门公交返回值。")
        } else {
            val raw = realtime(city, line.name, line.direction, order)
            records = raw.list("list").map {
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
internal val arrivalComparator = compareBy<Arrival> { it.waiting }.thenBy { it.distance == null }.thenBy { it.distance ?: Double.POSITIVE_INFINITY }.thenBy { it.remainingStations ?: Int.MAX_VALUE }
internal fun publicArrivals(raw: JsonElement): List<Arrival> = raw.list("routeOnStationRTimeInfoList").map {
    val waiting = it.s("busNumber").isEmpty() || (it.i("busToStationCount") ?: -1) < 0
    Arrival(if (waiting) null else it.i("busToStationCount"), if (waiting) null else it.d("busToStationDistance"), waiting,
        if (waiting) it.field("planTime").text() else null, it.s("toStationName"), it.field("busNumber").text(), it.s("busToStationTips"), it.s("busToStationTimeTips"), if (waiting) "" else it.s("busToStationDistanceTips"))
}.sortedWith(arrivalComparator)
/**
 * 进场车辆从近到远：离参照站还剩几站就是排序键，正在进站的那辆排第一。
 * 已经驶过的车不参与「谁先到」的比较，统一压到后面，否则会顶掉真正要进站的车。
 */
internal fun sortVehicles(records: List<Vehicle>, stationOrder: Int): List<Vehicle> =
    records.sortedWith(
        compareBy<Vehicle> {
            val order = it.order
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
private fun requireData(raw: JsonElement, message: String) { if (raw !is JsonObject || raw.isEmpty()) throw BusException(BusException.Kind.UPSTREAM, message) }
private fun direction(value: String) = argument(value in setOf("1", "2"), "线路方向需要为 1 或 2")
private fun coordinates(lat: Double, lng: Double) = argument(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0, "请输入有效经纬度")
