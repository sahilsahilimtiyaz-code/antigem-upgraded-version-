package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.AppColors

/** How much of a long thought is scanned (and kept) for the one-line preview under the header. */
private const val PREVIEW_SCAN_CHARS = 1_200
private const val PREVIEW_CHARS = 120

/**
 * The agent's reasoning, in the same visual language as its working row: a soft accent wash while it streams,
 * a live three-dot rhythm, a tinted rail beside the text and a duration chip when it settles.
 *
 * The preview is derived from a small, bounded slice of the text: a long streamed thought is not re-split and
 * re-scanned on every token, which is what used to make long turns stutter while scrolling.
 */
@Composable
fun ThinkingAccordion(
    thoughtText: String,
    durationMs: Long?,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = AppColors.primary
) {
    var isExpanded by remember { mutableStateOf(isStreaming) }
    var hasUserManuallyToggled by remember { mutableStateOf(false) }

    // Auto-expand while actively streaming thoughts if user hasn't manually toggled
    LaunchedEffect(isStreaming) {
        if (isStreaming && !hasUserManuallyToggled) {
            isExpanded = true
        }
    }

    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 720f),
        label = "thought_chevron_rotation"
    )

    val previewText = remember(thoughtText) { thinkingPreview(thoughtText) }
    val highlighted = isStreaming || isExpanded
    val headerBrush = remember(highlighted, isStreaming, accent) {
        if (highlighted) {
            Brush.horizontalGradient(
                listOf(
                    accent.copy(alpha = if (isStreaming) 0.11f else 0.055f),
                    accent.copy(alpha = 0.02f),
                    Color.Transparent
                )
            )
        } else {
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(headerBrush)
                .clickable {
                    hasUserManuallyToggled = true
                    isExpanded = !isExpanded
                }
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse thinking" else "Expand thinking",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(15.dp)
                    .rotate(rotation)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(11.dp)
                )
            }

            Spacer(modifier = Modifier.width(7.dp))

            if (isStreaming) {
                Text(
                    text = "Thinking",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    letterSpacing = 0.1.sp
                )
                Spacer(modifier = Modifier.width(7.dp))
                AgentThinkingDots(color = accent)
                if (previewText.isNotBlank()) {
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = previewText,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            } else {
                Text(
                    text = if (durationMs != null && durationMs > 0) "Thought for ${formatDuration(durationMs)}" else "Thinking process",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )

                if (!isExpanded && previewText.isNotBlank()) {
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = previewText,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                if (durationMs != null && durationMs > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    DurationChip(durationMs = durationMs, accent = accent)
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(animationSpec = tween(180)) +
                expandVertically(animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)),
            exit = fadeOut(animationSpec = tween(120)) +
                shrinkVertically(animationSpec = spring(dampingRatio = 1f, stiffness = 620f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp)
                    .drawBehind {
                        // the tinted reasoning rail: drawn, not laid out, so it always spans the real text height
                        val railWidth = 2.dp.toPx()
                        if (size.height <= 0f || railWidth <= 0f) return@drawBehind
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    accent.copy(alpha = 0.78f),
                                    accent.copy(alpha = 0.24f),
                                    Color.Transparent
                                )
                            ),
                            topLeft = Offset(0f, 0f),
                            size = Size(railWidth, size.height),
                            cornerRadius = CornerRadius(railWidth / 2f, railWidth / 2f)
                        )
                    }
                    .padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 4.dp)
            ) {
                Text(
                    text = thoughtText,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    lineHeight = 17.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isStreaming && durationMs != null && durationMs > 0) {
                    Spacer(modifier = Modifier.height(7.dp))
                    DurationChip(durationMs = durationMs, accent = accent, label = "Thought for")
                }
            }
        }
    }
}

@Composable
private fun DurationChip(durationMs: Long, accent: Color, label: String? = null) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
                tint = accent.copy(alpha = 0.82f),
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = listOfNotNull(label, formatDuration(durationMs)).joinToString(" "),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.9f),
                maxLines = 1
            )
        }
    }
}

/** The first meaningful line of a thought, from a bounded slice: cheap even while tokens stream in. */
private fun thinkingPreview(text: String): String {
    if (text.isBlank()) return ""
    val head = if (text.length > PREVIEW_SCAN_CHARS) text.substring(0, PREVIEW_SCAN_CHARS) else text
    return head.lineSequence()
        .map { it.trim().trimStart('#', '*', '-', '>', ' ').trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(PREVIEW_CHARS)
        .orEmpty()
}
