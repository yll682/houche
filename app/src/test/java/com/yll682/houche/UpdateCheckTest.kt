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
        // 断言只压格式，不压具体版本号：每次发版都要改 version.json，写死会连带改测试。
        assertTrue(github.versionName.matches(Regex("""\d+\.\d+\.\d+""")))
        assertTrue(github.versionCode > 0)
        assertTrue(github.notes.isNotEmpty())
        assertEquals("GitHub", github.source)
        assertTrue(github.downloadUrl.startsWith("https://github.com/yll682/houche/releases/download/v"))
        assertTrue(github.downloadUrl.endsWith("houche-v${github.versionName}.apk"))

        // 同源优先：来源为 CNB 时拿 CNB 的下载链接。
        val cnb = parseRelease(json, "CNB")
        assertEquals("CNB", cnb.source)
        assertTrue(cnb.downloadUrl.startsWith("https://cnb.cool/yll682/houche/-/releases/download/v"))
        assertTrue(cnb.downloadUrl.endsWith("houche-v${cnb.versionName}.apk"))
        assertEquals(github.versionCode, cnb.versionCode)
        assertEquals(github.versionName, cnb.versionName)
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