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
    val arrivingFlag: Int?,
    /** 跨站观测可能跨过区间边界，不能借代表观测的 next 代替原值。 */
    val nextStationCode: String = ""
)

/** 一条真实列车观测；arriveSeconds 只属于 source 的参考站，不是任意当前站的 ETA。 */
data class MetroTrain(
    val arriveSeconds: Int,
    val nextStationCode: String,
    val nextStationName: String,
    /** 到 nextStationCode 的前一区间内、沿本方向行驶的进度；缺失或无效时为 0。 */
    val scale: Double,
    /** 服务端状态原值；2 也可能仍在站前区间，不可覆盖真实 scale 坐标。 */
    val arrivingFlag: Int?,
    val specialPosition: Int,
    val specialPositionDesc: String,
    val source: MetroObservationSource? = null,
    /** 缺失或无效的比例不能参与跨参考站的近似识别。 */
    val scaleKnown: Boolean = true,
    /** 多于一项表示无车辆 ID 的近似去重；代表观测自身的原值仍保留在本对象。 */
    val matchedObservations: List<MetroTrainObservation> = emptyList()
) {
    /** 服务端进站状态，不等于已经停在站台。 */
    val arriving: Boolean get() = arrivingFlag == 2
    /** 官方把 1 画在下一站前一段，不能据此判断是否接近参考站。 */
    val approaching: Boolean get() = arrivingFlag == 1
}

/** 接口没有车辆 ID；单条观测和跨参考站的近似关联都不是已确认的物理车辆身份。 */
enum class MetroTrainIdentityConfidence { SINGLE_OBSERVATION, APPROXIMATE_CROSS_REFERENCE_MATCH }

/** 位置发生冲突的观测来源；保留身份与 ETA，只隐藏缺乏物理间距的车标。 */
enum class MetroPositionOverlapKind {
    NONE,
    /** 同一参考站给出的不同班次，位置字段不足以分别定位。 */
    SAME_REFERENCE_OBSERVATIONS,
    /** 跨参考站记录未获充分身份关联证据，可能重复，也可能是相邻列车。 */
    UNRESOLVED_CROSS_REFERENCE
}

/** 不代表车辆 ID；待发计划不等于已经占据站台的车辆。 */
enum class MetroTrainPositionState { RUNNING, AT_STATION, TERMINAL_PENDING, SCHEDULED, UNKNOWN }

enum class MetroPositionSuppressionReason {
    UNKNOWN_POSITION,
    /** 本站行缺失或关联有歧义，暂不为未关联候选发号，避免冒用本站行号。 */
    UNRESOLVED_IDENTITY_ASSOCIATION,
    /** 同一始发端的后续发车计划，不能全夹到 0 后画成多辆站台车。 */
    SCHEDULED_DEPARTURE,
    /** 可能重复或位置字段互相矛盾；仅抑制车标，不判为同一身份。 */
    UNRESOLVED_CLOSE_POSITION
}

data class MetroPositionSuppression(
    val train: MetroTrain,
    val stationPosition: Double,
    val reason: MetroPositionSuppressionReason,
    val retainedTrain: MetroTrain? = null
)

/** 本站 ETA 对应车优先使用 1..3；其余身份使用余下的唯一编号，车标不再错层堆放。 */
data class MetroPositionPresentation(
    val train: MetroTrain,
    val stationPosition: Double,
    val ordinal: Int,
    val arrivalRowOrder: Int?,
    /** 方向站序和语义坐标组成的呈现群键，不是车辆 ID。未知位置不成群。 */
    val clusterId: String?,
    val stackIndex: Int,
    /** 保留旧 UI 接口；物理约束后的可见车标均为单 lane。 */
    val stackSize: Int,
    val clusterSize: Int,
    val identityConfidence: MetroTrainIdentityConfidence,
    val overlapKind: MetroPositionOverlapKind
)

/**
 * core 口径的站位投影：`ordinal` 是**全线行驶进度序**（越靠该方向终点越靠前），`passed`
 * 只做标记。位置条/ETA 的「第 N 班」用的是 UI 侧的 `arrivalOrdinals`（到本站先后），
 * 两者名字不同、语义不同，不要混用。
 */
