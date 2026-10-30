package com.yll682.houche.ui.screens.settings

import com.yll682.houche.AppGraph
import com.yll682.houche.BuildConfig
import com.yll682.houche.core.City
import com.yll682.houche.ui.StateViewModel

data class SettingsData(val city: City, val cleared: Boolean = false, val versionName: String = BuildConfig.VERSION_NAME, val versionCode: Int = BuildConfig.VERSION_CODE)

class SettingsViewModel(private val graph: AppGraph, city: City) : StateViewModel<SettingsData>(SettingsData(city)) {
    fun clearCache() = action {
        update(data.copy(cleared = false))
        graph.repository().clearCache()
        update(data.copy(cleared = true))
        kotlinx.coroutines.delay(4_000)
        update(data.copy(cleared = false))
    }
}
