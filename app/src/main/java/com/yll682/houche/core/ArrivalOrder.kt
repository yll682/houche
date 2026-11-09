package com.yll682.houche.core

/**
 * 「从近到远」的统一排序规则（站点页「经过线路」、首页「附近站点」共用）。
 *
 * 主键是「到该站还剩几站」（[Arrival.remainingStations] 小的在前），不是距离公里数：
 * 用户看的是「还有几站到」，距离只用来在同站数内区分。
 *
 * 没有站数的行一律排在有站数的行之后，内部再分两档：
 * - 第 1 档 等待发车 / 计划发车（上游明确说没有在途车辆，但有下一班计划时间）；
 * - 第 2 档 信息不全（既没有站数也没有发车时间，例如站点无实时数据）。
 * 依据：有明确下一班时间的排班车比「什么都不知道」的行更 actionable。
 *
 * 同站数（同档）内的次级排序：距离（有距离的在前，null 垫底）→ 下一班发车时间 →
 * 线路号自然序 → 站名 → 方向。全部键都能从行数据算出，`sortedWith` 又是稳定排序，
 * 因此同一份数据每次渲染顺序一致。
 */
internal data class NearKey(
    val rank: Int, val stations: Int, val distanceMissing: Boolean, val distance: Double,
    val departure: String, val name: String, val direction: String
) : Comparable<NearKey> {
    override fun compareTo(other: NearKey): Int {
        if (rank != other.rank) return rank - other.rank
        if (stations != other.stations) return stations - other.stations
        if (distanceMissing != other.distanceMissing) return if (distanceMissing) 1 else -1
        if (distance != other.distance) return distance.compareTo(other.distance)
        val byDeparture = naturalLineOrder.compare(departure, other.departure)
        if (byDeparture != 0) return byDeparture
        val byName = naturalLineOrder.compare(name, other.name)
        if (byName != 0) return byName
        return direction.compareTo(other.direction)
    }
}

/** 有站数（在途车辆）。 */
internal const val NEAR_RANK_STATIONS = 0
/** 等待发车 / 计划发车：没有在途车辆，但有下一班时间。 */
internal const val NEAR_RANK_WAITING = 1
/** 信息不全：既没有站数也没有发车时间。 */
internal const val NEAR_RANK_UNKNOWN = 2
/** 整条线路没有拿到任何实时行（站点页 / 首页里没有到站信息的线路）。 */
internal const val NEAR_RANK_NO_ARRIVAL = 3

/** 空串当作「最大」，保证没有发车时间的行排在同档的末尾而不是最前。 */
private const val BLANK_TAIL = "\uFFFF"

internal fun rankOf(arrival: Arrival): Int = when {
    arrival.remainingStations != null -> NEAR_RANK_STATIONS
    arrival.waiting -> NEAR_RANK_WAITING
    else -> NEAR_RANK_UNKNOWN
}

internal fun arrivalKey(arrival: Arrival): NearKey = NearKey(
    rank = rankOf(arrival),
    stations = arrival.remainingStations ?: Int.MAX_VALUE,
    distanceMissing = arrival.distance == null,
    distance = arrival.distance ?: Double.POSITIVE_INFINITY,
    departure = arrival.nextDeparture?.trim().orEmpty().ifEmpty { BLANK_TAIL },
    name = arrival.name,
    direction = arrival.direction
)

internal val nearArrivalComparator: Comparator<Arrival> = compareBy { arrivalKey(it) }

/** 到站记录按「从近到远」排序：站点页和首页拿到的聚合结果都用这个顺序。 */
internal fun orderArrivalsByNear(arrivals: List<Arrival>): List<Arrival> = arrivals.sortedWith(nearArrivalComparator)

/** 同一线路可能有多辆车，取最近的那班代表整条线路的名次。 */
internal fun lineKey(summary: LineSummary, arrivals: List<Arrival>): NearKey {
    val best = arrivals.filter { it.name == summary.name && it.direction == summary.direction }
        .minWithOrNull(nearArrivalComparator)
        ?: return NearKey(
            rank = NEAR_RANK_NO_ARRIVAL, stations = Int.MAX_VALUE, distanceMissing = true,
            distance = Double.POSITIVE_INFINITY, departure = BLANK_TAIL,
            name = summary.name, direction = summary.direction
        )
    return arrivalKey(best).copy(name = summary.name, direction = summary.direction)
}

/**
 * 线路列表按「从近到远」排序：有到站信息的按最近一班的名次排，没拿到实时数据的
 * 按线路号自然序垫底（原注释承诺的行为，这里真正实现成确定性顺序）。
 */
internal fun orderLinesByNear(lines: List<LineSummary>, arrivals: List<Arrival>): List<LineSummary> =
    lines.map { it to lineKey(it, arrivals) }.sortedWith(compareBy { it.second }).map { it.first }

/** 线路号自然序：「9路」<「10路」，「807路」<「854路」，数字段按数值、其余按字符。 */
internal val naturalLineOrder: Comparator<String> = Comparator(::compareNatural)

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var endA = i
            var endB = j
            while (endA < a.length && a[endA].isDigit()) endA++
            while (endB < b.length && b[endB].isDigit()) endB++
            val digitsA = a.substring(i, endA).trimStart('0')
            val digitsB = b.substring(j, endB).trimStart('0')
            if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
            val byValue = digitsA.compareTo(digitsB)
            if (byValue != 0) return byValue
            i = endA
            j = endB
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
