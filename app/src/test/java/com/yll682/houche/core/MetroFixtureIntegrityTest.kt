package com.yll682.houche.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class MetroFixtureIntegrityTest {
    @Test fun restoredHistoricalSamplesKeepTheirArchivePayloads() {
        val names = listOf("metro-response-sample.json", "metro-realtime-line2-flag2-sample.json")
        names.forEach { name ->
            val file = MetroFixtures.file(name)
            assertTrue("历史样本从版本化测试资源读取", file.canonicalPath.contains("/app/src/test/resources/"))
        }
        // 站表只在 sampleNote 补充来源说明，归档中的原始响应、明文和密钥字符串逐字节不变。
        val station = BusNetwork.json.parseToJsonElement(MetroFixtures.file(names.first()).readText())
        val payload = listOf("rawResponse", "plaintext", "cipherHexUpper", "secret")
            .joinToString("\u0000") { station.s(it) }.toByteArray(Charsets.UTF_8)
        assertEquals("8629fb024cbae62132899c5b3e965cd2cb0886ede95e98ae60069b9460d5b216", sha256(payload))
        assertEquals(
            "855d560a52f9d1a30e88b2a6cc87e3f7f74b19d3738b92be7258bcd410c400a0",
            sha256(MetroFixtures.file(names.last()).readBytes())
        )
    }

    @Test fun historicalFlag2SampleKeepsItsPlaintextAndExactTrainValues() {
        val sample = BusNetwork.json.parseToJsonElement(MetroFixtures.file("metro-realtime-line2-flag2-sample.json").readText())
        val envelope = BusNetwork.json.parseToJsonElement(sample.s("rawResponse"))
        assertEquals(listOf("responseData"), sample.list("outerKeys").map { it.text() })
        assertEquals(sample.s("plaintext"), MetroGateway.decrypt(envelope.s("responseData")))
        val frame = MetroRealtimeRemote().parse(sample.s("rawResponse"), "02000000", "02170000")
        assertTrue(frame.note, frame.online)
        assertEquals("02170000", frame.stationCode)
        val train = frame.directions.first().trains.first()
        assertEquals(2, train.arrivingFlag)
        assertEquals(44, train.arriveSeconds)
        assertEquals(0.62, train.scale, 0.0)
    }

    @Test fun restoredRound2FilesMatchTheHistoricalManifest() {
        val manifest = MetroFixtures.file("metro-round2/manifest.json")
        val entries = BusNetwork.json.parseToJsonElement(manifest.readText()).list("files")
        assertEquals(51, entries.size)
        assertEquals(53, manifest.parentFile!!.listFiles().orEmpty().count { it.isFile })
        entries.forEach { entry ->
            val file = MetroFixtures.file("metro-round2/${entry.s("file")}")
            assertEquals(entry.s("file"), entry.i("bytes")!!.toLong(), file.length())
            assertEquals(entry.s("file"), entry.s("sha256"), sha256(file.readBytes()))
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
