package com.example.pulsecam

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PulseCamScreen(vm: HeartRateViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) vm.start()
    }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE uses a TextureView, which lets us clip the preview to a circle.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val camera = remember { PpgCamera(context) { t, r, g -> vm.onFrame(t, r, g) } }

    // Camera (and torch) run only while a measurement is in progress.
    DisposableEffect(state.measuring) {
        if (state.measuring) {
            camera.start(lifecycleOwner, previewView) { vm.onCameraError(it) }
        }
        onDispose { camera.stop() }
    }
    DisposableEffect(Unit) { onDispose { camera.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PulseCam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape),
        )

        val shownBpm = state.finalBpm ?: state.liveBpm
        Text(
            text = shownBpm?.toString() ?: "--",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.finalBpm != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Text("BPM", style = MaterialTheme.typography.titleMedium)

        Waveform(
            samples = state.waveform,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
        )

        if (state.measuring) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }

        Text(state.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)

        Button(
            onClick = {
                when {
                    state.measuring -> vm.stop()
                    hasPermission -> vm.start()
                    else -> permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        ) {
            Text(if (state.measuring) "Stop" else "Start measurement")
        }

        Text(
            "Not a medical device. For fitness and curiosity only; don't use it to diagnose or monitor a health condition.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Waveform(samples: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = size.width * i / (samples.size - 1)
            val y = size.height / 2f - (s / 2.5f) * (size.height / 2f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        drawLine(
            color.copy(alpha = 0.2f),
            Offset(0f, size.height / 2f),
            Offset(size.width, size.height / 2f),
        )
    }
}
