package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** 真实加密样本与完全离线的内存传输，既验证协议也覆盖畸形响应。 */
class MetroRemoteTest {
    private val remote = MetroRemote()
    private val sample get() = MetroFixtures.sample

    @Test fun `真实响应解密后与样本明文逐字一致`() {
        assertEquals(sample.s("cipherHexUpper"), BusNetwork.json.parseToJsonElement(sample.s("rawResponse")).s("responseData"))
        assertEquals(sample.s("plaintext"), remote.decrypt(sample.s("cipherHexUpper")))
    }

    @Test fun `真实响应只保留地铁并带出站序时间坐标与运营状态`() {
        val probe = remote.parse(sample.s("rawResponse"))
        assertTrue(probe.online)
        assertEquals(listOf("1", "4"), probe.lines.map { it.id })
        val line = probe.lines.first()
        assertEquals("地铁一号线", line.name)
        assertEquals("#DA7028", line.color)
        assertEquals(listOf("镇海路", "中山公园"), line.liveStations.map { it.name })
        assertEquals(listOf("01010000", "01020000"), line.liveStations.map { it.code })
        assertEquals(listOf(1, 2), line.liveStations.map { it.order })
        assertEquals("镇海路", line.from)
        assertEquals("中山公园", line.to)
        assertEquals("06:01", line.stations[1].first)
        assertEquals("23:01", line.stations[1].last)
        assertEquals("118.089852", line.stations.first().lng)
        assertEquals("24.457147", line.stations.first().lat)
        assertFalse(probe.lines.last().stations.single().opened)
        assertFalse(probe.lines.last().opened)
    }

    @Test fun `接口乱序仍以官方站码排序`() {
        val root = MetroFixtures.plaintext as JsonObject
        val body = JsonObject(root + ("data" to JsonArray(root.list("data").reversed())))
        val probe = remote.parse(MetroFixtures.envelope(body.toString()))
        assertEquals(listOf("镇海路", "中山公园"), probe.lines.first().liveStations.map { it.name })
    }

    @Test fun `外层只有 responseData 没有状态码也能在线成功`() {
        val outer = BusNetwork.json.parseToJsonElement(sample.s("rawResponse")) as JsonObject
        assertEquals(setOf("responseData"), outer.keys)
        assertEquals(listOf("responseData"), sample.list("outerKeys").map { it.text() })
        assertTrue(remote.parse(outer.toString()).online)
        assertTrue(remote.parse(JsonObject(outer + ("code" to JsonPrimitive(0))).toString()).online)
    }

    @Test fun `网关显式拒绝时带上层级错误码与消息且不继续解密`() {
        val error = sample.field("gatewayError") as JsonObject
        assertFalse("网关错误样本应无密文", "responseData" in error)
        val probe = remote.parse(error.toString())
        assertFalse(probe.online)
        assertEquals("网关拒绝：10004 sign缺失", probe.note)
        val outer = BusNetwork.json.parseToJsonElement(sample.s("rawResponse")) as JsonObject
        assertEquals(probe, remote.parse(JsonObject(outer + error).toString()))
    }

    @Test fun `内层携带时间戳与追踪编号时仍可读取站表`() {
        val root = MetroFixtures.plaintext as JsonObject
        // 原有裁剪样本未保留这两个字段；此处只构造兼容性变体，不改动基准密文和明文。
        val body = JsonObject(root + mapOf("ts" to JsonPrimitive(1794000000000L), "traceId" to JsonPrimitive("离线回归测试")))
        assertEquals(remote.parse(sample.s("rawResponse")), remote.parse(MetroFixtures.envelope(body.toString())))
    }

    @Test fun `缺少密文或显式状态码无效不能当作成功`() {
        for (body in listOf("{}", "[]", "null", """{"code":{},"responseData":[]}""")) {
            assertFalse(body, remote.parse(body).online)
        }
        val outer = BusNetwork.json.parseToJsonElement(sample.s("rawResponse")) as JsonObject
        for (code in listOf(JsonNull, JsonObject(emptyMap()), JsonPrimitive("无效"))) {
            assertFalse(remote.parse(JsonObject(outer + ("code" to code)).toString()).online)
        }
    }

