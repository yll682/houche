package com.yll682.houche.core

import kotlin.math.abs

private const val MATCH_CAPTURE_GAP_MILLIS = 8_000L
private const val MATCH_POSITION_TOLERANCE_SECONDS = 150.0
private const val MATCH_ANCHOR_TOLERANCE_SECONDS = 200.0
private const val MATCH_DEGENERATE_ANCHOR_TOLERANCE_SECONDS = 150.0

/**
 * 只合并本次输入的真实响应，不记忆上一轮列车。
 * trains 始终来自 stationCode 自身的响应；其他参考站只补充 positions。
 * 没有车辆 ID：用方向运行秒 profile 对齐「位置运行秒」与「到站秒−参考站累计运行秒」。
 * 所有身份候选均保留于 positionTrains；首站未来计划是否可画车由位置层另行判断。
 * 缺运行资料、共同时间域或完整方向链时保留原车；这里不推算其他参考站到本站的 ETA。
 */
fun mergeMetroRealtime(
    snapshots: List<MetroRealtime>,
    stationCode: String,
    lineCode: String = ""
): MetroRealtime {
    val actualLineCode = lineCode.ifBlank {
        snapshots.firstOrNull { it.stationCode == stationCode }?.lineCode
            ?: snapshots.firstOrNull()?.lineCode.orEmpty()
    }
    val successful = snapshots.filter { it.online && (actualLineCode.isBlank() || it.lineCode == actualLineCode) }
    val selected = successful.filter { it.stationCode == stationCode }
        .maxWithOrNull(compareBy<MetroRealtime> { it.source?.receivedAtElapsedMillis ?: Long.MIN_VALUE }
            .thenBy { it.source?.serverEpochMillis ?: Long.MIN_VALUE })
    val groups = linkedMapOf<Any, MutableList<DirectionObservation>>()
    successful.forEachIndexed { snapshotIndex, snapshot ->
        snapshot.directions.forEachIndexed { directionIndex, direction ->
            val source = direction.source ?: snapshot.source ?: MetroObservationSource(snapshot.stationCode)
            // 缺码方向各自保留，绝不借数组序号、站名或首末站猜一个可跨响应合并的 key。
            val key = direction.directionKey ?: IncompleteDirection(snapshotIndex, directionIndex)
            groups.getOrPut(key) { mutableListOf() }.add(DirectionObservation(direction, source))
        }
    }
    val orderedGroups = groups.values.sortedWith(compareBy<MutableList<DirectionObservation>> { observations ->
        // 以所选站响应的方向序为准；其他网络完成先后和 data 排列不影响此顺序。
        selected?.directions?.indexOfFirst { selectedDirection ->
            val key = selectedDirection.directionKey
            if (key != null) key == observations.first().direction.directionKey
            else observations.any { it.direction === selectedDirection }
        }?.takeIf { it >= 0 } ?: Int.MAX_VALUE
    }.thenBy { it.first().direction.stationCodes.joinToString("\u0000") }
        .thenBy { it.minOf { observation -> observation.source.referenceStationCode } })
    val directions = orderedGroups.map { observations ->
        val current = observations.withIndex()
            .groupBy { indexed -> indexed.value.source.referenceStationCode.takeIf(String::isNotBlank) ?: MissingReference(indexed.index) }
            .values.flatMap { indexed ->
                val sameReference = indexed.map { it.value }
                // 有共同时间域才可判定同参考站新旧；无时间的重复观测宁可保留位置，不删车。
                when {
                    sameReference.all { it.source.receivedAtElapsedMillis != null } -> {
                        val newest = sameReference.maxOf { it.source.receivedAtElapsedMillis!! }
                        sameReference.filter { it.source.receivedAtElapsedMillis == newest }
                    }
                    sameReference.all { it.source.serverEpochMillis != null } -> {
                        val newest = sameReference.maxOf { it.source.serverEpochMillis!! }
                        sameReference.filter { it.source.serverEpochMillis == newest }
                    }
                    else -> sameReference
                }
            }
        val selectedDirection = current.firstOrNull { it.source.referenceStationCode == stationCode }
        val seed = selectedDirection ?: current.sortedWith(observationStableOrder).first()
        val rows = selectedDirection?.direction?.trains.orEmpty().map { train ->
            train.copy(source = train.source ?: selectedDirection!!.source)
        }.filter { it.source?.referenceStationCode == stationCode }.take(3)
        seed.direction.copy(
            trains = rows,
            source = seed.source,
            positionTrains = mergePositions(current),
            arrivalsAvailable = selectedDirection != null
        )
    }
    val sources = successful.flatMap { snapshot ->
        listOfNotNull(snapshot.source) + snapshot.directions.map { direction ->
            direction.source ?: snapshot.source ?: MetroObservationSource(snapshot.stationCode)
        }
    }.distinct().sortedWith(sourceStableOrder)
    val source = selected?.source ?: sources.maxWithOrNull(sourceRecency)
    val arrivalsMissing = directions.isEmpty() || directions.any { !it.arrivalsAvailable }
    val note = when {
        successful.isEmpty() || arrivalsMissing -> "本站到站暂不可用"
        else -> "实时到站已更新"
    }
    return MetroRealtime(
        online = successful.isNotEmpty(), note = note, lineCode = actualLineCode,
        stationCode = stationCode, stationName = selected?.stationName.orEmpty(),
        directions = directions, source = source, sources = sources
    )
}

