package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.yll682.houche.R
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.minutesUntil

/**
 * ETA 蓝块（规格 2.5）：`#ECF2FF`、300×73.7dp、圆角 4dp、无描边无阴影，
 * 三等分、每列「20sp 数字+分钟 / 11.5sp 第N班预计到达」。
 *
 * 两处**故意**照抄官方：①内容偏上（上内距 13dp、下内距 28.7dp，都是**墨迹**到块边），不居中；
 * ②列序反着排——最右一列是最前面那班（官方截图左 16分钟/中 9分钟/右 3分钟）。
 *
 * **容器永远在**：绑定的是 [MetroEtaState]，不再是「trains 为空就整块 return」。
 * 切站瞬间整块不消失，只是换成占位文案；三种非 Ready 状态各自一句话，
 * 失败/该站尚未采集（Unavailable）与「确实没有到站列车」（Empty）不能混为一谈。
 *
 * 分钟数只来自所选参考站原始的三条到站行（`Ready.trains`），
 * 位置条上补进来的车不参与这里，因此不会有「别站的 ETA 冒充本站」。
 * 「第N班」用的是**全方向班序**：由 `ordinalOfRow` 把本站第 k 行映射到唯一的
 * `presentation.ordinal`；关联不唯一时只写「预计到达」，不拿本站第 k 行冒充全方向第 k 班。
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
        modifier.padding(horizontal = 20.dp).fillMaxWidth().height(73.7.dp)
            .clip(OfficialEtaShape).background(palette.EtaBackground)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (etaState) {
            is MetroEtaState.Ready -> {
                val rows = etaState.trains.take(3)
                // 列序反排：最右一列是最前面那班。
                rows.reversed().forEachIndexed { index, train ->
                    val order = rows.size - index
                    // 全方向班序优先；没有唯一关联就退成不带数字的「预计到达」。
                    val ordinal = ordinalOfRow(order)
                    val minutes = minutesUntil(train.arriveSeconds)
                    val detail = ordinal?.let { stringResource(R.string.metro_position_eta, it) }
                        ?: stringResource(R.string.metro_position_eta_title)
                    // 两行各自贴边：大数字 ink 顶距块顶 13dp，标签 ink 底距块底 28.7dp。
                    // 不能让它们顺排——大数字的行盒比墨迹高 1.3dp，「第N班」会被顶低 1.7dp。
                    Box(
                        Modifier.weight(1f).fillMaxHeight().fillMaxWidth()
                            .clearAndSetSemantics { contentDescription = "$minutes 分钟$detail" }
                    ) {
                        Row(Modifier.align(Alignment.TopCenter).padding(top = EtaNumberBoxTop)) {
                            Text("$minutes", style = type.EtaNumber, maxLines = 1, modifier = Modifier.alignByBaseline())
                            Text(
                                stringResource(R.string.metro_position_minutes),
                                style = type.EtaUnit,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline()
                            )
                        }
                        Text(
                            detail,
                            Modifier.align(Alignment.BottomCenter).padding(bottom = EtaLabelBoxBottom),
                            style = type.EtaLabel,
                            maxLines = 1
                        )
                    }
                }
            }
            // 非 Ready：容器不变，只换一句话。
            else -> Text(
                etaPlaceholder(etaState),
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                style = type.EtaLabel,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/** 三种非 Ready 状态各自的占位文案；Unavailable 绝不能显示成 Empty。 */
@Composable
private fun etaPlaceholder(state: MetroEtaState): String = when (state) {
    is MetroEtaState.Loading -> stringResource(R.string.metro_position_eta_loading)
    is MetroEtaState.Empty -> stringResource(R.string.metro_position_eta_empty)
    is MetroEtaState.Unavailable -> stringResource(R.string.metro_position_eta_unavailable)
    is MetroEtaState.Ready -> stringResource(R.string.metro_position_eta_title)
}

/**
 * 规格 2.5 的两个内距量的都是**墨迹**到块边的距离：上 39px＝13dp（大数字 ink 顶）、
 * 下 86px＝28.7dp（「第N班预计到达」ink 底）。行盒里 ink 上下还各留一段空白，
 * 落版时要把它们换回 padding：数字 ink 距本行行盒顶 13px≈4.3dp，
 * 标签行盒底到 ink 底 9px＝3dp。
 */
private val EtaInkTop = 13.dp
private val EtaInkBottom = 28.7.dp

/** 大数字行盒里 ink 顶偏移 13px ÷ 3。 */
private val EtaNumberInkInset = 4.3.dp

/** 标签行盒底到 ink 底 9px ÷ 3。 */
private val EtaLabelInkInset = 3.dp

/** 大数字行盒距块顶：13 − 4.3 ＝ 8.7dp（ink 顶正好落在 13dp）。 */
private val EtaNumberBoxTop = EtaInkTop - EtaNumberInkInset

/** 标签行盒距块底：28.7 − 3 ＝ 25.7dp（ink 底正好落在 28.7dp）。 */
private val EtaLabelBoxBottom = EtaInkBottom - EtaLabelInkInset