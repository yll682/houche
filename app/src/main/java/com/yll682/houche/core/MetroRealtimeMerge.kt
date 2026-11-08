package com.yll682.houche.core

import kotlin.math.abs

private const val MATCH_CAPTURE_GAP_MILLIS = 5_000L
private const val MATCH_SCALE_DIFFERENCE = 0.10
private const val MATCH_OFFSET_SPREAD_MILLIS = 2_000L

/**
 * 只合并本次输入的真实响应，不记忆上一轮列车。
 * trains 始终来自 stationCode 自身的响应；其他参考站只补充 positions。
 * 没有车辆 ID：仅在近同时、位置相容且至少两组互为唯一候选的站间 ETA 偏移一致时近似去重。
 * 缺证据、存在歧义或方向链不完整时保留原车；这里不推算其他参考站到本站的 ETA。
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
private data class TrainObservation(val train: MetroTrain, val source: MetroObservationSource, val referenceIndex: Int)

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
    val ranges = ordered.map { observation ->
        val first = nodes.size
        observation.direction.trains.forEach { train ->
            val source = train.source ?: observation.source
            nodes.add(TrainObservation(train.copy(source = source), source, direction.stationCodes.indexOf(source.referenceStationCode)))
        }
        (first until nodes.size).toList()
    }
    val edges = Array(nodes.size) { mutableSetOf<Int>() }
    if (direction.directionKey != null) {
        for (leftIndex in ordered.indices) for (rightIndex in leftIndex + 1 until ordered.size) {
            val leftSource = ordered[leftIndex].source
            val rightSource = ordered[rightIndex].source
            val elapsed = matchingTimeDifference(leftSource, rightSource) ?: continue
            if (abs(elapsed) > MATCH_CAPTURE_GAP_MILLIS ||
                leftSource.referenceStationCode == rightSource.referenceStationCode ||
                leftSource.referenceStationCode !in direction.stationCodes ||
                rightSource.referenceStationCode !in direction.stationCodes) continue
            val candidates = ranges[leftIndex].associateWith { left ->
                ranges[rightIndex].filter { right ->
                    // 冲突的车级来源不能套用方向级参考站的时间偏移，但原车仍会保留。
                    nodes[left].source == leftSource && nodes[right].source == rightSource &&
                        compatiblePosition(nodes[left], nodes[right], direction)
                }
            }
            val unique = candidates.mapNotNull { (left, rightCandidates) ->
                val right = rightCandidates.singleOrNull() ?: return@mapNotNull null
                if (candidates.values.count { right in it } == 1) left to right else null
            }
            // 两组不同列车的共同偏移是最小交叉支持；一个 next 相同还不足以删除一条车记录。
            if (unique.size < 2) continue
            val offsets = unique.map { (left, right) ->
                elapsed + (nodes[right].train.arriveSeconds.toLong() - nodes[left].train.arriveSeconds) * 1_000L
            }
            if (offsets.maxOrNull()!! - offsets.minOrNull()!! > MATCH_OFFSET_SPREAD_MILLIS) continue
            val referenceAdvance = direction.stationCodes.indexOf(rightSource.referenceStationCode) -
                direction.stationCodes.indexOf(leftSource.referenceStationCode)
            // 下游参考站的到站时刻应较晚；不估计站间运行秒数，仅排除明显反向的偏移。
            if (offsets.any { if (referenceAdvance > 0) it < -MATCH_OFFSET_SPREAD_MILLIS else it > MATCH_OFFSET_SPREAD_MILLIS }) continue
            unique.forEach { (left, right) -> edges[left].add(right); edges[right].add(left) }
        }
    }
    val visited = mutableSetOf<Int>()
    val result = mutableListOf<MetroTrain>()
    nodes.indices.forEach { first ->
        if (!visited.add(first)) return@forEach
        val component = mutableListOf(first)
        var cursor = 0
        while (cursor < component.size) {
            edges[component[cursor++]].sorted().forEach { neighbor ->
                if (visited.add(neighbor)) component.add(neighbor)
            }
        }
        // 不对 A~B、B~C 作传递身份推断。每对都须直接通过，且同一参考源只能有一条车。
        val onePerSource = component.map { nodes[it].source.referenceStationCode }.distinct().size == component.size
        val clique = component.all { left -> component.all { right -> left == right || right in edges[left] } }
        if (component.size == 1 || !onePerSource || !clique) {
            result.addAll(component.map { nodes[it].train })
        } else {
            val members = component.map { nodes[it] }
            val representative = members.sortedWith(representativeOrder(direction)).first()
            result.add(representative.train.copy(matchedObservations = members.sortedWith(Comparator { left, right ->
                sourceStableOrder.compare(left.source, right.source)
            }).map {
                MetroTrainObservation(it.source, it.train.arriveSeconds, it.train.scale, it.train.arrivingFlag)
            }))
        }
    }
    return result.sortedWith(compareByDescending<MetroTrain> { direction.positionOf(it) }
        .thenBy { it.nextStationCode }.thenBy { it.source?.referenceStationCode }.thenBy { it.arriveSeconds })
}

private fun compatiblePosition(left: TrainObservation, right: TrainObservation, direction: MetroDirection): Boolean =
    left.source.referenceStationCode != right.source.referenceStationCode &&
        left.train.nextStationCode.isNotBlank() && left.train.nextStationCode == right.train.nextStationCode &&
        left.train.nextStationCode in direction.stationCodes &&
        left.train.scaleKnown && right.train.scaleKnown &&
        left.train.scale.isFinite() && right.train.scale.isFinite() &&
        left.train.scale in 0.0..1.0 && right.train.scale in 0.0..1.0 &&
        abs(left.train.scale - right.train.scale) <= MATCH_SCALE_DIFFERENCE + 1e-9

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
