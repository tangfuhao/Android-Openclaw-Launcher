package com.openclaw.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.ui.theme.StatusConnecting
import com.openclaw.android.ui.theme.StatusError
import com.openclaw.android.ui.theme.StatusRunning
import com.openclaw.android.ui.theme.StatusWarning

@Composable
fun ConnectionStatusBar(state: GatewayState) {
    val (statusColor, statusText) = when (state) {
        is GatewayState.Connected -> StatusRunning to "Connected"
        is GatewayState.Connecting -> StatusConnecting to "Connecting..."
        is GatewayState.Handshaking -> StatusConnecting to "Authenticating..."
        is GatewayState.Reconnecting -> StatusWarning to "Reconnecting..."
        is GatewayState.Disconnected -> StatusError to "Disconnected"
        is GatewayState.Error -> StatusError to "Error: ${state.message}"
        is GatewayState.Idle -> StatusWarning to "Idle"
    }

    val animatedColor by animateColorAsState(targetValue = statusColor, label = "status_color")

    if (state !is GatewayState.Connected) {
        Surface(tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(animatedColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
