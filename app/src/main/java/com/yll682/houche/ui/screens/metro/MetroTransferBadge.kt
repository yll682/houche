package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroTransferTarget
import com.yll682.houche.ui.theme.Capsule

/** 换乘徽标的命中区高度下限（任务书：≥32dp；视觉胶囊本身比它小一圈）。 */
internal val TransferBadgeTapHeight = 32.dp

/**
 * 换乘徽标：一个目标一枚小胶囊，**底色用目标线路自己的官方主题色**（`MetroTransferTarget.color`，
 * 即与被换乘线路 tab 上那个色块同一个色值），字色按底色亮度在深墨/白字之间自动切（[onLineColor]）。
 *
 * 数据只认 `station.transferTargets`（Codex 第五轮从官方共站码推导出来的定向目标），
 * 不从「换乘 2 号线」这种文案反解析线路，也不拿共站码去请求——目标线路有自己的 `stationCode`。
 *
 * 点击把**完整目标**交给上层（`vm.selectTransfer(lineId, stationCode)`）；目标无效时数据层返回
 * false 并保持当前选择，UI 这边不弹错、不改视觉，按下去只有一次常规水波纹反馈。
 */
@Composable
internal fun TransferBadges(
    targets: List<MetroTransferTarget>,
    onSelect: (MetroTransferTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    if (targets.isEmpty()) return
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        targets.forEach { target -> TransferBadge(target, onSelect) }
    }
}

@Composable
private fun TransferBadge(target: MetroTransferTarget, onSelect: (MetroTransferTarget) -> Unit) {
    val type = officialTypography()
    // 颜色全部来自站表：目标线路色解析失败时退回官方兜底色，不在这里写死任何线路色。
    val fill = metroColor(target.color)
    val ink = onLineColor(fill)
    val label = transferBadgeLabel(target)
    val a11y = stringResource(R.string.metro_transfer_jump, target.lineName)
    Box(
        Modifier.heightIn(min = TransferBadgeTapHeight)
            .clickable { onSelect(target) }
            .semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.clip(Capsule).background(fill).padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            // 小、圆角、不加粗过重：与官方那套字级一致（11sp MetaLabel），只把颜色换成线路色。
            Text(label, style = type.MetaLabel.copy(color = ink, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/**
 * 胶囊里只放线路号（内置站表是 1/2/3/4/6），太长或不是数字的线路标识退回线路名，
 * 不给窄屏塞「二十一号线」这种超宽字串。
 */
private fun transferBadgeLabel(target: MetroTransferTarget): String {
    val id = target.lineId.trim()
    return if (id.isNotEmpty() && id.length <= 2 && id.all { it in '0'..'9' }) id else target.lineName
}