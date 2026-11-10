package com.yll682.houche

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yll682.houche.ui.HomeTab
import com.yll682.houche.ui.homeTabs
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
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="zh-rCN-w411dp-h891dp-xxhdpi", application=android.app.Application::class)
class TelegramMotionTest {
    @get:Rule val compose=createComposeRule()
    private fun capture():Bitmap {
        compose.waitForIdle()
        val view=(compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var bitmap:Bitmap
        compose.runOnUiThread {
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }
    private fun shot(name:String):Bitmap {
        val bitmap=capture()
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
        var selected by mutableIntStateOf(HomeTab.BUS)
        compose.setContent { HoucheTheme { Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) { TelegramNavigation(selected,{selected=it},homeTabs(),Modifier.testTag("nav")) } } }
        compose.mainClock.advanceTimeBy(32)
        val start=shot("nav-000")
        compose.onNodeWithText("收藏").performClick()
        compose.mainClock.advanceTimeBy(16)
        val frames=mutableListOf(start)
        for(i in 1..4) { compose.mainClock.advanceTimeBy(80);frames+=shot("nav-${i*25}") }
        assertTrue("the contour/indicator must move at 25%",changed(frames[0],frames[1])>100)
        assertTrue("50% is not a hard cut to the final icon",changed(frames[2],frames[4])>10)
        assertEquals(HomeTab.FAVORITES,selected)
        compose.onNode(isSelected() and hasText("收藏")).assertExists()
        val bounds=compose.onNodeWithTag("nav").fetchSemanticsNode().boundsInRoot
        val y=(bounds.top+bounds.height*.12f).toInt()
        compose.mainClock.advanceTimeBy(800)
        val settled=capture()
        val movingPixels=(bounds.left.toInt() until bounds.right.toInt()).count { x -> frames[1].getPixel(x,y)!=settled.getPixel(x,y) }
        assertTrue("the indicator itself must have an intermediate spring position",movingPixels>10)
    }
    @Test fun navigationDragWithoutLongPressCommitsOnceAndCancelDoesNotNavigate() {
        var selected by mutableIntStateOf(HomeTab.BUS);var clicks=0
        compose.setContent { HoucheTheme { TelegramNavigation(selected,{ selected=it;clicks++ },homeTabs(),Modifier.testTag("nav")) } }
        compose.onNodeWithTag("nav").performTouchInput {
            down(Offset(width/8f,height/2f));moveTo(Offset(width*7f/8f,height/2f));advanceEventTime(40);cancel()
        }
        compose.waitForIdle()
        assertEquals(HomeTab.BUS,selected);assertEquals(0,clicks)
        compose.onNodeWithTag("nav").performTouchInput {
            down(Offset(width/8f,height/2f));moveTo(Offset(width*7f/8f,height/2f));advanceEventTime(40);up()
        }
        compose.waitForIdle();assertEquals(HomeTab.SETTINGS,selected);assertEquals(1,clicks)
    }
    @Test fun navigationTapsAndHeldTapsStillSelectWithoutDragging() {
        var selected by mutableIntStateOf(HomeTab.BUS);var clicks=0
        compose.setContent { HoucheTheme { TelegramNavigation(selected,{ selected=it;clicks++ },homeTabs(),Modifier.testTag("nav")) } }
        compose.onNodeWithText("地铁").performTouchInput { click(center) }
        compose.waitForIdle();assertEquals(HomeTab.METRO,selected);assertEquals(1,clicks)
        compose.onNodeWithText("收藏").performTouchInput { down(center);advanceEventTime(600);up() }
        compose.waitForIdle();assertEquals(HomeTab.FAVORITES,selected);assertEquals(2,clicks)
        compose.onNodeWithText("设置").performTouchInput { down(center);moveBy(Offset(1f,1f));up() }
        compose.waitForIdle();assertEquals(HomeTab.SETTINGS,selected);assertEquals(3,clicks)
    }
    @Test fun navigationVerticalMovementKeepsTheTabClick() {
        var selected by mutableIntStateOf(HomeTab.BUS);var clicks=0;var consumedMovement=false
        compose.setContent { HoucheTheme {
            Box(Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while(true) {
                        val event=awaitPointerEvent(PointerEventPass.Final)
                        if(event.changes.any { it.pressed && it.previousPressed && it.position!=it.previousPosition && it.isConsumed }) consumedMovement=true
                    }
                }
            }) {
                TelegramNavigation(selected,{ selected=it;clicks++ },homeTabs(),Modifier.testTag("nav"))
            }
        } }
        compose.onNodeWithText("地铁").performTouchInput {
            down(center);moveBy(Offset(0f,-30f));up()
        }
        compose.waitForIdle()
        assertFalse("vertical movement must remain available to other handlers",consumedMovement)
        assertEquals(HomeTab.METRO,selected);assertEquals(1,clicks)
    }
    @Test fun navigationParentConsumptionCancelsAnActiveDragBeforeRelease() {
        var selected by mutableIntStateOf(HomeTab.BUS);var clicks=0
        var intercept by mutableStateOf(false)
        compose.setContent { HoucheTheme {
            Box(Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while(true) {
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        if(intercept) event.changes.forEach { it.consume() }
                    }
                }
            }) {
                TelegramNavigation(selected,{ selected=it;clicks++ },homeTabs(),Modifier.testTag("nav"))
            }
        } }
        val settled=capture()
        compose.onNodeWithTag("nav").performTouchInput {
            down(Offset(width/8f,height/2f));moveTo(Offset(width*5f/8f,height/2f))
        }
        compose.onNode(isSelected() and hasText("收藏")).assertExists()
        compose.runOnIdle { intercept=true }
        compose.onNodeWithTag("nav").performTouchInput { moveTo(Offset(width*7f/8f,height/2f)) }
        compose.onNode(isSelected() and hasText("公交")).assertExists()
        compose.onNodeWithTag("nav").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(HomeTab.BUS,selected);assertEquals(0,clicks)
        assertEquals("cancelled drag must restore the original indicator",0,changed(settled,capture()))
    }
    @Test fun navigationMovementConsumedBeforeSlopCannotStartADrag() {
        var selected by mutableIntStateOf(HomeTab.BUS);var clicks=0
        compose.setContent { HoucheTheme {
            Box(Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while(true) {
                        val event=awaitPointerEvent(PointerEventPass.Main)
                        event.changes.filter { it.pressed && it.previousPressed }.forEach { it.consume() }
                    }
                }
            }) {
                TelegramNavigation(selected,{ selected=it;clicks++ },homeTabs(),Modifier.testTag("nav"))
            }
        } }
        compose.onNodeWithTag("nav").performTouchInput {
            down(Offset(width/8f,height/2f));moveBy(Offset(1f,0f))
            moveTo(Offset(width*7f/8f,height/2f));up()
        }
        compose.waitForIdle()
        assertEquals(HomeTab.BUS,selected);assertEquals(0,clicks)
        compose.onNode(isSelected() and hasText("公交")).assertExists()
    }
    @Test fun navigationFirstPlacementAndRecreationHaveNoIndicatorMotion() {
        compose.mainClock.autoAdvance=false
        var homeVisible by mutableStateOf(true)
        compose.setContent { HoucheTheme {
            Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
                if(homeVisible) TelegramNavigation(HomeTab.METRO,{},homeTabs(),Modifier.testTag("nav"))
            }
        } }
        compose.mainClock.advanceTimeBy(32)
        val first=capture()
        compose.mainClock.advanceTimeBy(800)
        val settled=capture()
        assertEquals("first measured placement must already be at its final position",0,changed(first,settled))
        val bounds=compose.onNodeWithTag("nav").fetchSemanticsNode().boundsInRoot
        val selectedX=compose.onNodeWithText("地铁").fetchSemanticsNode().boundsInRoot.center.x.toInt()
        val otherX=compose.onNodeWithText("公交").fetchSemanticsNode().boundsInRoot.center.x.toInt()
        val indicatorY=(bounds.top+bounds.height*.12f).toInt()
        assertNotEquals("indicator must be visible in the selected cell",settled.getPixel(otherX,indicatorY),settled.getPixel(selectedX,indicatorY))
        compose.runOnUiThread { homeVisible=false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("nav").assertDoesNotExist()
        compose.runOnUiThread { homeVisible=true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        val returned=capture()
        compose.mainClock.advanceTimeBy(800)
        val returnedSettled=capture()
        assertEquals("recreated navigation must immediately match the settled position",0,changed(settled,returned))
        assertEquals("returning home must not animate the indicator into place",0,changed(returned,returnedSettled))
    }
    @Test fun navigationFirstDrawShowsIndicatorOnColdStartAndRecreation() {
        compose.mainClock.autoAdvance=false
        data class Placement(val parentWidth:Dp,val selected:Int,val recreated:Boolean)
        val placements=listOf(380.dp,280.dp).flatMap { width ->
            (0 until HomeTab.COUNT).flatMap { selected ->
                listOf(Placement(width,selected,false),Placement(width,selected,true))
            }
        }
        var placement by mutableStateOf<Placement?>(placements.first())
        // 普通集合只保存每次入组合的第一次真实绘制，后续重绘不能覆盖它。
        val firstFrames=mutableMapOf<Placement,Bitmap>()
        lateinit var rootView:android.view.View
        compose.setContent { rootView=LocalView.current;HoucheTheme {
            Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal=8.dp)) {
                placement?.let { current -> key(current) {
                    val colors=MaterialTheme.colorScheme
                    val tabs=homeTabs()
                    Box(Modifier.width(current.parentWidth)) {
                        TelegramNavigation(current.selected,{},tabs,Modifier.testTag("first-frame-nav").drawWithContent {
                            if(current in firstFrames) {
                                drawContent()
                            } else {
                                // 当场将这一次 drawContent 画进位图，再把同一位图交给屏幕；
                                // 不调用 capture()/waitForIdle() 重新绘制，也不等待定位 effect。
                                val bitmap=Bitmap.createBitmap(size.width.roundToInt(),size.height.roundToInt(),Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.White.toArgb())
                                val image=bitmap.asImageBitmap()
                                val screenCanvas=drawContext.canvas
                                drawContext.canvas=androidx.compose.ui.graphics.Canvas(image)
                                try { drawContent() } finally { drawContext.canvas=screenCanvas }
                                drawImage(image)
                                firstFrames[current]=bitmap

                                assertEquals("$current: first draw must capture the actual bar width",
                                    minOf(344.dp,current.parentWidth).roundToPx(),bitmap.width)
                                assertTrue("$current: first draw must include the full navigation height",
                                    bitmap.height>=56.dp.roundToPx())
                                val background=colors.surface.copy(alpha=.95f).compositeOver(Color.White)
                                val indicator=colors.primary.copy(alpha=.11f).compositeOver(background)
                                val cell=(bitmap.width-8.dp.toPx())/tabs.size
                                // 取图标上方、圆角中心内的 5×3 像素，避免图标/文字冒充指示块。
                                for(index in tabs.indices) {
                                    val x=(4.dp.toPx()+cell*(index+.5f)).roundToInt()
                                    val y=6.dp.roundToPx()
                                    val expected=(if(index==current.selected) indicator else background).toArgb()
                                    for(dx in -2..2) for(dy in 0..2) {
                                        val actual=bitmap.getPixel(x+dx,y+dy)
                                        assertTrue("$current: first draw cell $index at (${x+dx},${y+dy}) "+
                                            "expected #${expected.toUInt().toString(16)}, got #${actual.toUInt().toString(16)}",
                                            (0..24 step 8).all { shift ->
                                                abs(((expected ushr shift) and 255)-((actual ushr shift) and 255))<=2
                                            })
                                    }
                                }
                            }
                        })
                    }
                } }
            }
        } }
        placements.forEachIndexed { index,current ->
            if(index>0) {
                compose.runOnUiThread { placement=null;Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("first-frame-nav").assertDoesNotExist()
                compose.runOnUiThread { placement=current;Snapshot.sendApplyNotifications() }
                // 只驱动新子树入组合；断言在它第一次 drawContent 当场执行。
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("first-frame-nav").assertExists()
            }
            compose.runOnIdle {
                // Robolectric 暂停帧时钟时可能只布局、不自动 draw，主动驱动 View 绘制。
                // 若系统此前已绘制，首帧早已被上面的 modifier 保存，绝不覆盖。
                val screen=Bitmap.createBitmap(rootView.width,rootView.height,Bitmap.Config.ARGB_8888)
                rootView.draw(Canvas(screen))
                assertTrue("$current: navigation never reached its first draw",current in firstFrames)
            }
        }
        assertEquals("both widths and every selected tab must draw on cold start and recreation",16,firstFrames.size)
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
