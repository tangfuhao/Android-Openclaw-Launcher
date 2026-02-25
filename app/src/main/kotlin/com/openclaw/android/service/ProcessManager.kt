package com.openclaw.android.service

import android.content.Context
import android.util.Log
import com.openclaw.android.bootstrap.EnvironmentSetup
import com.openclaw.android.core.OpenClawConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the Node.js / OpenClaw Gateway process lifecycle.
 *
 * Responsibilities:
 * - Start / stop the gateway process via ProcessBuilder
 * - Stream stdout/stderr to a log buffer
 * - Detect crashes and auto-restart with exponential backoff
 * - Expose process state as observable Flow
 */
class ProcessManager(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
    private val environmentSetup: EnvironmentSetup,
) {
    companion object {
        private const val TAG = "ProcessManager"
        private const val LOG_BUFFER_MAX_LINES = 500
    }

    private var gatewayProcess: Process? = null
    private val restartCount = AtomicInteger(0)

    private val _processState = MutableStateFlow<ProcessState>(ProcessState.Stopped)
    val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    val isRunning: Boolean
        get() = gatewayProcess?.isAlive == true

    /**
     * Starts the OpenClaw gateway process.
     * Returns true if the process was launched successfully.
     */
    suspend fun startGateway(): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "Gateway already running, skipping start")
            return@withContext true
        }

        val verification = environmentSetup.verifyInstallation()
        if (verification is EnvironmentSetup.VerificationResult.MissingBinaries) {
            val msg = "Cannot start: missing binaries: ${verification.names}"
            Log.e(TAG, msg)
            _processState.value = ProcessState.Error(msg)
            return@withContext false
        }

        try {
            _processState.value = ProcessState.Starting

            val env = environmentSetup.buildEnvironment()
            val command = listOf(
                paths.nodeBinary.absolutePath,
                paths.openclawEntry.absolutePath,
                "gateway",
                "--port", OpenClawConstants.GATEWAY_PORT.toString(),
                "--bind", OpenClawConstants.GATEWAY_HOST,
            )

            Log.i(TAG, "Starting gateway: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command)
                .directory(paths.home)
                .redirectErrorStream(true)

            processBuilder.environment().apply {
                clear()
                putAll(env)
            }

            val process = processBuilder.start()
            gatewayProcess = process

            // Start log reader in background
            readProcessOutput(process)

            // Give the process a moment to start and check if it's still alive
            delay(1000)
            if (!process.isAlive) {
                val exitCode = process.exitValue()
                val msg = "Gateway process exited immediately with code $exitCode"
                Log.e(TAG, msg)
                _processState.value = ProcessState.Error(msg)
                return@withContext false
            }

            restartCount.set(0)
            _processState.value = ProcessState.Running
            Log.i(TAG, "Gateway process started (pid: ${process.pid()})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start gateway", e)
            _processState.value = ProcessState.Error(e.message ?: "Failed to start process")
            false
        }
    }

    /**
     * Stops the gateway process gracefully, falling back to force-kill.
     */
    fun stopGateway() {
        val process = gatewayProcess ?: return
        Log.i(TAG, "Stopping gateway process")

        try {
            // Send SIGTERM for graceful shutdown
            process.destroy()

            // Wait up to 5 seconds for graceful shutdown
            val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                Log.w(TAG, "Gateway did not exit gracefully, force killing")
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping gateway", e)
            process.destroyForcibly()
        } finally {
            gatewayProcess = null
            _processState.value = ProcessState.Stopped
        }
    }

    /**
     * Attempts to restart the gateway with exponential backoff.
     * Returns false if max retries exceeded.
     */
    suspend fun restartWithBackoff(): Boolean {
        val attempt = restartCount.incrementAndGet()
        if (attempt > OpenClawConstants.PROCESS_RESTART_MAX_RETRIES) {
            Log.e(TAG, "Max restart attempts ($attempt) exceeded")
            _processState.value = ProcessState.Error(
                "Gateway crashed repeatedly ($attempt times). Manual restart required."
            )
            return false
        }

        val delayMs = OpenClawConstants.PROCESS_RESTART_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
        Log.i(TAG, "Restarting gateway (attempt $attempt, delay ${delayMs}ms)")
        _processState.value = ProcessState.Restarting(attempt)

        stopGateway()
        delay(delayMs)
        return startGateway()
    }

    /** Resets the restart counter (call after a period of healthy operation). */
    fun resetRestartCount() {
        restartCount.set(0)
    }

    /**
     * Launches a bash shell session for the terminal tab.
     * Returns the Process with connected stdin/stdout.
     */
    fun createShellSession(): Process {
        val env = environmentSetup.buildEnvironment()
        val processBuilder = ProcessBuilder(paths.shellBinary.absolutePath, "--login")
            .directory(paths.home)
            .redirectErrorStream(true)

        processBuilder.environment().apply {
            clear()
            putAll(env)
        }

        return processBuilder.start()
    }

    private fun readProcessOutput(process: Process) {
        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        appendLog(line)
                        Log.d(TAG, "[gateway] $line")
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                if (process.isAlive) {
                    Log.e(TAG, "Error reading process output", e)
                }
            } finally {
                if (!process.isAlive && _processState.value is ProcessState.Running) {
                    val exitCode = process.exitValue()
                    Log.w(TAG, "Gateway process exited with code $exitCode")
                    _processState.value = ProcessState.Crashed(exitCode)
                }
            }
        }, "gateway-log-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendLog(line: String) {
        _logLines.value = (_logLines.value + line).takeLast(LOG_BUFFER_MAX_LINES)
    }

    private fun Process.pid(): Long {
        return try {
            pid().toLong()
        } catch (_: Exception) {
            -1L
        }
    }

    sealed interface ProcessState {
        data object Stopped : ProcessState
        data object Starting : ProcessState
        data object Running : ProcessState
        data class Restarting(val attempt: Int) : ProcessState
        data class Crashed(val exitCode: Int) : ProcessState
        data class Error(val message: String) : ProcessState

        val isOperational: Boolean
            get() = this is Running

        val displayText: String
            get() = when (this) {
                Stopped -> "Stopped"
                Starting -> "Starting..."
                Running -> "Running"
                is Restarting -> "Restarting (attempt $attempt)..."
                is Crashed -> "Crashed (exit code $exitCode)"
                is Error -> "Error: $message"
            }
    }
}