    @Test fun `内层非零状态码归为站表接口报错并保留错误码与消息`() {
        val root = MetroFixtures.plaintext as JsonObject
        val error = JsonObject(root + mapOf("code" to JsonPrimitive(999), "msg" to JsonPrimitive("站表维护中")))
        val probe = remote.parse(MetroFixtures.envelope(error.toString()))
        assertFalse(probe.online)
        assertEquals("站表接口报错：999 站表维护中", probe.note)
    }

    @Test fun `内层错误码与非法站表不能当作成功`() {
        val root = MetroFixtures.plaintext as JsonObject
        val cases = listOf(
            JsonObject(root + ("code" to JsonPrimitive(999))),
            JsonObject(root - "code"),
            JsonObject(root + ("code" to JsonArray(emptyList()))),
            JsonObject(root + ("data" to JsonNull)),
            JsonObject(root + ("data" to JsonObject(emptyMap()))),
            JsonObject(root + ("data" to JsonArray(emptyList())))
        )
        cases.forEach { assertFalse(it.toString(), remote.parse(MetroFixtures.envelope(it.toString())).online) }
    }

    @Test fun `非 JSON 与解密失败静默返回不可用`() {
        assertFalse(remote.parse("<html>502 Bad Gateway</html>").online)
        assertFalse(remote.parse(MetroFixtures.envelope("不是 JSON")).online)
        val corrupt = sample.s("cipherHexUpper").let { it.dropLast(32) + "0".repeat(32) }
        for (hex in listOf("", "F", "GG".repeat(16), "00".repeat(16), corrupt)) {
            val body = JsonObject(mapOf("code" to JsonPrimitive(0), "responseData" to JsonPrimitive(hex)))
            assertFalse(remote.parse(body.toString()).online)
        }
    }

    @Test fun `字段缺失或类型不符会跳过坏条目并保留可用站点`() {
        val root = MetroFixtures.plaintext as JsonObject
        val station = root.list("data").first() as JsonObject
        val minimal = JsonObject(station.filterKeys { it in setOf("vehicle", "lineName", "name") })
        val malformed = JsonObject(station + mapOf("name" to JsonObject(emptyMap()), "lineName" to JsonArray(emptyList())))
        val wrongOptional = JsonObject(station + mapOf("firstBusTime" to JsonObject(emptyMap()), "lastBusTime" to JsonNull, "showFlag" to JsonPrimitive("false")))
        val data = JsonArray(listOf(JsonNull, JsonPrimitive(1), malformed, minimal, wrongOptional))
        val probe = remote.parse(MetroFixtures.envelope(JsonObject(root + ("data" to data)).toString()))
        assertTrue(probe.online)
        assertEquals(2, probe.lines.single().stations.size)
        assertTrue(probe.lines.single().stations.all { it.first.isEmpty() && it.last.isEmpty() })
        assertEquals(1, probe.lines.single().pendingCount)
    }

    @Test fun `未知线路名称可以由线路码识别而不依赖内置清单`() {
        val root = MetroFixtures.plaintext as JsonObject
        val station = root.list("data").first() as JsonObject
        val extra = JsonObject(station + mapOf("lineCode" to JsonPrimitive("05000000"), "lineName" to JsonPrimitive("新增线路"), "stationCode" to JsonPrimitive("05010000")))
        val body = JsonObject(root + ("data" to JsonArray(root.list("data") + extra)))
        val probe = remote.parse(MetroFixtures.envelope(body.toString()))
        assertTrue(probe.online)
        assertEquals("地铁新增线路", probe.lines.first { it.id == "5" }.name)
        assertEquals("05010000", probe.lines.first { it.id == "5" }.stations.single().code)
    }

    @Test fun `同一份在线站表推导换乘且保留次日时间原文`() {
        val root = MetroFixtures.plaintext as JsonObject
        val station = root.list("data").first() as JsonObject
        val extra = JsonObject(station + mapOf("lineCode" to JsonPrimitive("02000000"), "lineName" to JsonPrimitive("二号线"), "lastBusTime" to JsonPrimitive("次日00:00")))
        val body = JsonObject(root + ("data" to JsonArray(listOf(station, extra))))
        val probe = remote.parse(MetroFixtures.envelope(body.toString()))
        assertEquals(listOf("二号线"), probe.lines.first().stations.single().transfer)
        assertEquals(listOf("一号线"), probe.lines.last().stations.single().transfer)
        assertEquals("次日00:00", probe.lines.last().stations.single().last)
    }

