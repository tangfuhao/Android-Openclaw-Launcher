package com.openclaw.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.openclaw.android.gateway.GatewayState
import com.openclaw.android.service.ProcessManager.ProcessState
import com.openclaw.android.ui.theme.StatusError
import com.openclaw.android.ui.theme.StatusRunning

private enum class StepStatus { PENDING, ACTIVE, DONE, ERROR }

@Composable
fun ServiceStartupView(
    processState: ProcessState,
    gatewayState: GatewayState,
    modifier: Modifier = Modifier,
) {
    val linuxStep = when (processState) {
        is ProcessState.Stopped -> StepStatus.ACTIVE
        is ProcessState.Starting -> StepStatus.ACTIVE
        is ProcessState.Error, is ProcessState.Crashed -> StepStatus.ERROR
        is ProcessState.Restarting -> StepStatus.ACTIVE
        is ProcessState.Running -> StepStatus.DONE
    }

    val gatewayStep = when {
        linuxStep != StepStatus.DONE -> StepStatus.PENDING
        processState is ProcessState.Running && !gatewayState.isActive && !gatewayState.isConnected ->
            StepStatus.ACTIVE
        gatewayState is GatewayState.Connecting || gatewayState is GatewayState.Handshaking ->
            StepStatus.ACTIVE
        gatewayState is GatewayState.Reconnecting -> StepStatus.ACTIVE
        gatewayState is GatewayState.Error -> StepStatus.ERROR
        gatewayState.isConnected -> StepStatus.DONE
        else -> StepStatus.ACTIVE
    }

    val wsStep = when {
        gatewayStep != StepStatus.DONE -> StepStatus.PENDING
        gatewayState.isConnected -> StepStatus.DONE
        else -> StepStatus.ACTIVE
    }

    val errorMessage = when {
        processState is ProcessState.Error -> processState.message
        processState is ProcessState.Crashed -> "Gateway crashed (exit code ${processState.exitCode})"
        gatewayState is GatewayState.Error -> gatewayState.message
        gatewayState is GatewayState.Disconnected -> gatewayState.reason
        else -> null
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "\uD83E\uDD9E",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Starting OpenClaw",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))

            StartupStep(
                icon = Icons.Default.Terminal,
                label = "Linux Environment",
                status = linuxStep,
            )
            StartupStep(
                icon = Icons.Default.Hub,
                label = "Gateway Service",
                status = gatewayStep,
            )
            StartupStep(
                icon = Icons.Default.Cloud,
                label = "WebSocket Connection",
                status = wsStep,
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun StartupStep(
    icon: ImageVector,
    label: String,
    status: StepStatus,
) {
    val tint by animateColorAsState(
        targetValue = when (status) {
            StepStatus.DONE -> StatusRunning
            StepStatus.ERROR -> StatusError
            StepStatus.ACTIVE -> MaterialTheme.colorScheme.primary
            StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        label = "stepTint",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        when (status) {
            StepStatus.ACTIVE -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            StepStatus.DONE -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = StatusRunning,
                )
            }
            StepStatus.ERROR -> {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = StatusError,
                )
            }
            StepStatus.PENDING -> {
                Spacer(modifier = Modifier.size(18.dp))
            }
        }
    }
}
