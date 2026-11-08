package com.yll682.houche.core

import kotlin.math.roundToInt

/** 单线路二十秒滚动窗口的物理请求上限；失败、超时同样占用预算。 */
internal const val METRO_REALTIME_MAX_REFERENCES = 8
internal const val METRO_REALTIME_WINDOW_MILLIS = 20_000L
internal const val METRO_REALTIME_MAX_CONCURRENCY = 2

/** 两轮全站快照验证过的完整站码链；其他 24 站线路不能借用此采样结论。 */
private val verifiedLine1StationCodes = (1..24).map { "01${it.toString().padStart(2, '0')}0000" }
private val verifiedLine1AnchorIndices = listOf(0, 6, 12, 18, 23)

/** 完整站序只用于选锚点，不意味着把整条线路逐站请求一遍。 */
internal data class MetroRealtimePlan(
    val stationCodes: List<String>,
    /** 用户所选站优先发送；已验证线路使用固定锚点，其他线路保留邻站和补洞策略。 */
    val referenceStationCodes: List<String>,
    val selectedStationCode: String,
    /** 只补固定锚点/邻站去重后空出的槽位，不增加单线物理请求预算。 */
    val gapFillStationCodes: List<String> = emptyList()
) {
    /** 只描述参考站采样间隔，不能用它推出未观测列车数或保证第 5 班。 */
    val maxReferenceGapStations: Int get() = referenceStationCodes.map(stationCodes::indexOf)
        .sorted().zipWithNext { left, right -> right - left }.maxOrNull() ?: 0
    val unqueriedStationCount: Int get() = (stationCodes.size - referenceStationCodes.size).coerceAtLeast(0)
}

/**
 * 已验证的 1 号线完整站码链使用五个固定锚点加所选站，每批五或六次。
 * 其他线路最多五个固定锚点（含两端）加用户参考站及左右邻站，总数不超过八个。
 * 锚点与邻站重复时，把剩余槽位分配给当前最大参考站间隙的中点；同长间隙先取站序较小者。
 * 保留五个稳定锚点，不依赖上一批列车或参考站 ETA 猜测密度，避免把旧车带入新一批。
 * 三十二站时固定锚点下标为 0/8/16/23/31，广域间隔最多八站。
 * 所选站下标 9 时旧计划 7 次、新计划 8 次，补下标 4，最大间隔仍为 8，但减少一个八站空洞。
 * 这是有限预算的全线采样计划，不是所有列车必被覆盖的证明。
 */
internal fun planMetroRealtimeReferences(stationCodes: List<String>, selectedStationCode: String): MetroRealtimePlan {
    val stations = stationCodes.filter(String::isNotBlank).distinct()
    if (stations.isEmpty()) return MetroRealtimePlan(emptyList(), emptyList(), "")
    val selected = selectedStationCode.takeIf { it in stations } ?: stations.first()
    if (stations == verifiedLine1StationCodes) {
        // 2026-11-08 两轮 24 站实测：五锚点同时覆盖两个方向全部观测身份。
        // 固定站距至多六站；所选站额外最多一次，不把样本最优解表写成运营保证。
        // analysis/round6_evidence_audit.py 重现五站下界、漏区间和样本适用范围。
        val anchors = verifiedLine1AnchorIndices.map(stations::get)
        return MetroRealtimePlan(stations, (listOf(selected) + anchors).distinct(), selected)
    }
    val anchors = if (stations.size <= METRO_REALTIME_MAX_REFERENCES) stations else {
        val count = 5
        List(count) { index -> stations[(index.toDouble() * stations.lastIndex / (count - 1)).roundToInt()] }
    }
    val selectedIndex = stations.indexOf(selected)
    val nearby = listOfNotNull(stations.getOrNull(selectedIndex - 1), stations.getOrNull(selectedIndex + 1))
    val references = (listOf(selected) + nearby + anchors).distinct().toMutableList()
    val gapFill = mutableListOf<String>()
    while (references.size < minOf(stations.size, METRO_REALTIME_MAX_REFERENCES)) {
        val gap = references.map(stations::indexOf).sorted().zipWithNext()
            .filter { (left, right) -> right - left > 1 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second - it.first }.thenBy { it.first })
            .firstOrNull() ?: break
        val reference = stations[gap.first + (gap.second - gap.first) / 2]
        references.add(reference)
        gapFill.add(reference)
    }
    return MetroRealtimePlan(stations, references, selected, gapFill)
}
