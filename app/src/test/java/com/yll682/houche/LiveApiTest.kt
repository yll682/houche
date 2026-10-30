package com.yll682.houche

import com.yll682.houche.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

// JUnit 默认忽略；-Dlive=true 时仅对本测试类解除 Ignore，无须改源代码。
class LiveApiRunner(type: Class<*>) : BlockJUnit4ClassRunner(type) {
    private val enabled = System.getProperty("live") == "true"
    override fun isIgnored(child: FrameworkMethod): Boolean = if (enabled) false else super.isIgnored(child)

}
@RunWith(LiveApiRunner::class)
class LiveApiTest {
    private val repository = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef"))
    private val xm = City("11", "厦门市", "0592")
    private val bj = City("0", "北京市", "010")
    private fun record(text: String) { println("联网自检：$text") }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenSearch() = runBlocking {
        val result = repository.search(xm, "91路")
        record("厦门市 搜索 91路：线路=${result.lines.size}，站点=${result.stations.size}，样例=${result.lines.firstOrNull()}")
        Assert.assertTrue(result.lines.isNotEmpty())
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenLine() = runBlocking {
        val result = repository.lineDetail(xm, "91路", "1")
        record("厦门市 线路详情：name=${result.name}，id=${result.id}，方向=${result.direction}，站点=${result.stations.size}，首末=${result.first}/${result.last}，起终=${result.from}/${result.to}，首站=${result.stations.firstOrNull()}")
        Assert.assertTrue(result.stations.size > 5)
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenNearby() = runBlocking {
        val result = repository.nearby(xm, 24.48, 118.13)
        record("厦门市 附近 (24.48,118.13)：站点=${result.size}，首站=${result.firstOrNull()}")
        Assert.assertTrue(result.isNotEmpty())
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingSearch() = runBlocking {
        val result = repository.search(bj, "1路")
        record("北京市 搜索 1路：线路=${result.lines.size}，站点=${result.stations.size}，样例=${result.lines.firstOrNull()}")
        Assert.assertTrue(result.lines.isNotEmpty())
    }
}