private data class IncompleteDirection(val snapshotIndex: Int, val directionIndex: Int)
private data class MissingReference(val observationIndex: Int)
private data class DirectionObservation(val direction: MetroDirection, val source: MetroObservationSource)
private data class TrainObservation(
    val train: MetroTrain,
    val source: MetroObservationSource,
    val referenceIndex: Int,
    val profile: MetroRunningTimeProfile?,
    val consistentSource: Boolean
)

private val sourceRecency = compareBy<MetroObservationSource> { it.receivedAtElapsedMillis ?: Long.MIN_VALUE }
    .thenBy { it.serverEpochMillis ?: Long.MIN_VALUE }
    .thenBy { it.receivedAtEpochMillis ?: Long.MIN_VALUE }
private val sourceStableOrder = compareBy<MetroObservationSource> { it.referenceStationCode }
    .then(sourceRecency)
private val observationStableOrder = Comparator<DirectionObservation> { left, right ->
    sourceStableOrder.compare(left.source, right.source)
}

private fun mergePositions(observations: List<DirectionObservation>): List<MetroTrain> {
    val ordered = observations.sortedWith(observationStableOrder)
    val direction = ordered.first().direction
    val nodes = mutableListOf<TrainObservation>()
    ordered.forEach { observation ->
        observation.direction.trains.forEach { train ->
            val source = train.source ?: observation.source
            val profile = observation.direction.runningTimeProfile?.takeIf {
                it.valid && it.stationCodes == direction.stationCodes
            }
            nodes.add(TrainObservation(train.copy(source = source), source,
                direction.stationCodes.indexOf(source.referenceStationCode), profile, source == observation.source))
        }
    }
    // 排序只打破完全相同分数的平局，不以用户选站、网络完成先后或期望车辆数量决定身份。
    nodes.sortWith(compareBy<TrainObservation> { it.source.referenceStationCode }
        .thenBy { it.source.serverEpochMillis ?: Long.MIN_VALUE }
        .thenBy { it.source.receivedAtElapsedMillis ?: Long.MIN_VALUE }
        .thenBy { it.train.arriveSeconds }.thenBy { it.train.nextStationCode }
        .thenBy { it.train.scale }.thenBy { it.train.arrivingFlag ?: Int.MIN_VALUE })
    val groups = nodes.indices.map { mutableListOf(it) }.toMutableList()
    val distances = Array(nodes.size) { left -> DoubleArray(nodes.size) { right ->
        if (left == right || direction.directionKey == null) Double.POSITIVE_INFINITY
        else identityDistance(nodes[left], nodes[right])
    } }
    val active = BooleanArray(nodes.size) { true }
    while (true) {
        var bestLeft = -1
        var bestRight = -1
        var bestDistance = Double.POSITIVE_INFINITY
        for (left in nodes.indices) if (active[left]) {
            for (right in left + 1 until nodes.size) if (active[right]) {
                if (distances[left][right] < bestDistance) {
                    bestDistance = distances[left][right]
                    bestLeft = left
                    bestRight = right
                }
            }
        }
        if (bestLeft < 0) break
        groups[bestLeft].addAll(groups[bestRight])
        active[bestRight] = false
        for (other in nodes.indices) if (active[other] && other != bestLeft) {
            // Complete linkage：新组最不相容的一对仍必须直接过双判据；A~B~C 不可跨越阈值。
            val distance = maxOf(distances[bestLeft][other], distances[bestRight][other])
            distances[bestLeft][other] = distance
            distances[other][bestLeft] = distance
        }
    }
    val result = nodes.indices.filter { active[it] }.map { groupIndex ->
        val members = groups[groupIndex].map { nodes[it] }
        if (members.size == 1) members.single().train else {
            val representative = members.sortedWith(representativeOrder(direction)).first()
            representative.train.copy(matchedObservations = members.sortedWith(Comparator { left, right ->
                sourceStableOrder.compare(left.source, right.source)
            }).map {
                MetroTrainObservation(it.source, it.train.arriveSeconds, it.train.scale, it.train.arrivingFlag,
                    nextStationCode = it.train.nextStationCode)
            })
        }
    }
    return result.sortedWith(direction.positionOrder())
}

