package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroPositionPresentation

/**
 * 「到本站先后」投影（用户拍板：**第 1 班 = 最先到达当前参考站的那班**）。
 *
 * 为什么本层要单独投影一次：`core` 也有一个 `MetroDirection.stationOrdinals(...)`，但它是
 * **全线行驶进度序 / 本地 ETA 行号**口径（越靠该方向终点越靠前），对本站来说已经开过本站的车
 * 反而排在最前面；它还把「已过本站」的身份编进 4..N，于是「已过」一个都画不出来。数据与位置
 * 本身不动，只在本层按 `stationPosition` 与参考站下标 `r` 的关系重新编号：
 *
 * 1. `p > r`（含 [MetroPassedTolerance]）→ **已过本站**：仍然画车标，标签写「已过」；
 * 2. 其余按 `p` 从大到小（越大越近本站）依次编号 1..N，**不截断到 3**，连续不跳号；
 * 3. 本站原始到站行（ETA 第 k 行）按 [MetroArrivalOrdinals.ordinalOfRow] 取同一套编号，
 *    与位置条完全一致；某一行没有位置记录时按上一行顺延（行序本身就是到站先后），
 *    不退化成不带班号的「预计到达」。
 *
 * ### 为什么名字叫 `arrivalOrdinals` 而不是 `stationOrdinals`
 *
 * 上一版本文件也叫 `MetroDirection.stationOrdinals`，和 `core` 的同名成员**签名完全一致**。
 * Kotlin 的成员永远优先于扩展函数，所以 UI 侧这份投影被静默遮蔽成了死代码，`已过` 分支不可达
 * ——位置条把 7 辆已过本站的车编成第 4–10 班，就是这么来的。现在的做法是**改名 + 显式调用**：
 * 名字刻意与 `core` 的 `stationOrdinals` 不同（也不同于 `MetroStationPositionOrdinals`），
 * 任何一处 `direction.arrivalOrdinals(...)` 都只能落在本文件这一个实现上，不可能再被遮蔽；
 * `core` 那份保持原样不动，只在 KDoc 里注明它不属于位置条。
 */
internal data class MetroArrivalTrain(
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
internal data class MetroArrivalOrdinals(
    /** 当前参考站在该方向站序里的下标 `r`；-1 表示站序里没有这个站。 */
    val referenceIndex: Int,
    /** 位置已知的车标，按 `p` 从大到小（已过本站的排在最前面，但它们没有班号）。 */
    val trains: List<MetroArrivalTrain>,
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
 * `positionOf` 的返回值按真实 `scale` 连续变化，本站站台上 `flag=2` 的车 `p == r`，
 * **不能**判成已过 → 用严格大于号就正好排除它；而刚开走的那班（本站与下一站之间，真实
 * `scale` 例如 `.30`）必然是 `p > r`。容差必须小于「刚开走时的第一段进度」，故取 0。
 */
internal const val MetroPassedTolerance = 0.0

/**
 * 把该方向的全部位置记录投影成「到本站先后」的班号。
 *
 * @param stationCode 当前参考站（本站）
 * @param rowCount 本站原始到站行数（ETA 蓝块要编号的行数）
 * @param tolerance 判「已过本站」的容差，默认 [MetroPassedTolerance]
 */
internal fun MetroDirection.arrivalOrdinals(
    stationCode: String,
    rowCount: Int = trains.size,
    tolerance: Double = MetroPassedTolerance
): MetroArrivalOrdinals {
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
        MetroArrivalTrain(presentation, if (passed) null else ++next)
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
    return MetroArrivalOrdinals(reference, trains, rows)
}