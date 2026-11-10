package com.yll682.houche

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.HoucheTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="zh-rCN-w411dp-h891dp-xxhdpi", application=android.app.Application::class)
class TelegramMotionTest {
    @get:Rule val compose=createComposeRule()
    private fun shot(name:String):Bitmap {
        compose.waitForIdle()
        val view=(compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var bitmap:Bitmap
        compose.runOnUiThread {
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val file=File(System.getProperty("screenshots.dir"),"motion/$name.png")
        file.parentFile.mkdirs();file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return bitmap
    }
    private fun changed(a:Bitmap,b:Bitmap):Int {
        val x=IntArray(a.width*a.height); val y=IntArray(b.width*b.height)
        a.getPixels(x,0,a.width,0,0,a.width,a.height);b.getPixels(y,0,b.width,0,0,b.width,b.height)
        return x.indices.count { x[it]!=y[it] }
    }
    @Test fun navigationHasIntermediateContourAndIndicatorFrames() {
        compose.mainClock.autoAdvance=false
        var selected by mutableIntStateOf(0)
        compose.setContent { HoucheTheme { Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) { TelegramNavigation(selected,{selected=it},Modifier.testTag("nav")) } } }
        compose.mainClock.advanceTimeBy(32)
        val start=shot("nav-000")
        compose.onNodeWithText("收藏").performClick()
        compose.mainClock.advanceTimeBy(16)
        val frames=mutableListOf(start)
        for(i in 1..4) { compose.mainClock.advanceTimeBy(80);frames+=shot("nav-${i*25}") }
        assertTrue("the contour/indicator must move at 25%",changed(frames[0],frames[1])>100)
        assertTrue("50% is not a hard cut to the final icon",changed(frames[2],frames[4])>10)
        assertEquals(1,selected)
        compose.onNode(isSelected() and hasText("收藏")).assertExists()
    }
    @Test fun navigationLongPressDragCommitsOnceAndCancelDoesNotNavigate() {
        var selected by mutableIntStateOf(0);var clicks=0
        compose.setContent { HoucheTheme { TelegramNavigation(selected,{ selected=it;clicks++ },Modifier.testTag("nav")) } }
        compose.onNodeWithTag("nav").performTouchInput {
            down(Offset(width/6f,height/2f));advanceEventTime(600);moveTo(Offset(width*5f/6f,height/2f));advanceEventTime(40);cancel()
        }
        compose.waitForIdle()
        assertEquals(0,selected);assertEquals(0,clicks)
        compose.onNodeWithTag("nav").performTouchInput {
            down(Offset(width/6f,height/2f));advanceEventTime(600);moveTo(Offset(width*5f/6f,height/2f));advanceEventTime(40);up()
        }
        compose.waitForIdle();assertEquals(2,selected);assertEquals(1,clicks)
    }
    @Test fun swipeThresholdCanBeCrossedBackAndCancelled() {
        var deleted=0;var opened=0
        compose.setContent { HoucheTheme { SwipeHistoryRow("A",{deleted++},Modifier.testTag("row")) {
            TextButton({opened++},Modifier.fillMaxWidth().height(60.dp).background(Color.White)) { Text("A") }
        } } }
        compose.onNodeWithTag("row").performTouchInput {
            val start=Offset(width*.9f,height/2f)
            down(start);moveTo(start-Offset(width*.44f,0f),120);moveTo(start-Offset(width*.46f,0f),120)
            moveTo(start-Offset(width*.44f,0f),120);advanceEventTime(300);up()
        }
        compose.waitForIdle();assertEquals(0,deleted);assertEquals(0,opened)
        compose.onNodeWithTag("row").performTouchInput {
            val start=Offset(width*.9f,height/2f);down(start);moveTo(start-Offset(width*.65f,0f),200);cancel()
        }
        compose.waitForIdle();assertEquals(0,deleted)
        compose.onNodeWithTag("row").performTouchInput {
            val start=Offset(width*.9f,height/2f);down(start);moveTo(start-Offset(width*.6f,0f),200);up()
        }
        compose.waitForIdle();assertEquals(1,deleted);assertEquals(0,opened)
    }
    @Test fun pressCancellationNeverClicksAndClearRetainsInputRole() {
        var calls=0
        compose.setContent { HoucheTheme { Column {
            TelegramIconButton({calls++},"action") { Text("按钮") }
            TelegramClearButton(true,false) { calls++ }
        } } }
        compose.onNodeWithContentDescription("action").performTouchInput { down(center);advanceEventTime(40);cancel() }
        compose.waitForIdle();assertEquals(0,calls)
        compose.onNodeWithContentDescription("清空").performClick();assertEquals(1,calls)
    }
    @Test fun textOnlyAnimatesChangedGlyphsAndLatestTargetWins() {
        assertEquals(listOf(0 to 0),matchingGlyphs("12","13"))
        compose.mainClock.autoAdvance=false
        var value by mutableStateOf("12")
        compose.setContent { HoucheTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(24.dp)) { TelegramText(value) } } } }
        compose.mainClock.advanceTimeBy(32);val a=shot("text-000")
        compose.runOnUiThread { value="13" };compose.waitForIdle();compose.mainClock.advanceTimeBy(96);val b=shot("text-025")
        assertTrue(changed(a,b)>0)
        for(next in listOf("14","15","16","17")) { compose.runOnUiThread { value=next };compose.mainClock.advanceTimeBy(20) }
        compose.mainClock.advanceTimeBy(800);compose.onNodeWithText("17").assertExists();shot("text-final")
    }
    @Test fun clearMorphHasGeometricMiddleFrames() {
        compose.mainClock.autoAdvance=false
        var loading by mutableStateOf(false)
        compose.setContent { HoucheTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(24.dp)) { TelegramClearButton(true,loading) {} } } } }
        compose.mainClock.advanceTimeBy(32);val start=shot("clear-000")
        compose.runOnUiThread { loading=true };compose.waitForIdle();compose.mainClock.advanceTimeBy(250);val middle=shot("clear-025")
        compose.mainClock.advanceTimeBy(250);val arc=shot("clear-050")
        assertTrue(changed(start,middle)>0);assertTrue(changed(middle,arc)>0)
        compose.runOnUiThread { loading=false };compose.waitForIdle();compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithContentDescription("清空").assertHasClickAction();shot("clear-restored")
    }
    @Test fun undoAndReplacementAreExclusiveTerminalStates() {
        val center=BulletinCenter();var a=0;var b=0;var committed=0
        center.show("A",{a++},{committed++});val first=center.current!!
        center.show("B",{b++},{committed++});val second=center.current!!
        assertEquals(1,committed)
        center.undo(first.id);assertEquals(0,a)
        center.undo(second.id);center.undo(second.id);center.dismiss()
        assertEquals(1,b);assertEquals(1,committed)
    }
    @Test fun sheetShortCancelledPullKeepsContextAndActionWorks() {
        var shown by mutableStateOf(true);var selected=0
        compose.setContent { HoucheTheme {
            if(shown) TelegramSheet({ shown=false }) {
                Text("站点预览",Modifier.padding(20.dp))
                Button({ selected++;shown=false },Modifier.padding(20.dp)) { Text("确认站点") }
            }
        } }
        compose.waitUntil(5000) { compose.onNodeWithText("确认站点").isDisplayed() }
        compose.onNodeWithText("确认站点").assertIsDisplayed()
        compose.onNodeWithContentDescription("拖动面板").performTouchInput {
            down(center);moveBy(Offset(0f,20f));advanceEventTime(200);cancel()
        }
        compose.waitForIdle();assertTrue(shown);assertEquals(0,selected)
        compose.onNodeWithText("确认站点").performClick()
        compose.waitForIdle();assertFalse(shown);assertEquals(1,selected)
    }
    @Test fun sheetDistanceAndVelocityUseTheCorrectPhysicalBranch() {
        assertTrue(sheetShouldReturn(30f,150f,100f))
        assertFalse(sheetShouldReturn(30f,150f,4000f))
        assertTrue(sheetShouldReturn(180f,150f,-4000f))
        assertTrue(sheetShouldReturn(30f,150f,4000f,5000f))
    }
}
