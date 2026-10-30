package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

// 所有客户端默认共享此实例；由应用进程持有，不能在单次查询结束时关闭。
object BusNetwork {
    val json = Json { ignoreUnknownKeys = true }
    val client = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 15_000 }
        engine { config { retryOnConnectionFailure(false) } }
    }
}
internal suspend fun requestJson(client: HttpClient, block: HttpRequestBuilder.() -> Unit): JsonElement = withContext(Dispatchers.IO) {
    // 全部调用都是幂等查询；仅网络失败和临时服务端故障重试一次。
    for (attempt in 0..1) {
        try {
            val response = client.request { block() }
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                if (attempt == 0 && (response.status.value == 429 || response.status.value >= 500)) continue
                throw BusException(BusException.Kind.UPSTREAM, "公交服务返回 HTTP ${response.status.value}")
            }
            return@withContext BusNetwork.json.parseToJsonElement(body).also {
                if (it !is JsonObject) throw BusException(BusException.Kind.UPSTREAM, "公交服务返回的数据格式无效")
            }
        } catch (error: Exception) {
            val wrapped = failure(error, "公交查询失败，请检查网络后重试")
            if (attempt == 0 && wrapped.kind == BusException.Kind.NETWORK) continue
            throw wrapped
        }
    }
    error("无法到达的请求分支")
}

val NORMAL_PATHS = setOf("search", "lineDetail", "busList", "timeTable", "stationDetail", "nearestExactStation", "stationDetailBuses", "nearestBuses", "exactStationDetail", "exactStationDetailBuses", "busRemainingInfo", "transit", "busListInTransit")
class XmbusClient(private val deviceId: String, private val http: HttpClient = BusNetwork.client) {
    init { argument(deviceId.isNotEmpty(), "设备编号不能为空") }
    suspend fun query(path: String, params: Map<String, Any?>): JsonElement = withContext(Dispatchers.IO) {
        argument(path in NORMAL_PATHS || path in V2_ORDER, "未知接口：$path")
        val normalized = normalizeParams(params)
        val timestamp = System.currentTimeMillis().toString()
        val v2 = path in V2_ORDER
        val signature = if (v2) v2Sign(path, normalized, timestamp) else normalSign(normalized, deviceId, timestamp)
        val result = requestJson(http) {
            method = io.ktor.http.HttpMethod.Post
            url("https://app.xmbus.com/" + if (v2) "xmbus/api/bus/v2/$path" else "xmbus_web_app/bus/pub/routeQuery/$path")
            url { normalized.forEach { (key, value) -> parameters.append(key, value) } }
            headers {
                mapOf("client-id" to "1", "client-type" to "android", "client-key" to "xmbus-app-01", "device-id" to deviceId,
                    "timestamp" to timestamp, "token" to "", "appId" to "xmrbi202305", "app-version" to "3.2.2", "appVersion" to "Android-v3.2.2",
                    "clientId" to "", "device-model" to "Android", "area-code" to "", "version" to "v0.1", "ticket-log" to "", "sign" to signature).forEach { (k, v) -> append(k, v) }
            }
        }
        if (result.s("success") == "false" || result.i("code") != if (v2) 200 else 0)
            throw failure(BusException(BusException.Kind.UPSTREAM, "$path：上游查询未成功，状态 ${result.s("code")}"), "上游拒绝查询")
        result
    }
}

// 仅逐字段读取，兼容上游数字/字符串混用以及可选字段缺失。
internal fun JsonElement.field(key: String): JsonElement = (this as? JsonObject)?.get(key) ?: JsonNull
internal fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement.s(key: String): String = field(key).text().orEmpty()
internal fun JsonElement.i(key: String): Int? = field(key).text()?.toIntOrNull()
internal fun JsonElement.d(key: String): Double? = field(key).text()?.toDoubleOrNull()?.takeIf { it.isFinite() }
internal fun JsonElement.items(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
internal fun JsonElement.list(key: String): List<JsonElement> = field(key).items()
