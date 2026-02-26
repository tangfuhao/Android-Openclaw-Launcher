package com.openclaw.android.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.proot.ProotExecutor
import com.openclaw.android.proot.RootfsInstaller
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootfsInstaller: RootfsInstaller,
    private val paths: OpenClawConstants.Paths,
    private val prootExecutor: ProotExecutor,
) : ViewModel() {

    companion object {
        private const val TAG = "TerminalViewModel"
        private const val DEFAULT_FONT_SIZE = 14
        private const val TRANSCRIPT_ROWS = 5000
    }

    private val _rootfsInstalled = MutableStateFlow(rootfsInstaller.isInstalled())
    val rootfsInstalled: StateFlow<Boolean> = _rootfsInstalled.asStateFlow()

    private val _fontSize = MutableStateFlow(DEFAULT_FONT_SIZE)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _sessionTitle = MutableStateFlow("Terminal")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    private var terminalSession: TerminalSession? = null
    private var terminalView: TerminalView? = null

    val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            _sessionTitle.value = changedSession.title ?: "Terminal"
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            Log.i(TAG, "Terminal session finished")
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            session.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }
    }

    val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val newSize = (_fontSize.value * scale).toInt().coerceIn(8, 32)
            if (newSize != _fontSize.value) {
                _fontSize.value = newSize
                terminalView?.setTextSize(newSize)
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent) {}

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(e: MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() {}

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }
    }

    /**
     * Creates a new TerminalSession running an interactive shell inside proot.
     * The shell process is: proot --rootfs=<rootfs> /usr/bin/bash --login
     */
    fun createSession(): TerminalSession {
        terminalSession?.finishIfRunning()

        val prootCommand = prootExecutor.buildCommand(
            listOf(OpenClawConstants.INNER_SHELL_BINARY, "--login")
        )
        val env = prootExecutor.buildEnvironment()
        val envArray = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()

        val session = TerminalSession(
            prootExecutor.prootBinaryPath,
            paths.root.absolutePath,
            prootCommand.toTypedArray(),
            envArray,
            TRANSCRIPT_ROWS,
            sessionClient,
        )
        terminalSession = session
        return session
    }

    fun attachView(view: TerminalView) {
        terminalView = view
        view.setTerminalViewClient(viewClient)
        view.setTextSize(_fontSize.value)

        val session = terminalSession ?: createSession()
        view.attachSession(session)
    }

    fun detachView() {
        terminalView = null
    }

    override fun onCleared() {
        super.onCleared()
        terminalSession?.finishIfRunning()
        terminalSession = null
        terminalView = null
    }
}
