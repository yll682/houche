package com.yll682.houche.core

/** 共同官方共站码与补充推导分开记录；后者不是官方共站码的证明。 */
enum class MetroTransferEvidence {
    OFFICIAL_STATION_UNIQUE_CODE,
    FLAG_NAME_AND_COORDINATES
}

/** 跳转使用目标线路自己的站码；文案和颜色来自同份站表里的目标线路。 */
data class MetroTransferTarget(
    val lineId: String,
    val stationCode: String,
    val lineName: String,
    val color: String,
    val evidence: MetroTransferEvidence
)

/**
 * 优先使用 allStation 的 changeFlag 与 stationUniqueCode，不凭同名或距离制造换乘。
 * 鼎美的两个官方 uniqueCode 各为自身，但双方 changeFlag=true，且精确同名、坐标相等；
 * 这种补充证据单独标为 FLAG_NAME_AND_COORDINATES。缺字段、缺坐标或目标有歧义时不补。
 * 旧 transfer 文案仅供兼容展示，不会反向生成导航目标，也不补造国铁/BRT。
 */
internal fun metroTransfers(lines: List<MetroLine>): List<MetroLine> {
    val lineCounts = lines.groupingBy { it.id }.eachCount()
    val calls = lines.flatMap { line -> line.stations.map { MetroCalling(line, it) } }
    val identityCounts = calls.groupingBy { it.line.id to it.station.code }.eachCount()
    val eligible = calls.filter { call ->
        call.line.id.isNotBlank() && lineCounts[call.line.id] == 1 &&
            call.station.code.isOfficialStationCode() && identityCounts[call.line.id to call.station.code] == 1 &&
            call.station.name.isNotBlank() && call.station.changeFlag == true &&
            call.station.stationUniqueCode.isOfficialStationCode() && call.station.transferCoordinates() != null
    }
    val byUniqueCode = eligible.groupBy { it.station.stationUniqueCode }
    val byNameAndCoordinates = eligible.filter { it.station.coordinateSystem == "BD09" }
        .groupBy { it.station.name to it.station.transferCoordinates() }

    return lines.map { line ->
        line.copy(stations = line.stations.map { station ->
            val identity = line.id to station.code
            val source = eligible.firstOrNull { it.line.id == line.id && it.station === station }
            val candidates = if (source == null) emptyList() else {
                val official = byUniqueCode[station.stationUniqueCode].orEmpty()
                val corroborated = if (station.coordinateSystem == "BD09")
                    byNameAndCoordinates[station.name to station.transferCoordinates()].orEmpty() else emptyList()
                (official + corroborated).distinctBy { it.line.id to it.station.code }
            }
            // 同一线若对应不止一站，源站与目标站都不能靠 first() 猜选。
            val perLine = candidates.groupBy { it.line.id }
            val targets = if (perLine[line.id]?.singleOrNull()?.let { it.line.id to it.station.code } != identity) {
                emptyList()
            } else {
                candidates.filter { it.line.id != line.id && perLine[it.line.id]?.size == 1 }.map { target ->
                    MetroTransferTarget(
                        lineId = target.line.id,
                        stationCode = target.station.code,
                        lineName = target.line.name.removePrefix("地铁"),
                        color = target.line.color,
                        evidence = if (station.stationUniqueCode == target.station.stationUniqueCode)
                            MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE
                        else MetroTransferEvidence.FLAG_NAME_AND_COORDINATES
                    )
                }.sortedWith(compareBy<MetroTransferTarget> { it.lineId.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.lineId }.thenBy { it.stationCode })
            }
            station.copy(
                transfer = (station.transfer + targets.map { it.lineName }).distinct(),
                transferTargets = targets
            )
        })
    }
}

/** 当前官方站码为八位 ASCII 数字；全零和占位文本不能充当跨线身份。 */
private fun String.isOfficialStationCode(): Boolean =
    length == 8 && all { it in '0'..'9' } && any { it != '0' }

/** 只核验原始坐标完整性；不将坐标系换算或距离阈值作为换乘关系来源。 */
private fun MetroStation.transferCoordinates(): Pair<Double, Double>? {
    val longitude = lng.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
    val latitude = lat.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
    return (longitude to latitude).takeUnless { longitude == 0.0 && latitude == 0.0 }
}
