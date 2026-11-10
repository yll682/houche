package com.yll682.houche.ui.screens.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroStation
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import kotlin.math.roundToInt

/** 真实 ETA 组件的语义、排版与像素验证；输出写 /tmp，不覆盖历史截图。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Android qualifier 顺序固定：night/notnight 属于 night mode，必须排在 density(xxhdpi) 之前。
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-notnight-xxhdpi", application = android.app.Application::class)
class MetroEtaMessageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allStatesKeepReadyGeometryAndOfficialCopyInLightMode() = verifyStates(OfficialLight, "light")

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-xxhdpi")
    fun allStatesKeepReadyGeometryAndOfficialCopyInDarkMode() = verifyStates(OfficialDark, "dark")

    private fun verifyStates(palette: OfficialPalette, mode: String) {
        val rows = listOf(180, 540, 960).map { seconds ->
            MetroTrain(seconds, "01010000", "镇海路", .5, 1, 0, "")
        }
        var state: MetroEtaState by mutableStateOf(MetroEtaState.Ready("01010000", rows))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    // 340dp 卡片 − 左右20dp = 300dp 蓝块；2.0.x 起高度至少 96dp，density3 即900×288px。
                    Box(Modifier.width(340.dp)) { MetroEtaBlock(state, ordinalOfRow = { it }) }
                }
            }
        }
        compose.waitForIdle()
        val ready = etaBounds()
        assertEquals(900f, ready.width, 0f)
        assertEquals(288f, ready.height, 0f)
        // 2.0.x 列序：按原顺序，第1班在最左：左3/中9/右16。
        val columns = listOf("3 分钟第1班预计到达", "9 分钟第2班预计到达", "16 分钟第3班预计到达")
            .map { description ->
                compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
        assertTrue(columns.zipWithNext().all { (left, right) -> left.left < right.left })
        saveBlock("ready-$mode", ready)

        val states = listOf(
            "loading" to MetroEtaState.Loading("01010000"),
            "empty" to MetroEtaState.Empty("01010000"),
            "unavailable" to MetroEtaState.Unavailable("01010000", "timeout"),
            "out-of-service" to MetroEtaState.OutOfService("01010000")
        )
        states.forEach { (name, next) ->
            compose.runOnIdle { state = next }
            compose.waitForIdle()
            assertEquals("$name 不改变蓝块任何几何", ready, etaBounds())
            val message = requireNotNull(metroEtaMessage(next))
            val text = compose.onNodeWithText(message).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(16.sp, layout.layoutInput.style.fontSize)
            assertEquals(16.sp, layout.layoutInput.style.lineHeight)
            assertEquals(2, layout.layoutInput.maxLines)
            assertEquals(if (next is MetroEtaState.OutOfService) 2 else 1, layout.lineCount)
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            // 文案既不能被省略号截断，也不能被裁掉。
            // 这里不用 TextLayoutResult.hasVisualOverflow：设了 textAlign 之后段落按约束宽度
            // （756px）排版，而节点宽度是文字自身宽度（如「正在获取」192px），Compose 因此报
            // 「溢出宽」，实际既没省略也没裁掉；改用「没有省略号 + 没有高度溢出」判定。
            assertFalse("$name 文案不得被裁掉（高度溢出）", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("$name 第${line + 1}行不得出现省略号", layout.isLineEllipsized(line))
            }
            val bitmap = saveBlock("$name-$mode", ready)
            verifyIcon(bitmap, textBounds, ready, palette)
            assertEquals("背景沿用现有palette", palette.EtaBackground.toArgb(), bitmap.getPixel(20, 20))
            println("round10 ETA $name-$mode: bounds=$ready, text=$textBounds, font=16sp, lineHeight=16sp, lines=${layout.lineCount}")
        }
        // 切回正常到站列，验证四种提示都没有改变 Ready 的原锚点。
        compose.runOnIdle { state = MetroEtaState.Ready("01010000", rows) }
        compose.waitForIdle()
        assertEquals(ready, etaBounds())
        columns.forEachIndexed { index, before ->
            val description = listOf("3 分钟第1班预计到达", "9 分钟第2班预计到达", "16 分钟第3班预计到达")[index]
            assertEquals(before, compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot)
        }
        println("round10 ETA ready-$mode: bounds=$ready, columns=$columns, afterNonReady=unchanged")
    }

    /** 图标（Outlined.Info 20dp，主色）位于文本前 28dp（20dp 图标 + 8dp 间隙）：图标区内必须有主色墨迹。 */
    private fun verifyIcon(bitmap: Bitmap, text: Rect, block: Rect, palette: OfficialPalette) {
        val iconLeft = (text.left - block.left - 84f).roundToInt()
        val indigo = palette.TabIndicator.toArgb()
        var ink = 0
        for (y in 0 until bitmap.height) for (x in 0 until 60) {
            if (bitmap.getPixel(iconLeft + x, y) == indigo) ink++
        }
        assertTrue("文本左侧必须实际绘制主色图标，主色像素=$ink", ink > 0)
        println("round10 ETA icon: iconLeft=$iconLeft, ink=$ink")
    }

    @Test
    fun oneShotFailureEventShowsOfficialToastAndRecompositionDoesNotReplayIt() {
        ShadowToast.reset()
        val channel = Channel<MetroEvent>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()
        var change by mutableStateOf(0)
        compose.setContent {
            // 状态变化会重组，事件流保持同一个实例。
            Box(Modifier.width((340 + change).dp)) { MetroEventEffects(events) }
        }
        compose.waitForIdle()
        assertEquals(0, ShadowToast.shownToastCount())
        compose.runOnIdle { channel.trySend(MetroEvent.StationTimetableUnavailable("内洲")) }
        compose.waitForIdle()
        assertEquals("暂时无法获取该站点列车时刻表", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
        compose.runOnIdle { change++ }
        compose.waitForIdle()
        assertEquals("重组不能重复弹失败提示", 1, ShadowToast.shownToastCount())
        channel.close()
    }

    @Test
    fun stationListUsesTheSamePerFieldOperatingTimeFallback() {
        val firstOnly = MetroStation(1, "首班站", code = "01010000", first = "08:02")
        val lastOnly = MetroStation(2, "末班站", code = "01020000", last = "次日00:20")
        val line = MetroLine("1", "1号线", "#DA7028", "首班站", "末班站", first = "08:00", last = "20:00", stations = listOf(firstOnly, lastOnly))
        val data = MetroData(lines = listOf(line), selected = "1", realtimeStation = "01010000", realtimeStationName = "首班站")
        compose.setContent { MaterialTheme { MetroContent(UiState.Success(data), {}) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("首班站，首 08:02，末 20:00，乘车站").assertIsDisplayed()
        compose.onNodeWithContentDescription("末班站，首 08:00，末 次日00:20").assertIsDisplayed()
    }

    private fun etaBounds(): Rect = compose.onNodeWithContentDescription("预计到达").fetchSemanticsNode().boundsInRoot

    private fun saveBlock(name: String, bounds: Rect): Bitmap {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var screenshot: Bitmap
        compose.runOnUiThread {
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            screenshot = Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt())
        }
        val folder = File("/tmp/houche-round10-ui").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return screenshot
    }
}
