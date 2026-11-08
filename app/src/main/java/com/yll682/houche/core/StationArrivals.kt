package com.yll682.houche.core

/** EMPTY 仅表示本次查询没有到站记录，不能据此推断停运或没有车辆。 */
enum class StationArrivalsStatus { READY, EMPTY, PARTIAL, UNAVAILABLE }

/** 已知经过线路没有返回记录；保留站台来源，不把缺报改写成等待发车。 */
data class StationArrivalGap(
    val line: LineSummary,
    val platformNumber: String? = null,
    val platformLat: Double? = null,
    val platformLng: Double? = null
)

data class StationArrivals(
    val station: Station,
    val arrivals: List<Arrival>,
    val status: StationArrivalsStatus,
    val errors: List<String> = emptyList(),
    val unreportedLines: List<StationArrivalGap> = emptyList()
)
