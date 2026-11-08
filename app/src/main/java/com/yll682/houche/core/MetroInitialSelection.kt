package com.yll682.houche.core

/** 已由页面外部取得的位置；本对象不会申请权限或启动定位。 */
data class MetroInitialLocation(
    val lat: Double?,
    val lng: Double?,
    val accuracyMeters: Double?,
    val coordinateSystem: String
)

enum class MetroInitialSelectionPhase { AwaitingLocation, AwaitingStations, Consumed }

/** 一次页面实例只消费首个定位结果；手动选择和显式线路入口优先。 */
data class MetroInitialSelection private constructor(
    val phase: MetroInitialSelectionPhase,
    private val pendingLocation: MetroInitialLocation? = null
) {
    companion object {
        fun start(explicitLineId: String? = null): MetroInitialSelection = MetroInitialSelection(
            if (explicitLineId.isNullOrBlank()) MetroInitialSelectionPhase.AwaitingLocation
            else MetroInitialSelectionPhase.Consumed
        )
    }

    /** 即使手动选中了当前项，也表示用户已接管；迟到定位不能覆盖。 */
    fun manualSelection(): MetroInitialSelection = MetroInitialSelection(MetroInitialSelectionPhase.Consumed)

    /** null 包含无权限、失败和无现成位置；同样消费机会，不在之后偷偷重试。 */
    fun receive(location: MetroInitialLocation?, lines: List<MetroLine>): MetroInitialSelectionResult {
        if (phase != MetroInitialSelectionPhase.AwaitingLocation) return MetroInitialSelectionResult(this)
        if (location == null || !isMetroLocationUsable(location.lat, location.lng, location.accuracyMeters, location.coordinateSystem)) {
            return MetroInitialSelectionResult(manualSelection())
        }
        if (lines.isEmpty()) return MetroInitialSelectionResult(
            MetroInitialSelection(MetroInitialSelectionPhase.AwaitingStations, location)
        )
        return resolve(location, lines)
    }

    /** 站表迟到时只兑现已保存的第一次位置；在线替换不会重新选站。 */
    fun stationsLoaded(lines: List<MetroLine>): MetroInitialSelectionResult {
        if (phase != MetroInitialSelectionPhase.AwaitingStations || lines.isEmpty()) return MetroInitialSelectionResult(this)
        return resolve(checkNotNull(pendingLocation), lines)
    }

    private fun resolve(location: MetroInitialLocation, lines: List<MetroLine>): MetroInitialSelectionResult =
        MetroInitialSelectionResult(
            manualSelection(),
            lines.nearestStation(location.lat, location.lng, location.accuracyMeters, location.coordinateSystem)
        )
}

data class MetroInitialSelectionResult(
    val state: MetroInitialSelection,
    val station: MetroNearestStation? = null
)
