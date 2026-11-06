package com.yll682.houche

import com.yll682.houche.core.BusException
import com.yll682.houche.core.RemoteRelease
import com.yll682.houche.core.isNewer
import com.yll682.houche.core.parseRelease
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// 纯 JVM 用例：只比较版本号与解析字段，不发起任何网络请求。
class UpdateCheckTest {
    private fun release(code: Int, name: String = "1.3.0") =
        RemoteRelease(versionName = name, versionCode = code, notes = "", source = "GitHub", downloadUrl = "")

    private fun versionJson() = sequenceOf(File("version.json"), File("../version.json"))
        .first { it.exists() }.readText()

    @Test fun newerCodesAreDetected() {
        assertTrue(isNewer(release(4), 3))
        assertTrue(isNewer(release(5), 4))
    }

    @Test fun sameCodeIsNotNewer() {
        assertFalse(isNewer(release(4), 4))
    }

    @Test fun olderCodeIsNotNewer() {
        assertFalse(isNewer(release(3), 4))
        assertFalse(isNewer(release(1), 100))
    }

    @Test fun parsesReleaseFields() {
        val json = Json.parseToJsonElement(versionJson())
        val github = parseRelease(json, "GitHub")
        assertEquals("1.3.0", github.versionName)
        assertEquals(4, github.versionCode)
        assertTrue(github.notes.isNotEmpty())
        assertEquals("GitHub", github.source)
        assertEquals("https://github.com/yll682/houche/releases/download/v1.3.0/houche-v1.3.0.apk", github.downloadUrl)

        // 同源优先：来源为 CNB 时拿 CNB 的下载链接。
        val cnb = parseRelease(json, "CNB")
        assertEquals("CNB", cnb.source)
        assertEquals("https://cnb.cool/yll682/houche/-/releases/download/v1.3.0/houche-v1.3.0.apk", cnb.downloadUrl)
        assertEquals(github.versionCode, cnb.versionCode)
    }

    @Test fun rejectsReleaseWithoutVersionCode() {
        val json = Json.parseToJsonElement("""{"versionName":"1.3.0","notes":"","downloads":{}}""")
        try {
            parseRelease(json, "GitHub")
            fail("应拒绝缺少版本号的 JSON")
        } catch (error: BusException) {
            assertEquals(BusException.Kind.UPSTREAM, error.kind)
        }
    }
}