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

/** 官方站表请求的结果；失败原因只进日志与「设置 → 关于」。 */
data class MetroProbe(
    val online: Boolean,
    val note: String,
    val lines: List<MetroLine> = emptyList()
)

private const val METRO_STATION_PATH = "pub/station/allStation"
private val METRO_LINE_NAMES = mapOf("1" to "一号线", "2" to "二号线", "3" to "三号线", "4" to "四号线", "6" to "六号线")
private val METRO_LINE_COLORS = mapOf("1" to "#DA7028", "2" to "#52A441", "3" to "#52B5C8", "4" to "#E50012", "6" to "#92308D")

/**
 * 厦门地铁官方站表客户端，协议取自官方 App 4.0.9 的 HeaderParamInterceptor。
 * 匿名空 token 已在境内网络实测通过。每次调用都请求最新资料，失败交由加载流程静默兜底。
 */
class MetroRemote(private val http: HttpClient = BusNetwork.client) {
    @Volatile private var lastProbe: MetroProbe? = null

    /** 仅保存最近一次诊断，不缓存请求结果，避免断网一次后一直不再查询。 */
    val last: MetroProbe? get() = lastProbe

    suspend fun latest(): MetroProbe = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(MetroGateway.TIMEOUT_MILLIS) { request() }
            ?: MetroProbe(online = false, note = "地铁在线资料请求超时")
        logger.log(Level.FINE, result.note)
        lastProbe = result
        result
    }

    private suspend fun request(): MetroProbe {
        // 查询可安全重试一次；整个过程仍受八秒总超时约束。
        for (attempt in 0..1) {
            try {
                val response = MetroGateway.post(http, METRO_STATION_PATH)
                val status = response.status.value
                if (status !in 200..299) {
                    if (attempt == 0 && (status == 429 || status >= 500)) continue
                    return MetroProbe(online = false, note = "地铁接口返回 HTTP $status")
                }
                return parse(MetroGateway.responseText(response))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt == 0 && (error is IOException || error is HttpRequestTimeoutException)) continue
                logger.log(Level.FINE, "地铁在线资料请求失败", error)
                return MetroProbe(online = false, note = "地铁在线资料请求失败：${error.javaClass.simpleName}")
            }
        }
        error("无法到达的请求分支")
    }

    /** 成功外层只有密文；仅在网关显式提供状态码时检查，内层成功码仍必须为零。 */
    internal fun parse(text: String): MetroProbe {
        val outer = runCatching { BusNetwork.json.parseToJsonElement(text) }.getOrNull()
            ?: return MetroProbe(online = false, note = "响应不是合法 JSON")
        if (outer is JsonObject && "code" in outer && outer.i("code") != 0) return rejected(outer, "网关拒绝")
        val encrypted = outer.s("responseData")
        if (encrypted.isBlank()) return MetroProbe(online = false, note = "响应没有数据体")
        val plain = decrypt(encrypted) ?: return MetroProbe(online = false, note = "响应数据解密失败")
        val inner = runCatching { BusNetwork.json.parseToJsonElement(plain) }.getOrNull()
            ?: return MetroProbe(online = false, note = "解密后的数据不是合法 JSON")
        if (inner.i("code") != 0) return rejected(inner, "站表接口报错")
        val lines = parseLines(inner)
        return if (lines.isEmpty()) MetroProbe(online = false, note = "接口未返回可识别的站序")
            else MetroProbe(online = true, note = "在线资料 ${lines.size} 条线路", lines = lines)
    }

    private fun rejected(root: JsonElement, layer: String): MetroProbe = MetroProbe(
        online = false,
        note = "$layer：" + (root.i("code")?.let { "$it ${root.s("msg").ifBlank { "未知错误" }}" } ?: "响应状态码无效")
    )

    /** 只接收 METRO；未知线路也按站码分组，不能让内置线路清单限制在线新增内容。 */
    private fun parseLines(root: JsonElement): List<MetroLine> {
        val grouped = root.list("data").filter { it.s("vehicle") == "METRO" && it.s("name").isNotBlank() }
            .groupBy { item ->
                val code = item.s("lineCode")
                val name = item.s("lineName").removePrefix("地铁")
                code.takeIf { it.length == 8 && it.all(Char::isDigit) }?.take(2)?.toIntOrNull()?.toString()
                    ?: METRO_LINE_NAMES.entries.firstOrNull { it.value == name }?.key ?: name
            }
        val lines = grouped.filterKeys { it.isNotBlank() }.map { (id, members) ->
            val ordered = members.sortedWith(compareBy<JsonElement> { it.s("stationCode").isBlank() }.thenBy { it.s("stationCode") })
            val stations = ordered.mapIndexed { index, item ->
                MetroStation(
                    order = index + 1,
                    name = item.s("name"),
                    opened = flag(item, "showFlag") && flag(item, "activeFlag"),
                    code = item.s("stationCode"),
                    first = item.s("firstBusTime"),
                    last = item.s("lastBusTime"),
                    lng = item.s("longitude"),
                    lat = item.s("latitude"),
                    stationUniqueCode = item.s("stationUniqueCode"),
                    changeFlag = when (item.field("changeFlag").text()?.lowercase()) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> null
                    },
                    coordinateSystem = "BD09"
                )
            }
            val name = members.first().s("lineName").removePrefix("地铁").ifBlank { METRO_LINE_NAMES[id] ?: "${id}号线" }
            MetroLine(
                id = id,
                name = "地铁$name",
                color = METRO_LINE_COLORS[id].orEmpty(),
                from = stations.first().name,
                to = stations.last().name,
                opened = stations.any { it.opened },
                first = stations.first().first,
                last = stations.first().last,
                stations = stations,
                apiCode = members.first().s("lineCode")
            )
        }.sortedWith(compareBy<MetroLine> { it.id.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.id })
        return metroTransfers(lines)
    }

    private fun flag(item: JsonElement, key: String): Boolean = when (item.field(key).text()?.lowercase()) {
        "false", "0" -> false
        else -> true
    }

    internal fun decrypt(hexText: String): String? = MetroGateway.decrypt(hexText)

    /** 使用固定自有编号，复刻 DeviceIdUtil：AES 后取大写十六进制的前五十字符。 */
    internal fun deviceId(): String = MetroGateway.deviceId()

    /** 参数指 requestData 解密所得的键值；站表接口无参数，不发送 requestData。 */
    internal fun signature(deviceId: String, timestamp: String, params: Map<String, String> = emptyMap()): String =
        MetroGateway.signature(deviceId, timestamp, params)
}