    @Test fun `设备编号和签名与独立离线计算一致`() {
        assertEquals("DB590BD125A84B1F699B69E7A7659936B623118321E00F4776", remote.deviceId())
        assertEquals(remote.deviceId(), MetroRemote().deviceId())
        assertEquals("MjA0MjI0NzY2MTNGMjQ4OEFCOEY4N0E2QUZGNDI4REQ=", remote.signature(remote.deviceId(), "1794000000000"))
        assertEquals("Q0MxNEM4RjQxOTlFREVEMEY0NTUzNEJBNTA1NDM0QzY=", remote.signature(remote.deviceId(), "1794000000000", mapOf("z" to "1", "a" to "中文")))
    }

    @Test fun `实际请求使用官方 POST 请求头并能读取 gzip 响应`() = runBlocking {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(sample.s("rawResponse").toByteArray(Charsets.UTF_8)) }
        val requests = mutableListOf<HttpRequestData>()
        client { data ->
            requests += data
            Reply(output.toByteArray(), headers = headersOf("Content-Encoding", "gzip"))
        }.use { http ->
            val probe = MetroRemote(http).latest()
            assertTrue(probe.note, probe.online)
        }
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://app.i-amtr.com/iets-web-app/pub/station/allStation", request.url.toString())
        val headers = request.headers
        mapOf("client-id" to "1", "client-type" to "android", "client-key" to "AMTR-IETS-0002", "app-version" to "4.0.9", "version" to "3.0", "Accept-Encoding" to "gzip").forEach { (key, value) -> assertEquals(key, value, headers[key]) }
        listOf("token", "device-model", "area-code", "longitude", "latitude", "ticket-log").forEach { assertEquals(it, "", headers[it]) }
        assertNull(headers["app-id"])
        assertEquals(remote.deviceId(), headers["device-id"])
        assertEquals(remote.signature(headers["device-id"]!!, headers["timestamp"]!!), headers["sign"])
    }

    @Test fun `请求声明 gzip 但响应没有压缩头时直接读取明文`() = runBlocking {
        client { request ->
            assertEquals("gzip", request.headers["Accept-Encoding"])
            Reply(sample.s("rawResponse").toByteArray(Charsets.UTF_8), headers = headersOf(
                "Content-Type" to listOf("application/json"),
                "Transfer-Encoding" to listOf("chunked"),
                "Vary" to listOf("Accept-Encoding")
            ))
        }.use { http ->
            val probe = MetroRemote(http).latest()
            assertTrue(probe.note, probe.online)
        }
    }

    @Test fun `先失败再进入会重新请求而非永久缓存失败`() = runBlocking {
        var count = 0
        client {
            count++
            Reply(if (count == 1) """{"code":999,"msg":"维护中"}""".toByteArray() else sample.s("rawResponse").toByteArray())
        }.use { http ->
            val source = MetroRemote(http)
            assertFalse(source.latest().online)
            assertTrue(source.latest().online)
            assertTrue(source.last!!.online)
            assertEquals(2, count)
        }
    }

    @Test fun `HTTP 错误不把有效响应体当成成功且临时故障只重试一次`() = runBlocking {
        var count = 0
        client {
            count++
            Reply(sample.s("rawResponse").toByteArray(), status = HttpStatusCode.ServiceUnavailable)
        }.use { http ->
            assertFalse(MetroRemote(http).latest().online)
            assertEquals(2, count)
        }
    }

    private data class Reply(val bytes: ByteArray, val status: HttpStatusCode = HttpStatusCode.OK, val headers: Headers = Headers.Empty)

    /** 用现有 Ktor 引擎接口截获请求，不创建连接，也不增加测试依赖。 */
    @OptIn(io.ktor.util.InternalAPI::class)
    private fun client(reply: (HttpRequestData) -> Reply): HttpClient = HttpClient(object : HttpClientEngineBase("地铁协议测试") {
        override val config = HttpClientEngineConfig()
        override val dispatcher = Dispatchers.Unconfined
        override val supportedCapabilities = setOf(HttpTimeout)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val result = reply(data)
            return HttpResponseData(result.status, GMTDate(), result.headers, HttpProtocolVersion.HTTP_1_1, ByteReadChannel(result.bytes), callContext())
        }
    }) { install(HttpTimeout) }
}
