package com.yll682.houche.ui.screens.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 位置条交互的硬断言（不靠肉眼看图）：
 *
 * 1. 点位置条上的站名 → 真的回调了 `selectStation`，并且「选中项」从旧站挪到新站；
 * 2. 横向拖动 → 不触发选中（`clickable` 与滚动的手势竞争）；
 * 3. 站点命中区 ≥ 44dp 且宽度覆盖整列（不是只点得中文字）；
 * 4. 点「刷新」→ 图标真的转（同一区域前后两张图有差异），收尾后停回整圈
 *    （与未转时**逐像素一致**，即角度归零看不出跳变）。
 *
 * 数据用 2 号线现场构造（与 `ScreenshotTest.metroLine2Xinan_light` 同一场景），
 * 站码、站名全部来自 `assets/metro.json`，不手抄。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroStripInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<MetroNetwork>(text).openedLines
    }
    private val line2: MetroLine by lazy { lines.first { it.id == "2" } }

    /** 往天竺山方向：站序与 metro.json 相反。 */
    private val codes: List<String> by lazy { line2.liveStations.map { it.code }.reversed() }
    private val names: Map<String, String> by lazy { line2.liveStations.associate { it.code to it.name } }

    /** 参考站「新垵」；它在 reversed 站序里靠后，两边都有站，视口锚定 78% 后左右邻居都在屏内。 */
    private val reference = "02070000"
    private val referenceIndex by lazy { codes.indexOf(reference) }

    private fun direction(): MetroDirection {
        val source = MetroObservationSource(reference)
        fun train(seconds: Int, at: Int) = MetroTrain(
            arriveSeconds = seconds, nextStationCode = codes[referenceIndex + at],
            nextStationName = names.getValue(codes[referenceIndex + at]),
            scale = 0.0, arrivingFlag = 1, specialPosition = 0, specialPositionDesc = "", source = source
        )
        val first = train(170, 2)
        val second = train(510, 3)
        return MetroDirection(
            origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
            startTime = "06:00", endTime = "23:00", delayMinutes = 0,
            trains = listOf(first, second), stationCodes = codes, source = source,
            positionTrains = listOf(first, second), arrivalsAvailable = true
        )
    }

    private fun seed(): MetroData = MetroData(
        lines = lines, selected = "2", updated = "2026-11-08",
        realtime = MetroRealtime(
            online = true, note = "", lineCode = "02000000",
            stationCode = reference, stationName = names.getValue(reference),
            directions = listOf(direction())
        ),
        realtimeStation = reference, realtimeStationName = names.getValue(reference),
        realtimeUpdated = "12:40", directionIndex = 0
    )

    /**
     * 只放一班车（下一站 = 参考站往前 `at` 站、段内进度 `scale`），用来量车标的横坐标。
     *
     * 造例必须给**真实比例**：core 的 `positionOf` 现在是 `index(next) − 1 + scale`，
     * `arrivingFlag` 不参与坐标（flag=2 也可能是 scale=.83 的站前车）。
     * 旧造例只动 flag 不动 scale（0.0），两批位置的 `stationPosition` 完全相同，
     * 车标自然一格都不动——那是造例不合法，不是滑动逻辑坏了。
     */
    private fun oneTrain(at: Int, scale: Double, flag: Int = 1, seconds: Int = 170): MetroData {
        val next = codes[referenceIndex + at]
        val train = MetroTrain(
            arriveSeconds = seconds, nextStationCode = next, nextStationName = names.getValue(next),
            scale = scale, arrivingFlag = flag, specialPosition = 0, specialPositionDesc = "",
            source = MetroObservationSource(reference)
        )
        val direction = direction().copy(trains = listOf(train), positionTrains = listOf(train))
        return seed().let { it.copy(realtime = it.realtime!!.copy(directions = listOf(direction))) }
    }

    /** 位置条上的站点节点：站名做 contentDescription，`selected` 表示当前参考站。 */
    private fun stripStation(name: String) = compose.onAllNodesWithContentDescription(name).onFirst()

    private fun Metro(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Test
    fun stripStationTapSwitchesReference() {
        var data by mutableStateOf(seed())
        val picks = mutableListOf<String>()
        Metro {
            MetroContent(
                UiState.Success(data), {}, {},
                selectStation = { code ->
                    picks += code
                    // 模拟 ViewModel 那一步的可见结果：参考站跟着数据走（这里不清实时数据，好看清选中变化）
                    data = data.copy(
                        realtimeStation = code, realtimeStationName = names[code].orEmpty(),
                        // 真机上参考站随刷新回来的响应一起更到 realtime.stationCode，位置条按它画选中环
                        realtime = data.realtime?.copy(stationCode = code, stationName = names[code].orEmpty())
                    )
                }
            )
        }
        compose.waitForIdle()

        val target = codes[referenceIndex - 1]
        val targetName = names.getValue(target)
        val oldName = names.getValue(reference)

        stripStation(targetName).performClick()
        compose.waitForIdle()
        // 点站先弹底部面板，面板里点「设为乘车站」才真正切换。
        assertTrue("点站名只开面板，不能直接切换，实际回调 $picks", picks.isEmpty())
        compose.onNodeWithTag("station-confirm").performClick()
        compose.waitForIdle()

        assertEquals("确认后必须把站码透给 selectStation", listOf(target), picks)
        assertEquals("选中项应换成刚点的站", target, data.realtimeStation)
        stripStation(targetName).assertIsSelected()
        stripStation(oldName).assertIsNotSelected()
    }

    @Test
    fun stripDragDoesNotSwitchReference() {
        var data by mutableStateOf(seed())
        val picks = mutableListOf<String>()
        Metro { MetroContent(UiState.Success(data), {}, {}, selectStation = { code -> picks += code }) }
        compose.waitForIdle()

        stripStation(names.getValue(codes[referenceIndex - 1])).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertTrue("横向拖动不能触发选站，实际回调 $picks", picks.isEmpty())
        assertEquals(reference, data.realtimeStation)
    }

    @Test
    fun stripStationHitAreaCoversWholeColumn() {
        Metro { MetroContent(UiState.Success(seed()), {}, {}) }
        compose.waitForIdle()

        val density = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.displayMetrics.density
        val bounds = stripStation(names.getValue(reference)).fetchSemanticsNode().boundsInRoot
        assertTrue("命中区高度应 ≥ 44dp，实际 ${bounds.height / density}dp", bounds.height / density >= 44f)
        assertTrue("命中区宽度应覆盖整列（一格 68.2dp），实际 ${bounds.width / density}dp", bounds.width / density >= 68f)
    }

    @Test
    fun refreshGlyphSpinsThenStopsOnWholeTurn() {
        compose.mainClock.autoAdvance = false
        Metro { MetroContent(UiState.Success(seed()), {}, {}, refresh = {}) }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }

        val button = compose.onNodeWithContentDescription("刷新")
        val idle = crop(button)

        // 走点击语义（等价于点按回调），避开按压水波纹干扰像素比较；
        // 真实手势那条路由 stripStationTapSwitchesReference 用 performClick 覆盖。
        button.performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(240)
        val spinning = crop(button)
        assertNotEquals("点下 240ms 后图标应已经转起来", 0, diff(idle, spinning))

        // 600ms 最短时长 + 最多补完一圈 700ms，3000ms 内必然收尾。
        compose.mainClock.advanceTimeBy(3_000)
        val settled = crop(button)
        assertEquals("刷新结束后应平滑停在整圈，与未转时逐像素一致", 0, diff(idle, settled))
    }

    /**
     * 车标位移：同一班车小幅前进（下一站不变、scale 0.10→0.64，位移 0.54 格）应滑过去；
     * 跨度超过 1.5 格（这里 2.6 格）必须吸附，不能从屏幕一头飞过去。
     *
     * 造例照抄真实响应语义：位置＝`index(next) − 1 + scale`，flag 只是服务端状态，
     * 所以「同下一站、scale 变大、ETA 变小」才是真正的同一班车往前走一步。
     */
    @Test
    fun trainMarkerGlidesOnSmallMoveAndSnapsOnBigJump() {
        compose.mainClock.autoAdvance = false
        var data by mutableStateOf(oneTrain(at = -1, scale = 0.10, flag = 1, seconds = 170))
        Metro { MetroContent(UiState.Success(data), {}, {}) }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }

        val start = markerLeft()

        // 同一班车从「下一站前一段 10%」走到「到站前进站段 64%」（0.54 格），ETA 同步 170→105 秒。
        data = oneTrain(at = -1, scale = 0.64, flag = 2, seconds = 105)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        val mid = markerLeft()
        compose.mainClock.advanceTimeBy(600)
        val end = markerLeft()
        assertTrue("过渡中车标应停在起点与终点之间：$start → $mid → $end", mid > start + 8 && mid < end - 8)
        assertEquals("400ms 后应停在新位置（0.54 格 ≈ 110px）", 110f, (end - start).toFloat(), 8f)

        // 跨度 2.6 格（换站/重排）→ 直接吸附：50ms 后就已经在新位置（插值此时只走得出约 15%）。
        data = oneTrain(at = -4, scale = 1.0, flag = 2, seconds = 480)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(50)
        val snapped = markerLeft()
        compose.mainClock.advanceTimeBy(600)
        val settled = markerLeft()
        assertTrue("大跨度应吸附而不是插值：$end → $snapped", kotlin.math.abs(snapped - end) > 300)
        assertEquals("吸附后不应再有位移", settled, snapped, .5f)
    }

    /**
     * 「第1班」车标的左边界（px）。
     *
     * 用未裁剪的 `positionInRoot` 而不是 `boundsInRoot`：位置条是横向滚动 + `clipToBounds` 的，
     * 车标滑到视口边缘外时 `boundsInRoot` 会被裁到视口边界（实测吸附后少算 10px，
     * 而且「吸附后不再移动」会被裁切掩盖），量位移必须用 layout 坐标。
     */
    private fun markerLeft() =
        compose.onAllNodesWithContentDescription("第1班").onFirst().fetchSemanticsNode().positionInRoot.x

    // ---- 截图工具：直接画真实 Compose View（与 ScreenshotTest 同一套做法） ----

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

    private fun diff(a: Bitmap, b: Bitmap): Int {
        var count = 0
        for (y in 0 until minOf(a.height, b.height)) {
            for (x in 0 until minOf(a.width, b.width)) if (a.getPixel(x, y) != b.getPixel(x, y)) count++
        }
        return count
    }
}
