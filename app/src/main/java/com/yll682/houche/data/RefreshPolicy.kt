package com.yll682.houche.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** 0 = 仅手动；可选间隔不短于实时查询缓存的 15 秒窗口。 */
object RefreshPolicy {
    val options = listOf(0, 15, 20, 30, 60, 120)
    const val DEFAULT_SECONDS = 15

    var seconds by mutableIntStateOf(DEFAULT_SECONDS)
        private set
    private var store: SharedPreferences? = null

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("houche_refresh", Context.MODE_PRIVATE)
        store = prefs
        seconds = prefs.getInt("seconds", DEFAULT_SECONDS).takeIf { it in 0..120 } ?: DEFAULT_SECONDS
    }

    fun set(value: Int) {
        if (value !in 0..120) return
        seconds = value
        store?.edit()?.putInt("seconds", value)?.apply()
    }

    fun millis(): Long = if (seconds == 0) Long.MAX_VALUE else seconds * 1000L
}

internal class RefreshBackoff(private val seconds: Int) {
    private var failures = 0

    fun nextMillis(failed: Boolean): Long {
        if (seconds == 0) return Long.MAX_VALUE
        failures = if (failed) (failures + 1).coerceAtMost(2) else 0
        val millis = seconds * 1000L
        return if (failed) (millis * (1 shl failures)).coerceAtMost(60_000L) else millis
    }
}
