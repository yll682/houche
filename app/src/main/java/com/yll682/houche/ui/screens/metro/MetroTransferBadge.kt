package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroTransferTarget

/** 换乘徽标的命中区高度下限（任务书：≥32dp；徽标本身是 19.3dp 的方块，比它小一圈）。 */
internal val TransferBadgeTapHeight = 32.dp

/**
 * 换乘徽标：一个目标一枚**共享线路徽标**（[MetroLineBadge]）。
 *
 * 2026-11-08 统一前这里是「胶囊 + 11sp 文字」，和旁边那枚 13.3dp 的圆形线路号徽标
 * 完全是两套设计语言（用户反馈：两个数字明显不一样大）。现在它和列表里的「本站线路号」
 * 都用同一个共享组件、同一档尺寸（[SmallBadgeSize] 19.3dp、圆角 2.3dp、白字），
 * 并排出现时是同一套规格。
 *
 * 底色用目标线路自己的官方主题色（`MetroTransferTarget.color`，即与被换乘线路 tab 上
 * 那个色块同一个色值），字色由共享组件写死为白（官方徽标实测就是白字）。
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
    // 颜色全部来自站表：目标线路色解析失败时退回官方兜底色，不在这里写死任何线路色。
    val fill = metroColor(target.color)
    val digit = transferBadgeDigit(target)
    val a11y = stringResource(R.string.metro_transfer_jump, target.lineName)
    Box(
        Modifier.heightIn(min = TransferBadgeTapHeight)
            .clickable { onSelect(target) }
            .semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center
    ) {
        // 徽标本体与列表里的「本站线路号」完全同款：19.3dp 圆角方块 + 大数字 + 竖排「号线」。
        MetroLineBadge(SmallBadgeSize, digit, fill)
    }
}

/**
 * 徽标里那个大数字：内置站表的线路号都是 1/2/3/4/6，直接用线路号；
 * 不是数字的线路标识退回线路名的第一个字，不给窄屏塞「二十一号线」这种超宽字串。
 */
private fun transferBadgeDigit(target: MetroTransferTarget): String {
    val id = target.lineId.trim()
    return if (id.isNotEmpty() && id.length <= 2 && id.all { it in '0'..'9' }) id
    else target.lineName.trim().take(1)
}