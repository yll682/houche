package com.yll682.houche

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.yll682.houche.core.City
import com.yll682.houche.core.LineSummary
import com.yll682.houche.core.Station
import com.yll682.houche.ui.HomeTab
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavigationStateTest {
    private val store = ViewModelStore()
    private val navigation = NavigationState().also { store.put("navigation", it) }
    private val city = City("11", "厦门市")

    @After fun clear() { store.clear() }

    @Test fun metroFromNestedPagesReturnsToOriginalHomeAndReleasesRetiredStores() {
        val home = navigation.stack.single()
        val homeModel = TrackingModel().also { home.viewModelStore.put("retained", it) }
        val search = PageEntry(Screen.Search(city))
        val stop = PageEntry(Screen.Stop(city, Station("吕厝", "TEST")))
        val detail = PageEntry(Screen.Line(city, LineSummary("91路", "1")))
        val retired = listOf(search, stop, detail).map { entry ->
            TrackingModel().also { entry.viewModelStore.put("page", it) }
        }
        navigation.stack.addAll(listOf(search, stop, detail))
        navigation.segment = HomeTab.FAVORITES
        navigation.pendingMetroLine = "stale"

        navigation.metro("2", city)

        assertEquals(1, navigation.stack.size)
        assertSame(home, navigation.stack.single())
        assertEquals(HomeTab.METRO, navigation.segment)
        assertEquals("2", navigation.pendingMetroLine)
        assertEquals("入口保留来源城市", city, navigation.metroCity)
        assertFalse("首页 Store 保留地铁及其他 Tab 的状态", homeModel.cleared)
        assertTrue("已退出的中间页立即释放", retired.take(2).all { it.cleared })
        assertFalse("可见页等待退场后再释放", retired.last().cleared)
        navigation.release(detail)
        assertTrue(retired.last().cleared)
        navigation.release(home)
        assertFalse(homeModel.cleared)
    }

    @Test fun homeClearsPendingAndASecondMetroEntryReplacesTheOldTarget() {
        val home = navigation.stack.single()
        navigation.metro("2", city)
        assertEquals(city, navigation.metroCity)
        navigation.metro("3")
        assertSame(home, navigation.stack.single())
        assertEquals("3", navigation.pendingMetroLine)
        assertNull("无来源城市的新入口不沿用上次城市", navigation.metroCity)
        navigation.metro("2", city)
        navigation.home()
        assertEquals(HomeTab.BUS, navigation.segment)
        assertNull(navigation.pendingMetroLine)
        assertNull("回首页清空地铁来源城市", navigation.metroCity)
        navigation.segment = HomeTab.METRO
        assertNull("单纯切回地铁不重放旧目标", navigation.pendingMetroLine)
    }

    @Test fun metroEntryPreservesSourceCityWithoutChangingTheBusCity() {
        val source = City("beijing", "北京市")
        val home = navigation.stack.single()
        navigation.push(Screen.Search(source))
        navigation.push(Screen.Stop(source, Station("来源城市站点", "TEST")))
        navigation.metro("2", source)
        assertSame(home, navigation.stack.single())
        assertEquals(source, navigation.metroCity)
        assertEquals("2", navigation.pendingMetroLine)
        navigation.segment = HomeTab.BUS
        navigation.segment = HomeTab.METRO
        assertEquals("切 Tab 保留明确来源", source, navigation.metroCity)
        navigation.home()
        assertNull(navigation.metroCity)
    }

    private class TrackingModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
}