data class MetroStationPosition(
    val presentation: MetroPositionPresentation,
    val ordinal: Int?,
    val passed: Boolean
) {
    val stationPosition: Double get() = presentation.stationPosition
    val markerKey: Int get() = presentation.ordinal
}

data class MetroStationPositionOrdinals(
    val referenceIndex: Int,
    val trains: List<MetroStationPosition>,
    val rowOrdinals: Map<Int, Int>
) {
    val maxOrdinal: Int get() = trains.mapNotNull { it.ordinal }.maxOrNull() ?: 0
    val passedCount: Int get() = trains.count { it.passed }
    fun ordinalOfRow(row: Int): Int? = rowOrdinals[row]
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
    val arrivalsAvailable: Boolean = true,
    /** 真实 wayStations 的区间运行时间；缺少时仍执行保守的站距约束。 */
    val runningTimeProfile: MetroRunningTimeProfile? = null
) {
    /** 全部近似列车身份，包括尚未发出的计划；不能把其数量当作可见车标数。 */
    val allIdentifiedTrains: List<MetroTrain> get() = positionTrains ?: trains

    /** 仅可见物理位置；被抑制的身份仍在 allIdentifiedTrains，本站原始 ETA 行也完整保留。 */
    val positions: List<MetroTrain> get() = visiblePositions(1.0).first.map { it.value }

    /** 视口锚定距当前站最近的有效位置；同距选上游，不把全线最下游车当作本站最近车。 */
    fun nearestPositionTo(stationCode: String): Double? {
        val referenceIndex = stationCodes.indexOf(stationCode)
        if (referenceIndex < 0) return null
        return positions.map { positionOf(it) }.filter { it.isFinite() && it >= 0.0 }
            .minWithOrNull(compareBy<Double> { abs(it - referenceIndex) }.thenBy { it })
    }

    /**
     * 本站原始 ETA 第 k 行锚定编号 k，其余全线身份按行驶坐标降序使用其余编号。
     * 已过本站的车不会挤占 1..3。它是当前视图的关联编号，不是车辆 ID 或全线到达顺序。
     * 被隐藏的待发身份也保留编号；因此可见编号可以不连续，但不会超过身份总数。
     */
    fun arrivalOrderOf(positionTrain: MetroTrain): Int? {
        val ordered = numberedIdentities()
        val exact = ordered.filter { it.value === positionTrain }
        val match = if (exact.isNotEmpty()) exact.singleOrNull()
            else ordered.filter { it.value == positionTrain }.singleOrNull()
        return match?.index?.plus(1)
    }

    /** 位置车能唯一关联本站某条原始到站行时才有本站行序；补充位置不产生本站 ETA。 */
    fun arrivalRowOrderOf(positionTrain: MetroTrain): Int? {
        val matches = trains.mapIndexedNotNull { index, row ->
            val representativeMatches = row === positionTrain || (row.nextStationCode == positionTrain.nextStationCode &&
                row.source == positionTrain.source &&
                row.arriveSeconds == positionTrain.arriveSeconds && row.scale.toBits() == positionTrain.scale.toBits() &&
                row.arrivingFlag == positionTrain.arrivingFlag)
            val originalMatches = positionTrain.matchedObservations.any { observation ->
                row.nextStationCode == observation.nextStationCode.ifBlank { positionTrain.nextStationCode } &&
                    row.source == observation.source && row.arriveSeconds == observation.arriveSeconds &&
                    row.scale.toBits() == observation.scale.toBits() && row.arrivingFlag == observation.arrivingFlag
            }
            if (representativeMatches || originalMatches) index + 1 else null
        }
        return matches.singleOrNull()
    }

    /**
     * 两轮一号线修正模型的全站代表位置最小间隔 1.32 站，本站六参考站子集最小 1.25 站。
     * 取一站作为保守显示约束；这不是适用于所有线路的行车安全定理。
     * 同向任意可见两车必须相隔至少一站；参数只能扩大间距，不能绕过物理下限。
     * 不移动坐标、不把冲突身份合并、不为增加可见数量制造车辆；隐藏原因可单独检查。
     */
    fun positionPresentations(collisionDistanceInStations: Double = 1.0): List<MetroPositionPresentation> {
        require(collisionDistanceInStations.isFinite() && collisionDistanceInStations > 0.0)
        val (visible, suppressed) = visiblePositions(collisionDistanceInStations.coerceAtLeast(1.0))
        return visible.sortedWith { left, right -> positionOrder().compare(left.value, right.value) }.map { item ->
            val train = item.value
            val position = positionOf(train)
            val conflicts = suppressed.filter { it.retainedTrain === train }
            val references = (listOf(train) + conflicts.map { it.train }).mapNotNull { it.source?.referenceStationCode }
                .filter(String::isNotBlank).distinct()
            val kind = when {
                conflicts.isEmpty() -> MetroPositionOverlapKind.NONE
                references.size == 1 -> MetroPositionOverlapKind.SAME_REFERENCE_OBSERVATIONS
                else -> MetroPositionOverlapKind.UNRESOLVED_CROSS_REFERENCE
            }
            MetroPositionPresentation(
                train = train, stationPosition = position, ordinal = item.index + 1,
                arrivalRowOrder = arrivalRowOrderOf(train),
                clusterId = stationCodes.joinToString("/") + "@" + position,
                stackIndex = 0, stackSize = 1, clusterSize = conflicts.size + 1,
                identityConfidence = if (train.matchedObservations.size > 1)
                    MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH
                    else MetroTrainIdentityConfidence.SINGLE_OBSERVATION,
                overlapKind = kind
            )
        }
    }

    fun positionSuppressions(): List<MetroPositionSuppression> = visiblePositions(1.0).second

    /**
     * **全线行驶进度序**口径：ETA 永远是本地第 1/2/3 行，位置记录沿用 `presentation.ordinal`，
     * 已过本站的身份也照样带编号（`passed` 只作标记）。
     *
     * 它**不是**用户拍板的「到本站先后」：那套编号（第 1 班 = 最先到本站那班，已过本站的车
     * 不占号、标「已过」）在 `ui/screens/metro/MetroArrivalOrdinals.kt` 的 `arrivalOrdinals`。
     * 上一轮两个实现同名（成员 + 扩展），扩展被成员静默遮蔽，位置条因此把已过本站的车编成
     * 第 4..N 班；改名后不会再出现同名遮蔽，位置条与 ETA 只调用 `arrivalOrdinals`。
     */
    fun stationOrdinals(
        stationCode: String,
        rowCount: Int = trains.size,
        tolerance: Double = 0.0
    ): MetroStationPositionOrdinals {
        require(tolerance.isFinite() && tolerance >= 0.0)
        val reference = stationCodes.indexOf(stationCode)
        val markers = positionPresentations().map { presentation ->
            MetroStationPosition(presentation, presentation.ordinal,
                reference >= 0 && presentation.stationPosition > reference + tolerance)
        }
        return MetroStationPositionOrdinals(reference, markers,
            (1..rowCount.coerceIn(0, trains.size)).associateWith { it })
    }

    fun positionStateOf(train: MetroTrain): MetroTrainPositionState {
        val position = positionOf(train)
        if (!position.isFinite() || position < 0.0) return MetroTrainPositionState.UNKNOWN
        if (stationCodes.indexOf(train.nextStationCode) == 0 && train.arrivingFlag != 2) {
            return if (positionSuppressions().any { it.train === train && it.reason == MetroPositionSuppressionReason.SCHEDULED_DEPARTURE })
                MetroTrainPositionState.SCHEDULED else MetroTrainPositionState.TERMINAL_PENDING
        }
        return if (position == position.toInt().toDouble() && (!train.scaleKnown || train.scale == 1.0))
            MetroTrainPositionState.AT_STATION else MetroTrainPositionState.RUNNING
    }

    private fun numberedIdentities(): List<IndexedValue<MetroTrain>> {
        val ordered = allIdentifiedTrains.sortedWith(positionOrder())
        val anchored = trains.indices.map { row ->
            ordered.filter { arrivalRowOrderOf(it) == row + 1 }.singleOrNull()
        }
        val linked = anchored.mapIndexedNotNull { index, train ->
            train?.takeIf { index < allIdentifiedTrains.size }?.let { IndexedValue(index, it) }
        }
        // 本站行缺失或歧义时只保留已证实关联的编号。不得退回位置排序，让无关车冒用 1..3。
        if (anchored.any { it == null } || linked.map { it.value }.distinct().size != anchored.size) return linked
        return linked + ordered.filter { candidate -> anchored.none { it === candidate } }
            .mapIndexed { index, train -> IndexedValue(trains.size + index, train) }
    }

    private fun visiblePositions(minimumDistance: Double): Pair<List<IndexedValue<MetroTrain>>, List<MetroPositionSuppression>> {
        val numbered = numberedIdentities()
        val retained = mutableListOf<IndexedValue<MetroTrain>>()
        val suppressed = allIdentifiedTrains.filter { candidate -> numbered.none { it.value === candidate } }
            .map { MetroPositionSuppression(it, positionOf(it), MetroPositionSuppressionReason.UNRESOLVED_IDENTITY_ASSOCIATION) }
            .toMutableList()
        val priority = compareBy<IndexedValue<MetroTrain>> {
            val train = it.value
            when {
                train.arrivingFlag == 2 && (stationCodes.indexOf(train.nextStationCode) == 0 ||
                    !train.scaleKnown || !train.scale.isFinite() || train.scale !in 0.0..1.0 || train.scale == 1.0) -> 0
                stationCodes.indexOf(train.nextStationCode) == 0 -> 3
                arrivalRowOrderOf(train) != null -> 1
                else -> 2
            }
        }.thenBy {
            val train = it.value
            if (stationCodes.indexOf(train.nextStationCode) == 0) terminalDepartureKey(train) else 0.0
        }.thenByDescending { it.value.matchedObservations.map { observation -> observation.source.referenceStationCode }.distinct().size }
            .thenBy { if (it.value.scaleKnown && it.value.scale > 0.0 && it.value.scale < 1.0) 0 else 1 }
            .thenComparator { left, right -> positionOrder().compare(left.value, right.value) }
        numbered.sortedWith(priority).forEach { item ->
            val train = item.value
            val position = positionOf(train)
            if (!position.isFinite() || position < 0.0) {
                suppressed.add(MetroPositionSuppression(train, position, MetroPositionSuppressionReason.UNKNOWN_POSITION))
                return@forEach
            }
            val conflict = retained.firstOrNull { abs(positionOf(it.value) - position) + 1e-9 < minimumDistance }
            if (conflict == null) retained.add(item) else {
                val scheduled = position == 0.0 && stationCodes.indexOf(train.nextStationCode) == 0 && train.arrivingFlag != 2
                suppressed.add(MetroPositionSuppression(train, position,
                    if (scheduled) MetroPositionSuppressionReason.SCHEDULED_DEPARTURE
                    else MetroPositionSuppressionReason.UNRESOLVED_CLOSE_POSITION, conflict.value))
            }
        }
        return retained.sortedWith { left, right -> positionOrder().compare(left.value, right.value) } to suppressed
    }

    private fun terminalDepartureKey(train: MetroTrain): Double {
        val reference = stationCodes.indexOf(train.source?.referenceStationCode)
        val cumulative = runningTimeProfile?.cumulativeSeconds?.getOrNull(reference)
        // 有真实运行时间时归一到始发端；否则只在相同参考站内比较原 ETA。
        return if (cumulative != null) train.arrivalTimeKey() - cumulative
        else if (reference == 0 || train.source == null) train.arrivalTimeKey() else Double.POSITIVE_INFINITY
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
 * 两轮一号线全站数据证实 next 是本方向即将到达的一端，scale 是驶向该端的进度：
 * p = index(next) - 1 + scale，反向同样使用自己的行驶站序，不能按数值站码猜正反向。
 * 例如吕厝本站 ETA118/scale0，60秒后 ETA57/scale.51，不能提前画到本站。
 * flag2 仍可能 scale.83、距本站19秒，故不覆盖连续位置。旧 flag 半格公式已被该证据推翻。
 * 缺有效比例时保留官方站点/半格兜底；首站仅表示始发端待发，多个计划由可见约束处理。
 */
fun MetroDirection.positionOf(train: MetroTrain): Double {
    if (train.nextStationCode.isBlank()) return -1.0
    val index = stationCodes.indexOf(train.nextStationCode)
    if (index < 0) return -1.0
    if (train.scaleKnown && train.scale.isFinite() && train.scale in 0.0..1.0) {
        return (index - 1.0 + train.scale).coerceAtLeast(0.0)
    }
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
