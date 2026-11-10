package com.example.gemini.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.theme.AppColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The agent activity system.
 *
 * Every agent has its own identity while it works: a brand gradient, a set of verbs that sound like the tool, and
 * motion timings tuned to its character (Antigravity glides, OpenCode is snappy, Claude is playful, Codex is
 * deliberate). The chat's working row, thinking blocks, tool calls and inline notices all read from here, so the
 * four agents look like four products instead of one spinner with four tints.
 *
 * Everything here is built for the hot path: animations are read while drawing (never during composition), the
 * same state drives every frame, and every loop can stand still when the system's animations are turned off.
 */

/** One agent's look and feel while it works. */
@Immutable
data class AgentActivityStyle(
    val agent: AgentKind?,
    val name: String,
    val accent: Color,
    val secondary: Color,
    val verbs: List<String>,
    /** One full turn of the activity orb, in milliseconds. */
    val orbPeriodMs: Int,
    /** Delay between two characters while a verb is "decoded" in. */
    val decodeFrameMs: Long,
    /** How long each verb stays before the next one: 2 s, 3 s, then the last value. */
    val wordDelaysMs: List<Long>
)

private val AGY_VERBS = listOf(
    "Orbiting", "Lifting", "Charting", "Gravitating", "Navigating", "Assembling", "Sketching",
    "Synthesizing", "Calibrating", "Surveying", "Aligning", "Exploring", "Composing", "Tracing",
    "Mapping", "Shaping", "Scouting", "Weighing", "Connecting", "Building"
)

private val CLAUDE_VERBS = listOf(
    "Accomplishing", "Baking", "Brewing", "Calculating", "Cerebrating", "Channeling", "Churning",
    "Clauding", "Coalescing", "Cogitating", "Combobulating", "Concocting", "Considering",
    "Contemplating", "Cooking", "Crafting", "Creating", "Crunching", "Deciphering", "Deliberating",
    "Determining", "Discombobulating", "Elucidating", "Enchanting", "Envisioning", "Forging",
    "Flibbertigibbeting", "Generating", "Hatching", "Ideating", "Imagining", "Inferring",
    "Manifesting", "Marinating", "Mulling", "Musing", "Noodling", "Percolating", "Pondering",
    "Processing", "Reticulating", "Ruminating", "Shimmying", "Simmering", "Spelunking",
    "Synthesizing", "Thinking", "Tinkering", "Unfurling", "Vibing", "Wrangling"
)

private val OPENCODE_VERBS = listOf(
    "Indexing", "Compiling", "Refactoring", "Formatting", "Linting", "Patching", "Bisecting",
    "Bootstrapping", "Profiling", "Bundling", "Diffing", "Grepping", "Resolving", "Wiring",
    "Migrating", "Fixing", "Testing", "Building", "Shipping", "Committing"
)

private val CODEX_VERBS = listOf(
    "Reasoning", "Deliberating", "Planning", "Inspecting", "Deriving", "Validating", "Executing",
    "Reviewing", "Verifying", "Analyzing", "Decomposing", "Sequencing", "Measuring", "Refining",
    "Proving", "Checking", "Tracing", "Simulating", "Concluding", "Solving"
)

private val GENERIC_VERBS = listOf(
    "Thinking", "Working", "Analyzing", "Building", "Checking", "Planning", "Reading",
    "Searching", "Writing", "Reviewing"
)

/** The identity of [agent]; [fallbackAccent] keeps a caller's own color when the agent is unknown. */
fun agentActivityStyle(agent: AgentKind?, fallbackAccent: Color? = null): AgentActivityStyle = when (agent) {
    AgentKind.AGY -> AgentActivityStyle(
        agent = agent,
        name = "Antigravity",
        accent = AgyAccent,
        secondary = Color(0xFF22D3EE),
        verbs = AGY_VERBS,
        orbPeriodMs = 2600,
        decodeFrameMs = 38L,
        wordDelaysMs = listOf(2_000L, 3_000L, 5_000L)
    )
    AgentKind.CLAUDE -> AgentActivityStyle(
        agent = agent,
        name = "Claude Code",
        accent = ClaudeAccent,
        secondary = Color(0xFFF0B37E),
        verbs = CLAUDE_VERBS,
        orbPeriodMs = 2800,
        decodeFrameMs = 40L,
        wordDelaysMs = listOf(2_000L, 3_000L, 5_000L)
    )
    AgentKind.OPENCODE -> AgentActivityStyle(
        agent = agent,
        name = "OpenCode",
        accent = OpenCodeAccent,
        secondary = Color(0xFF34D399),
        verbs = OPENCODE_VERBS,
        orbPeriodMs = 2200,
        decodeFrameMs = 32L,
        wordDelaysMs = listOf(1_800L, 2_600L, 4_200L)
    )
    AgentKind.CODEX -> AgentActivityStyle(
        agent = agent,
        name = "Codex",
        accent = CodexAccent,
        secondary = Color(0xFFA78BFA),
        verbs = CODEX_VERBS,
        orbPeriodMs = 3000,
        decodeFrameMs = 44L,
        wordDelaysMs = listOf(2_400L, 3_400L, 5_600L)
    )
    null -> AgentActivityStyle(
        agent = null,
        name = "Agent",
        accent = fallbackAccent ?: AppColors.primary,
        secondary = AppColors.tertiary,
        verbs = GENERIC_VERBS,
        orbPeriodMs = 2600,
        decodeFrameMs = 40L,
        wordDelaysMs = listOf(2_000L, 3_000L, 5_000L)
    )
}

