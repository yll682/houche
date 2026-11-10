package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.R
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.minutesUntil

/**
 * ETA 蓝块（规格 2.5）：`#ECF2FF`、300×73.7dp、圆角 4dp、无描边无阴影，
 * 三等分、每列「20sp 数字+分钟 / 11.5sp 第N班预计到达」。
 *
 * 2.0.x 样式：高度至少 96dp、圆角 12dp，三列等分、按原顺序排（第 1 班在最左）。
 *
 * **容器永远在**：绑定的是 [MetroEtaState]，不再是「trains 为空就整块 return」。
 * 切站瞬间整块不消失，只是换成占位文案；四种非 Ready 状态各自一句话，
 * 失败/该站尚未采集（Unavailable）与「确实没有到站列车」（Empty）不能混为一谈。
 *
 * 分钟数只来自所选参考站原始的三条到站行（`Ready.trains`），
 * 位置条上补进来的车不参与这里，因此不会有「别站的 ETA 冒充本站」。
 * 「第N班」用的是**到本站先后**的班号（用户决定：第 1 班 = 最先到本站那班）：
 * 由 `ordinalOfRow` 把本站第 k 行映射到位置条上同一个班号（见 UI 侧的 `arrivalOrdinals`），
 * 于是「第 k 行」与位置条「第 k 班」永远是同一辆车。只有整条位置批次都关联不上时
 * 才会只写「预计到达」，不拿本站第 k 行去冒充别的班。
 */
@Composable
internal fun MetroEtaBlock(
    etaState: MetroEtaState,
    ordinalOfRow: (Int) -> Int? = { null },
    modifier: Modifier = Modifier
) {
    val palette = officialPalette()
    val type = officialTypography()
    val title = stringResource(R.string.metro_position_eta_title)
    Row(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 96.dp)
            .clip(OfficialEtaShape).background(palette.EtaBackground)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (etaState) {
            is MetroEtaState.Ready -> {
                // 2.0.x：按原顺序排，第 1 班在最左。
                val rows = etaState.trains.take(3)
                rows.forEachIndexed { index, train ->
                    val order = index + 1
                    // 全方向班序优先；没有唯一关联就退成不带数字的「预计到达」。
                    val ordinal = ordinalOfRow(order)
                    val minutes = minutesUntil(train.arriveSeconds)
                    val detail = ordinal?.let { stringResource(R.string.metro_position_eta, it) }
                        ?: stringResource(R.string.metro_position_eta_title)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 16.dp)
                            .clearAndSetSemantics { contentDescription = "$minutes 分钟$detail" },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row {
                            Text("$minutes", style = type.EtaNumber, maxLines = 1, modifier = Modifier.alignByBaseline())
                            Text(
                                stringResource(R.string.metro_position_minutes),
                                style = type.EtaUnit,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline().padding(start = 2.dp)
                            )
                        }
                        Text(
                            detail,
                            Modifier.padding(top = 4.dp),
                            style = type.EtaLabel,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                }
            }
            else -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, null, Modifier.size(20.dp).clearAndSetSemantics {}, tint = palette.TabIndicator)
                Spacer(Modifier.width(8.dp))
                Text(
                    requireNotNull(metroEtaMessage(etaState)),
                    style = type.EtaLabel.copy(
                        color = palette.TabIndicator,
                        fontSize = 16.sp,
                        lineHeight = 16.sp
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}
