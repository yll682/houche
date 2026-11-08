package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroFixtures
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeRemote
import com.yll682.houche.core.mergeMetroRealtime
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ETA 三格的「第N班」必须与「第几近」同向：最近的一班是第 1 班，越往左班号越大，且三格连续无空格。
 *
 * 数据不是手编的：直接读 `analysis/fixtures/metro-realtime-line2-flag2-sample.json`
 * （官方二号线真实响应，参考站 02170000 育秀东路，服务端 2026-11-08 08:15:41 +08），
 * 走生产同一条 `parse` + `mergeMetroRealtime` 路径，再喂给 [MetroContent]。
 *
 * 真实响应里参考站三行的形态是：第 1 行 `arriveNextStationCode` 就是本站（flag=2），
 * 第 2、3 行是本站**上游**的更远站（flag=1），ETA 越大越靠上游；
 * 所以「行驶进度序」与「本站到站先后」在这批真实数据上是同一件事，班号必须是 1/2/3。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroEtaOrdinalOrderTest {
    @get:Rule val compose = createComposeRule()

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<MetroNetwork>(text).openedLines
    }

    /** 真实响应明文封装；`rawResponse` 是官方加密外壳，由生产 `parse` 解密。 */
    private val realResponse: MetroRealtime by lazy {
        val outer = MetroFixtures.file("metro-realtime-line2-flag2-sample.json").readText()
        val raw = Json.parseToJsonElement(outer).jsonObject.getValue("rawResponse").jsonPrimitive.content
        MetroRealtimeRemote().parse(raw, "02000000", "02170000")
    }

    /** 与生产一样：单参考站的一批快照合并成本站视图。 */
    private val frame: MetroRealtime by lazy {
        mergeMetroRealtime(listOf(realResponse), "02170000", "02000000")
    }

    private fun data(): MetroData = MetroData(
        lines = lines, selected = "2", updated = "2026-11-08",
        realtime = frame, realtimeLastSuccessfulFrame = frame,
        realtimeStation = "02170000", realtimeStationName = frame.stationName,
        realtimeUpdated = "08:15", directionIndex = 0
    )

    private fun Metro(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Test
    fun realPayloadKeepsMovingDirectionAndArrivalOrderInStep() {
        val direction = requireNotNull(data().direction)
        // 三行都来自参考站自己的响应，行序＝ETA 从小到大。
        assertEquals(3, direction.trains.size)
        val seconds = direction.trains.map { it.arriveSeconds }
        assertEquals("真实行序必须是从近到远", seconds.sorted(), seconds)

        val presentations = direction.positionPresentations()
        assertEquals("每行都要有唯一呈现记录", 3, presentations.size)
        assertEquals(
            "本站第 k 行的全方向班号必须等于 k，否则 ETA 的班号就会与远近相反",
            listOf(1, 2, 3),
            presentations.map { it.arrivalRowOrder }
        )
        assertEquals(
            "行关联与班号必须一一对上，不出现空格",
            listOf(1, 2, 3),
            presentations.map { it.ordinal }
        )
        // 越近越靠后（ordinal 小）＝坐标越靠该方向终点。
        assertTrue(
            "位置必须随行序单调回退，实际 ${presentations.map { it.stationPosition }}",
            presentations.map { it.stationPosition }.zipWithNext().all { (near, far) -> near > far }
        )
    }

    @Test
    fun etaCellsNumberNearestFirstWithoutGaps() {
        val data = data()
        assertTrue("真实响应必须已取到本站到站行", data.etaState is com.yll682.houche.core.MetroEtaState.Ready)
        Metro { MetroContent(UiState.Success(data), {}, {}) }
        compose.waitForIdle()

        // 真实值：44s/404s/764s → 1/7/13 分钟；最近的一班在最右一格，是第 1 班。
        val nearest = compose.onNodeWithContentDescription("1 分钟第1班预计到达")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val middle = compose.onNodeWithContentDescription("7 分钟第2班预计到达")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val farthest = compose.onNodeWithContentDescription("13 分钟第3班预计到达")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        // 列序反排：最右＝最近。班号 3 → 2 → 1 从左到右。
        assertTrue("第1班必须在第2班右边", nearest.left > middle.left)
        assertTrue("第2班必须在第3班右边", middle.left > farthest.left)

        // 三格都不许退化成没有班号的「预计到达」。
        val descriptions = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        ).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        val degraded = descriptions.filter { it.matches(Regex("""^\d+ 分钟预计到达$""")) }
        assertTrue("ETA 格不许丢掉班号，实际退化：$degraded", degraded.isEmpty())
        // 唯一允许的裸「预计到达」是 ETA 容器的语义标题。
        assertEquals(1, descriptions.count { it == "预计到达" })
    }

    @Test
    fun mergedFrameKeepsGlobalOrdinalEqualsRowOrderForEveryDirection() {
        // 两个方向都要成立：反方向站码链是同一集合的逆序，班号不能因此翻转。
        frame.directions.forEachIndexed { index, direction ->
            assertTrue("方向 $index 必须有到站行", direction.trains.isNotEmpty())
            val presentations = direction.positionPresentations()
            assertEquals(
                "方向 $index（${direction.origin}→${direction.destination}）行序与班号不一致",
                presentations.map { it.ordinal },
                presentations.map { it.arrivalRowOrder }
            )
            assertNull(
                "方向 $index 不该出现无法关联本站行的位置",
                presentations.firstOrNull { it.arrivalRowOrder == null }
            )
        }
    }
}
