package com.yll682.houche

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.*
import com.yll682.houche.data.Prefs
import com.yll682.houche.ui.screens.search.SearchViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class TelegramStateTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    @Test fun undoRestoresOnlyItsOwnDeletedItemsAndOriginalOrder()= runBlocking {
        val prefs=Prefs(app)
        prefs.clearSearches();prefs.addSearch("C");prefs.addSearch("B");prefs.addSearch("A")
        val first=prefs.removeSearchesWithSnapshot("A")
        val second=prefs.removeSearchesWithSnapshot("B")
        assertEquals(listOf("C"),prefs.recentSearches.first())
        prefs.restoreSearches(second)
        assertEquals(listOf("B","C"),prefs.recentSearches.first())
        prefs.addSearch("D")
        prefs.restoreSearches(first)
        assertEquals(listOf("D","A","B","C"),prefs.recentSearches.first())
        prefs.restoreSearches(first)
        assertEquals(listOf("D","A","B","C"),prefs.recentSearches.first())
        val clear=prefs.removeSearchesWithSnapshot()
        assertTrue(prefs.recentSearches.first().isEmpty())
        prefs.addSearch("new");prefs.restoreSearches(clear)
        assertEquals(listOf("new","D","A","B","C"),prefs.recentSearches.first())
        prefs.clearSearches()
    }
    @Test fun lateRequestCannotReplaceNewQueryOrReturnAfterClear() {
        val graph=AppGraph(app)
        val gates=ConcurrentHashMap<String,CompletableDeferred<SearchResults>>()
        val responses=mapOf("a" to SearchResults(listOf(LineSummary("旧结果","1","A","B")),emptyList()),
            "ab" to SearchResults(listOf(LineSummary("新结果","1","A","B")),emptyList()))
        val vm=SearchViewModel(graph,City("xiamen","厦门市"),searchRequest={ query ->
            val gate=CompletableDeferred<SearchResults>().also { gates[query]=it }
            try { withContext(NonCancellable) { gate.await() } }
            catch(error:CancellationException) { responses.getValue(query) }
        },localRequest={ emptyList() })
        val store=ViewModelStore().apply { put("search",vm) }
        try {
            vm.query("a",true);await { gates.containsKey("a") }
            vm.query("ab",true);await { gates.containsKey("ab") }
            gates.getValue("ab").complete(responses.getValue("ab"))
            await { vm.state.value.data!!.result.lines.firstOrNull()?.name=="新结果" }
            gates.getValue("a").complete(responses.getValue("a"))
            repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(2) }
            assertEquals("ab",vm.state.value.data!!.query)
            assertEquals("新结果",vm.state.value.data!!.result.lines.first().name)
            vm.query("a",true);await { gates["a"]?.isCompleted==false }
            vm.query("");gates.getValue("a").complete(responses.getValue("a"))
            repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(2) }
            assertEquals("",vm.state.value.data!!.query)
            assertTrue(vm.state.value.data!!.result.lines.isEmpty())
        } finally { gates.values.forEach { it.complete(SearchResults(emptyList(),emptyList())) };store.clear() }
    }
    @Test fun undoFinishesEvenIfTheSearchPageIsRetiredImmediately() {
        val graph=AppGraph(app)
        runBlocking { graph.prefs.clearSearches();graph.prefs.addSearch("restore-me") }
        val vm=SearchViewModel(graph,City("xiamen","厦门市"),searchRequest={ SearchResults(emptyList(),emptyList()) },localRequest={ emptyList() })
        val store=ViewModelStore().apply { put("search",vm) }
        var undo:(()->Unit)?=null
        vm.remove("restore-me") { undo=it }
        await { undo!=null }
        undo!!.invoke();store.clear()
        runBlocking {
            withTimeout(5000) { graph.prefs.recentSearches.first { "restore-me" in it } }
            graph.prefs.clearSearches()
        }
    }
    private fun await(condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<end) { shadowOf(android.os.Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(5) }
        assertTrue("condition timed out",condition())
    }
}
