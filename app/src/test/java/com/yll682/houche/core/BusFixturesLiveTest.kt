package com.yll682.houche.core

import com.yll682.houche.LiveApiRunner
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 使用生产客户端签名并在解析前保存响应字节；默认离线，显式 -Dlive=true 才发请求。 */
@RunWith(LiveApiRunner::class)
class Bus原始响应抓取测试 {
    @Ignore("联网抓取默认关闭，使用 -Dlive=true 启用")
    @Test fun `厦门九十一路双向多参照站抓取`() = capture(City("11", "厦门市", "0592"), "xiamen", "91路")

    @Ignore("联网抓取默认关闭，使用 -Dlive=true 启用")
    @Test fun `北京一路双向多参照站抓取`() = capture(City("0", "北京市", "010"), "beijing", "1路")

    private fun capture(city: City, cityFile: String, lineName: String) = runBlocking {
        val directory = sequenceOf(File("src/test/resources"), File("app/src/test/resources"))
            .first { it.isDirectory }.resolve("bus-fixtures").also { it.mkdirs() }
        val sequence = AtomicInteger()
        val requestedDirection = AtomicReference<String>()
        val errors = mutableListOf<String>()
        var responsesWithVehicles = 0
        var routeResponses = 0
        val stamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
        val http = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            engine { config {
                retryOnConnectionFailure(false)
                addInterceptor { chain ->
                    val request = chain.request()
                    val params = request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() }.toMutableMap()
                    request.body?.takeIf { it.contentType()?.subtype == "x-www-form-urlencoded" }?.let { body ->
                        val buffer = okio.Buffer()
                        body.writeTo(buffer)
                        buffer.readUtf8().split('&').filter { it.isNotEmpty() }.forEach { entry ->
                            params[URLDecoder.decode(entry.substringBefore('='), "UTF-8")] =
                                URLDecoder.decode(entry.substringAfter('=', ""), "UTF-8")
                        }
                    }
                    val started = Instant.now()
                    val label = if (city.isXiamen) request.url.encodedPath.substringAfterLast('/') else "cmd${params["CMD"]}"
                    val filename = "${cityFile}-${lineName}-d${params["direction"] ?: params["DIRECTION"] ?: "both"}-" +
                        "ref${params["stationSeq"] ?: params["STATIONORDER"] ?: "none"}-$label-${stamp.format(started)}-${sequence.incrementAndGet()}"
                    val metadata = buildJsonObject {
                        put("startedAtUtc", started.toString())
                        put("city", city.name)
                        put("cityCode", city.code)
                        put("line", lineName)
                        put("requestedDirection", requestedDirection.get())
                        put("source", if (city.isXiamen) "xmbus" else "zsgj-h5")
                        put("method", request.method)
                        put("endpoint", "${request.url.scheme}://${request.url.host}${request.url.encodedPath}")
                        putJsonObject("params") { params.forEach { (key, value) -> put(key, value) } }
                    }
                    fun record(extra: JsonObject) {
                        synchronized(Bus原始响应抓取测试::class.java) {
                            directory.resolve("capture-attempts.jsonl").appendText(
                                JsonObject(metadata + extra).toString() + "\n", Charsets.UTF_8)
                        }
                    }
                    val response = try { chain.proceed(request) }
                    catch (error: Exception) {
                        record(buildJsonObject {
                            put("finishedAtUtc", Instant.now().toString())
                            put("error", "${error.javaClass.name}: ${error.message}")
                            put("responseFile", JsonNull)
                        })
                        throw error
                    }
                    val body = response.body
                    // 保存完整应用层响应体，不经 JSON parse / stringify；包含错误响应和合法空数据。
                    val bytes = body?.bytes() ?: byteArrayOf()
                    val responseFile = "$filename." + if (bytes.toString(Charsets.UTF_8).trimStart().firstOrNull() in listOf('{', '[')) "json" else "response"
                    directory.resolve(responseFile).writeBytes(bytes)
                    record(buildJsonObject {
                        put("finishedAtUtc", Instant.now().toString())
                        put("httpStatus", response.code)
                        put("contentType", body?.contentType()?.toString())
                        put("responseFile", responseFile)
                        put("bytes", bytes.size)
                        put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
                    })
                    response.newBuilder().body(bytes.toResponseBody(body?.contentType())).build()
                }
            } }
        }
        try {
            val zsgj = ZsgjClient(http)
            val repository = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), zsgj)
            for (direction in listOf("1", "2")) {
                requestedDirection.set(direction)
                val route = try { repository.lineDetail(city, lineName, direction) }
                catch (error: Exception) {
                    errors += "direction=$direction lineDetail: ${error.message}; cause=${error.cause}"
                    // CMD 103 失败也记录 CMD 104 的连通性；1/16 只是诊断请求参数，
                    // 未经站表验证，不能据其响应校准物理站位。厦门不能捏造线路/站点 ID。
                    if (!city.isXiamen) for (order in listOf(1, 16)) {
                        try {
                            zsgj.publicQuery("104", mapOf("CITYNAME" to city.name, "LINENAME" to lineName,
                                "DIRECTION" to direction, "STATIONORDER" to order))
                        } catch (diagnostic: Exception) {
                            errors += "direction=$direction diagnostic ref=$order: ${diagnostic.message}; cause=${diagnostic.cause}"
                        }
                    }
                    continue
                }
                routeResponses++
                val anchors = listOfNotNull(route.stations.firstOrNull(), route.stations.getOrNull(route.stations.size / 2), route.stations.lastOrNull())
                    .distinctBy { it.order }
                for (anchor in anchors) {
                    try {
                        val result = repository.vehicles(city, route, anchor)
                        if (result.vehicles.isNotEmpty()) responsesWithVehicles++
                        println("公交抓取 ${city.name} $lineName direction=$direction ref=${anchor.order}: vehicles=${result.vehicles.size}")
                    } catch (error: Exception) {
                        errors += "direction=$direction ref=${anchor.order}: ${error.message}; cause=${error.cause}"
                    }
                }
            }
            assertTrue("没有取得可对照站表的有车响应；$errors", routeResponses > 0 && responsesWithVehicles > 0)
        } finally { http.close() }
    }
}
