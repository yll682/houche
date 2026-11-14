package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.MetroTrainIdentityConfidence
import com.yll682.houche.core.minutesUntil
import com.yll682.houche.core.projectMetroArrival

/**
 * 「点某列车 + 某站 → 还有多久到」的 UI 侧解析与口径。
 *
 * 这一层只做两件事：
 *
 * 1. 把用户选中的车标解析成**当前这一批观测里**的那班车；
 * 2. 把 core 的 [projectMetroArrival] 推算结果翻译成展示用的分钟数与文案口径。
 *
 * **不写回 core 的 ETA 行**：推算值只出现在实时卡片底部那一行和站序弹层里，
 * 绝不进 `MetroEtaState.Ready.trains`（那是本站接口的官方读数，core 的
 * `hasStationArrivals` 是它的护栏）。
 */

/**
 * 位置条上被选中的那班列车。
 *
 * [markerKey] 是位置条动画用的身份键（core 的全线进度序），**不是车辆 ID**：
 * 接口没有车辆 ID，它只是同一批观测内的呈现身份。刷新后它可能指向另一班车，
 * 所以同时记下选中时的下一站码 [nextStationCode]：对不上就当这次选择已经过期，
 * 绝不把别的车的推算显示成用户选的那辆。
 */
data class MetroSelectedTrain(
    val markerKey: Int,
    val nextStationCode: String
)

/** 选中车标在当前参考站下的身份（班号等）；选择过期或没有实时方向时为 null。 */
internal data class MetroSelectedMarker(
    /** 位置条上的车标身份。 */
    val trainKey: Int,
    /** 到本站先后班号；null = 该车已过本站（位置条标签写「已过」，不占班号）。 */
    val ordinal: Int?,
    /** 选中时的下一站码（用于刷新后校验身份）。 */
    val nextStationCode: String,
    /** 选中的那班车本身：推算公式的输入（它自己的 source/arriveSeconds）。 */
    val train: MetroTrain
)

/**
 * 选中列车到某个目标站的推算结果（**不是**目标站接口的官方读数）。
 *
 * 口径全部来自 core 的 [projectMetroArrival]：`ETA(S) = train.arriveSeconds + C[S] − C[R]`，
 * R 是该车自己那次观测的参考站，秒数相对**那次观测的采样时刻**（不替旧观测续命）。
 */
internal data class MetroTrainEtaProjection(
    /** 位置条上的车标身份。 */
    val trainKey: Int,
    /** 到本站先后班号；null = 该车已过本站。 */
    val ordinal: Int?,
    val targetStationCode: String,
    val targetStationName: String,
    /** 推算分钟（向上取整，至少 1 分钟）；null = 推算失败，**不要显示数字**。 */
    val minutes: Int?,
    /** 目标站已被该车驶过（core 口径：推算 ETA 为负）。 */
    val passed: Boolean,
    /** 跨参考站的近似身份匹配：口径要写得更保守（「推算·约」）。 */
    val approximate: Boolean
)

/**
 * 解析当前选中的车标；选择过期（刷新后 markerKey 指向别的车）时返回 null。
 */
internal fun metroSelectedMarker(
    direction: MetroDirection?,
    referenceStationCode: String,
    selectedTrain: MetroSelectedTrain?
): MetroSelectedMarker? {
    val current = selectedTrain ?: return null
    val dir = direction ?: return null
    val marker = dir.arrivalOrdinals(referenceStationCode).trains
        .firstOrNull { it.markerKey == current.markerKey } ?: return null
    // markerKey 只是同一批观测内的身份：刷新后它可能指向别的车，下一站码对不上就作废这次选择。
    if (current.nextStationCode.isNotBlank() && marker.presentation.train.nextStationCode != current.nextStationCode) return null
    return MetroSelectedMarker(
        trainKey = marker.markerKey,
        ordinal = marker.ordinal,
        nextStationCode = marker.presentation.train.nextStationCode,
        train = marker.presentation.train
    )
}

/** 当前被选中的车标 key（markerKey）；没有选择或选择已过期时为 null。 */
internal fun metroSelectedTrainKey(
    direction: MetroDirection?,
    referenceStationCode: String,
    selectedTrain: MetroSelectedTrain?
): Int? = metroSelectedMarker(direction, referenceStationCode, selectedTrain)?.trainKey

