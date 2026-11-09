package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.MetroEtaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetroEtaMessagesTest {
    @Test
    fun officialMessagesKeepAllFourNonReadyStatesDistinct() {
        assertEquals("正在获取", metroEtaMessage(MetroEtaState.Loading("01010000")))
        assertEquals("暂无到站列车", metroEtaMessage(MetroEtaState.Empty("01010000")))
        assertEquals(
            "暂时无法获取列车到站信息",
            metroEtaMessage(MetroEtaState.Unavailable("01010000", "HTTP 503 / 内部重试细节"))
        )
        assertEquals(
            "非运营时段，暂时无法提供列车到站查询功能",
            metroEtaMessage(MetroEtaState.OutOfService("01010000"))
        )
        assertNull(metroEtaMessage(MetroEtaState.Ready("01010000", emptyList())))
    }

    @Test
    fun failureToastUsesTheOfficialCopyForEveryStation() {
        listOf("镇海路", "内洲").forEach { name ->
            assertEquals(
                "暂时无法获取该站点列车时刻表",
                metroEventMessage(MetroEvent.StationTimetableUnavailable(name))
            )
        }
    }
}
