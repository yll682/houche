package com.yll682.houche.core

import kotlin.math.roundToInt

/** 单线路二十秒滚动窗口的物理请求上限；失败、超时同样占用预算。 */
internal const val METRO_REALTIME_MAX_REFERENCES = 8
internal const val METRO_REALTIME_WINDOW_MILLIS = 20_000L
internal const val METRO_REALTIME_MAX_CONCURRENCY = 2

/** 完整站序只用于选锚点，不意味着把整条线路逐站请求一遍。 */
internal data class MetroRealtimePlan(
    val stationCodes: List<String>,
    /** 用户所选站优先发送，其余固定锚点随换站保持稳定以复用缓存。 */
    val referenceStationCodes: List<String>,
    val selectedStationCode: String
)

/**
 * 最多五个固定锚点（含两端）加用户参考站及左右邻站，总数不超过八个。
 * 三十二站时固定锚点下标为 0/8/16/23/31，广域间隔最多八站。
 * 这是有限预算的全线采样计划，不是所有列车必被覆盖的证明。
 */
internal fun planMetroRealtimeReferences(stationCodes: List<String>, selectedStationCode: String): MetroRealtimePlan {
    val stations = stationCodes.filter(String::isNotBlank).distinct()
    if (stations.isEmpty()) return MetroRealtimePlan(emptyList(), emptyList(), "")
    val selected = selectedStationCode.takeIf { it in stations } ?: stations.first()
    val anchors = if (stations.size <= METRO_REALTIME_MAX_REFERENCES) stations else {
        val count = 5
        List(count) { index -> stations[(index.toDouble() * stations.lastIndex / (count - 1)).roundToInt()] }
    }
    val selectedIndex = stations.indexOf(selected)
    val nearby = listOfNotNull(stations.getOrNull(selectedIndex - 1), stations.getOrNull(selectedIndex + 1))
    return MetroRealtimePlan(stations, (listOf(selected) + nearby + anchors).distinct(), selected)
}
