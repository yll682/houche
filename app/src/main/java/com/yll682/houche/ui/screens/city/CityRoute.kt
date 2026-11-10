@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.yll682.houche.ui.screens.city

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yll682.houche.core.City
import com.yll682.houche.ui.components.*

@Composable fun CityRoute(cities: List<City>, located: City?, dismiss: () -> Unit, error: String? = null, failed: Boolean = false, retry: () -> Unit = {}, choose: (City) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var province by rememberSaveable { mutableStateOf<String?>(null) }
    // 不再用 Dialog：直接铺在应用全屏窗口里，状态栏/导航栏的处理和其它页面一致。
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(250)) }
    BackHandler { if (province != null && query.isBlank()) province = null else dismiss() }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 48.dp.toPx() }
    ) {
        CitySheet(CityData(cities, located, query, province, error, failed), dismiss, retry, choose, { query = it }, { province = it })
    }
}
