package com.yll682.houche.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.logging.Level

/** 一次地铁页加载的展示资料；内置快照只在在线响应到达前或失败时使用。 */
data class MetroSnapshot(val network: MetroNetwork?, val online: Boolean = false, val note: String = "")

/** 在线请求先启动，读取内置快照不阻塞它；在线成功后整份替换，不与旧站序合并。 */
class MetroLoader(
    private val fallback: suspend (City) -> MetroNetwork?,
    private val latest: suspend () -> MetroProbe,
    private val hasMetro: suspend (City) -> Boolean
) {
    fun load(city: City): Flow<MetroSnapshot> = flow {
        coroutineScope {
            // 仅查轻量城市索引；UNDISPATCHED 保证先进入在线调用，再读取完整内置站表。
            val request = if (hasMetro(city)) async(start = CoroutineStart.UNDISPATCHED) { latest() } else null
            val bundled = try {
                fallback(city)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                logger.log(Level.FINE, "地铁内置资料读取失败", error)
                null
            }
            if (bundled != null) emit(MetroSnapshot(bundled))
            val probe = request?.await()
            if (probe != null && probe.online && probe.lines.any { it.stations.isNotEmpty() }) {
                val network = MetroNetwork(
                    city = city.name,
                    cityKey = city.key,
                    source = "厦门地铁官方接口 pub/station/allStation",
                    lines = probe.lines
                )
                // 在线响应没有发布日期，不沿用内置快照的日期以免误导。
                emit(MetroSnapshot(network, online = true, note = probe.note))
            } else if (bundled == null) {
                emit(MetroSnapshot(null))
            }
            // 已有内置展示时，失败不再发出状态更新，界面保持原样。
        }
    }
}
