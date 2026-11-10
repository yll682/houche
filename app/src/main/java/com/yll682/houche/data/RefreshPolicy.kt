package com.yll682.houche.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 自动刷新间隔（设置里的「自动刷新间隔」）。默认 15 秒，可选 5/10/15/20/30/60/120 秒；
 * 保存在 SharedPreferences「houche_refresh」的 seconds 里（合法范围 5..120）。
 * 各页面的前台轮询都读 [seconds]，值变了轮询会按新间隔重启。
 */
object RefreshPolicy {
    val options = listOf(5, 10, 15, 20, 30, 60, 120)
    const val DEFAULT_SECONDS = 15

    var seconds by mutableIntStateOf(DEFAULT_SECONDS)
        private set
    private var store: SharedPreferences? = null

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("houche_refresh", Context.MODE_PRIVATE)
        store = prefs
        seconds = prefs.getInt("seconds", DEFAULT_SECONDS).takeIf { it in 5..120 } ?: DEFAULT_SECONDS
    }

    fun set(value: Int) {
        if (value !in 5..120) return
        seconds = value
        store?.edit()?.putInt("seconds", value)?.apply()
    }

    fun millis(): Long = seconds * 1000L
}
