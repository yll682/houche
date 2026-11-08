package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.logging.Level

private const val METRO_REALTIME_PATH = "pub/transTime/getTransTimes"

/** 官方实时到站客户端；每次取最新两方向信息，失败只返回诊断，不缓存旧列车。 */
class MetroRealtimeRemote(private val http: HttpClient = BusNetwork.client) {
    suspend fun latest(lineCode: String, stationCode: String = ""): MetroRealtime = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(MetroGateway.TIMEOUT_MILLIS) { request(lineCode, stationCode) }
            ?: unavailable("地铁实时到站请求超时", lineCode, stationCode)
        logger.log(Level.FINE, result.note)
        result
    }

    private suspend fun request(lineCode: String, stationCode: String): MetroRealtime {
        val query = buildMap {
            put("lineCode", lineCode)
            if (stationCode.isNotEmpty()) put("stationCode", stationCode)
        }
        // 与站表一致，仅临时网络故障重试一次，两次合计仍受八秒总超时约束。
        for (attempt in 0..1) {
            try {
                val response = MetroGateway.post(http, METRO_REALTIME_PATH, query)
                val status = response.status.value
                if (status !in 200..299) {
                    if (attempt == 0 && (status == 429 || status >= 500)) continue
                    return unavailable("地铁实时接口返回 HTTP $status", lineCode, stationCode)
                }
                return parse(MetroGateway.responseText(response), lineCode, stationCode)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt == 0 && (error is IOException || error is HttpRequestTimeoutException)) continue
                logger.log(Level.FINE, "地铁实时到站请求失败", error)
                return unavailable("地铁实时到站请求失败：${error.javaClass.simpleName}", lineCode, stationCode)
            }
        }
        error("无法到达的请求分支")
    }

    /** 外层可能只有密文；内层必须显式成功，有方向但暂时没有列车仍是有效结果。 */
    internal fun parse(text: String, lineCode: String = "", stationCode: String = ""): MetroRealtime {
        val outer = runCatching { BusNetwork.json.parseToJsonElement(text) }.getOrNull()
            ?: return unavailable("响应不是合法 JSON", lineCode, stationCode)
        if (outer is JsonObject && "code" in outer && outer.i("code") != 0) return rejected(outer, "网关拒绝", lineCode, stationCode)
        val encrypted = outer.s("responseData")
        if (encrypted.isBlank()) return unavailable("响应没有数据体", lineCode, stationCode)
        val plain = MetroGateway.decrypt(encrypted) ?: return unavailable("响应数据解密失败", lineCode, stationCode)
        val inner = runCatching { BusNetwork.json.parseToJsonElement(plain) }.getOrNull()
            ?: return unavailable("解密后的数据不是合法 JSON", lineCode, stationCode)
        if (inner.i("code") != 0) return rejected(inner, "实时接口报错", lineCode, stationCode)
        val entries = inner.list("data")
        val directions = entries.mapNotNull(::parseDirection)
        if (directions.isEmpty()) return unavailable("接口未返回可识别的行车方向", lineCode, stationCode)
        val station = entries.firstOrNull { it.s("stationCode").isNotBlank() }
        return MetroRealtime(
            online = true,
            note = "实时到站 ${directions.size} 个方向",
            lineCode = lineCode,
            stationCode = station?.s("stationCode") ?: stationCode,
            stationName = station?.s("stationName").orEmpty(),
            directions = directions
        )
    }

    private fun parseDirection(entry: JsonElement): MetroDirection? {
        val stations = entry.list("wayStations")
        val origin = stations.firstOrNull()?.s("name").orEmpty()
        val destination = stations.lastOrNull()?.s("name").orEmpty()
        if (origin.isBlank() || destination.isBlank()) return null
        return MetroDirection(
            origin = origin,
            destination = destination,
            startTime = entry.s("transStartTime"),
            endTime = entry.s("transEndTime"),
            delayMinutes = entry.i("arriveTimeDelay") ?: 0,
            trains = entry.list("transTimes").mapNotNull(::parseTrain),
            // 缺码时保留空位，否则删除站点会改变剩余站数的下标差。
            stationCodes = stations.map { it.s("stationCode") }
        )
    }

    private fun parseTrain(entry: JsonElement): MetroTrain? {
        val seconds = entry.i("arriveTime") ?: return null
        return MetroTrain(
            arriveSeconds = seconds,
            nextStationCode = entry.s("arriveNextStationCode"),
            nextStationName = entry.s("arriveNextStationName"),
            scale = entry.d("arriveNextStationScale") ?: 0.0,
            arrivingFlag = entry.i("arrivingFlag") ?: 0,
            specialPosition = entry.i("specialPosition") ?: 0,
            specialPositionDesc = entry.s("specialPositionDesc")
        )
    }

    private fun rejected(root: JsonElement, layer: String, lineCode: String, stationCode: String): MetroRealtime = unavailable(
        "$layer：" + (root.i("code")?.let { "$it ${root.s("msg").ifBlank { "未知错误" }}" } ?: "响应状态码无效"),
        lineCode,
        stationCode
    )

    private fun unavailable(note: String, lineCode: String, stationCode: String): MetroRealtime =
        MetroRealtime(online = false, note = note, lineCode = lineCode, stationCode = stationCode)
}
