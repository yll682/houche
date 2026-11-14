package com.yll682.houche.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

data class VehicleShareTarget(
    val cityCode: String,
    val lineId: String?,
    val lineName: String,
    val direction: String,
    val plate: String
)

object VehicleShareLink {
    const val HOST = "houche.rainlodyn.cn"
    const val PATH = "/share/"
    const val SCHEME = "houche"

    private val knownParameters = setOf("city", "line", "name", "dir", "plate")

    /** 生成 https 分享链接（分享到外面用这个）。 */
    fun buildWebLink(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String,
        plate: String
    ): String = "https://$HOST$PATH?${buildQuery(cityCode, lineId, lineName, direction, plate)}"

    /** 生成 App Scheme 链接（服务器跳转用这个）。 */
    fun buildSchemeLink(
        cityCode: String,
        lineId: String?,
        lineName: String,
        direction: String,
        plate: String
    ): String = "$SCHEME://vehicle?${buildQuery(cityCode, lineId, lineName, direction, plate)}"

    /** 解析 https 分享链接或 houche://vehicle 链接；非法返回 null。 */
    fun parse(uri: String): VehicleShareTarget? {
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
            val plate = parameters["plate"]?.takeIf { it.isNotBlank() } ?: return null
            VehicleShareTarget(
                cityCode = cityCode,
                lineId = parameters["line"]?.takeIf { it.isNotBlank() },
                lineName = parameters["name"].orEmpty(),
                direction = parameters["dir"]?.takeIf { it == "1" || it == "2" }.orEmpty(),
                plate = plate
            )
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
                uri.host.equals("vehicle", ignoreCase = true) &&
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
        plate: String
    ): String = listOf(
        "city" to cityCode,
        "line" to lineId.orEmpty(),
        "name" to lineName,
        "dir" to direction,
        "plate" to plate
    ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}" }

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
