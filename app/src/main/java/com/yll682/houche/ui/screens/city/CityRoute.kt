@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.yll682.houche.ui.screens.city

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.*
import androidx.compose.ui.window.*
import com.yll682.houche.core.City
import com.yll682.houche.ui.components.*

@Composable fun CityRoute(cities: List<City>, located: City?, dismiss: () -> Unit, error: String? = null, failed: Boolean = false, retry: () -> Unit = {}, choose: (City) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var province by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(province != null && query.isBlank()) { province = null }
        CitySheet(CityData(cities, located, query, province, error, failed), dismiss, retry, choose, { query = it }, { province = it })
    }
}
