package com.yll682.houche.core

import java.security.MessageDigest
import java.util.Base64

val V2_ORDER = mapOf(
    "lineBus" to listOf("lineName", "upDown", "carNo"),
    "stationLineBus" to listOf("stationNo", "stationName", "stationLon", "stationLat", "lineNum"),
    "transferBus" to listOf("stationNames", "lineNames", "upDowns", "stationIndexs"),
    "transferDetailBus" to listOf("stationNames", "lineNames", "upDowns", "stationIndexs")
)
fun normalizeParams(params: Map<String, Any?>): Map<String, String> = params.mapValues { (_, value) ->
    (if (value == null || value == "null") "" else when (value) {
        true -> "True"
        false -> "False"
        else -> value.toString()
    }).replace("<", "＜").replace(">", "＞")
}
private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02X".format(it.toInt() and 255) }
fun normalSign(params: Map<String, Any?>, deviceId: String, timestamp: String, token: String = "", version: String = "3.2.2"): String {
    val salt = "e0hxlyguergtjpr6z7t1fck2mogvgcu9"
    val source = "$salt&app-version=$version&client-key=xmbus-app-01&client-id=1&client-type=android&device-id=$deviceId&timestamp=$timestamp&token=$token&ticket-log=&$salt" +
        normalizeParams(params).toSortedMap().entries.joinToString("") { "&${it.key}=${it.value}" }
    return Base64.getEncoder().encodeToString(md5(source).toByteArray(Charsets.US_ASCII))
}
fun v2Sign(path: String, params: Map<String, Any?>, timestamp: String): String {
    val order = V2_ORDER[path] ?: throw BusException(BusException.Kind.ARGUMENT, "未知接口：$path")
    argument(params.keys == order.toSet(), "$path 参数集合要求：${order.joinToString()}")
    val normalized = normalizeParams(params)
    return md5(md5("xmrbi202305" + order.joinToString("") { "&${normalized[it]}" } + "&$timestamp") + "&5F4E66A9FA0F11ED80C20CDA411D78A6")
}
