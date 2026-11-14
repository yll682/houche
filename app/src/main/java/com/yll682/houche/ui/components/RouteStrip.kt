package com.yll682.houche.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.R
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.ui.theme.Capsule

/**
 * 横向站点图：线路页与车辆详情页共用同一个组件，只吃 core 模型 + 回调，不依赖任何 ViewModel。
 *
 * - [highlight]：高亮站。线路页＝乘车站，车辆页＝下车提醒站。它同时是三件事的基准：
 *   ①高亮样式；②「已过站」灰化基准；③进入页面时的自动滚动目标。
 * - [focusedPlate]：要强调的那辆车（车牌）。车辆页用它突出当前这辆车；
 *   命中时车标加底色，其余车标保持原样。它同时给出车辆页「已过站」的灰化基准（车前面的站已经开过）。
 * - [onStationClick]：点站（线路页＝看这站的到站，车辆页＝选下车提醒站）。
 * - [onVehicleClick]：点车标（线路页传 null 保持不可点，车辆页用来切换车辆）。
 *
 * 站名一律**整串竖排、一字一行**，绝不按字数拆成两列：名字越长，这一列只往上长（盒子变高），
 * 列宽始终是一个字宽（48dp 的列），两列竖排那种「第一门医院｜海沧院区」的观感已经去掉。
 */
@Composable
fun RouteStrip(
    route: Route,
    vehicles: List<Vehicle>,
    modifier: Modifier = Modifier,
    /** 高亮站：线路页=乘车站；车辆页=下车提醒站。 */
    highlight: RouteStation? = null,
    /** 需要强调的那辆车（车牌）；车辆页用来突出当前车。 */
    focusedPlate: String? = null,
    onStationClick: ((RouteStation) -> Unit)? = null,
    onVehicleClick: ((Vehicle) -> Unit)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val listState = rememberLazyListState()
    // 列宽固定 48dp（一屏约 7 站）；站名再长也不加宽，只加高。
    val column = 48.dp
    // 焦点车（车辆页正在看的那辆车）的站序即「已过站」基准：它前面的站车已经开过。
    // 线路页没有焦点车（focusedPlate 为 null），仍以高亮站（乘车站）为基准，行为与从前一致。
    val focused = focusedPlate?.let { plate -> vehicles.firstOrNull { it.plate == plate } }
    val passedFrom = focused?.order ?: highlight?.order
    LaunchedEffect(route.name, route.direction) {
        // 自动滚动目标：优先高亮站，其次焦点车，最后全线第一辆车。
        val target = highlight
            ?: focused?.order?.let { order -> route.stations.firstOrNull { it.order == order } }
            ?: vehicles.firstOrNull()?.order?.let { order -> route.stations.firstOrNull { it.order == order } }
        // 留一列余量：途中车辆要画在「两站之间」，贴左边缘会被裁掉半个图形。
        val index = (route.stations.indexOf(target) - 1).coerceAtLeast(0)
        if (route.stations.isNotEmpty()) listState.scrollToItem(index)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.testTag("route-strip")
    ) {
        itemsIndexed(route.stations, key = { index, stop -> "${stop.order}-${stop.name}-$index" }) { index, stop ->
            val active = stop == highlight
            val buses = vehicles.filter { it.order != null && it.order == stop.order }
            val passed = passedFrom?.let { (stop.order ?: it) < it } == true
            val description = if (buses.isEmpty()) stop.name else stringResource(R.string.stop_vehicles_accessibility, stop.name, buses.size)
            Box(Modifier.width(column).heightIn(min = 152.dp)) {
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (onStationClick != null) Modifier.clickable(role = Role.Button) { onStationClick(stop) } else Modifier)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 车标画在下面的浮层里（同一高度带），这里只占位，站序/圆点/站名的位置一字不动。
                    Spacer(Modifier.height(26.dp))
                    Text(stop.order?.toString().orEmpty(), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall, color = if (active) primary else MaterialTheme.colorScheme.outline)
                    Box(Modifier.fillMaxWidth().height(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                        Box(Modifier.align(if (index == 0) Alignment.CenterEnd else Alignment.CenterStart)
                            .fillMaxWidth(if (index == 0 || index == route.stations.lastIndex) .5f else 1f).height(4.dp).background(primary.copy(alpha = .28f)))
                        Box(Modifier.size(15.dp).clip(Capsule).background(if (active) primary else MaterialTheme.colorScheme.surface)
                            .border(if (buses.isNotEmpty()) 2.dp else 1.5.dp, primary.copy(alpha = if (active || buses.isNotEmpty()) 1f else .35f), Capsule), contentAlignment = Alignment.Center) {
                            if (active) Box(Modifier.size(5.dp).clip(Capsule).background(Color.White))
                        }
                    }
                    // 竖排站名：整串一字一行，**不拆列**。名字越长，这一列只往上长。
                    val nameColor = if (active) primary else if (passed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                    Text(
                        verticalStationName(stop.name),
                        Modifier.clearAndSetSemantics {},
                        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 18.sp),
                        textAlign = TextAlign.Center,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = nameColor
                    )
                }
                // 车标浮层：与站点列分开摆放，点车标＝切换车辆；语义也不被站点的合并节点吃掉。
                Box(Modifier.align(Alignment.TopCenter).height(26.dp).fillMaxWidth()) {
                    if (buses.isNotEmpty()) {
                        val focusedBus = buses.firstOrNull { it.plate == focusedPlate }
                        val anchor = focusedBus ?: buses.first()
                        // 车的位置就是状态：到站停在该站正上方（正脸），途中偏左半格落在两站之间（侧视）。
                        val running = anchor.positionState != "at-stop"
                        val plate = anchor.plate.ifBlank { stringResource(R.string.no_plate) }
                        val where = anchor.currentStation?.takeIf { it.isNotBlank() }
                            ?: anchor.nextStation?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.unknown_position)
                        val vehicleLabel = stringResource(R.string.vehicle_position, plate, where)
                        Row(
                            Modifier.align(Alignment.Center).offset(x = if (running) -(column / 2f) else 0.dp)
                                .then(if (onVehicleClick != null) Modifier.clickable { onVehicleClick(anchor) } else Modifier)
                                .then(
                                    if (onVehicleClick != null) Modifier.semantics { contentDescription = vehicleLabel }
                                    else Modifier.clearAndSetSemantics {}
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (focusedBus != null) {
                                // 焦点车加一圈底色，其余车标保持原样，一眼分得清哪辆是正在看的那辆。
                                Box(Modifier.size(22.dp).clip(CircleShape).background(primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(16.dp), tint = primary)
                                }
                            } else {
                                Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(18.dp), tint = primary)
                            }
                            if (buses.size > 1) {
                                Text(buses.size.toString(), Modifier.padding(start = 1.dp),
                                    style = MaterialTheme.typography.labelSmall, color = primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 站名整串竖排：一字一行，**不拆列**。
 *
 * 用户明确要求「站名太长不换行，继续增长那个框」：9 个字（如「第一门医院海沧院区」）就是 9 行一列，
 * 盒子只往上长；不会再出现「第一门医院｜海沧院区」那种并排两列。
 * 按码点切，emoji 等代理对不会被劈成两行。
 */
internal fun verticalStationName(name: String): String =
    name.codePoints().toArray().joinToString("\n") { codePoint -> String(Character.toChars(codePoint)) }