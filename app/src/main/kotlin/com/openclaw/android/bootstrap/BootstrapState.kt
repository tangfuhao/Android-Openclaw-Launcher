package com.openclaw.android.bootstrap

sealed interface BootstrapState {
    data object NotInstalled : BootstrapState
    data object Checking : BootstrapState
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : BootstrapState
    data class Extracting(val progress: Float) : BootstrapState
    data object Configuring : BootstrapState
    data object Installed : BootstrapState
    data class Error(val message: String, val cause: Throwable? = null) : BootstrapState

    val isTerminal: Boolean
        get() = this is Installed || this is Error
}
