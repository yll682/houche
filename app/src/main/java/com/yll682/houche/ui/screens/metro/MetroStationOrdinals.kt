package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroPositionPresentation

/**
 * 「到本站先后」投影（用户拍板：**第 1 班 = 最先到达当前参考站的那班**）。
 *
 * 为什么要在这里再投影一次：`core` 的 `positionPresentations().ordinal` 是**全线行驶进度序**
 * （越靠该方向终点越靠前）。对本站来说，已经开过本站的车反而排在最前面，于是本站 ETA
 * 可能从「第 2 班」开始——这就是用户报的那条。数据与位置本身不动，只在本层按
 * `stationPosition` 与参考站下标 `r` 的关系重新编号：
 *
 * 1. `p > r`（含 [MetroPassedTolerance]）→ **已过本站**：仍然画车标，标签写「已过」；
 * 2. 其余按 `p` 从大到小（越大越近本站）依次编号 1..N，**不截断到 3**；
 * 3. 本站原始到站行（ETA 第 k 行）按 [MetroStationOrdinals.ordinalOfRow] 取同一套编号，
 *    与位置条完全一致；某一行没有位置记录时按上一行顺延（行序本身就是到站先后），
 *    不退化成不带班号的「预计到达」。
 */
internal data class MetroStationTrain(
    val presentation: MetroPositionPresentation,
    /** 到本站的班号；`null` 表示已过本站（只画车标 + 「已过」，不给班号）。 */
    val ordinal: Int?
) {
    val stationPosition: Double get() = presentation.stationPosition
    val passed: Boolean get() = ordinal == null

    /**
     * 车标动画的稳定身份：沿用 core 的全线进度序（同一批观测内唯一且有序），
     * 只用来做 compose key，**不显示**——显示用 [ordinal]。
     */
    val markerKey: Int get() = presentation.ordinal
}

/** 一次投影的结果：位置条的车标编号 + 本站到站行的编号。 */
internal data class MetroStationOrdinals(
    /** 当前参考站在该方向站序里的下标 `r`；-1 表示站序里没有这个站。 */
    val referenceIndex: Int,
    /** 位置已知的车标，按 `p` 从大到小（已过本站的排在最前面，但它们没有班号）。 */
    val trains: List<MetroStationTrain>,
    /** 本站原始到站行序（1..n）→ 第几班。 */
    val rowOrdinals: Map<Int, Int>
) {
    /** 位置条上最大的班号；一个未到站的车都没有时为 0。 */
    val maxOrdinal: Int get() = trains.mapNotNull { it.ordinal }.maxOrNull() ?: 0

    /** 已过本站的车标数（车标照样画，只是标「已过」）。 */
    val passedCount: Int get() = trains.count { it.passed }

    /** 本站第 k 行（1 起）对应第几班；没有这一行时为 null。 */
    fun ordinalOfRow(row: Int): Int? = rowOrdinals[row]
}

/**
 * 判「已过本站」的容差，单位「站」。
 *
 * 取 **0** 的理由（真实样本实测，见 `analysis/fixtures/metro-round2/`）：
 * `positionOf` 的返回值只可能是整数（`flag=2`，车在下一站站台）或半整数
 * （`flag=1`，车在下一站前一段中点），不是浮点近似值，所以判定不需要留浮点余量：
 * - 本站站台上 `flag=2` 的车 `p == r`，**不能**判成已过 → 用严格大于号就正好排除它；
 * - 刚开走的那班在「本站与下一站之间」⇒ `p == r + 0.5`，真实合并样本
 *   （095729 行政中心 + 马青路两份真实响应，合并后那班区间车 `p = 22.5`、`r = 22`）
 *   里就出现过，它是**已经过站**的车。若把容差取到 0.5，这班会被当成「第 1 班」，
 *   而它没有本站到站行，本站 ETA 第 1 行又会变成「第 2 班」——正好把用户报的那条
 *   错位重新引入。0.5 是半整数坐标的最小间隔，容差必须小于它，故取 0。
 */
internal const val MetroPassedTolerance = 0.0

/**
 * 把该方向的全部位置记录投影成「到本站先后」的班号。
 *
 * @param stationCode 当前参考站（本站）
 * @param rowCount 本站原始到站行数（ETA 蓝块要编号的行数）
 * @param tolerance 判「已过本站」的容差，默认 [MetroPassedTolerance]
 */
internal fun MetroDirection.stationOrdinals(
    stationCode: String,
    rowCount: Int = trains.size,
    tolerance: Double = MetroPassedTolerance
): MetroStationOrdinals {
    val reference = stationCodes.indexOf(stationCode)
    // p < 0 是位置未知（缺码/未知站）：画不出来，也不参与编号，保持原有丢弃行为。
    // 顺序：p 从大到小；同一坐标沿用 core 的既定次序（ordinal 升序），不另造平局规则。
    val ordered = positionPresentations()
        .filter { it.stationPosition >= 0.0 }
        .sortedWith(
            compareByDescending<MetroPositionPresentation> { it.stationPosition }
                .thenBy { it.ordinal }
        )
    var next = 0
    val trains = ordered.map { presentation ->
        val passed = reference >= 0 && presentation.stationPosition > reference + tolerance
        MetroStationTrain(presentation, if (passed) null else ++next)
    }
    // 行 → 班号：优先按位置记录的唯一关联（arrivalRowOrder），这条路径就是「同一辆车」的证据。
    val linked = trains.mapNotNull { train ->
        val row = train.presentation.arrivalRowOrder ?: return@mapNotNull null
        val ordinal = train.ordinal ?: return@mapNotNull null
        row to ordinal
    }.toMap()
    val rows = LinkedHashMap<Int, Int>(rowCount)
    var running = 0
    for (row in 1..rowCount) {
        // 没有位置记录的那一行（位置批次比到站行短）按上一行顺延：
        // 本站行序本身按到站先后排，比「没有班号」更接近事实。
        val value = linked[row] ?: (running + 1)
        rows[row] = value
        running = value
    }
    return MetroStationOrdinals(reference, trains, rows)
}