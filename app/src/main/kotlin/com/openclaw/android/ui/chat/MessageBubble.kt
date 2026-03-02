package com.openclaw.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.openclaw.android.data.ChatMessage
import com.openclaw.android.data.ContentBlock
import com.openclaw.android.data.RunPhase
import com.openclaw.android.data.ToolActivity
import com.openclaw.android.data.ToolPhase
import com.openclaw.android.proot.FileBridge
import com.openclaw.android.ui.chat.media.FullScreenImageViewer
import com.openclaw.android.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    fileBridge: FileBridge? = null,
    onRetry: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val isDark = isSystemInDarkTheme()
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
    var showMenu by remember { mutableStateOf(false) }
    var fullScreenImage by remember { mutableStateOf<Any?>(null) }

    val bubbleColor = when {
        isUser && isDark -> DarkUserBubble
        isUser -> UserBubble
        isDark -> DarkAssistantBubble
        else -> AssistantBubble
    }
    val textColor = when {
        isUser && isDark -> DarkUserBubbleText
        isUser -> UserBubbleText
        isDark -> DarkAssistantBubbleText
        else -> AssistantBubbleText
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp,
    )

    val skipToolBlocksInContent = message.toolActivities.isNotEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .animateContentSize()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

                    // Thinking indicator (visible when THINKING and no text yet)
                    if (message.runPhase == RunPhase.THINKING && message.textContent.isBlank()) {
                        ThinkingIndicator()
                    }

                    // Content blocks (text, images, attachments — skip tool blocks if toolActivities present)
                    message.contentBlocks.forEach { block ->
                        when (block) {
                            is ContentBlock.Text -> {
                                if (block.text.isNotBlank()) {
                                    if (isUser) {
                                        Text(
                                            text = block.text,
                                            color = textColor,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    } else {
                                        Markdown(
                                            content = block.text,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            is ContentBlock.ToolUse -> {
                                if (!skipToolBlocksInContent) {
                                    ToolUseCard(
                                        toolName = block.name,
                                        input = block.input.toString(),
                                        isStreaming = message.isStreaming,
                                    )
                                }
                            }
                            is ContentBlock.ToolResult -> {
                                if (!skipToolBlocksInContent) {
                                    ToolResultCard(
                                        content = block.content,
                                        isError = block.isError,
                                    )
                                }
                            }
                            is ContentBlock.Image -> {
                                InlineImageCard(
                                    block = block,
                                    fileBridge = fileBridge,
                                    onFullScreen = { source -> fullScreenImage = source },
                                )
                            }
                            is ContentBlock.MediaRef -> {
                                MediaRefCard(
                                    block = block,
                                    fileBridge = fileBridge,
                                )
                            }
                            else -> {}
                        }
                    }

                    // Agent activity section (live tool execution)
                    if (message.toolActivities.isNotEmpty()) {
                        AgentActivitySection(
                            activities = message.toolActivities,
                            runPhase = message.runPhase,
                            fileBridge = fileBridge,
                            onFullScreenImage = { source -> fullScreenImage = source },
                        )
                    }

                    // Streaming indicator at bottom
                    if (message.isStreaming && message.runPhase != RunPhase.THINKING) {
                        Spacer(modifier = Modifier.height(4.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = textColor.copy(alpha = 0.6f),
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatTimestamp(message.timestamp),
                        color = textColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }

            MessageContextMenu(
                expanded = showMenu,
                message = message,
                onDismiss = { showMenu = false },
                onRetry = onRetry,
                onDelete = onDelete,
            )
        }
    }

    if (fullScreenImage != null) {
        FullScreenImageViewer(
            imageSource = fullScreenImage!!,
            onDismiss = { fullScreenImage = null },
        )
    }
}

// ── Thinking Indicator ──────────────────────────────────────────────

@Composable
private fun ThinkingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Agent Activity Section ──────────────────────────────────────────

@Composable
private fun AgentActivitySection(
    activities: List<ToolActivity>,
    runPhase: RunPhase,
    fileBridge: FileBridge? = null,
    onFullScreenImage: ((Any) -> Unit)? = null,
) {
    var expanded by remember(runPhase) {
        mutableStateOf(runPhase != RunPhase.DONE)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header: summary + toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.size(6.dp))

                val running = activities.count { it.phase == ToolPhase.RUNNING }
                val headerText = when {
                    runPhase == RunPhase.DONE -> {
                        val count = activities.size
                        "Used $count tool${if (count != 1) "s" else ""}"
                    }
                    running > 0 -> {
                        val latest = activities.last { it.phase == ToolPhase.RUNNING }
                        "Running: ${latest.toolName}"
                    }
                    else -> "Agent tools"
                }

                Text(
                    text = headerText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )

                if (runPhase != RunPhase.DONE) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                }

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Expanded detail list with max height
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val scrollState = rememberScrollState()

                LaunchedEffect(activities.size) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    activities.forEachIndexed { index, activity ->
                        val isLast = index == activities.lastIndex
                        ToolActivityItem(
                            activity = activity,
                            showDetail = isLast && activity.phase == ToolPhase.RUNNING,
                            fileBridge = fileBridge,
                            onFullScreenImage = onFullScreenImage,
                        )
                    }
                }
            }
        }
    }
}

// ── Tool Activity Item ──────────────────────────────────────────────

