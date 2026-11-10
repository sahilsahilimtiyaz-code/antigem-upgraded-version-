package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.remote.HubMediaResolver
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.theme.*

private val IMAGE_FILE_REGEX = Regex(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE)

/** A runaway command can print megabytes; the card shows a bounded slice so opening it cannot lock up the chat. */
private const val MAX_TOOL_OUTPUT_CHARS = 60_000

private fun clippedToolOutput(output: String): String =
    if (output.length <= MAX_TOOL_OUTPUT_CHARS) output
    else output.take(MAX_TOOL_OUTPUT_CHARS) +
        "\n\n… output truncated for display (" + (output.length - MAX_TOOL_OUTPUT_CHARS) +
        " more characters; Copy gets the whole output)"

/**
 * Clean, professional inline tool call component matching Cursor & Claude developer UI.
 * Rendered without heavy bordered boxes — features an animated frontend chevron (->), status icon,
 * concise action description, and smoothly expandable terminal stdout output.
 */
@Composable
fun AgentToolCallCard(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
    onApprove: ((ToolCall) -> Unit)? = null,
    onReject: ((ToolCall) -> Unit)? = null,
    onTerminate: ((ToolCall) -> Unit)? = null,
    onSubmitChoices: ((ToolCall, List<AskQuestionResponseItemDto>, String) -> Unit)? = null,
    onSkipChoices: ((ToolCall, List<AskQuestionResponseItemDto>) -> Unit)? = null
) {
    val isChoice = toolCall.toolType == ToolType.ASK_CHOICE
    if (isChoice) {
        ChoiceQuestionnaireCard(
            toolCall = toolCall,
            onSubmit = { responses, summary -> onSubmitChoices?.invoke(toolCall, responses, summary) },
            onSkip = { responses -> onSkipChoices?.invoke(toolCall, responses) },
            onCancel = { onReject?.invoke(toolCall) },
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val isExpanded = ToolCallExpansionCache.isExpanded(toolCall, default = false)

    val isSearch = toolCall.toolType == ToolType.SEARCH_WEB
    val isReader = toolCall.toolType == ToolType.READ_URL
    val isMath = toolCall.toolType == ToolType.MATH
    val isViewFile = toolCall.toolType == ToolType.VIEW_FILE
    val isEditFile = toolCall.toolType == ToolType.EDIT_FILE
    val isListDir = toolCall.toolType == ToolType.LIST_DIR
    val isFind = toolCall.toolType == ToolType.FIND
    val isGrep = toolCall.toolType == ToolType.GREP_SEARCH
    val isGenImg = toolCall.toolType == ToolType.GENERATE_IMAGE
    val isMcp = toolCall.toolType == ToolType.MCP
    val isBash = toolCall.toolType == ToolType.BASH
    val isSystemNotice = toolCall.toolType == ToolType.SYSTEM_NOTIFICATION || toolCall.name == "system_notice"

    val isImageOutput = remember(toolCall.output, isGenImg) {
        isGenImg || toolCall.output.startsWith("data:image/") ||
        (toolCall.output.startsWith("file://") && toolCall.output.matches(IMAGE_FILE_REGEX))
    }

    // Resolve off the composition: the old synchronous lookup ran disk checks while the chat was drawing, and a
    // malformed media path could throw straight through the message list. Both now happen once, guarded, in an effect.
    var resolvedImageUri by remember(toolCall.output) { mutableStateOf("") }
    var isResolvingImage by remember(toolCall.output) {
        mutableStateOf(isImageOutput && toolCall.output.isNotBlank())
    }
    var showFullScreenViewer by remember { mutableStateOf(false) }

    LaunchedEffect(toolCall.output, isImageOutput) {
        if (!isImageOutput || toolCall.output.isBlank()) {
            isResolvingImage = false
            return@LaunchedEffect
        }
        isResolvingImage = true
        resolvedImageUri = runCatching {
            if (HubMediaResolver.isLocalOrCached(context, toolCall.output)) {
                HubMediaResolver.getResolvedUriSync(context, toolCall.output)
            } else {
                HubMediaResolver.resolveMediaUri(context, toolCall.output)
            }
        }.getOrDefault("").ifBlank {
            runCatching { HubMediaResolver.getResolvedUriSync(context, toolCall.output) }.getOrDefault("")
        }
        isResolvingImage = false
    }

    val isPendingApproval = toolCall.status == "PENDING_APPROVAL"
    val isRunning = toolCall.status == "RUNNING"
    val isTerminated = toolCall.status == "TERMINATED"
    val isRejected = toolCall.status == "REJECTED"
    val isSuccess = toolCall.status == "SUCCESS" || (toolCall.status != "FAILED" && !isTerminated && !isRejected && !isPendingApproval && toolCall.exitCode == 0)
    val isFailed = toolCall.status == "FAILED" || (toolCall.exitCode != null && toolCall.exitCode != 0 && !isTerminated && !isRejected)

    val actionPrefix = when {
        isPendingApproval && isMcp -> "Approve MCP:"
        isPendingApproval -> "Approve Command:"
        isRunning && isSearch -> "Searching web..."
        isRunning && isReader -> "Fetching page..."
        isRunning && isMath -> "Evaluating math..."
        isRunning && isViewFile -> "Reading file..."
        isRunning && isEditFile -> "Editing file..."
        isRunning && isListDir -> "Listing directory..."
        isRunning && (isFind || isGrep) -> "Searching code..."
        isRunning && isGenImg -> "Generating image..."
        isRunning && isMcp -> "Executing MCP tool..."
        isRunning && isSystemNotice -> "System event..."
        isRunning -> "Executing in Termux..."

        isSuccess && isSystemNotice -> "System:"
        isSuccess && isSearch -> "Web search"
        isSuccess && isReader -> "Read page"
        isSuccess && isMath -> "Math evaluated"
        isSuccess && isViewFile -> "Read file"
        isSuccess && isEditFile -> "Edited file"
        isSuccess && isListDir -> "Listed directory"
        isSuccess && (isFind || isGrep) -> "Searched code"
        isSuccess && isGenImg -> "Generated image"
        isSuccess && isMcp -> "MCP tool"
        isSuccess -> "Ran bash"

        isTerminated -> "Stopped:"
        isRejected -> "Rejected:"
        isFailed -> "Failed:"
        else -> "Tool:"
    }

    val actionColor = when {
        isPendingApproval -> com.example.gemini.theme.AppColors.warning
        isRunning -> ClaudeTerracotta
        isSuccess -> MaterialTheme.colorScheme.onSurface
        isTerminated || isFailed -> com.example.gemini.theme.AppColors.danger
        isRejected -> Color.Gray
        else -> MaterialTheme.colorScheme.onSurface
    }

    val animatedActionColor by animateColorAsState(
        targetValue = actionColor,
        animationSpec = tween(durationMillis = 220),
        label = "tool_action_color"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 760f),
        label = "tool_chevron_rotation"
    )

    val displayOutput = remember(toolCall.output) { clippedToolOutput(toolCall.output) }
    val headerShape = remember { RoundedCornerShape(8.dp) }
    // Keyed on the states (and theme colors) that change it: a streaming command does not allocate brushes per token.
    val headerAccent = ClaudeTerracotta
    val headerWarning = AppColors.warning
    val headerSurface = MaterialTheme.colorScheme.surfaceVariant
    val headerBrush = remember(isRunning, isPendingApproval, isExpanded, headerAccent, headerWarning, headerSurface) {
        when {
            isRunning -> Brush.horizontalGradient(
                listOf(headerAccent.copy(alpha = 0.1f), headerAccent.copy(alpha = 0.025f), Color.Transparent)
            )
            isPendingApproval -> Brush.horizontalGradient(
                listOf(headerWarning.copy(alpha = 0.12f), headerWarning.copy(alpha = 0.02f), Color.Transparent)
            )
            isExpanded -> Brush.horizontalGradient(
                listOf(headerSurface.copy(alpha = 0.5f), Color.Transparent)
            )
            else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        // Flat, borderless single-line Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(headerShape)
                .background(headerBrush)
                .clickable { ToolCallExpansionCache.setExpanded(toolCall, !isExpanded) }
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frontend Expand Chevron (-> / rotating arrow)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )

            Spacer(modifier = Modifier.width(4.dp))

            val isTimer = toolCall.command.contains("timer", ignoreCase = true) || toolCall.command.contains("schedule", ignoreCase = true)
            val toolIcon = when {
                isSystemNotice -> if (isTimer) Icons.Outlined.Schedule else Icons.Outlined.Notifications
                isSearch -> Icons.Outlined.Search
                isReader -> Icons.Outlined.Language
                isMath -> Icons.Outlined.Functions
                isViewFile -> Icons.Default.Description
                isEditFile -> Icons.Default.Edit
                isListDir -> Icons.Default.Folder
                isFind || isGrep -> Icons.Default.Search
                isGenImg -> Icons.Default.Image
                isMcp -> Icons.Default.Build
                else -> Icons.Default.Terminal
            }

            // Tool / State mark: a tinted disc, with a live pulse while the call is in flight.
            val statusTint = when {
                isPendingApproval -> AppColors.warning
                isRunning -> ClaudeTerracotta
                isSuccess -> QuotaGreen
                isTerminated || isFailed -> AppColors.danger
                isRejected -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusIcon = when {
                isPendingApproval -> Icons.Default.HourglassEmpty
                isTerminated -> Icons.Default.Cancel
                isRejected -> Icons.Default.Close
                isFailed -> Icons.Default.Warning
                else -> toolIcon
            }
            val statusDescription = when {
                isPendingApproval -> "Pending approval"
                isRunning -> "Running"
                isSuccess -> "Done"
                isTerminated -> "Terminated"
                isRejected -> "Rejected"
                isFailed -> "Failed"
                else -> null
            }
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRunning) {
                    AgentStatusPulse(color = statusTint, diameter = 18.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(statusTint.copy(alpha = 0.11f))
                    )
                }
                Icon(
                    imageVector = statusIcon,
                    contentDescription = statusDescription,
                    tint = statusTint,
                    modifier = Modifier.size(11.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Action Verb
            Text(
                text = actionPrefix,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = animatedActionColor
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Command / Target argument
            Text(
                text = toolCall.command,
                fontFamily = if (isBash || isMcp || isViewFile || isEditFile) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Pristine Circular Terminate (Stop) Button while Running
            if (isRunning && onTerminate != null && isBash) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(com.example.gemini.theme.AppColors.danger.copy(alpha = 0.18f))
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = { onTerminate(toolCall) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop command",
                        tint = com.example.gemini.theme.AppColors.danger,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // Duration
            if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                Text(
                    text = formatDuration(toolCall.durationMs),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Exit Code Badge
            if (isTerminated) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppColors.danger.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "stopped",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.danger
                    )
                }
            } else if (toolCall.exitCode != null && !isRunning && !isPendingApproval && !isRejected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSuccess) QuotaGreen.copy(alpha = 0.12f) else Color.Red.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "exit ${toolCall.exitCode}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSuccess) QuotaGreen else Color.Red
                    )
                }
            }
        }

        // Always-visible Image Preview for Generate Image tool (once generated)
        if (isImageOutput && !isRunning && (toolCall.output.isNotBlank() || isSuccess)) {
            val displayUri = resolvedImageUri.ifBlank { toolCall.output }
            val coilData: Any? = remember(displayUri, resolvedImageUri) {
                    val memKey = HubMediaResolver.normalizeKey(toolCall.output)
                    val cachedBytes = HubMediaResolver.getImageBytes(memKey)
                    when {
                        cachedBytes != null -> cachedBytes
                        displayUri.startsWith("data:image/") -> {
                            try {
                                val b64 = displayUri.substringAfter("base64,")
                                if (b64.isNotBlank()) {
                                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                } else null
                            } catch (_: Exception) { displayUri }
                        }
                        displayUri.startsWith("file://") -> {
                            val f = java.io.File(displayUri.removePrefix("file://"))
                            if (f.exists() && f.canRead()) f else displayUri
                        }
                        displayUri.isNotBlank() -> {
                            val f = java.io.File(displayUri)
                            if (f.exists() && f.canRead()) f else displayUri
                        }
                        else -> null
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = com.example.gemini.theme.AppColors.terminalBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(com.example.gemini.theme.AppColors.editorBg)
                                .clickable {
                                    if (displayUri.isNotBlank()) showFullScreenViewer = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isResolvingImage) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AgentInlinePulse(color = ClaudeTerracotta, diameter = 11.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Preparing image…",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(coilData)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = toolCall.command,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 140.dp, max = 340.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Tap image to enlarge • Double-tap zoom",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { showFullScreenViewer = true },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Full view",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            val snackbarHostState = LocalSnackbarHostState.current
                            val coroutineScope = rememberCoroutineScope()
                            IconButton(
                                onClick = {
                                    ImageDownloadHelper.downloadImage(
                                        context = context,
                                        imageSource = displayUri,
                                        title = toolCall.command,
                                        coroutineScope = coroutineScope,
                                        snackbarHostState = snackbarHostState
                                    )
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download Image",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

        // Expanded Terminal / Tool Output
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(animationSpec = tween(170)) +
                expandVertically(animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)),
            exit = fadeOut(animationSpec = tween(120)) +
                shrinkVertically(animationSpec = spring(dampingRatio = 1f, stiffness = 620f))
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = com.example.gemini.theme.AppColors.terminalBg,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    // Command Bar with copy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val commandPrefix = when {
                            isSystemNotice -> "Notification: ${toolCall.command}"
                            isSearch -> "Search Query: \"${toolCall.command}\""
                            isReader -> "URL: ${toolCall.command}"
                            isMath -> "CAS Expr: ${toolCall.command}"
                            isViewFile -> "File: ${toolCall.command}"
                            isEditFile -> "File: ${toolCall.command}"
                            isListDir -> "Directory: ${toolCall.command}"
                            isGrep -> "Grep: ${toolCall.command}"
                            isFind -> "Find: ${toolCall.command}"
                            isGenImg -> "Prompt: ${toolCall.command}"
                            isMcp -> "MCP: ${toolCall.command}"
                            else -> if (isBash) "$ ${toolCall.command}" else toolCall.command
                        }
                        Text(
                            text = commandPrefix,
                            fontFamily = if (isBash || isMcp) FontFamily.Monospace else FontFamily.Default,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = ClaudeTerracotta,
                            modifier = Modifier.weight(1f)
                        )

                        if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                            Text(
                                text = "⏱️ ${formatDuration(toolCall.durationMs)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Tool Output", toolCall.output)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy Output",
                                tint = Color.Gray,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (toolCall.output.isEmpty()) {
                        val emptyMessage = when {
                            isRunning && isViewFile -> "Reading file contents..."
                            isRunning && isEditFile -> "Applying file modifications..."
                            isRunning && isListDir -> "Scanning directory..."
                            isRunning && (isFind || isGrep) -> "Searching..."
                            isRunning && isGenImg -> "Generating image..."
                            isRunning && isMcp -> "Executing MCP tool..."
                            isRunning && isBash -> "Running in Termux..."
                            isRunning -> "Processing..."
                            isPendingApproval -> "(Waiting for approval)"
                            isViewFile -> "(File inspected by model)"
                            isEditFile -> "(File modification completed)"
                            isListDir -> "(Directory contents scanned)"
                            isFind || isGrep -> "(Search completed - no output)"
                            isGenImg -> "(Image generation completed)"
                            isMcp -> "(MCP tool executed - no output)"
                            else -> "(No output returned)"
                        }
                        Text(
                            text = emptyMessage,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    } else {
                        Text(
                            text = displayOutput,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = com.example.gemini.theme.AppColors.editorText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    if (showFullScreenViewer) {
        val displayUri = resolvedImageUri.ifBlank { toolCall.output }
        FullScreenImageDialog(
            imageUrl = displayUri,
            title = toolCall.command,
            onDismiss = { showFullScreenViewer = false }
        )
    }
}
