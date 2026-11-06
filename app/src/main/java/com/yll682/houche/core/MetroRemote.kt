package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.logging.Level

/**
 * 地铁线路资料的在线来源（厦门地铁官方网关 app.i-amtr.com）。
 *
 * 网关要求三个请求头：sign / app-id / version。签名算法取自官方 H5（amtr-trip）的公开前端代码：
 * `sign = md5("oms3lbg5pns82ym9at76jvzuxd4r1wfoq0h" + 毫秒时间戳 + "1.0" + 请求体 JSON)`。
 * 网关接受该签名后还要求登录 token（匿名一律返回 999 未认证），所以这里的定位是「探测」：
 * 拿到数据就换成在线资料，拿不到就安静地继续用内置 metro.json，不向界面抛错误。
 */
data class MetroProbe(
    val online: Boolean,
    /** 服务端原话或解析失败原因，只进日志与「设置 → 关于」，不当作界面文案。 */
    val note: String,
    val lines: List<MetroLine> = emptyList()
)

private const val METRO_GATEWAY = "https://app.i-amtr.com/gateway/api/iets-oms-service/"
private const val METRO_SIGN_SALT = "oms3lbg5pns82ym9at76jvzuxd4r1wfoq0h"
private const val METRO_SIGN_VERSION = "1.0"
private const val METRO_APP_ID = "oms"
private const val METRO_TIMEOUT_MILLIS = 6_000L

/** 站序接口：先打实测存在的这条，再退到同义路径。 */
private val METRO_PATHS = listOf("pub/station/getAllStations", "pub/station/allStation")

/** 站点对象里站名字段的候选键，按优先级取第一个非空。 */
private val NAME_KEYS = listOf("stationName", "name", "stationCnName", "cnName", "stationCn")
private val LINE_KEYS = listOf("lineName", "lineCode", "lineId", "lineNo")
private val ORDER_KEYS = listOf("sort", "sortNum", "orderNum", "order", "seq", "index")

class MetroRemote(private val http: HttpClient = BusNetwork.client) {

    /** 进程内只探一次：官方接口通不通在一段时间内是稳定的，没必要每次开页面都等超时。 */
    @Volatile private var cached: MetroProbe? = null

    /** 最近一次探测结论；没探过时为空。只给日志与「设置 → 关于」读，不参与主界面。 */
    val last: MetroProbe? get() = cached

    /** 探测一次；任何异常都收敛成 online=false，调用方据此继续用内置资料。 */
    suspend fun latest(): MetroProbe = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val result = probeAll()
        cached = result
        result
    }

    private suspend fun probeAll(): MetroProbe {
        var last = "在线资料不可用"
        for (path in METRO_PATHS) {
            val probe = withTimeoutOrNull(METRO_TIMEOUT_MILLIS) { request(path) }
                ?: MetroProbe(online = false, note = "$path 请求超时")
            if (probe.online) return probe
            last = probe.note
        }
        return MetroProbe(online = false, note = last)
    }

    private suspend fun request(path: String): MetroProbe {
        return try {
            val timestamp = System.currentTimeMillis().toString()
            val sign = md5(METRO_SIGN_SALT + timestamp + METRO_SIGN_VERSION)
            val text = http.get(METRO_GATEWAY + path) {
                timeout { requestTimeoutMillis = METRO_TIMEOUT_MILLIS }
                header("app-id", METRO_APP_ID)
                header("version", METRO_SIGN_VERSION)
                header("timestamp", timestamp)
                header("sign", sign)
                header("Accept", "application/json")
            }.bodyAsText()
            parse(text)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            logger.log(Level.FINE, "地铁在线资料请求失败：$path", error)
            MetroProbe(online = false, note = "$path 请求失败：${error.javaClass.simpleName}")
        }
    }

    /** 只认 code==0 且能解析出站序的响应；其余情况一律当作不可用，绝不半信半疑地拼数据。 */
    internal fun parse(text: String): MetroProbe {
        val root = runCatching { BusNetwork.json.parseToJsonElement(text) }.getOrNull()
            ?: return MetroProbe(online = false, note = "响应不是合法 JSON")
        val code = root.i("code")
        if (code != null && code != 0) {
            val message = root.s("msg").ifBlank { "未知错误" }
            return MetroProbe(online = false, note = "$code $message")
        }
        val lines = parseLines(root)
        return if (lines.isEmpty()) MetroProbe(online = false, note = "接口未返回可识别的站序")
        else MetroProbe(online = true, note = "在线资料 ${lines.size} 条线路", lines = lines)
    }

    /** 上游字段名没有文档，按常见命名挨个试；分组后按 order 排序，缺 order 就保持原顺序。 */
    private fun parseLines(root: JsonElement): List<MetroLine> {
        val candidates = collectStations(root)
        if (candidates.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<Triple<Int, String, String>>>()
        candidates.forEachIndexed { index, element ->
            val name = NAME_KEYS.firstNotNullOfOrNull { key -> element.s(key).takeIf { it.isNotBlank() } } ?: return@forEachIndexed
            val lineKey = LINE_KEYS.firstNotNullOfOrNull { key -> element.s(key).takeIf { it.isNotBlank() } } ?: "地铁"
            val order = ORDER_KEYS.firstNotNullOfOrNull { key -> element.s(key).toIntOrNull() } ?: index
            grouped.getOrPut(lineKey) { mutableListOf() }.add(Triple(order, name, lineKey))
        }
        return grouped.map { (key, stations) ->
            val ordered = stations.sortedBy { it.first }.mapIndexed { index, item ->
                MetroStation(order = index + 1, name = item.second)
            }
            MetroLine(
                id = key.filter { it.isLetterOrDigit() }.ifBlank { key },
                name = if (key.startsWith("地铁")) key else "地铁$key",
                from = ordered.first().name,
                to = ordered.last().name,
                stations = ordered
            )
        }.sortedBy { it.id }
    }

    /** 从响应里找出「像站点」的对象数组：递归收集所有对象数组，取含站名字段且元素最多的那个。 */
    private fun collectStations(element: JsonElement): List<JsonElement> {
        val arrays = mutableListOf<List<JsonElement>>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonArray -> {
                    arrays.add(node.toList())
                    node.forEach(::walk)
                }
                is JsonObject -> node.values.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
        return arrays
            .filter { list -> list.any { it is JsonObject && NAME_KEYS.any { key -> it.s(key).isNotBlank() } } }
            .maxByOrNull { it.size }
            .orEmpty()
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