@Composable
fun rememberAgentActivityStyle(agent: AgentKind?, fallbackAccent: Color? = null): AgentActivityStyle =
    remember(agent, fallbackAccent) { agentActivityStyle(agent, fallbackAccent) }

/**
 * Whether motion should play: false in previews and when the user turned animations off in Android's accessibility
 * settings. The infinite transitions also honor that setting, but the hand-written tickers in the chat do not.
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0.01f
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * The agent's live mark: a breathing halo, a rotating brand-gradient arc and a bright core. Drawn in one canvas,
 * a few dozen draw calls, with every animation value read in the draw phase so it never recomposes the chat.
 */
@Composable
fun AgentActivityOrb(
    accent: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 22.dp,
    animated: Boolean = rememberSystemAnimationsEnabled(),
    periodMs: Int = 2600
) {
    val transition = rememberInfiniteTransition(label = "agent_activity_orb")
    val phase = if (animated) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMs.coerceAtLeast(400), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "agent_activity_orb_phase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Spacer(
        modifier = modifier
            .size(diameter)
            .drawWithCache {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                // Three shaders, built once per size and reused on every frame.
                val halo = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.34f), Color.Transparent),
                    center = center,
                    radius = radius * 0.98f
                )
                val ring = Brush.sweepGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.02f),
                        accent.copy(alpha = 0.85f),
                        secondary,
                        accent.copy(alpha = 0.02f)
                    ),
                    center = center
                )
                val core = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.96f), secondary, accent),
                    center = Offset(center.x - radius * 0.1f, center.y - radius * 0.1f),
                    radius = radius * 0.72f
                )
                onDrawBehind {
                    if (radius <= 0f) return@onDrawBehind
                    val angle = phase.value

                    // soft breathing halo, kept inside the canvas so it never bleeds into the text beside it
                    drawCircle(brush = halo, radius = radius * 0.98f, center = center)

                    // brand ring, opening toward the top as it turns
                    drawArc(
                        brush = ring,
                        startAngle = angle,
                        sweepAngle = 258f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius * 0.78f, center.y - radius * 0.78f),
                        size = Size(radius * 1.56f, radius * 1.56f),
                        style = Stroke(width = (radius * 0.2f).coerceAtLeast(1.2f), cap = StrokeCap.Round)
                    )

                    // glassy core
                    drawCircle(brush = core, radius = radius * 0.36f, center = center)

                    // a single spark tracing the orbit
                    val sparkAngle = Math.toRadians((angle * 1.35f).toDouble())
                    drawCircle(
                        color = secondary.copy(alpha = 0.9f),
                        radius = (radius * 0.11f).coerceAtLeast(0.8f),
                        center = Offset(
                            center.x + kotlin.math.cos(sparkAngle).toFloat() * radius * 0.78f,
                            center.y + kotlin.math.sin(sparkAngle).toFloat() * radius * 0.78f
                        )
                    )
                }
            }
    )
}

