package com.openclaw.android.service

import android.content.Context
import android.util.Log
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.proot.ProotExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the OpenClaw Gateway process lifecycle inside the proot environment.
 *
 * All process execution is delegated to [ProotExecutor], which wraps commands
 * with proot to run them inside the Debian rootfs.
 */
class ProcessManager(
    private val context: Context,
    private val paths: OpenClawConstants.Paths,
    private val prootExecutor: ProotExecutor,
    private val configWriter: OpenClawConfigWriter,
) {
    companion object {
        private const val TAG = "ProcessManager"
        private const val LOG_BUFFER_MAX_LINES = 500
    }

    @Volatile
    private var gatewayProcess: Process? = null
    private val restartCount = AtomicInteger(0)
    private val lifecycleMutex = Mutex()

    private val _processState = MutableStateFlow<ProcessState>(ProcessState.Stopped)
    val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    val isRunning: Boolean
        get() = gatewayProcess?.isAlive == true

    /**
     * Starts the OpenClaw gateway process inside proot.
     * Returns true if the process was launched successfully.
     */
    suspend fun startGateway(): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "Gateway already running, skipping start")
            return@withContext true
        }

        if (!prootExecutor.isAvailable()) {
            val msg = "Cannot start: proot binary not found"
            Log.e(TAG, msg)
            _processState.value = ProcessState.Error(msg)
            return@withContext false
        }

        if (!paths.hostNodeBinary.exists()) {
            val msg = "Cannot start: node binary not found in rootfs"
            Log.e(TAG, msg)
            _processState.value = ProcessState.Error(msg)
            return@withContext false
        }

        try {
            _processState.value = ProcessState.Starting

            configWriter.writeConfig()
            Log.i(TAG, "openclaw.json written before gateway launch")

            val innerCommand = listOf(
                OpenClawConstants.INNER_NODE_BINARY,
                OpenClawConstants.INNER_OPENCLAW_ENTRY,
                "gateway",
                "--port", OpenClawConstants.GATEWAY_PORT.toString(),
                "--bind", OpenClawConstants.GATEWAY_HOST,
            )

            Log.i(TAG, "Starting gateway via proot: ${innerCommand.joinToString(" ")}")

            val process = prootExecutor.execute(innerCommand)
            gatewayProcess = process

            readProcessOutput(process)

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
            Log.i(TAG, "Gateway process started (pid: ${process.extractPid()})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start gateway", e)
            _processState.value = ProcessState.Error(e.message ?: "Failed to start process")
            false
        }
    }

    fun stopGateway() {
        val process = gatewayProcess ?: return
        Log.i(TAG, "Stopping gateway process")

        try {
            process.destroy()
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
    suspend fun restartWithBackoff(): Boolean = lifecycleMutex.withLock {
        val attempt = restartCount.incrementAndGet()
        if (attempt > OpenClawConstants.PROCESS_RESTART_MAX_RETRIES) {
            Log.e(TAG, "Max restart attempts ($attempt) exceeded")
            _processState.value = ProcessState.Error(
                "Gateway crashed repeatedly ($attempt times). Manual restart required."
            )
            return@withLock false
        }

        val delayMs = OpenClawConstants.PROCESS_RESTART_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
        Log.i(TAG, "Restarting gateway (attempt $attempt, delay ${delayMs}ms)")
        _processState.value = ProcessState.Restarting(attempt)

        stopGateway()
        delay(delayMs)
        startGateway()
    }

    fun resetRestartCount() {
        restartCount.set(0)
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

    private fun Process.extractPid(): Long {
        return try {
            val field = this.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(this).toLong()
        } catch (_: Throwable) {
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
