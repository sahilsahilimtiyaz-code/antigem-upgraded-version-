package com.example.gemini.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.delay

/**
 * The agent is working.
 *
 * One calm, glassy row that changes personality with the agent: its own brand gradient, its own vocabulary and its
 * own sense of pace (see [AgentActivityStyle]). The top line is the verb, the second line says what is happening
 * right now ("Editing MainActivity.kt"), and a mono clock keeps the turn honest.
 *
 * Built for the streaming hot path: the orb, signal, sheen and decoded text read their animation state only while
 * drawing, so a frame invalidates this row and nothing else; the text box never changes size, so the chat around it
 * never re-lays out; and with Android's animations turned off every loop stands still.
 */
@Composable
fun AgentWorkingSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    compacting: Boolean = false,
    agent: AgentKind? = null,
    activityHint: String? = null
) {
    val style = rememberAgentActivityStyle(agent, color)
    val accent = if (agent == null) color else style.accent
    val secondary = style.secondary
    val animations = rememberSystemAnimationsEnabled()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)

    val wordStyle = remember(accent, secondary, textColor) {
        TextStyle(
            brush = Brush.linearGradient(listOf(accent, secondary, textColor)),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.15.sp
        )
    }

    // The longest verb the agent can show, plus "..." and the cursor. One remembered char count, measured never.
    val textWidth = remember(style.verbs) {
        maxOf(style.verbs.maxOfOrNull { it.length } ?: 8, "Compacting".length) + 3
    }

    val shown = remember(style.agent) { mutableStateOf(" ".repeat(textWidth)) }
    val startedAt = remember(style.agent) { SystemClock.elapsedRealtime() }

    // Driven by Compose's frame clock (not a delay loop), so the sweep pauses with the app in the background.
    val sheenTransition = rememberInfiniteTransition(label = "agent_activity_sheen")
    val sheen = if (animations) {
        sheenTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "agent_activity_sheen_phase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    WordDecoder(shown = shown, compacting = compacting, style = style, textWidth = textWidth, animations = animations)

    val hint = when {
        compacting -> "Compacting the conversation"
        !activityHint.isNullOrBlank() -> activityHint
        else -> "${style.name} is working"
    }
    val description = "${style.name}: $hint"

    val shape = RoundedCornerShape(16.dp)
    val borderBrush = Brush.horizontalGradient(
        listOf(
            accent.copy(alpha = 0.42f),
            secondary.copy(alpha = 0.22f),
            accent.copy(alpha = 0.08f)
        )
    )
    val backgroundBrush = Brush.horizontalGradient(
        listOf(
            accent.copy(alpha = 0.115f),
            accent.copy(alpha = 0.035f),
            Color.Transparent
        )
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.5f),
                spotColor = accent.copy(alpha = 0.35f)
            )
            .clip(shape)
            .background(backgroundBrush)
            .drawWithContent {
                // a slow light sweeping across the glass: draw-phase only, no recomposition
                if (size.width > 0f && size.height > 0f) {
                    val sweep = 260f
                    val x = -sweep + (size.width + sweep * 2f) * sheen.value
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.5f to accent.copy(alpha = 0.07f),
                            1f to Color.Transparent,
                            startX = x,
                            endX = x + sweep
                        )
                    )
                }
                drawContent()
            }
            .border(0.8.dp, borderBrush, shape)
            .semantics { contentDescription = description }
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentActivityOrb(
            accent = accent,
            secondary = secondary,
            diameter = 22.dp,
            animated = animations,
            periodMs = style.orbPeriodMs
        )

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            ) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val layout = measurer.measure(
                    shown.value,
                    style = wordStyle,
                    softWrap = false,
                    maxLines = 1
                )
                val baseline = (size.height - layout.size.height) / 2f + layout.firstBaseline
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(0f, baseline - layout.firstBaseline)
                )
            }

            Text(
                text = hint,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (animations) {
            Spacer(Modifier.width(8.dp))
            AgentActivitySignal(color = secondary)
        }

        Spacer(Modifier.width(9.dp))

        ElapsedClock(startedAtMs = startedAt, color = accent)
    }
}

@Composable
private fun ElapsedClock(startedAtMs: Long, color: Color) {
    val elapsedMs by produceState(initialValue = 0L, key1 = startedAtMs) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    val label = remember(elapsedMs) {
        val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (minutes > 0) "$minutes:${seconds.toString().padStart(2, '0')}" else "${seconds}s"
    }
    Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = color.copy(alpha = 0.72f),
        maxLines = 1
    )
}

/**
 * Types a verb in behind a running cursor, with the three characters behind it flickering before they settle.
 * The state is read in the canvas above, so decoding frames never recompose the row.
 */
@Composable
private fun WordDecoder(
    shown: MutableState<String>,
    compacting: Boolean,
    style: AgentActivityStyle,
    textWidth: Int,
    animations: Boolean
) {
    val verbs = remember(style.verbs) { style.verbs.ifEmpty { listOf("Working") } }
    var verb by remember(style.agent) { mutableStateOf(verbs.random()) }

    LaunchedEffect(style.agent, animations, verbs) {
        if (!animations) return@LaunchedEffect
        var changes = 0
        while (true) {
            val wait = style.wordDelaysMs.getOrElse(changes) { style.wordDelaysMs.lastOrNull() ?: 4_500L }
            delay(wait.coerceAtLeast(600L))
            changes++
            verb = verbs.random()
        }
    }

    LaunchedEffect(verb, compacting, style.agent, textWidth, animations) {
        val word = if (compacting) "Compacting" else verb
        val target = (word + "...").padEnd(textWidth)
        if (!animations) {
            shown.value = target
            return@LaunchedEffect
        }
        val frameDelay = style.decodeFrameMs.coerceIn(16L, 80L)
        var cursor = 0
        while (cursor - 3 < target.length) {
            val next = StringBuilder(shown.value)
            for (behind in 0..3) {
                val index = cursor - behind
                if (index < 0 || index >= target.length || index >= next.length) continue
                val want = target[index]
                next.setCharAt(
                    index,
                    when {
                        want == ' ' -> ' '
                        behind == 3 -> want
                        behind == 0 -> '▌'
                        else -> charArrayOf('.', '_', want).random()
                    }
                )
            }
            shown.value = next.toString()
            cursor++
            delay(frameDelay)
        }
    }
}
