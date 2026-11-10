@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package com.yll682.houche.ui.components

import android.provider.Settings
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlin.math.*

val TelegramQuint = CubicBezierEasing(.23f, 1f, .32f, 1f)
val TelegramDecelerate = Easing { 1f - (1f-it)*(1f-it) }

/** M01: independent actions only. The .035 compression is a caller adaptation, not a source default. */
@Composable
fun TelegramIconButton(onClick: () -> Unit, label: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val progress by animateFloatAsState(if (pressed && enabled) 1f else 0f,
        tween(if (pressed) 60 else 350, easing = if (pressed) FastOutSlowInEasing else Easing { OvershootInterpolator(5f).getInterpolation(it) }), label = "action-pressure")
    Box(Modifier.size(48.dp).graphicsLayer { scaleX = 1f - .035f*progress; scaleY=scaleX }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { content() }
}

/** M09/M10/M30: equal glyphs retain their outlines; latest pending target replaces older pending targets.
 * The semantic value changes immediately. First composition is settled; no fake entrance.
 */
@Composable
fun TelegramText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge,
                 color: Color = MaterialTheme.colorScheme.onSurface, duration: Int = 320, amplitude: Float = .3f) {
    val latest = rememberUpdatedState(text)
    val timing = rememberUpdatedState(duration)
    var previous by remember { mutableStateOf(text) }
    var target by remember { mutableStateOf(text) }
    val progress = remember { Animatable(1f) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val resolved = style.copy(color = color)
    val old = measurer.measure(AnnotatedString(previous), resolved, maxLines = 1, overflow = TextOverflow.Clip)
    val next = measurer.measure(AnnotatedString(target), resolved, maxLines = 1, overflow = TextOverflow.Clip)
    val matches = remember(previous, target) { matchingGlyphs(previous, target) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest.value }.conflate().collect { value ->
            if (value != target) {
                previous = target; target = value
                progress.snapTo(0f)
                progress.animateTo(1f, tween(timing.value, easing = if (timing.value == 150) TelegramDecelerate else TelegramQuint))
                previous = target
            }
        }
    }
    val p = progress.value
    val width = old.size.width + (next.size.width-old.size.width)*p
    val height = max(old.size.height,next.size.height)
    val oldNumber = previous.filter(Char::isDigit).toLongOrNull()
    val newNumber = target.filter(Char::isDigit).toLongOrNull()
    val direction = if (oldNumber != null && newNumber != null && newNumber < oldNumber) -1f else 1f
    val rise = with(density) { resolved.fontSize.toPx() } * amplitude * direction
    Canvas(modifier.width(with(density) { max(width,1f).toDp() }).height(with(density) { height.toDp() })
        .clipToBounds().clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        val oldUsed = matches.map { it.first }.toSet()
        val newUsed = matches.map { it.second }.toSet()
        fun glyph(s: String, index: Int, x: Float, y: Float, alpha: Float) {
            if (alpha <= .001f || index !in s.indices) return
            val layout = measurer.measure(AnnotatedString(s[index].toString()), resolved.copy(color=color.copy(alpha=color.alpha*alpha)), maxLines=1)
            drawText(layout, topLeft=Offset(x, y + next.firstBaseline-layout.firstBaseline))
        }
        matches.forEach { (a,b) ->
            val x0 = old.getBoundingBox(a).left; val x1 = next.getBoundingBox(b).left
            glyph(target,b,x0+(x1-x0)*p,0f,1f)
        }
        previous.indices.filterNot { it in oldUsed }.forEach { i -> glyph(previous,i,old.getBoundingBox(i).left,-rise*p,1f-p) }
        target.indices.filterNot { it in newUsed }.forEach { i -> glyph(target,i,next.getBoundingBox(i).left,rise*(1f-p),p) }
    }
}

internal fun matchingGlyphs(old: String, new: String): List<Pair<Int,Int>> {
    val lengths=Array(old.length+1) { IntArray(new.length+1) }
    for (i in old.lastIndex downTo 0) for(j in new.lastIndex downTo 0)
        lengths[i][j]=if(old[i]==new[j]) 1+lengths[i+1][j+1] else max(lengths[i+1][j],lengths[i][j+1])
    val result=mutableListOf<Pair<Int,Int>>();var i=0;var j=0
    while(i<old.length && j<new.length) {
        if(old[i]==new[j]) { result+=i to j;i++;j++ }
        else if(lengths[i+1][j]>=lengths[i][j+1]) i++ else j++
    }
    return result
}

/** M07 visibility is alpha/scale/rotation, M08 is a single cross/arc geometry, not an icon swap. */
@Composable
fun TelegramClearButton(visible: Boolean, loading: Boolean, clear: () -> Unit) {
    val show by animateFloatAsState(if(visible) 1f else 0f,tween(180,easing=TelegramDecelerate),label="clear-presence")
    if (!visible && show <= .001f) { Spacer(Modifier.size(48.dp)); return }
    TelegramIconButton(clear,"清空",visible) {
        Box(Modifier.graphicsLayer { alpha=show;scaleX=show;scaleY=show;rotationZ=45f*(1f-show) }) {
            CrossProgress(loading)
        }
    }
}

@Composable
fun CrossProgress(loading: Boolean, modifier: Modifier=Modifier.size(24.dp)) {
    val latest=rememberUpdatedState(loading)
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(loading) {
        val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) { angle=if (loading) 360f else 0f;return@LaunchedEffect }
        var previous=withFrameNanos { it }
        while(latest.value || angle>0f) {
            val now=if (latest.value) withInfiniteAnimationFrameNanos { it } else withFrameNanos { it }; val step=(now-previous)/1_000_000f* (.72f / durationScale);previous=now
            val next=angle+step
            angle=if(next>=720f) { if(latest.value) next%720f else 0f } else next
        }
    }
    val color=MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val a=angle
        val phase=(a/360f).coerceIn(0f,2f)
        val retract=if(phase<1f) phase else 2f-phase
        val length=8.dp.toPx()*(1f-retract)
        val center=Offset(size.width/2,size.height/2)
        rotate(a/2f,center) {
            if(length>.01f) {
                val d=length/sqrt(2f)
                drawLine(color,center-Offset(d,d),center+Offset(d,d),2.dp.toPx(),StrokeCap.Round)
                drawLine(color,center+Offset(-d,d),center+Offset(d,-d),2.dp.toPx(),StrokeCap.Round)
            }
            val radius=8.dp.toPx()
            if(retract>.001f) drawArc(color,-90f,360f*retract,false,center-Offset(radius,radius),Size(radius*2,radius*2),style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
        }
    }
}

/** M27: pulse belongs to the value slot; the label and group never disappear. */
@Composable
fun TelegramSettingValue(value: String, loading: Boolean) {
    val visibility by animateFloatAsState(if(loading) 1f else 0f,tween(150),label="value-loading")
    Box(contentAlignment=Alignment.CenterEnd) {
        Box(Modifier.graphicsLayer { alpha=1f-visibility }) {
            TelegramText(value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary,amplitude=.55f)
        }
        if(visibility>0f) {
            val transition=rememberInfiniteTransition(label="local-loading")
            val pulse by transition.animateFloat(0f,1f,infiniteRepeatable(tween(1000),RepeatMode.Reverse),label="local-pulse")
            Surface(Modifier.width(48.dp).height(6.dp).graphicsLayer { alpha=(.6f+.4f*pulse)*visibility },shape=androidx.compose.foundation.shape.RoundedCornerShape(3.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.28f)) {}
        }
    }
}
