package com.yll682.houche.core

/**
 * 单列身份在目标站的推算值，不是目标站接口的官方读数，也不能回填本站 ETA 行。
 * arriveSeconds 以 source 的原始观测时刻为基准；不读取当前时间、不替旧观测续命。
 * stationName 由调用方从已知站表传入；方向没有完整站名表，缺名时保留 null。
 */
data class MetroArrivalProjection(
    val train: MetroTrain,
    val stationCode: String,
    val stationName: String?,
    val arriveSeconds: Int,
    val source: MetroObservationSource,
    val identityConfidence: MetroTrainIdentityConfidence
) {
    /** 负的最终 ETA 才表示推算已过站；目标在参考站上游本身不等于已经过站。 */
    val passed: Boolean get() = arriveSeconds < 0
    /** 即便目标就是原参考站，也保留此独立推算结果的口径。 */
    val isEstimated: Boolean get() = true
}

/**
 * 口径：ETA(S) = train.arriveSeconds + C[S] - C[R]，R 只取该车自己的 source。
 * 合并身份明确选代表观测的 source/arriveSeconds 成对投影，不平均不同采样时刻的原始观测；
 * matchedObservations 只说明近似身份置信度，不能把 direction.source 借给其他参考站的车。
 *
 * 不猜的边界：目标或原参考站不在本方向、缺 source/profile/累计秒、profile 站序不一致、
 * 或结果超出 Int 秒数范围，均返回 null。只消费本方向的 profile，不按站码大小重判方向，
 * 不按 scale/平均站间时间补值；完全不修改 trains，不生成官方本站到站行。
 */
fun projectMetroArrival(
    direction: MetroDirection,
    train: MetroTrain,
    stationCode: String,
    stationName: String? = null
): MetroArrivalProjection? {
    if (stationCode !in direction.stationCodes) return null
    val source = train.source ?: return null
    if (source.referenceStationCode !in direction.stationCodes) return null
    val profile = direction.runningTimeProfile ?: return null
    if (profile.stationCodes != direction.stationCodes) return null
    val referenceSeconds = profile.secondsAt(source.referenceStationCode) ?: return null
    val targetSeconds = profile.secondsAt(stationCode) ?: return null
    val seconds = train.arriveSeconds.toLong() + targetSeconds - referenceSeconds
    if (seconds !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    return MetroArrivalProjection(
        train = train,
        stationCode = stationCode,
        stationName = stationName?.takeIf(String::isNotBlank),
        arriveSeconds = seconds.toInt(),
        source = source,
        identityConfidence = if (train.matchedObservations.size > 1) {
            MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH
        } else {
            MetroTrainIdentityConfidence.SINGLE_OBSERVATION
        }
    )
}

/**
 * 本方向全线已识别身份在某站的推算，保留身份列表原序，包含已过站及未来待发计划。
 * 使用 allIdentifiedTrains，避免只取可见车标而漏掉被抑制的身份；无法投影者逐条略过。
 * 双向线路由调用方分别传入两个方向，不能将相反方向或未知身份拼成官方本站 ETA。
 */
fun projectMetroArrivals(
    direction: MetroDirection,
    stationCode: String,
    stationName: String? = null
): List<MetroArrivalProjection> = direction.allIdentifiedTrains.mapNotNull { train ->
    projectMetroArrival(direction, train, stationCode, stationName)
}
