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

/** 接口没有车辆 ID；单条观测和跨参考站的近似关联都不是已确认的物理车辆身份。 */
enum class MetroTrainIdentityConfidence { SINGLE_OBSERVATION, APPROXIMATE_CROSS_REFERENCE_MATCH }

/** 站点/半区间位置及标记宽度造成的潜在呈现碰撞，不据此删除任何记录。 */
enum class MetroPositionOverlapKind {
    NONE,
    /** 同一参考站给出多条记录；应分别展示，不能因靠近或同坐标合并。 */
    SAME_REFERENCE_OBSERVATIONS,
    /** 跨参考站记录未获充分身份关联证据，可能重复，也可能是相邻列车。 */
    UNRESOLVED_CROSS_REFERENCE
}

/** UI 按 stackIndex 错层，ordinal 是全方向观测班序，arrivalRowOrder 仅关联本站原始 ETA 行。 */
data class MetroPositionPresentation(
    val train: MetroTrain,
    val stationPosition: Double,
    val ordinal: Int,
    val arrivalRowOrder: Int?,
    /** 方向站序和语义坐标组成的呈现群键，不是车辆 ID。未知位置不成群。 */
    val clusterId: String?,
    val stackIndex: Int,
    /** 贪心错层所需的 lane 数，远端记录可以复用同一 lane。 */
    val stackSize: Int,
    val clusterSize: Int,
    val identityConfidence: MetroTrainIdentityConfidence,
    val overlapKind: MetroPositionOverlapKind
)

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

    /**
     * 全方向 positions 的第 1..N 班，与所选参考站和本站 ETA 行无关。
     * “先到”采用可证实的行驶坐标降序：越靠终点越先；同坐标仅在同参考站/同时间域比较 ETA，
     * 跨参考站以稳定字段打破平局，绝不直接比较不同目的参考站的秒数。
     * 这不是全线真实 ETA 或车辆 ID，不能据相邻批次班序变化推断物理超车。未知位置排最后。
     * 完全相同的重复值无法唯一回查时返回 null；呈现层可直接使用 positionPresentations 的 ordinal。
     */
    fun arrivalOrderOf(positionTrain: MetroTrain): Int? {
        val ordered = positions.sortedWith(positionOrder())
        val exact = ordered.withIndex().filter { it.value === positionTrain }
        val match = if (exact.isNotEmpty()) exact.singleOrNull()
            else ordered.withIndex().filter { it.value == positionTrain }.singleOrNull()
        return match?.index?.plus(1)
    }

    /** 位置车能唯一关联本站某条原始到站行时才有本站行序；补充位置不产生本站 ETA。 */
    fun arrivalRowOrderOf(positionTrain: MetroTrain): Int? {
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

    /**
     * 所有真实位置记录均保留并全局编号；相邻坐标差小于阈值的记录连通成潜在碰撞群。
     * 默认一站纳入半格碰撞；UI 可按实际标记/标签宽度除以站距传入阈值。
     * 贪心复用与该 lane 前一条相隔至少阈值的 lane，0/.5/1 在阈值 1 时使用 0/1/0。
     */
    fun positionPresentations(collisionDistanceInStations: Double = 1.0): List<MetroPositionPresentation> {
        require(collisionDistanceInStations.isFinite() && collisionDistanceInStations > 0.0)
        val ordered = positions.sortedWith(positionOrder())
        val groups = mutableListOf<MutableList<IndexedValue<MetroTrain>>>()
        ordered.withIndex().filter { positionOf(it.value) >= 0.0 }.forEach { item ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (previous == null || positionOf(previous.value) - positionOf(item.value) >= collisionDistanceInStations) {
                groups.add(mutableListOf(item))
            } else groups.last().add(item)
        }
        data class Stack(val clusterId: String, val index: Int, val lanes: Int, val size: Int, val kind: MetroPositionOverlapKind)
        val stacks = mutableMapOf<Int, Stack>()
        groups.forEach { group ->
            val laneEnds = mutableListOf<Double>()
            val lanes = group.map { item ->
                val position = positionOf(item.value)
                val available = laneEnds.indexOfFirst { previous -> previous - position >= collisionDistanceInStations }
                val lane = if (available >= 0) available else laneEnds.size.also { laneEnds.add(position) }
                laneEnds[lane] = position
                lane
            }
            val references = group.flatMap { (_, member) ->
                listOf(member.source?.referenceStationCode) + member.matchedObservations.map { it.source.referenceStationCode }
            }.filterNotNull().filter(String::isNotBlank).distinct()
            val kind = when {
                group.size <= 1 -> MetroPositionOverlapKind.NONE
                references.size == 1 && group.all { !it.value.source?.referenceStationCode.isNullOrBlank() } ->
                    MetroPositionOverlapKind.SAME_REFERENCE_OBSERVATIONS
                else -> MetroPositionOverlapKind.UNRESOLVED_CROSS_REFERENCE
            }
            val clusterId = stationCodes.joinToString("/") + "@" + positionOf(group.first().value) + ":" + positionOf(group.last().value)
            group.forEachIndexed { index, item ->
                stacks[item.index] = Stack(clusterId, lanes[index], laneEnds.size, group.size, kind)
            }
        }
        return ordered.mapIndexed { index, train ->
            val position = positionOf(train)
            val stack = stacks[index]
            MetroPositionPresentation(
                train = train, stationPosition = position, ordinal = index + 1,
                arrivalRowOrder = arrivalRowOrderOf(train),
                clusterId = stack?.clusterId, stackIndex = stack?.index ?: 0,
                stackSize = stack?.lanes ?: 1, clusterSize = stack?.size ?: 1,
                identityConfidence = if (train.matchedObservations.size > 1)
                    MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH
                    else MetroTrainIdentityConfidence.SINGLE_OBSERVATION,
                overlapKind = stack?.kind ?: MetroPositionOverlapKind.NONE
            )
        }
    }
}

/** 只在同参考站、同种时钟下比较预计到站时刻；缺采样时间时同源退回原秒数。 */
private fun MetroTrain.arrivalTimeDomain(): Int = when {
    source?.serverEpochMillis != null -> 0
    source?.receivedAtElapsedMillis != null -> 1
    else -> 2
}

private fun MetroTrain.arrivalTimeKey(): Double = arriveSeconds.toDouble() + when (arrivalTimeDomain()) {
    0 -> source!!.serverEpochMillis!!.toDouble() / 1_000.0
    1 -> source!!.receivedAtElapsedMillis!!.toDouble() / 1_000.0
    else -> 0.0
}

/** 固定字段用于不可分辨的同位观测排序；不使用输入下标、本站 rows 或所选参考站。 */
internal fun MetroDirection.positionOrder(): Comparator<MetroTrain> =
    compareByDescending<MetroTrain> { positionOf(it) }
        .thenBy { it.source?.referenceStationCode.orEmpty() }
        .thenBy { it.arrivalTimeDomain() }
        .thenBy { it.arrivalTimeKey() }
        .thenBy { it.nextStationCode }
        .thenBy { it.arriveSeconds }
        .thenBy { it.arrivingFlag }
        .thenBy { it.scaleKnown }
        .thenBy { it.scale.toBits() }
        .thenBy { it.source?.serverEpochMillis }
        .thenBy { it.source?.receivedAtElapsedMillis }
        .thenBy { it.source?.receivedAtEpochMillis }
        .thenBy { it.specialPosition }
        .thenBy { it.specialPositionDesc }
        .thenBy { it.nextStationName }
        .thenBy { train -> train.matchedObservations.map { it.toString() }.sorted().joinToString("\u0000") }

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
