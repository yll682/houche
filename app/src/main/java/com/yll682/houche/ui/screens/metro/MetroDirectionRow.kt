package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yll682.houche.R

/**
 * 方向行（2.0.x）：左边「开往 终点」（titleMedium）下面一行起点（bodySmall、次要色），
 * 右边是只有图标的「反向」按钮（Outlined.SwapHoriz，主题主色，只有一个以上方向时可点）。
 */
@Composable
internal fun MetroDirectionRow(
    origin: String,
    destination: String,
    canSwap: Boolean,
    onSwap: () -> Unit
) {
    val palette = officialPalette()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.to, destination),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(origin, style = MaterialTheme.typography.bodySmall, color = palette.SubInk, maxLines = 1)
        }
        IconButton(onSwap, Modifier.size(48.dp), enabled = canSwap) {
            Icon(
                Icons.Outlined.SwapHoriz, stringResource(R.string.reverse), Modifier.size(24.dp),
                tint = if (canSwap) palette.TabIndicator else palette.TabIndicator.copy(alpha = .38f)
            )
        }
    }
}
