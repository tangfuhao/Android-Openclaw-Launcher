package com.openclaw.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.ui.theme.StatusConnecting
import com.openclaw.android.ui.theme.StatusError
import com.openclaw.android.ui.theme.StatusRunning
import com.openclaw.android.ui.theme.StatusWarning

@Composable
fun StatusDot(
    connectionState: GatewayState,
    activeRunId: String?,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val isProcessing = connectionState.isConnected && activeRunId != null
    val isConnecting = connectionState is GatewayState.Connecting ||
        connectionState is GatewayState.Handshaking ||
        connectionState is GatewayState.Reconnecting

    val shouldPulse = isProcessing || isConnecting

    val color = when {
        connectionState.isConnected -> StatusRunning
        isConnecting -> StatusWarning
        connectionState is GatewayState.Idle -> StatusConnecting
        else -> StatusError
    }

    val alpha = if (shouldPulse) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        animatedAlpha
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}
