package com.openclaw.android.proot

/**
 * State machine representing every phase of the rootfs installation lifecycle.
 * Observed by the UI to display download/extraction/verification progress.
 */
sealed interface RootfsState {
    data object NotInstalled : RootfsState
    data object Checking : RootfsState

    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : RootfsState

    data class Extracting(val progress: Float) : RootfsState
    data object Configuring : RootfsState
    data object Verifying : RootfsState
    data object Installed : RootfsState

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : RootfsState

    val isTerminal: Boolean
        get() = this is Installed || this is Error
}
