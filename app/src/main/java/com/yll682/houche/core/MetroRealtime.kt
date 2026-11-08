package com.yll682.houche.core

import kotlin.math.abs

/** 一班即将到站的车；arriveSeconds 是接口给的秒数。 */
data class MetroTrain(
    val arriveSeconds: Int,
    val nextStationCode: String,
    val nextStationName: String,
    val scale: Double,
    /** 服务端原值：1 = 接近下一站，2 = 已进站。 */
    val arrivingFlag: Int,
    val specialPosition: Int,
    val specialPositionDesc: String
) {
    /** 车已经停到下一站，才算「到站」。 */
    val arriving: Boolean get() = arrivingFlag >= 2
    /** 还在上一段路上。 */
    val approaching: Boolean get() = arrivingFlag == 1
}

/** 一个行车方向的实时信息；站序就是列车行驶顺序，下标随行驶递增。 */
data class MetroDirection(
    /** 始发站：wayStations 的第一站。 */
    val origin: String,
    /** 终到站：wayStations 的最后一站，也是列车开往的方向。 */
    val destination: String,
    val startTime: String,
    val endTime: String,
    val delayMinutes: Int,
    val trains: List<MetroTrain>,
    val stationCodes: List<String>
)

/**
 * 列车在该方向站序里的位置，单位是「站」。
 * 下一站下标为 j 时，车位于 j-1 与 j 之间，scale 是已经走完的比例。
 */
fun MetroDirection.positionOf(train: MetroTrain): Double {
    val index = stationCodes.indexOf(train.nextStationCode)
    if (index < 0) return -1.0
    return (index - 1 + train.scale.coerceIn(0.0, 1.0)).coerceAtLeast(0.0)
}

/** 一次实时查询的结果；站码以响应为准，未传站码时服务端会选择首站。 */
data class MetroRealtime(
    val online: Boolean,
    val note: String,
    val lineCode: String = "",
    val stationCode: String = "",
    val stationName: String = "",
    val directions: List<MetroDirection> = emptyList()
) {
    /** 两个方向的站序相反，必须在各自站序中取下标差；下一站就是本站时记零站。 */
    fun remainingStops(direction: MetroDirection, train: MetroTrain): Int {
        if (stationCode.isEmpty() || train.nextStationCode.isEmpty()) return -1
        val currentIndex = direction.stationCodes.indexOf(stationCode)
        val nextIndex = direction.stationCodes.indexOf(train.nextStationCode)
        return if (currentIndex < 0 || nextIndex < 0) -1 else abs(currentIndex - nextIndex)
    }
}

/** 某个方向此刻的运营状态；WAITING 还需调用方结合运营中但无待到列车的情况判定。 */
enum class MetroServiceState { RUNNING, WAITING, NOT_STARTED, FINISHED, NO_SERVICE }

/**
 * 接口给的是该方向在本站的当天运营窗口，首末班时刻本身均算运营中。
 * 两端空串表示没有班次；只有一端可解析时只约束该端，均不可解析时按运营中兜底。
 * 按接口约定直接比较当天分钟数，不自行推断跨午夜窗口。
 */
fun metroServiceState(start: String, end: String, nowMinutesOfDay: Int): MetroServiceState {
    if (start.isEmpty() && end.isEmpty()) return MetroServiceState.NO_SERVICE
    val startMinutes = metroTimeMinutes(start)
    val endMinutes = metroTimeMinutes(end)
    return when {
        startMinutes != null && nowMinutesOfDay < startMinutes -> MetroServiceState.NOT_STARTED
        endMinutes != null && nowMinutesOfDay > endMinutes -> MetroServiceState.FINISHED
        else -> MetroServiceState.RUNNING
    }
}

private fun metroTimeMinutes(time: String): Int? {
    if (time.length != 5 || time[2] != ':' || time.filterIndexed { index, _ -> index != 2 }.any { it !in '0'..'9' }) return null
    val hours = time.take(2).toIntOrNull() ?: return null
    val minutes = time.takeLast(2).toIntOrNull() ?: return null
    return if (hours in 0..23 && minutes in 0..59) hours * 60 + minutes else null
}

/** 与官方 H5 一样向上取整；即将到站与异常负秒数仍至少显示一分钟。 */
fun minutesUntil(seconds: Int): Int = ((seconds.toLong() + 59) / 60).coerceAtLeast(1).toInt()
