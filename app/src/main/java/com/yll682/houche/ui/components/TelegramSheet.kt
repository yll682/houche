package com.yll682.houche.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlin.math.*

/** 弹层承载层：应用根部的全屏图层。弹层把界面交给它画，遮罩才能盖住状态栏、按钮才能避开系统栏。 */
class SheetHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    private var owner: Any? = null
    fun show(token: Any, ui: @Composable () -> Unit) { owner = token; content = ui }
    fun hide(token: Any) { if (owner === token) { owner = null; content = null } }
}
val LocalSheetHost = staticCompositionLocalOf<SheetHost?> { null }
@Composable fun SheetHostLayer(host: SheetHost) { host.content?.invoke() }

internal fun sheetShouldReturn(y:Float,distance:Float,vy:Float,vx:Float=0f)=
    (y<distance && (vy<3500f || abs(vy)<abs(vx))) || (vy<0f && abs(vy)>=3500f)

/** M15/M16 physical 0.8cm threshold, child-first nested consumption, no Material sheet motion. */
@Composable fun TelegramSheet(dismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val scope=rememberCoroutineScope()
    val metrics=LocalContext.current.resources.displayMetrics
    val distance=remember(metrics) { metrics.ydpi*.8f/2.54f }
    val density=LocalDensity.current
    val screen=with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val position=remember { Animatable(screen+10f*density.density) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var height by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val latestDismiss=rememberUpdatedState(dismiss)
    val y=if(dragging) dragY else position.value
    fun close() { if(!closing) { closing=true;scope.launch { position.snapTo(if(dragging)dragY else position.value);position.animateTo(max(height,screen),tween(180,easing=TelegramQuint));latestDismiss.value() } } }
    fun settle(velocity:Float,cancel:Boolean=false) {
        val from=if(dragging)dragY else position.value;dragging=false
        scope.launch {
            position.snapTo(from)
            if(cancel || sheetShouldReturn(from,distance,velocity)) position.animateTo(0f,tween((250f*from/distance).roundToInt().coerceAtLeast(1),easing=FastOutSlowInEasing))
            else close()
        }
    }
    LaunchedEffect(height) { if(height>0f && !dragging && !closing) { delay(20);position.animateTo(0f,tween(400,easing=TelegramQuint)) } }
    val nested=remember {
        object:NestedScrollConnection {
            override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
                if(available.y<0f && dragY>0f && dragging) { val used=max(available.y,-dragY);dragY+=used;return Offset(0f,used) };return Offset.Zero
            }
            override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
                if(available.y>0f && source==NestedScrollSource.UserInput) {
                    if(!dragging) { dragY=position.value;dragging=true;scope.launch { position.stop() } }
                    dragY+=available.y;return Offset(0f,available.y)
                };return Offset.Zero
            }
            override suspend fun onPreFling(available:Velocity):Velocity { if(dragging) { settle(available.y);return available };return Velocity.Zero }
        }
    }
    val ui: @Composable () -> Unit = {
        androidx.activity.compose.BackHandler { close() }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            // 窗口铺满整屏（含状态栏）：面板最高到状态栏下方再留 16dp。
            val maxPanel = (maxHeight - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 16.dp).coerceAtLeast(48.dp)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha=.32f*(1f-y/max(height,1f)).coerceIn(0f,1f)))
                .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null) { close() })
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max=maxPanel)
                .offset { IntOffset(0,y.roundToInt()) }.onSizeChanged { height=it.height.toFloat() }.nestedScroll(nested)
                .testTag("sheet-panel"),
                shape=RoundedCornerShape(topStart=16.dp,topEnd=16.dp),color=MaterialTheme.colorScheme.surface) {
                Column {
                    Box(Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "拖动面板" }.pointerInput(Unit) {
                        val tracker=VelocityTracker()
                        detectVerticalDragGestures(onDragStart={ dragY=position.value;dragging=true;scope.launch { position.stop() };tracker.resetTracking() },
                            onDragCancel={ settle(0f,true) },onDragEnd={ settle(tracker.calculateVelocity().y) },
                            onVerticalDrag={ change,delta -> change.consume();tracker.addPosition(change.uptimeMillis,change.position);dragY=max(0f,dragY+delta) })
                    },contentAlignment=Alignment.Center) {
                        Box(Modifier.size(36.dp,4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.3f),RoundedCornerShape(2.dp)))
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                    // 底部确认按钮等内容固定在系统导航栏（手势条/三键）上方。
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
    val host = LocalSheetHost.current
    if (host != null) {
        val token = remember { Any() }
        SideEffect { host.show(token, ui) }
        DisposableEffect(token) { onDispose { host.hide(token) } }
    } else {
        Dialog(onDismissRequest={ close() },properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
            ui()
        }
    }
}