@Composable
private fun ToolActivityItem(
    activity: ToolActivity,
    showDetail: Boolean,
    fileBridge: FileBridge? = null,
    onFullScreenImage: ((Any) -> Unit)? = null,
) {
    var detailExpanded by remember(activity.toolId, activity.phase) {
        mutableStateOf(showDetail)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .clickable { detailExpanded = !detailExpanded }
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status icon
            when (activity.phase) {
                ToolPhase.RUNNING, ToolPhase.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ToolPhase.COMPLETED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFF4CAF50),
                    )
                }
                ToolPhase.ERROR -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.size(6.dp))

            Text(
                text = activity.toolName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (activity.phase == ToolPhase.COMPLETED || activity.phase == ToolPhase.ERROR) {
                Icon(
                    if (detailExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Detail: input (for running) or output (for completed)
        if (detailExpanded) {
            val detailText = when {
                activity.phase == ToolPhase.RUNNING && activity.input != null ->
                    activity.input.toString().take(500)
                activity.output != null ->
                    activity.output.take(500)
                else -> null
            }

            if (detailText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (activity.isError)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
            }

            activity.mediaBlocks.forEach { block ->
                when (block) {
                    is ContentBlock.Image -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        InlineImageCard(
                            block = block,
                            fileBridge = fileBridge,
                            onFullScreen = onFullScreenImage,
                        )
                    }
                    is ContentBlock.MediaRef -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        MediaRefCard(block = block, fileBridge = fileBridge)
                    }
                    else -> {}
                }
            }
        }
    }
}

// ── Legacy tool cards (for historical messages without toolActivities) ───

@Composable
private fun ToolUseCard(
    toolName: String,
    input: String,
    isStreaming: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
                Spacer(modifier = Modifier.size(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && input.isNotBlank() && input != "{}") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = input,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun ToolResultCard(
    content: String,
    isError: Boolean,
) {
    if (content.isBlank()) return

    var expanded by remember { mutableStateOf(content.lines().size <= 10) }
    val lineCount = content.lines().size
    val borderColor = if (isError)
        MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
    else
        MaterialTheme.colorScheme.outlineVariant

    Surface(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (isError) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            val displayContent = if (!expanded && lineCount > 10) {
                content.lines().take(10).joinToString("\n") + "\n..."
            } else {
                content
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(8.dp),
            ) {
                Text(
                    text = displayContent,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            if (lineCount > 10) {
                Text(
                    text = if (expanded) "Show less" else "Show all ($lineCount lines)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

// ── Inline Image Card ──────────────────────────────────────────────

@Composable
private fun InlineImageCard(
    block: ContentBlock.Image,
    fileBridge: FileBridge?,
    onFullScreen: ((Any) -> Unit)?,
) {
    val imageModel: Any? = remember(block) {
        resolveImageModel(block, fileBridge)
    }

    if (imageModel != null) {
        var loadFailed by remember { mutableStateOf(false) }

        if (!loadFailed) {
            AsyncImage(
                model = imageModel,
                contentDescription = "Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onFullScreen?.invoke(imageModel) },
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) loadFailed = true
                },
            )
        } else {
            ImagePlaceholderCard(block)
        }
    } else {
        ImagePlaceholderCard(block)
    }
}

private fun resolveImageModel(block: ContentBlock.Image, fileBridge: FileBridge?): Any? {
    if (!block.source.isNullOrBlank()) {
        val src = block.source
        return if (src.startsWith("/")) {
            val hostFile = fileBridge?.getHostFile(src)
            if (hostFile != null && hostFile.exists()) hostFile else null
        } else {
            "data:${block.mediaType};base64,$src"
        }
    }

    if (block.omitted && block.prootPath != null && fileBridge != null) {
        val hostFile = fileBridge.getHostFile(block.prootPath)
        if (hostFile.exists()) return hostFile
    }

    return null
}

@Composable
private fun ImagePlaceholderCard(block: ContentBlock.Image) {
    val sizeLabel = block.bytes?.let { formatFileSize(it) } ?: ""
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column {
                Text(
                    text = "Image${if (sizeLabel.isNotEmpty()) " ($sizeLabel)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (block.omitted) {
                    Text(
                        text = "Binary data omitted from history",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

// ── Media Ref Card ─────────────────────────────────────────────────

@Composable
private fun MediaRefCard(
    block: ContentBlock.MediaRef,
    fileBridge: FileBridge?,
) {
    val hostFile = remember(block.prootPath) {
        fileBridge?.getHostFile(block.prootPath)
    }
    val exists = hostFile?.exists() == true
    val isAudio = fileBridge?.isAudio(block.mimeType) == true
    val isVideo = fileBridge?.isVideo(block.mimeType) == true

    when {
        isAudio && exists -> {
            AudioPlayerCard(
                filePath = hostFile!!.absolutePath,
                fileName = block.fileName,
            )
        }
        isVideo && exists -> {
            VideoPlayerCard(filePath = hostFile!!.absolutePath)
        }
        else -> {
            FileRefCard(block = block, exists = exists)
        }
    }
}

@Composable
private fun FileRefCard(
    block: ContentBlock.MediaRef,
    exists: Boolean,
) {
    val sizeLabel = block.size?.let { formatFileSize(it) } ?: ""
    val icon = when {
        block.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
        block.mimeType.startsWith("video/") -> Icons.Default.VideoFile
        block.mimeType.startsWith("image/") -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    if (sizeLabel.isNotEmpty()) append(sizeLabel)
                    if (!exists) {
                        if (isNotEmpty()) append(" · ")
                        append("File not available locally")
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
