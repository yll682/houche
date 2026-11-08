package com.yll682.houche.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 来源必须显式标注；经纬度的数值范围无法证明坐标系。 */
enum class MetroCoordinateSystem {
    UNKNOWN, WGS84, GCJ02, BD09;

    companion object {
        fun from(value: String?): MetroCoordinateSystem =
            entries.firstOrNull { it.name == value?.trim()?.uppercase(Locale.ROOT) } ?: UNKNOWN
    }
}

/** 可直接交给线路、站点选择器的稳定标识；距离是球面直线距离，不是步行距离。 */
data class MetroNearestStation(val lineId: String, val stationCode: String, val distanceMeters: Double)

/** 站表尚未到达时也能拒绝无权限、失败、低精度或未知坐标系的定位结果。 */
fun isMetroLocationUsable(
    lat: Double?,
    lng: Double?,
    accuracyMeters: Double?,
    coordinateSystem: String
): Boolean = lat != null && lng != null && validMetroCoordinate(lat, lng) &&
    MetroCoordinateSystem.from(coordinateSystem) == MetroCoordinateSystem.GCJ02 &&
    accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters in 0.0..1_000.0

/**
 * 只接受 LocationProvider 已转换的 GCJ02 定位；失败、无权限时传 null 经纬度。
 * 未知精度、精度超过 1km、没有 5km 内的运营站时不自动选择。
 *
 * 每站坐标系由资料解析器填写：厦门官方 allStation 为 BD09，先按官方客户端算法转为
 * GCJ02；已明确为 GCJ02 的来源可直接比较。未知来源及暂不支持的 WGS84 不猜测、不换算。
 * 证据见 docs/metro-coordinate-evidence.md；同距按线路数字编号、线路 id、站码稳定排序。
 */
fun List<MetroLine>.nearestStation(
    lat: Double?,
    lng: Double?,
    accuracyMeters: Double?,
    coordinateSystem: String
): MetroNearestStation? {
    if (!isMetroLocationUsable(lat, lng, accuracyMeters, coordinateSystem)) return null
    // 上面的共用校验已经保证非空；局部值让后续距离计算只处理有效坐标。
    val locationLat = lat ?: return null
    val locationLng = lng ?: return null
    // 选择器靠线路 id 与站码回查；重复身份即使坐标不同也不能只挑其中一条。
    val lineIdCounts = groupingBy { it.id }.eachCount()

    return asSequence().filter { it.opened && it.id.isNotBlank() && lineIdCounts[it.id] == 1 }.flatMap { line ->
        val stationCodeCounts = line.stations.groupingBy { it.code }.eachCount()
        line.stations.asSequence().filter {
            it.opened && it.code.isNotBlank() && stationCodeCounts[it.code] == 1
        }.mapNotNull { station ->
            val stationLat = station.lat.toDoubleOrNull() ?: return@mapNotNull null
            val stationLng = station.lng.toDoubleOrNull() ?: return@mapNotNull null
            if (!validMetroCoordinate(stationLat, stationLng)) return@mapNotNull null
            val point = when (MetroCoordinateSystem.from(station.coordinateSystem)) {
                MetroCoordinateSystem.GCJ02 -> MetroCoordinate(stationLat, stationLng)
                MetroCoordinateSystem.BD09 -> bd09ToGcj02(stationLat, stationLng)
                else -> return@mapNotNull null
            }
            if (!validMetroCoordinate(point.lat, point.lng)) return@mapNotNull null
            val distance = sphericalMetroDistance(locationLat, locationLng, point.lat, point.lng)
            if (!distance.isFinite() || distance > 5_000.0) return@mapNotNull null
            MetroNearestStation(line.id, station.code, distance)
        }
    }.minWithOrNull(
        compareBy<MetroNearestStation> { it.distanceMeters }
            .thenBy { it.lineId.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.lineId }
            .thenBy { it.stationCode }
    )
}

private data class MetroCoordinate(val lat: Double, val lng: Double)

private fun validMetroCoordinate(lat: Double, lng: Double): Boolean =
    lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

/** 与已还原官方 GPSUtils.bd09_To_Gcj02 相同；不是再次做 WGS84→GCJ02。 */
private fun bd09ToGcj02(lat: Double, lng: Double): MetroCoordinate {
    val x = lng - 0.0065
    val y = lat - 0.006
    val xPi = PI * 3_000.0 / 180.0
    val radius = sqrt(x * x + y * y) - 0.00002 * sin(y * xPi)
    val angle = atan2(y, x) - 0.000003 * cos(x * xPi)
    return MetroCoordinate(radius * sin(angle), radius * cos(angle))
}

/** 平均地球半径的 haversine 近似，仅用于附近站排序和 5km 上限。 */
private fun sphericalMetroDistance(lat: Double, lng: Double, stationLat: Double, stationLng: Double): Double {
    val radians = PI / 180.0
    val latitudeDelta = (stationLat - lat) * radians
    val longitudeDelta = (stationLng - lng) * radians
    val latitudeSin = sin(latitudeDelta / 2.0)
    val longitudeSin = sin(longitudeDelta / 2.0)
    val halfChord = latitudeSin * latitudeSin +
        cos(lat * radians) * cos(stationLat * radians) * longitudeSin * longitudeSin
    return 2.0 * 6_371_008.8 * asin(sqrt(halfChord.coerceIn(0.0, 1.0)))
}
