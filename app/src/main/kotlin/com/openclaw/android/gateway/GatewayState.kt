package com.openclaw.android.gateway

sealed interface GatewayState {
    data object Idle : GatewayState
    data object Connecting : GatewayState
    data object Handshaking : GatewayState
    data class Connected(val protocol: Int) : GatewayState
    data object Reconnecting : GatewayState
    data class Disconnected(val reason: String? = null) : GatewayState
    data class Error(val message: String, val cause: Throwable? = null) : GatewayState

    val isConnected: Boolean get() = this is Connected
    val isActive: Boolean get() = this is Connected || this is Handshaking || this is Connecting
}
