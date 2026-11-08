package com.yll682.houche.core

/**
 * 四、六号线开通后，历史 allStation 的 showFlag 仍整线为 false。
 * 用户已确认开通；以下站码同时在真实 allStation 中 activeFlag=true，且与同线路
 * 实时响应的完整 wayStations 一致。只修正这些已核实站点，未来新增站不继承此状态。
 * 原始响应及其 showFlag/activeFlag 留在历史 fixture，本函数只归一展示资料。
 */
private val confirmedOpenedMetroStations = mapOf(
    "04000000" to setOf(
        "04010000", "04020000", "04030000", "04040000",
        "04050000", "04060000", "04080000", "04090000"
    ),
    "06000000" to setOf(
        "06030000", "06040000", "06050000", "06060000", "06070000", "06080000",
        "06090000", "06100000", "06110000", "06130000", "06140000", "06150000",
        "06160000", "06170000", "06180000", "06190000", "06200000"
    )
)

/** 在线与内置站表进入展示层时共用；不改变站序、线路码、时刻或坐标。 */
fun normalizeMetroOpenings(lines: List<MetroLine>): List<MetroLine> = lines.map { line ->
    val confirmed = confirmedOpenedMetroStations[line.realtimeCode()] ?: return@map line
    val stations = line.stations.map { station ->
        if (station.code in confirmed && !station.opened) station.copy(opened = true) else station
    }
    val opened = line.opened || stations.any { it.opened }
    if (stations == line.stations && opened == line.opened) line else line.copy(opened = opened, stations = stations)
}
