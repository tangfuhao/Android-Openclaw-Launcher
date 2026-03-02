package com.openclaw.android.ui.chat

import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun VoiceRecordButton(
    onRecordingComplete: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var hasPermission by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            startRecording(context) { rec, file ->
                recorder = rec
                outputFile = file
                isRecording = true
                elapsedSeconds = 0
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                recorder?.stop()
                recorder?.release()
            } catch (_: Exception) {}
        }
    }

    if (isRecording) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val transition = rememberInfiniteTransition(label = "recPulse")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulseScale",
            )

            Text(
                text = formatDuration(elapsedSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                recorder?.stop()
                                recorder?.release()
                            } catch (_: Exception) {}
                        }
                        isRecording = false
                        recorder = null
                        outputFile?.let { file ->
                            if (file.exists() && file.length() > 0) {
                                onRecordingComplete(file)
                            }
                        }
                    }
                },
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop recording",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }
    } else {
        IconButton(
            onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Record voice",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun startRecording(
    context: android.content.Context,
    onStarted: (MediaRecorder, File) -> Unit,
) {
    try {
        val dir = File(context.cacheDir, "voice_messages").also { it.mkdirs() }
        val file = File(dir, "voice_${UUID.randomUUID().toString().take(8)}.m4a")

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioSamplingRate(44100)
        rec.setAudioEncodingBitRate(128000)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()

        onStarted(rec, file)
    } catch (e: Exception) {
        android.util.Log.e("VoiceRecorder", "Failed to start recording", e)
    }
}
