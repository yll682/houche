package com.yll682.houche.core

import kotlin.math.abs

/** 一份真实响应的参考站和时间；服务器时间与本机单调时间不能互相比较。 */
data class MetroObservationSource(
    val referenceStationCode: String,
    val serverEpochMillis: Long? = null,
    val receivedAtElapsedMillis: Long? = null,
    /** 仅用于显示实际采样时刻，不与服务器或单调时钟混算。 */
    val receivedAtEpochMillis: Long? = null
)

/** 被近似配为同车的原始观测；没有稳定车辆 ID，不能把这些关联当作已证实身份。 */
data class MetroTrainObservation(
    val source: MetroObservationSource,
    val arriveSeconds: Int,
    val scale: Double,
    val arrivingFlag: Int?
)

/** 一条真实列车观测；arriveSeconds 只属于 source 的参考站，不是任意当前站的 ETA。 */
data class MetroTrain(
    val arriveSeconds: Int,
    val nextStationCode: String,
    val nextStationName: String,
    /** 服务端数值，缺失或无效时为 0；比例方向尚未验证，官方位置显示未使用此字段。 */
    val scale: Double,
    /** 服务端原值，缺失或无效为 null；官方位置显示：1 = 下一站前一段，2 = 下一站站点。 */
    val arrivingFlag: Int?,
    val specialPosition: Int,
    val specialPositionDesc: String,
    val source: MetroObservationSource? = null,
    /** 缺失或无效的比例不能参与跨参考站的近似识别。 */
    val scaleKnown: Boolean = true,
    /** 多于一项表示无车辆 ID 的近似去重；代表观测自身的原值仍保留在本对象。 */
    val matchedObservations: List<MetroTrainObservation> = emptyList()
) {
    /** 官方把 2 画在下一站站点；未知状态不推断为到站。 */
    val arriving: Boolean get() = arrivingFlag == 2
    /** 官方把 1 画在下一站前一段，不能据此判断是否接近参考站。 */
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
    /** 用户所选参考站的原始到站行；多参考站合并后仍最多三条。 */
    val trains: List<MetroTrain>,
    val stationCodes: List<String>,
    val source: MetroObservationSource? = null,
    /** null 表示单站响应，沿用 trains；copy(trains=...) 不会遗留旧位置列表。 */
    val positionTrains: List<MetroTrain>? = null,
    /** false 表示本方向没有当前参考站的响应，不能把空行判断为本站无车。 */
    val arrivalsAvailable: Boolean = true
) {
    /** 只供位置示意图使用，不能用其中其他参考站的秒数生成本站到站行。 */
    val positions: List<MetroTrain> get() = positionTrains ?: trains

    /** 视口锚定距当前站最近的有效位置；同距选上游，不把全线最下游车当作本站最近车。 */
    fun nearestPositionTo(stationCode: String): Double? {
        val referenceIndex = stationCodes.indexOf(stationCode)
        if (referenceIndex < 0) return null
        return positions.map { positionOf(it) }.filter { it.isFinite() && it >= 0.0 }
            .minWithOrNull(compareBy<Double> { abs(it - referenceIndex) }.thenBy { it })
    }

    /** 位置车能唯一对应本站某条原始到站行时才有班序；补充位置没有本站班序。 */
    fun arrivalOrderOf(positionTrain: MetroTrain): Int? {
        val matches = trains.mapIndexedNotNull { index, row ->
            if (row.nextStationCode != positionTrain.nextStationCode) return@mapIndexedNotNull null
            val representativeMatches = row.source == positionTrain.source &&
                row.arriveSeconds == positionTrain.arriveSeconds && row.scale.toBits() == positionTrain.scale.toBits() &&
                row.arrivingFlag == positionTrain.arrivingFlag
            val originalMatches = positionTrain.matchedObservations.any { observation ->
                row.source == observation.source && row.arriveSeconds == observation.arriveSeconds &&
                    row.scale.toBits() == observation.scale.toBits() && row.arrivingFlag == observation.arrivingFlag
            }
            if (representativeMatches || originalMatches) index + 1 else null
        }
        return matches.singleOrNull()
    }
}

/** 完整有序站码链标识方向；同名终点、数组序号、缺码链都不能代替它。 */
data class MetroDirectionKey(val stationCodes: List<String>)

val MetroDirection.directionKey: MetroDirectionKey?
    get() = stationCodes.takeIf { codes ->
        codes.size >= 2 && codes.all { it.isNotBlank() } && codes.distinct().size == codes.size
    }?.let { MetroDirectionKey(it.toList()) }

/**
 * 列车在该方向站序里的语义位置，整数 j 表示第 j 个站点。
 * 官方 H5 / Android 把 flag=1 放在下一站前一段，flag=2 放在下一站；
 * H5 的段内位置取中点，不消费语义尚未验证的 scale。
 * 已知下一站但 flag 缺失或未知时，保守放在该站前一段中点，避免整批车标静默消失。
 * 兜底不声称列车已经到站，也不按方向尚未验证的 scale 插值。
 * 首站前一段没有可展示站点，夹到首站；只有缺码、未知站返回 -1。
 * 这是站点坐标，不包含渲染层的格子中心偏移。证据见 metro-official-position-evidence.json。
 */
fun MetroDirection.positionOf(train: MetroTrain): Double {
    if (train.nextStationCode.isBlank()) return -1.0
    val index = stationCodes.indexOf(train.nextStationCode)
    if (index < 0) return -1.0
    return when (train.arrivingFlag) {
        1 -> (index - 0.5).coerceAtLeast(0.0)
        2 -> index.toDouble()
        else -> (index - 0.5).coerceAtLeast(0.0)
    }
}

/** 一次实时查询的结果；站码以响应为准，未传站码时服务端会选择首站。 */
data class MetroRealtime(
    val online: Boolean,
    val note: String,
    val lineCode: String = "",
    val stationCode: String = "",
    val stationName: String = "",
    val directions: List<MetroDirection> = emptyList(),
    val source: MetroObservationSource? = null,
    /** 合并所用成功响应，包括有效但没有列车的响应。 */
    val sources: List<MetroObservationSource> = emptyList()
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
