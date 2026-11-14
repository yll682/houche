package com.yll682.houche.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

sealed interface ShareTarget {
    val cityCode: String
    val lineId: String?
    val lineName: String
    val direction: String

    /** 具体车辆：plate 必填。 */
    data class Vehicle(
        override val cityCode: String,
        override val lineId: String?,
        override val lineName: String,
        override val direction: String,
        val plate: String
    ) : ShareTarget

    /** 线路：没有 plate。 */
    data class Line(
        override val cityCode: String,
        override val lineId: String?,
        override val lineName: String,
        override val direction: String
    ) : ShareTarget
}

object VehicleShareLink {
    const val HOST = "houche.rainlodyn.cn"
    const val PATH = "/share/"
    const val SCHEME = "houche"

    private val knownParameters = setOf("type", "city", "line", "name", "dir", "plate")

    /** 车辆分享链接（与 v2.3.0 一致，不带 type）。 */
    fun buildWebLink(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String,
        plate: String
    ): String = "https://$HOST$PATH?${buildQuery(cityCode, lineId, lineName, direction, plate)}"

    /** 车辆 Scheme 链接。 */
    fun buildSchemeLink(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String,
        plate: String
    ): String = "$SCHEME://vehicle?${buildQuery(cityCode, lineId, lineName, direction, plate)}"

    /** 线路分享链接（带 type=line，无 plate）。 */
    fun buildLineWebLink(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String
    ): String = "https://$HOST$PATH?type=line&${buildQuery(cityCode, lineId, lineName, direction)}"

    /** 线路 Scheme 链接。 */
    fun buildLineSchemeLink(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String
    ): String = "$SCHEME://line?type=line&${buildQuery(cityCode, lineId, lineName, direction)}"

    /** 解析 https 分享链接或 houche://vehicle|line 链接；非法返回 null。 */
    fun parse(uri: String): ShareTarget? {
        return try {
            val parsed = URI(uri)
            if (!supports(parsed)) return null
            val query = parsed.rawQuery?.takeIf { it.isNotEmpty() } ?: return null
            val parameters = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val key = decode(part.substringBefore('='))
                val value = decode(part.substringAfter('=', ""))
                if (key in knownParameters) {
                    if (parameters.containsKey(key)) return null
                    parameters[key] = value
                }
            }
            val cityCode = parameters["city"]?.takeIf { it.isNotBlank() } ?: return null
            val lineName = parameters["name"]?.takeIf { it.isNotBlank() } ?: return null
            val lineId = parameters["line"]?.takeIf { it.isNotBlank() }
            val direction = parameters["dir"]?.takeIf { it == "1" || it == "2" }.orEmpty()
            val isScheme = parsed.scheme.equals(SCHEME, ignoreCase = true)
            val type = when (val explicitType = parameters["type"]) {
                null -> if (isScheme && parsed.host.equals("line", ignoreCase = true)) "line" else "vehicle"
                "vehicle", "line" -> explicitType
                else -> return null
            }
            // 服务器原样转交 query；接受同类型 type，但不让 query 把 Scheme 入口改成另一种目标。
            if (isScheme && !parsed.host.equals(type, ignoreCase = true)) return null
            when (type) {
                "line" -> ShareTarget.Line(cityCode, lineId, lineName, direction)
                else -> ShareTarget.Vehicle(
                    cityCode, lineId, lineName, direction,
                    parameters["plate"]?.takeIf { it.isNotBlank() } ?: return null
                )
            }
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun supports(uri: URI): Boolean {
        if (uri.rawUserInfo != null) return false
        return when {
            uri.scheme.equals("https", ignoreCase = true) ->
                uri.host.equals(HOST, ignoreCase = true) &&
                    (uri.rawPath == PATH || uri.rawPath == "/share") &&
                    (uri.port == -1 || uri.port == 443)
            uri.scheme.equals(SCHEME, ignoreCase = true) ->
                (uri.host.equals("vehicle", ignoreCase = true) || uri.host.equals("line", ignoreCase = true)) &&
                    (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                    uri.port == -1
            else -> false
        }
    }

    private fun buildQuery(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String,
        plate: String? = null
    ): String = (listOf(
        "city" to cityCode,
        "line" to lineId.orEmpty(),
        "name" to lineName,
        "dir" to direction
    ) + if (plate != null) listOf("plate" to plate) else emptyList())
        .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}" }

    private fun decode(value: String): String {
        val decoded = URLDecoder.decode(value, Charsets.UTF_8.name())
        // URLDecoder 会替换非法 UTF-8 字节，先验证每段百分号字节以避免静默损坏标识。
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                index++
                continue
            }
            val bytes = ByteArrayOutputStream()
            while (index < value.length && value[index] == '%') {
                bytes.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            }
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray()))
        }
        return decoded
    }
}
