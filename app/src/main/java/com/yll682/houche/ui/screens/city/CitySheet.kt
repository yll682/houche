package com.yll682.houche.ui.screens.city

import androidx.compose.runtime.Composable
import com.yll682.houche.core.City

data class CityData(val cities: List<City>, val located: City? = null, val query: String = "", val province: String? = null, val error: String? = null, val failed: Boolean = false)

@Composable fun CitySheet(state: CityData, dismiss: () -> Unit, retry: () -> Unit, choose: (City) -> Unit, queryChange: (String) -> Unit, provinceChange: (String?) -> Unit) {
    CityContent(state, dismiss, retry, choose, queryChange, provinceChange)
}
