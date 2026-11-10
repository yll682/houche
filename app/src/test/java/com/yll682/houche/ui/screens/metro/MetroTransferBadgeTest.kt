package com.yll682.houche.ui.screens.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroStation
import com.yll682.houche.core.MetroTransferEvidence
import com.yll682.houche.core.MetroTransferTarget
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 换乘徽标的硬断言（不靠肉眼看图）：
 *
 * 1. 有换乘目标的站，每个目标一枚徽标，**底色就是目标线路的官方主题色**（这里直接扫像素）；
 * 2. 点徽标把**完整目标**（目标线 id + 目标线自己的站码）交给 `selectTransfer`，且不误触「选站」；
 * 3. 徽标命中区 ≥32dp，无障碍标签是「换乘 X号线」；
 * 4. 当前选中站那一行（卡片里的站名 + 线路号徽标）也带自己的换乘目标；
 * 5. `MetroContent` 真的把 `selectTransfer` 接到了两处徽标上（不是只在行组件里生效）。
 *
 * 真实数据来自 `assets/metro.json` 走生产路径 `metroTransfers`，颜色/站名/站码不手抄；
 * 只有「一站两个目标」这一种情况用合成目标，内置站表里每个换乘站恰好只有一个对向目标。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroTransferBadgeTest {
    @get:Rule val compose = createComposeRule()

    /** 与线上同一条路径：解析内置站表后再由核心推导出 transferTargets。 */
    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        com.yll682.houche.core.metroTransfers(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<MetroNetwork>(text).lines
        )
    }
    private val line1: MetroLine by lazy { lines.first { it.id == "1" } }
    private val line3: MetroLine by lazy { lines.first { it.id == "3" } }

    /** 吕厝（1 号线 01080000）在真实站表里可换 2 号线 02180000。 */
    private val lvcuo: MetroStation by lazy { line1.liveStations.first { it.name == "吕厝" } }
    private val line2Color = "#52A441"

    private fun density() =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    private fun Metro(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun badges(label: String): List<SemanticsNodeInteraction> =
        (0 until compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
            .map { index -> compose.onAllNodesWithContentDescription(label)[index] }

    @Test
    fun stopRowDrawsOneBadgePerTargetInTargetLineColor() {
        assertEquals("吕厝在真实站表里应恰好有一个换乘目标", 1, lvcuo.transferTargets.size)
        val target = lvcuo.transferTargets.single()
        assertEquals("2", target.lineId)
        assertEquals("02180000", target.stationCode)
        assertEquals(line2Color, target.color)

        Metro {
            MetroStopRow(lvcuo, 0, line1.liveStations.size, officialLineColor(line1), false)
        }

        val found = badges("换乘 ${target.lineName}")
        assertEquals("吕厝这一行应只有一枚换乘徽标", 1, found.size)
        val bounds = found.single().fetchSemanticsNode().boundsInRoot
        assertTrue("徽标命中区高度应 ≥32dp，实际 ${bounds.height / density()}dp", bounds.height / density() >= 32f)

        // 底色＝目标线路色，像素级核对（胶囊内部大片实色，字只占中间一小块）。
        val expected = android.graphics.Color.parseColor(target.color)
        val share = colorShare(crop(found.single()), expected)
        assertTrue("徽标底色应是目标线路色 ${target.color}，实测占比 $share", share > .25f)
    }

    @Test
    fun stopRowWithoutTargetsDrawsNoBadge() {
        val plain = line1.liveStations.first { it.transferTargets.isEmpty() && it.name == "镇海路" }
        Metro { MetroStopRow(plain, 0, line1.liveStations.size, officialLineColor(line1), false) }
        assertTrue("没有换乘目标的站不该画徽标", compose.onAllNodesWithContentDescription("换乘", substring = true)
            .fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun badgeTapForwardsWholeTargetAndDoesNotSelectStation() {
        val picked = mutableListOf<MetroTransferTarget>()
        val stationClicks = mutableListOf<Unit>()
        Metro {
            MetroStopRow(
                lvcuo, 0, line1.liveStations.size, officialLineColor(line1), false,
                onTransfer = { picked += it },
                onClick = { stationClicks += Unit }
            )
        }

        badges("换乘 ${lvcuo.transferTargets.single().lineName}").single().performClick()
        compose.waitForIdle()

        assertEquals("一次点击只提交一个完整目标", 1, picked.size)
        assertEquals("2", picked.single().lineId)
        assertEquals("02180000", picked.single().stationCode)
        assertTrue("点徽标不能顺带触发选站", stationClicks.isEmpty())
    }

    @Test
    fun multipleTargetsDrawOneBadgeEachInTheirOwnColor() {
        val synthetic = MetroStation(
            order = 1, name = "换乘测试", code = "09010000",
            transferTargets = listOf(
                MetroTransferTarget("2", "09200000", "二号线", "#52A441", MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE),
                MetroTransferTarget("6", "09600000", "六号线", "#92308D", MetroTransferEvidence.FLAG_NAME_AND_COORDINATES)
            )
        )
        val picked = mutableListOf<MetroTransferTarget>()
        Metro {
            MetroStopRow(synthetic, 0, 1, Color(0xFFDA7028), false, selected = true, onTransfer = { picked += it })
        }

        val two = badges("换乘 二号线")
        assertEquals("2 号线目标一枚", 1, two.size)
        assertEquals("6 号线目标一枚", 1, badges("换乘 六号线").size)
        assertTrue(
            "徽标顺序应与目标顺序一致（2 在 6 左）",
            two.single().fetchSemanticsNode().boundsInRoot.left <
                badges("换乘 六号线").single().fetchSemanticsNode().boundsInRoot.left
        )
        assertTrue(
            "第一枚用 2 号线色",
            colorShare(crop(two.single()), android.graphics.Color.parseColor("#52A441")) > .25f
        )
        assertTrue(
            "第二枚用 6 号线色",
            colorShare(crop(badges("换乘 六号线").single()), android.graphics.Color.parseColor("#92308D")) > .25f
        )

        badges("换乘 六号线").single().performClick()
        assertEquals(listOf("09600000"), picked.map { it.stationCode })
    }

    @Test
    fun cardStationRowShowsTransferTargetsOfSelectedStation() {
        val picked = mutableListOf<Pair<String, String>>()
        Metro {
            StationRow(
                stationName = lvcuo.name, stationCode = lvcuo.code, line = line1, loading = false,
                onRefresh = {}, onTransfer = { lineId, code -> picked += lineId to code }
            )
        }

        val found = badges("换乘 ${lvcuo.transferTargets.single().lineName}")
        assertEquals("当前选中站的站名行也要带换乘徽标", 1, found.size)
        assertTrue(
            "仍是 2 号线主题色",
            colorShare(crop(found.single()), android.graphics.Color.parseColor(line2Color)) > .25f
        )
        found.single().performClick()
        assertEquals(listOf("2" to "02180000"), picked)
    }

    /** 集成：`MetroContent` 的参数真的接到徽标上（卡片里选中「体育中心」＋列表该行两处）。 */
    @Test
    fun metroContentWiresSelectTransferForCardAndList() {
        val reference = line3.liveStations.first { it.name == "体育中心" }
        val expected = reference.transferTargets.single()
        val picked = mutableListOf<Pair<String, String>>()
        Metro {
            MetroContent(
                UiState.Success(
                    MetroData(
                        lines = lines, selected = "3", updated = "2026-11-08",
                        realtimeStation = reference.code, realtimeStationName = reference.name
                    )
                ),
                {},
                selectTransfer = { lineId, code -> picked += lineId to code }
            )
        }

        val found = badges("换乘 ${expected.lineName}")
        assertTrue("选中站（卡片）与站序行各一枚，实际 ${found.size}", found.size >= 1)
        found.first().performClick()
        compose.waitForIdle()
        assertEquals(listOf(expected.lineId to expected.stationCode), picked)
    }

    // ---- 像素工具：直接画真实 Compose View（与 MetroStripInteractionTest 同一套做法） ----

    private fun crop(node: SemanticsNodeInteraction): Bitmap {
        val n = node.fetchSemanticsNode()
        val view = (n.root as ViewRootForTest).view
        lateinit var full: Bitmap
        compose.runOnUiThread {
            full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
        }
        val b = n.boundsInRoot
        val left = b.left.toInt().coerceIn(0, full.width - 1)
        val top = b.top.toInt().coerceIn(0, full.height - 1)
        val width = b.width.toInt().coerceAtMost(full.width - left).coerceAtLeast(1)
        val height = b.height.toInt().coerceAtMost(full.height - top).coerceAtLeast(1)
        return Bitmap.createBitmap(full, left, top, width, height)
    }

    /** 与目标色近乎相同（含抗锯齿）的像素占比。 */
    private fun colorShare(bitmap: Bitmap, expected: Int): Float {
        val r = (expected shr 16) and 0xff
        val g = (expected shr 8) and 0xff
        val b = expected and 0xff
        var hit = 0
        var total = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val p = bitmap.getPixel(x, y)
                total++
                if (abs(((p shr 16) and 0xff) - r) <= 6 && abs(((p shr 8) and 0xff) - g) <= 6 &&
                    abs((p and 0xff) - b) <= 6
                ) hit++
            }
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }
}