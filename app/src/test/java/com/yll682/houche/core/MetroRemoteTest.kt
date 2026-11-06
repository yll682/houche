package com.yll682.houche.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地铁在线接口的响应解析：不能联网，所以只压「各种畸形响应不能崩、不能被骗」。
 * 真实字段名没有文档，这里锁定的是容错行为，不是上游格式。
 */
class MetroRemoteTest {

    private val remote = MetroRemote()

    @Test fun `网关错误码原样保留在 note 里`() {
        val probe = remote.parse("""{"code":999,"msg":"未认证或者token已过期","ts":1}""")
        assertFalse(probe.online)
        assertEquals("999 未认证或者token已过期", probe.note)
    }

    @Test fun `sign 缺失这类错误也不当成成功`() {
        val probe = remote.parse("""{"code":10004,"msg":"sign缺失"}""")
        assertFalse(probe.online)
        assertTrue(probe.note.contains("sign缺失"))
    }

    @Test fun `非 JSON 响应不崩`() {
        val probe = remote.parse("<html>502 Bad Gateway</html>")
        assertFalse(probe.online)
        assertTrue(probe.note.isNotBlank())
    }

    @Test fun `空对象没有站序就判为不可用`() {
        assertFalse(remote.parse("""{"code":0,"data":{}}""").online)
        assertFalse(remote.parse("""{"code":0,"data":[]}""").online)
    }

    @Test fun `能按线路分组并排出站序`() {
        val body = """
            {"code":0,"data":[
              {"stationName":"镇海路","lineName":"1号线","sort":3},
              {"stationName":"岩内","lineName":"1号线","sort":1},
              {"stationName":"中山公园","lineName":"1号线","sort":2},
              {"stationName":"五缘湾","lineName":"2号线","sort":2},
              {"stationName":"天竺山","lineName":"2号线","sort":1}
            ]}
        """.trimIndent()
        val probe = remote.parse(body)
        assertTrue(probe.online)
        assertEquals(2, probe.lines.size)
        val line1 = probe.lines.first { it.name.contains("1号线") }
        assertEquals(listOf("岩内", "中山公园", "镇海路"), line1.liveStations.map { it.name })
        assertEquals("岩内", line1.from)
        assertEquals("镇海路", line1.to)
    }

    @Test fun `缺 order 时保持原顺序不丢站`() {
        val body = """{"code":0,"data":[{"name":"甲","lineCode":"3"},{"name":"乙","lineCode":"3"}]}"""
        val probe = remote.parse(body)
        assertTrue(probe.online)
        assertEquals(listOf("甲", "乙"), probe.lines.single().liveStations.map { it.name })
    }

    @Test fun `站名缺失的条目跳过而不是崩`() {
        val body = """{"code":0,"data":[{"lineName":"1号线"},{"stationName":"岩内","lineName":"1号线"}]}"""
        val probe = remote.parse(body)
        assertTrue(probe.online)
        assertEquals(listOf("岩内"), probe.lines.single().liveStations.map { it.name })
    }
}