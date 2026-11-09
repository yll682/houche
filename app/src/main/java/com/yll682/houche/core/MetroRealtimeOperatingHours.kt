package com.yll682.houche.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

val METRO_OPERATING_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

/** 与站点列表共享原始文案：仅缺字段时逐项回退线路首末班，非法字段不猜测。 */
data class MetroOperatingTimes(val first: String, val last: String)

fun metroStationOperatingTimes(line: MetroLine, station: MetroStation): MetroOperatingTimes =
    MetroOperatingTimes(station.first.ifBlank { line.first }, station.last.ifBlank { line.last })

enum class MetroOperatingStatus { InService, OutOfService, Unknown }

data class MetroOperatingWindow(val status: MetroOperatingStatus, val nextChangeAt: Instant? = null)

/**
 * 按厦门站表的 HH:mm 分钟精度判窗，首末显示分钟均包含。
 * 例如 08:00–20:00 在 20:00:59.999 仍运营，20:01 才结束；
 * 「次日 00:07」和常规 06:00–00:07 均包括昨日运营日延续至今日 00:07 的部分。
 * 缺失、不完整、非法时间返回 Unknown，不能据此阻止真实查询。
 */
fun metroOperatingWindow(line: MetroLine, station: MetroStation, now: Instant): MetroOperatingWindow {
    val times = metroStationOperatingTimes(line, station)
    val first = parseOperatingTime(times.first, allowNextDay = false)
        ?: return MetroOperatingWindow(MetroOperatingStatus.Unknown)
    val last = parseOperatingTime(times.last, allowNextDay = true)
        ?: return MetroOperatingWindow(MetroOperatingStatus.Unknown)
    val crossesMidnight = last.nextDay || last.time < first.time
    val date = now.atZone(METRO_OPERATING_ZONE).toLocalDate()
    // 包含昨日窗口和后两日边界；显式次日可能与后一天窗口重叠，按窗口并集求真正变化。
    val windows = (-1L..2L).map { offset ->
        val serviceDate = date.plusDays(offset)
        val start = serviceDate.atTime(first.time).atZone(METRO_OPERATING_ZONE).toInstant()
        val end = serviceDate.plusDays(if (crossesMidnight) 1 else 0)
            .atTime(last.time).plusMinutes(1).atZone(METRO_OPERATING_ZONE).toInstant()
        start to end
    }
    fun isOpen(at: Instant): Boolean = windows.any { (start, end) -> !at.isBefore(start) && at.isBefore(end) }
    val open = isOpen(now)
    val next = windows.flatMap { listOf(it.first, it.second) }.distinct().sorted()
        .firstOrNull { it.isAfter(now) && isOpen(it) != open }
    return MetroOperatingWindow(if (open) MetroOperatingStatus.InService else MetroOperatingStatus.OutOfService, next)
}

private data class OperatingTime(val time: LocalTime, val nextDay: Boolean)

private fun parseOperatingTime(text: String, allowNextDay: Boolean): OperatingTime? {
    val match = Regex("^(次日\\s*)?([0-9]{2}):([0-9]{2})$").matchEntire(text.trim()) ?: return null
    val nextDay = match.groupValues[1].isNotEmpty()
    if (nextDay && !allowNextDay) return null
    val hour = match.groupValues[2].toInt()
    val minute = match.groupValues[3].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return OperatingTime(LocalTime.of(hour, minute), nextDay)
}
