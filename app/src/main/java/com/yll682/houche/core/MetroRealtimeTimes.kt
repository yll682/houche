package com.yll682.houche.core

import kotlinx.serialization.json.JsonElement

/**
 * 实时响应 wayStations 自带的官方区间运行时间，不增加站表请求。
 * cumulativeSeconds 与该方向站序一致，从始发端零秒起递增；只用于同车证据，不能生成本站 ETA。
 */
data class MetroRunningTimeProfile(
    val stationCodes: List<String>,
    val cumulativeSeconds: List<Int>
) {
    val valid: Boolean get() = stationCodes.size >= 2 && stationCodes.size == cumulativeSeconds.size &&
        stationCodes.all(String::isNotBlank) && stationCodes.distinct().size == stationCodes.size &&
        cumulativeSeconds.first() == 0 && cumulativeSeconds.zipWithNext().all { (a, b) -> b > a }

    fun secondsAt(stationCode: String): Int? = if (valid) {
        stationCodes.indexOf(stationCode).takeIf { it >= 0 }?.let { cumulativeSeconds[it] }
    } else null

    /**
     * next 是行驶方向前方的站，scale 是驶向该站的区间进度。
     * 例如本站 ETA 118 秒、next=本站、scale=0，60 秒后 scale=.51、ETA=57，
     * 因此位置是前一站累计秒 + scale×驶入区间秒，不能把 next 当作区间起点。
     * 缺比例、0/1 边界和首站前方均是退化位置，只可由到站基准时刻辅助认车。
     */
    fun physicalPositionSeconds(train: MetroTrain): Double? {
        if (!valid || !train.scaleKnown || !train.scale.isFinite() || train.scale <= 0.0 || train.scale >= 1.0) return null
        val next = stationCodes.indexOf(train.nextStationCode)
        if (next <= 0) return null
        val start = cumulativeSeconds[next - 1]
        return start + train.scale * (cumulativeSeconds[next] - start)
    }
}

/**
 * metadata 的 timeCost/revTimeCost 始终挂在站码较小的区间端点：
 * 正向 i→i+1 用 i.timeCost，反向 i+1→i 用 i.revTimeCost，单位分钟。
 * 不以平均两分钟替补缺值；缺站、方向非单调或无效区间资料时放弃跨站认车。
 */
internal fun parseMetroRunningTimeProfile(stations: List<JsonElement>): MetroRunningTimeProfile? {
    val codes = stations.map { it.s("stationCode") }
    if (codes.size < 2 || codes.any(String::isBlank) || codes.distinct().size != codes.size) return null
    val ascending = codes.zipWithNext().all { (a, b) -> a < b }
    val descending = codes.zipWithNext().all { (a, b) -> a > b }
    if (!ascending && !descending) return null
    val cumulative = mutableListOf(0)
    for (index in 1 until stations.size) {
        val lower = stations[if (ascending) index - 1 else index]
        val minutes = lower.d(if (ascending) "timeCost" else "revTimeCost") ?: return null
        if (!minutes.isFinite() || minutes <= 0.0 || minutes > 60.0) return null
        val seconds = minutes * 60.0
        if (seconds % 1.0 != 0.0) return null
        cumulative.add(cumulative.last() + seconds.toInt())
    }
    return MetroRunningTimeProfile(codes, cumulative).takeIf { it.valid }
}
