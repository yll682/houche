package com.yll682.houche.ui.screens.metro

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 线路徽标「数字 vs 号线」的墨迹回归：1/2/3/4/6 五个数字逐个量，两块墨迹盒不得相交。
 *
 * 用 [badgeInkOf]（生产代码里同一份测量）在 density 3 下算，数值与截图里的像素一致，
 * 见 `notes/ui-badge-back-2026-11-08.md` 的实测表。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroBadgeInkTest {
    private val density = Density(3f)

    private fun dp(px: Float) = px / 3f

    private fun inkOf(digit: String) = badgeInkOf(digit, density).also { layout ->
        println(
            "徽标 $digit: 色块 32dp | 数字墨迹 " +
                "x[${fmt(dp(layout.digitBox.left))}..${fmt(dp(layout.digitBox.right))}] " +
                "y[${fmt(dp(layout.digitBox.top))}..${fmt(dp(layout.digitBox.bottom))}] " +
                "宽${fmt(dp(layout.digitBox.width))}高${fmt(dp(layout.digitBox.height))}dp scaleX=${layout.digitScaleX} | " +
                "号码墨迹 x[${fmt(dp(layout.smallBox.left))}..${fmt(dp(layout.smallBox.right))}] " +
                "y[${fmt(dp(layout.smallBox.top))}..${fmt(dp(layout.smallBox.bottom))}] | " +
                "间隙 ${fmt(dp(layout.gap))}dp"
        )
    }

    private fun fmt(value: Float) = String.format(java.util.Locale.ROOT, "%.2f", value)

    @Test
    fun digitsNeverOverlapTheLineWord() {
        val block = 32f * 3f
        val minGap = 1f * 3f
        for (digit in listOf("1", "2", "3", "4", "6")) {
            val layout = inkOf(digit)
            val d = layout.digitBox
            val s = layout.smallBox
            // 1. 两块墨迹盒水平不相交，且留出 ≥1dp。
            assertTrue("$digit 号线墨迹左缘 ${s.left} 必须 ≥ 数字右缘 ${d.right} + 1dp", s.left - d.right >= minGap)
            // 2. 两块都在 32dp 色块内。
            for (box in listOf(d, s)) {
                assertTrue("$digit 墨迹越出左边界: ${box.left}", box.left >= 0f)
                assertTrue("$digit 墨迹越出右边界: ${box.right}", box.right <= block)
                assertTrue("$digit 墨迹越出上边界: ${box.top}", box.top >= 0f)
                assertTrue("$digit 墨迹越出下边界: ${box.bottom}", box.bottom <= block)
            }
            // 3. 四周留白不小于现状：数字墨迹左缘 ≥4.00dp（旧版最小 4.0dp），
            //    「号线」墨迹右缘 ≤28.97dp（旧版实测值）。
            assertTrue("$digit 数字左边距缩水: ${dp(d.left)}dp", dp(d.left) >= 4.00f)
            assertTrue("$digit 号码右边距缩水: ${dp(s.right)}dp", dp(s.right) <= 28.97f)
            // 4. 字号没缩：墨迹高度仍是 32sp 的约 23dp，只是宽度收到官方的 12dp 左右。
            assertTrue("$digit 数字墨迹高度变了: ${dp(d.height)}dp", dp(d.height) in 22f..24f)
            assertTrue("$digit 数字墨迹宽度: ${dp(d.width)}dp", dp(d.width) <= 12.6f)
        }
    }

    @Test
    fun digitWidthIsCondensedToTheOfficialRatio() {
        // 五条线统一收到 0.74 上下（4 号线最宽，按间隙自己再收一点），
        // 也就是官方位图 96px 色块里 36px 的数字墨迹比例；字号没有动。
        for (digit in listOf("1", "2", "3", "4", "6")) {
            val scale = inkOf(digit).digitScaleX
            assertTrue("$digit scaleX=$scale", scale in .70f..BadgeDigitMaxScaleXUpperBound)
        }
    }

    /** 与 `BadgeDigitMaxScaleX` 同档的上限护栏，防止未来调常量后测试静默失效。 */
    private val BadgeDigitMaxScaleXUpperBound = .74f
}