@Composable
fun AgentActivitySignal(
    color: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = rememberSystemAnimationsEnabled()
) {
    val transition = rememberInfiniteTransition(label = "agent_activity_signal")
    val phase = if (animated) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "agent_activity_signal_phase"
        )
    } else {
        remember { mutableStateOf(0.4f) }
    }

    Canvas(modifier = modifier.size(width = 15.dp, height = 13.dp)) {
        val bars = 3
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val gap = size.width * 0.18f
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        repeat(bars) { index ->
            val p = (phase.value + index * 0.24f) % 1f
            val lift = (sin(p * 2f * PI).toFloat() * 0.5f + 0.5f).coerceIn(0f, 1f)
            val height = (size.height * (0.28f + 0.72f * lift)).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.55f * lift),
                topLeft = Offset(index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/** A small live dot with an expanding glow ring; replaces Material's spinner in tight status rows. */
@Composable
fun AgentInlinePulse(
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 12.dp,
    animated: Boolean = rememberSystemAnimationsEnabled()
) {
    val transition = rememberInfiniteTransition(label = "agent_inline_pulse")
    val phase = if (animated) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "agent_inline_pulse_phase"
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    Canvas(modifier = modifier.size(diameter)) {
        val radius = size.minDimension / 2f
        if (radius <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val p = phase.value.coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = 0.16f), radius = radius, center = center)
        drawCircle(color.copy(alpha = 0.5f * (1f - p)), radius = radius * (0.4f + 0.55f * p), center = center)
        drawCircle(color, radius = radius * 0.28f, center = center)
    }
}

/** Three dots that lift in a wave while an agent thinks. */
@Composable
fun AgentThinkingDots(
    color: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = rememberSystemAnimationsEnabled()
) {
    val transition = rememberInfiniteTransition(label = "agent_thinking_dots")
    val phase = if (animated) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "agent_thinking_dots_phase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Canvas(modifier = modifier.size(width = 17.dp, height = 7.dp)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val dotRadius = (size.height * 0.26f).coerceAtLeast(0.9f)
        val step = size.width / 3f
        repeat(3) { index ->
            val p = (phase.value + index * 0.18f) % 1f
            val lift = sin(p * PI).toFloat().coerceIn(0f, 1f)
            drawCircle(
                color = color.copy(alpha = 0.28f + 0.68f * lift),
                radius = dotRadius,
                center = Offset(
                    step * index + step / 2f,
                    size.height - dotRadius - lift * (size.height - dotRadius * 2f)
                )
            )
        }
    }
}

/**
 * A pulsing status ring for a tool call in flight. It draws behind the tool's icon so a running call reads as
 * "alive" without an extra spinner or a per-frame recomposition.
 */
@Composable
fun AgentStatusPulse(
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 18.dp,
    pulsing: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "agent_status_pulse")
    val phase = if (pulsing) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "agent_status_pulse_phase"
        )
    } else {
        remember { mutableStateOf(0.35f) }
    }

    Canvas(modifier = modifier.size(diameter)) {
        val radius = size.minDimension / 2f
        if (radius <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val p = phase.value.coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = 0.14f), radius = radius, center = center)
        drawCircle(
            color = color.copy(alpha = 0.42f * (1f - p)),
            radius = radius * (0.55f + 0.42f * p),
            center = center,
            style = Stroke(width = (radius * 0.14f).coerceAtLeast(1f))
        )
        drawCircle(color.copy(alpha = 0.2f), radius = radius * 0.62f, center = center)
    }
}

/**
 * What the agent is doing right now, from its latest running tool, as a short human line for the working row.
 * Kept terse: it shares one line with the verb, and the row ellipsizes it.
 */
fun toolActivityHint(toolCall: ToolCall?): String? {
    if (toolCall == null || toolCall.status != "RUNNING") return null
    fun fileName(raw: String): String {
        val cleaned = raw.trim().trim('"', '\'')
        return cleaned.substringAfterLast('/').substringAfterLast('\\').ifBlank { cleaned }.take(42)
    }
    return when (toolCall.toolType) {
        ToolType.BASH -> toolCall.command.lineSequence().firstOrNull { it.isNotBlank() }?.let { "Running ${it.trim().take(56)}" }
            ?: "Running a command"
        ToolType.EDIT_FILE -> "Editing ${fileName(toolCall.command)}"
        ToolType.VIEW_FILE -> "Reading ${fileName(toolCall.command)}"
        ToolType.LIST_DIR -> "Exploring ${fileName(toolCall.command)}"
        ToolType.GREP_SEARCH, ToolType.FIND -> "Searching the codebase"
        ToolType.SEARCH_WEB -> "Searching the web"
        ToolType.READ_URL -> "Reading the page"
        ToolType.MATH -> "Working through the math"
        ToolType.GENERATE_IMAGE -> "Generating an image"
        ToolType.MCP -> "Using ${toolCall.name.ifBlank { "an MCP tool" }}"
        ToolType.ASK_CHOICE -> "Waiting for your answer"
        ToolType.SYSTEM_NOTIFICATION -> "Thinking"
        else -> "Working"
    }?.take(64)
}
