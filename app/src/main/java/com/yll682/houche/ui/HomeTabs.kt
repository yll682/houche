package com.yll682.houche.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsBusFilled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Subway
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.yll682.houche.R

// 统一页面索引，避免新增地铁页后导航与页面仍各自沿用旧的三页顺序。
object HomeTab {
    const val BUS = 0
    const val METRO = 1
    const val FAVORITES = 2
    const val SETTINGS = 3
    const val COUNT = 4
}

data class HomeTabSpec(
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector
)

// 文案随组合读取资源，语言配置变化时导航标签也能同步更新。
@Composable
fun homeTabs(): List<HomeTabSpec> = listOf(
    HomeTabSpec(
        stringResource(R.string.bus),
        Icons.Outlined.DirectionsBus,
        // 选中态用同一枚巴士字形的 filled 版本：外轮廓与轮廓版一致，只是车身填实。
        // Icons.Filled.DirectionsBus 是另一枚实心剪影，一选中就「换了辆车」，与另外三枚不一致。
        Icons.Filled.DirectionsBusFilled
    ),
    HomeTabSpec(
        stringResource(R.string.metro),
        Icons.Outlined.Subway,
        Icons.Filled.Subway
    ),
    HomeTabSpec(
        stringResource(R.string.favorites),
        Icons.Outlined.StarBorder,
        Icons.Filled.Star
    ),
    HomeTabSpec(
        stringResource(R.string.settings),
        Icons.Outlined.Settings,
        Icons.Filled.Settings
    )
)
