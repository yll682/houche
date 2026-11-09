package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.MetroEtaState

/** 本页官方状态文案；后台错误详情不替换给乘客看的提示，Ready 仍展示原来的到站列。 */
internal fun metroEtaMessage(state: MetroEtaState): String? = when (state) {
    is MetroEtaState.OutOfService -> "非运营时段，暂时无法提供列车到站查询功能"
    is MetroEtaState.Unavailable -> "暂时无法获取列车到站信息"
    is MetroEtaState.Empty -> "暂无到站列车"
    is MetroEtaState.Loading -> "正在获取"
    is MetroEtaState.Ready -> null
}

internal fun metroEventMessage(event: MetroEvent): String = when (event) {
    is MetroEvent.StationTimetableUnavailable -> "暂时无法获取该站点列车时刻表"
}
