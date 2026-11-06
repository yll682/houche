package com.yll682.houche

import com.yll682.houche.core.UpdateClient
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

/**
 * 检查更新的联网自检：真的去打 GitHub 与 CNB 两个 raw 地址。
 * 与 LiveApiTest 同样默认忽略，`-Dlive=true` 时才跑。
 */
@RunWith(LiveApiRunner::class)
class UpdateLiveTest {
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun latestRelease() = runBlocking {
        val release = UpdateClient().latest()
        println("联网自检：检查更新来源=${release.source}，版本=${release.versionName}（${release.versionCode}），下载=${release.downloadUrl}")
        Assert.assertTrue("版本号应不小于当前发布版本", release.versionCode >= 4)
        Assert.assertTrue("版本名不应为空", release.versionName.isNotBlank())
        Assert.assertTrue("下载链接应是 https", release.downloadUrl.startsWith("https://"))
        Assert.assertTrue("来源应是 GitHub 或 CNB", release.source == "GitHub" || release.source == "CNB")
    }

    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun downloadUrlIsReachable() = runBlocking {
        val release = UpdateClient().latest()
        val url = java.net.URL(release.downloadUrl).openConnection() as java.net.HttpURLConnection
        url.connectTimeout = 15_000
        url.readTimeout = 15_000
        url.instanceFollowRedirects = true
        url.setRequestProperty("Range", "bytes=0-99")
        val code = url.responseCode
        println("联网自检：下载链接 ${release.downloadUrl} 响应=$code")
        Assert.assertTrue("下载链接应可匿名访问", code in 200..399)
    }
}