package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.logging.Level

/** 一次检查更新得到的新版本信息；source 记录命中的来源，下载优先用同源链接。 */
data class RemoteRelease(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val source: String,
    val downloadUrl: String
)

/** 两个匿名 raw 直链：国内 CNB 快，海外 GitHub 快，所以并发请求，谁先成功用谁。 */
private val UPDATE_SOURCES = listOf(
    "GitHub" to "https://raw.githubusercontent.com/yll682/houche/main/version.json",
    "CNB" to "https://cnb.cool/yll682/houche/-/git/raw/main/version.json"
)
private const val REQUEST_TIMEOUT_MILLIS = 5_000L
private const val CONNECT_TIMEOUT_MILLIS = 4_000L
private const val USER_AGENT = "houche-android"

/** 版本比较只看 versionCode，避免版本名格式变化引起误判。 */
fun isNewer(remote: RemoteRelease, localCode: Int): Boolean = remote.versionCode > localCode

/**
 * 逐字段读取 version.json，兼容数字与字符串混用、可选字段缺失。
 * 不建 @Serializable 数据类：上游格式随时可能变，逐字段读更稳，也便于离线单测。
 */
internal fun parseRelease(json: JsonElement, source: String): RemoteRelease {
    val code = json.field("versionCode").text()?.toIntOrNull()
        ?: throw BusException(BusException.Kind.UPSTREAM, "更新信息缺少版本号")
    val name = json.s("versionName").trim()
    if (name.isEmpty()) throw BusException(BusException.Kind.UPSTREAM, "更新信息缺少版本名")
    // 同源链接优先，缺失时退回另一个镜像地址。
    val downloads = json.field("downloads")
    val keys = if (source == "CNB") listOf("cnb", "github") else listOf("github", "cnb")
    val downloadUrl = keys.map { downloads.s(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return RemoteRelease(
        versionName = name,
        versionCode = code,
        notes = json.s("notes").trim(),
        source = source,
        downloadUrl = downloadUrl
    )
}

class UpdateClient(private val http: HttpClient = BusNetwork.client) {
    /** 并发请求两个来源，返回第一个成功的结果；全部失败抛网络错误。 */
    suspend fun latest(): RemoteRelease = coroutineScope {
        val channel = Channel<RemoteRelease?>(Channel.UNLIMITED)
        val jobs = UPDATE_SOURCES.map { (source, url) ->
            launch {
                channel.send(fetch(source, url))
            }
        }
        val closer = launch {
            jobs.joinAll()
            channel.close()
        }
        for (release in channel) {
            if (release != null) {
                jobs.forEach { it.cancel() }
                closer.cancel()
                return@coroutineScope release
            }
        }
        throw BusException(BusException.Kind.NETWORK, "检查更新失败，请检查网络")
    }

    /** 单个来源：最多等 5 秒，任何失败都折算成 null，交给另一个来源。 */
    private suspend fun fetch(source: String, url: String): RemoteRelease? = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
        try {
            withContext(Dispatchers.IO) {
                val response = http.get(url) {
                    header("User-Agent", USER_AGENT)
                    timeout {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    }
                }
                val body = response.bodyAsText()
                if (response.status.value !in 200..299)
                    throw BusException(BusException.Kind.UPSTREAM, "更新信息 HTTP ${response.status.value}")
                parseRelease(BusNetwork.json.parseToJsonElement(body), source)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.WARNING, "$source 来源检查更新失败", error)
            null
        }
    }.also { if (it == null) logger.log(Level.WARNING, "$source 来源检查更新超时或失败") }
}