package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.logging.Level
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 官方站表与实时到站共用同一套匿名请求头、签名和密文协议。 */
internal object MetroGateway {
    const val TIMEOUT_MILLIS = 8_000L
    private const val METRO_HOST = "https://app.i-amtr.com/iets-web-app/"
    private const val METRO_SECRET = "QLJ1aZjhhTEm6RiN"
    private const val METRO_CLIENT_KEY = "AMTR-IETS-0002"
    private const val METRO_APP_VERSION = "4.0.9"
    private const val METRO_DEVICE_SEED = "houche-android-metro-client"

    suspend fun post(http: HttpClient, path: String, query: Map<String, String> = emptyMap()): HttpResponse {
        val timestamp = System.currentTimeMillis().toString()
        val deviceId = deviceId()
        return http.post(METRO_HOST + path) {
            timeout { requestTimeoutMillis = TIMEOUT_MILLIS }
            // 官方签名只覆盖 requestData；QueryMap 的线路、站码不参与签名。
            url { query.forEach { (key, value) -> parameters.append(key, value) } }
            header("client-id", "1")
            header("client-type", "android")
            header("client-key", METRO_CLIENT_KEY)
            header("device-id", deviceId)
            header("timestamp", timestamp)
            // 服务端区分缺失与空值，这些头必须实际发送，不能过滤空字符串。
            header("token", "")
            header("app-version", METRO_APP_VERSION)
            header("device-model", "")
            header("area-code", "")
            header("longitude", "")
            header("latitude", "")
            header("version", "3.0")
            header("sign", signature(deviceId, timestamp))
            header("ticket-log", "")
            header("Accept-Encoding", "gzip")
        }
    }

    suspend fun responseText(response: HttpResponse): String {
        val bytes = response.body<ByteArray>()
        // 显式声明 gzip 后 OkHttp 不会自动解压；缺少压缩头时直接读取明文。
        val gzip = response.headers["Content-Encoding"].orEmpty().split(',').any { it.trim().equals("gzip", true) }
        return if (gzip) GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            else bytes.toString(Charsets.UTF_8)
    }

    /** AES 密钥是原文 UTF-8 的十六字节，不足补零，不把它当十六进制密钥解析。 */
    private fun cipher(mode: Int): Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
        init(mode, SecretKeySpec(METRO_SECRET.toByteArray(Charsets.UTF_8).copyOf(16), "AES"))
    }

    fun decrypt(hexText: String): String? = try {
        require(hexText.isNotEmpty() && hexText.length % 32 == 0 && hexText.all { it.digitToIntOrNull(16) != null })
        val data = hexText.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        cipher(Cipher.DECRYPT_MODE).doFinal(data).toString(Charsets.UTF_8)
    } catch (error: Exception) {
        logger.log(Level.FINE, "地铁在线资料解密失败", error)
        null
    }

    /** 使用固定自有编号，复刻 DeviceIdUtil：AES 后取大写十六进制的前五十字符。 */
    fun deviceId(): String = cipher(Cipher.ENCRYPT_MODE).doFinal(METRO_DEVICE_SEED.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(it) }.take(50)

    /** 参数指 requestData 解密所得的键值；普通 query 参数不在此列。 */
    fun signature(deviceId: String, timestamp: String, params: Map<String, String> = emptyMap()): String {
        val origin = buildString {
            append(METRO_SECRET).append("&app-version=").append(METRO_APP_VERSION)
            append("&client-key=").append(METRO_CLIENT_KEY)
            append("&client-id=1&client-type=android&device-id=").append(deviceId)
            append("&timestamp=").append(timestamp).append("&token=&ticket-log=&").append(METRO_SECRET)
            params.toSortedMap().forEach { (key, value) -> append('&').append(key).append('=').append(value.takeUnless { it == "null" }.orEmpty()) }
        }
        val hex = MessageDigest.getInstance("MD5").digest(origin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        return Base64.getEncoder().encodeToString(hex.toByteArray(Charsets.US_ASCII))
    }
}
