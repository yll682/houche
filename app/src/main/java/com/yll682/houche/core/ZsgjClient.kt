package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

val PUBLIC_COMMANDS = mapOf(
    "101" to emptySet(), "102" to setOf("CITYNAME", "KEYWORD"),
    "103" to setOf("CITYNAME", "LINENAME", "DIRECTION"),
    "104" to setOf("CITYNAME", "LINENAME", "DIRECTION", "STATIONORDER"),
    "106" to setOf("CITYNAME", "LAT", "LNG"), "110" to setOf("CITYNAME", "KEYWORD"),
    "114" to setOf("CITYNAME", "KEYWORD"), "115" to setOf("CITYNAME", "STATIONNAME", "MYLAT", "MYLNG", "ALL"),
    "207" to setOf("CITYNAME", "ROUTEID", "DIRECTION"),
    "209" to setOf("CITYNAME", "STATIONNAME", "MYLAT", "MYLNG", "LAT", "LNG")
)
class ZsgjClient(private val http: HttpClient = BusNetwork.client) {
    suspend fun publicQuery(command: String, params: Map<String, Any?> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val required = PUBLIC_COMMANDS[command] ?: throw BusException(BusException.Kind.ARGUMENT, "未知查询指令：$command")
        argument(params.keys == required, "$command 参数集合要求：${required.joinToString()}")
        val normalized = normalizeParams(params)
        if ("CITYNAME" in required) argument(!normalized["CITYNAME"].isNullOrEmpty(), "城市名称不能为空")
        if ("DIRECTION" in required) argument(normalized["DIRECTION"] in setOf("1", "2"), "线路方向需要为 1 或 2")
        for (key in listOf("ROUTEID", "STATIONORDER")) if (key in required) {
            val value = normalized[key].orEmpty()
            argument(value.matches(Regex("[0-9]+")) && (value.toLongOrNull() ?: 0) > 0, "$key 需要为正整数")
        }
        val result = requestJson(http) {
            method = HttpMethod.Post
            url("https://h5.mygolbs.com/ApiData.do")
            headers {
                append("User-Agent", "Mozilla/5.0"); append("X-Requested-With", "XMLHttpRequest")
                append("Accept", "application/json, text/javascript, */*; q=0.01")
                append("Origin", "https://h5.mygolbs.com"); append("Referer", "https://h5.mygolbs.com/")
            }
            setBody(FormDataContent(Parameters.build { append("CMD", command); normalized.forEach { (k, v) -> append(k, v) } }))
        }
        if (result.s("status") != "1") throw failure(BusException(BusException.Kind.UPSTREAM, "指令 $command：上游查询未成功，状态 ${result.s("status")}"), "上游拒绝查询")
        result
    }
    suspend fun snapshot(path: String, params: Map<String, Any?>): JsonElement = withContext(Dispatchers.IO) {
        argument(path in setOf("searchLinesOrStation", "getNearStation", "getBusInfo", "getBusRouteInfo"), "未知快照接口：$path")
        val result = requestJson(http) {
            method = HttpMethod.Post; url("https://snapshot.mygolbs.com/snapshot/$path")
            header("token", "zsgjxljc"); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { normalizeParams(params).forEach { (k, v) -> put(k, v) }; put("serverTime", 152382832342L) }.toString())
        }
        if (result.i("status") != 200) throw failure(BusException(BusException.Kind.UPSTREAM, "$path：上游查询未成功，状态 ${result.s("status")}"), "上游拒绝查询")
        result
    }
}
