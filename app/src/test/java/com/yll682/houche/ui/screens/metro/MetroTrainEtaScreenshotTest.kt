package com.yll682.houche.ui.screens.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRunningTimeProfile
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「选中车 + 选中站」状态的截图自检。
 *
 * 输出目录由 `screenshots.dir` 系统属性指定（Gradle 里指向 `docs/screenshots`）。
 * 这一张专门看两件事：选中的那辆车在位置条上一眼能不能认出来，
 * 以及卡片底部那行时间文案读不读得懂（分钟、目标站名、「推算」口径都在不在）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroTrainEtaScreenshotTest {
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
    private val reference = "02070000"
    private val referenceIndex by lazy { codes.indexOf(reference) }

    /** 区间运行时间：每站 120 秒（真实接口 wayStations 自带，这里造一个同规格的）。 */
    private val profile by lazy { MetroRunningTimeProfile(codes, codes.indices.map { it * 120 }) }

    private fun train(seconds: Int, at: Int, scale: Double = 0.0, flag: Int = 1): MetroTrain {
        val next = codes[referenceIndex + at]
        return MetroTrain(
            arriveSeconds = seconds, nextStationCode = next, nextStationName = names.getValue(next),
            scale = scale, arrivingFlag = flag, specialPosition = 0, specialPositionDesc = "",
            source = MetroObservationSource(reference)
        )
    }

    /** 两班车都在参考站上游：第 1 班最靠近本站，第 2 班再往上游一站半。 */
    private fun direction(): MetroDirection {
        val first = train(300, at = -1, scale = .5)
        val second = train(600, at = -2)
        return MetroDirection(
            origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
            startTime = "06:00", endTime = "23:00", delayMinutes = 0,
            trains = listOf(first), stationCodes = codes, source = MetroObservationSource(reference),
            positionTrains = listOf(first, second), arrivalsAvailable = true,
            runningTimeProfile = profile
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
        realtimeUpdated = "12:40", directionIndex = 0,
        // 选中的第 1 班 + 目标站（本站往上游一站）：ETA = 300 − 120 = 180 秒 → 约 3 分钟。
        selectedTrain = MetroSelectedTrain(markerKey = 1, nextStationCode = codes[referenceIndex - 1]),
        selectedTargetStation = codes[referenceIndex - 1]
    )

    /**
     * 截图：仍截真实 Compose View，只把窗口 PixelCopy 换成 NATIVE Canvas
     * （Compose 1.7 的内置 captureToImage 在 Robolectric 等窗口重绘会超时，
     * 见 https://github.com/robolectric/robolectric/issues/8071；与 ScreenshotTest 同一套做法）。
     */
    private fun captureView(): Bitmap {
        val node = compose.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun capture(name: String, cropToRow: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
        val full = captureView()
        val bitmap = if (!cropToRow) full else {
            // 卡片特写：裁到「推算到站」那一行为止（含一点下边距）。
            val row = compose.onNodeWithTag("metro-train-eta").fetchSemanticsNode()
            val density = ApplicationProvider.getApplicationContext<android.content.Context>()
                .resources.displayMetrics.density
            val bottom = (row.boundsInRoot.bottom + 18 * density).toInt().coerceIn(1, full.height)
            Bitmap.createBitmap(full, 0, 0, full.width, bottom)
        }
        val dir = File(requireNotNull(System.getProperty("screenshots.dir"))).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG 写出失败：$name" }
        }
    }

    /** 整页：地铁页在「选中车 + 选中站」状态下的样子。 */
    @Test
    fun metroTrainEtaPage() = capture("metro-train-eta-page") {
        MetroContent(UiState.Success(seed()), {}, modifier = Modifier.testTag("metro-page"))
    }

    
        /** 卡片特写：位置条上的选中态 + 底部那行时间文案。 */
    @Test
    fun metroTrainEtaCard() = capture("metro-train-eta-card", cropToRow = true) {
        val data = seed()
        MetroRealtimeCard(
            direction = data.direction,
            directionCount = 1,
            stationCode = reference,
            etaState = data.etaState,
            loading = false,
            note = "",
            stationName = names.getValue(reference),
            line = line2,
            directionIndex = 0,
            onDirection = {},
            onRefresh = {},
            selectedTrain = data.selectedTrain,
            targetStationCode = data.selectedTargetStation,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}