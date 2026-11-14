package com.yll682.houche.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站名竖排规则：**整串一字一行，绝不拆列**。
 *
 * 用户原话：「现在公交站点名太长的情况下会换行，我们不换行，继续增长那个框」。
 * 从前 9 个字以上的站名会被 `chunked(8)` 拆成两竖列（「第一门医院｜海沧院区」），
 * 现在改成单列竖排：名字越长，竖排的盒子只往上长，列宽保持一致。
 */
class RouteStripNameTest {

    @Test
    fun nineCharacterNameIsOneColumnOfNineLines() {
        val name = "第一门医院海沧院区"
        val text = verticalStationName(name)
        assertEquals("9 个字＝9 行", 9, text.lines().size)
        assertEquals("整串不能少字", name, text.replace("\n", ""))
        // 拆列会在两列之间留下空行；单列竖排没有空行。
        assertTrue("不允许出现空行（=拆列）", text.lines().none { it.isEmpty() })
    }

    @Test
    fun eightCharacterNameIsOneColumnOfEightLines() {
        val text = verticalStationName("地铁林埭SM海沧")
        assertEquals("8 个码点＝8 行", 8, text.lines().size)
    }

    @Test
    fun surrogatePairIsNotSplitIntoTwoLines() {
        // emoji 是代理对，按码点切才不会劈成两行。
        val text = verticalStationName("A😀B")
        assertEquals(3, text.lines().size)
    }

    @Test
    fun emptyNameStaysEmpty() {
        assertEquals("", verticalStationName(""))
    }
}