/**
 * 150 秒位置容差覆盖站间运行/停站及参考站预测抖动；200 秒时刻容差覆盖累计运行分钟的舍入。
 * 两者是物理运行秒，和站格、车标宽度、期望车数无关。实测邻车超过 280 秒时不会因位置接近误并。
 * 缺/0/1 比例是服务端退化位置，缺少第二证据时收紧到站基准容差至 150 秒，
 * 小于实测首站最短两班待发间隔 180 秒，防止参考站丢失时误并不同未来计划。
 * 同一参考站的三班始终是三条身份。
 */
private fun identityDistance(left: TrainObservation, right: TrainObservation): Double {
    if (!left.consistentSource || !right.consistentSource ||
        left.source.referenceStationCode.isBlank() || left.source.referenceStationCode == right.source.referenceStationCode ||
        left.referenceIndex < 0 || right.referenceIndex < 0) return Double.POSITIVE_INFINITY
    if (!left.train.scale.isFinite() || !right.train.scale.isFinite() ||
        left.train.scale !in 0.0..1.0 || right.train.scale !in 0.0..1.0) return Double.POSITIVE_INFINITY
    val leftProfile = left.profile ?: return Double.POSITIVE_INFINITY
    val rightProfile = right.profile ?: return Double.POSITIVE_INFINITY
    if (leftProfile != rightProfile || left.train.nextStationCode !in leftProfile.stationCodes ||
        right.train.nextStationCode !in rightProfile.stationCodes) return Double.POSITIVE_INFINITY
    val elapsed = matchingTimeDifference(left.source, right.source) ?: return Double.POSITIVE_INFINITY
    if (abs(elapsed) > MATCH_CAPTURE_GAP_MILLIS) return Double.POSITIVE_INFINITY
    val elapsedSeconds = elapsed / 1_000.0
    val leftAnchor = left.train.arriveSeconds.toDouble() - leftProfile.cumulativeSeconds[left.referenceIndex]
    val rightAnchor = right.train.arriveSeconds.toDouble() - rightProfile.cumulativeSeconds[right.referenceIndex]
    val anchorDifference = abs(rightAnchor + elapsedSeconds - leftAnchor)
    if (anchorDifference > MATCH_ANCHOR_TOLERANCE_SECONDS) return Double.POSITIVE_INFINITY
    val leftPosition = leftProfile.physicalPositionSeconds(left.train)
    val rightPosition = rightProfile.physicalPositionSeconds(right.train)
    if (leftPosition != null && rightPosition != null) {
        // 较晚采样的位置退回共同采样时刻，不能把行驶中的列车当作位置突然改变。
        val positionDifference = abs(rightPosition - elapsedSeconds - leftPosition)
        if (positionDifference > MATCH_POSITION_TOLERANCE_SECONDS) return Double.POSITIVE_INFINITY
        return maxOf(positionDifference / MATCH_POSITION_TOLERANCE_SECONDS,
            anchorDifference / MATCH_ANCHOR_TOLERANCE_SECONDS)
    }
    if (anchorDifference > MATCH_DEGENERATE_ANCHOR_TOLERANCE_SECONDS) return Double.POSITIVE_INFINITY
    return anchorDifference / MATCH_DEGENERATE_ANCHOR_TOLERANCE_SECONDS
}

private fun matchingTimeDifference(left: MetroObservationSource, right: MetroObservationSource): Long? = when {
    left.serverEpochMillis != null && right.serverEpochMillis != null -> right.serverEpochMillis - left.serverEpochMillis
    left.receivedAtElapsedMillis != null && right.receivedAtElapsedMillis != null -> right.receivedAtElapsedMillis - left.receivedAtElapsedMillis
    else -> null
}

private fun representativeOrder(direction: MetroDirection): Comparator<TrainObservation> =
    compareBy<TrainObservation> { observation ->
        val remaining = observation.referenceIndex - direction.stationCodes.indexOf(observation.train.nextStationCode)
        if (observation.referenceIndex >= 0 && remaining >= 0) remaining else Int.MAX_VALUE
    }.thenByDescending { it.source.serverEpochMillis ?: Long.MIN_VALUE }
        .thenByDescending { it.source.receivedAtElapsedMillis ?: Long.MIN_VALUE }
        .thenBy { it.source.referenceStationCode }