/**
 * 选中列车到目标站的推算。
 *
 * 返回 null 表示「还推不出来」：没选车、没选目标站、没有实时方向，或选择已过期。
 * 推算本身失败（方向不对 / 缺运行资料 / 累计秒缺失）时返回**非 null 但 minutes 为 null**
 * 的结果，界面据此显示中性文案，而不是 0 或假数字。
 */
internal fun metroTrainEtaProjection(
    direction: MetroDirection?,
    referenceStationCode: String,
    selectedTrain: MetroSelectedTrain?,
    targetStationCode: String,
    stationNameOf: (String) -> String
): MetroTrainEtaProjection? = metroTrainEtaProjectionOf(
    direction, metroSelectedMarker(direction, referenceStationCode, selectedTrain), targetStationCode, stationNameOf
)

/** 同上，但车标已经解析好（界面里同一帧只解析一次，省掉重复的班号投影）。 */
internal fun metroTrainEtaProjectionOf(
    direction: MetroDirection?,
    marker: MetroSelectedMarker?,
    targetStationCode: String,
    stationNameOf: (String) -> String
): MetroTrainEtaProjection? {
    if (targetStationCode.isBlank()) return null
    val current = marker ?: return null
    val dir = direction ?: return null
    val name = stationNameOf(targetStationCode)
    val projection = projectMetroArrival(dir, current.train, targetStationCode, name)
    return MetroTrainEtaProjection(
        trainKey = current.trainKey,
        ordinal = current.ordinal,
        targetStationCode = targetStationCode,
        targetStationName = name.ifBlank { targetStationCode },
        // 已过的车不给分钟数：界面写「已过该站」，不写「−3 分钟」。
        minutes = projection?.takeIf { !it.passed }?.let { minutesUntil(it.arriveSeconds) },
        passed = projection?.passed == true,
        approximate = projection?.identityConfidence == MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH
    )
}

/**
 * 实时卡片底部那一行：选中列车 → 目标站的到站时间。
 *
 * 四种状态：
 *
 * - 没选车 → 引导「点列车图标可看它到站时间」（不留空白）；
 * - 选了车但没选目标站 → 提示去点站名；
 * - 推算成功 → 「第 N 班 → 站名：约 X 分钟（推算）」；
 * - 已过 / 推算失败 → 「已过该站」/「站数未知」，都不给假数字。
 */
@Composable
internal fun MetroTrainEtaRow(
    direction: MetroDirection?,
    referenceStationCode: String,
    selectedTrain: MetroSelectedTrain?,
    targetStationCode: String,
    stationNameOf: (String) -> String,
    modifier: Modifier = Modifier
) {
    val palette = officialPalette()
    val type = officialTypography()
    val marker = remember(direction, referenceStationCode, selectedTrain) {
        metroSelectedMarker(direction, referenceStationCode, selectedTrain)
    }
    val projection = remember(direction, marker, targetStationCode, stationNameOf) {
        metroTrainEtaProjectionOf(direction, marker, targetStationCode, stationNameOf)
    }
    // 班号与位置条同一套「到本站先后」编号；已过本站的车没有班号。
    val trainLabel = marker?.ordinal?.let { stringResource(R.string.metro_realtime_train_no, it) }
        ?: stringResource(R.string.metro_train_eta_train_passed)
    val text: String
    val estimated: Boolean
    when {
        marker == null -> {
            text = stringResource(R.string.metro_train_eta_hint)
            estimated = false
        }
        projection == null -> {
            text = stringResource(R.string.metro_train_eta_pick_station, trainLabel)
            estimated = false
        }
        projection.minutes != null && projection.approximate -> {
            text = stringResource(
                R.string.metro_train_eta_row_approximate,
                trainLabel, projection.targetStationName, projection.minutes
            )
            estimated = true
        }
        projection.minutes != null -> {
            text = stringResource(
                R.string.metro_train_eta_row, trainLabel, projection.targetStationName, projection.minutes
            )
            estimated = true
        }
        projection.passed -> {
            text = stringResource(R.string.metro_train_eta_row_passed, trainLabel, projection.targetStationName)
            estimated = false
        }
        else -> {
            text = stringResource(R.string.metro_train_eta_row_unknown, trainLabel, projection.targetStationName)
            estimated = false
        }
    }
    Row(
        modifier.fillMaxWidth().padding(start = 25.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)
            .testTag("metro-train-eta"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = if (estimated) type.EtaLabel else type.MetaLabel,
            maxLines = 2
        )
    }
}