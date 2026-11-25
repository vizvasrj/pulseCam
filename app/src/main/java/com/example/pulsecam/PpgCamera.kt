package com.example.pulsecam

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the rear camera with the torch on and reports, for every frame, the average
 * red and green brightness of the centre of the image (where the fingertip is).
 *
 * @param onFrame (sensor timestamp in ns, mean red 0..255, mean green 0..255)
 */
class PpgCamera(
    private val context: Context,
    private val onFrame: (Long, Double, Double) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var active = false

    fun start(owner: LifecycleOwner, previewView: PreviewView, onError: (String) -> Unit) {
        active = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(320, 240),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyze) }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
                if (!camera.cameraInfo.hasFlashUnit()) {
                    cameraProvider.unbindAll()
                    onError("This device has no flash, so it can't measure a pulse this way.")
                    return@addListener
                }
                camera.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                onError("Couldn't start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbinding the camera also turns the torch off. */
    fun stop() {
        active = false
        provider?.unbindAll()
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]          // RGBA_8888: one interleaved plane
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height

            var sumR = 0L
            var sumG = 0L
            var count = 0
            // Centre half of the frame, every 2nd pixel: plenty of signal, very cheap.
            for (y in h / 4 until 3 * h / 4 step 2) {
                var idx = y * rowStride + (w / 4) * pixelStride
                for (x in w / 4 until 3 * w / 4 step 2) {
                    sumR += buf.get(idx).toInt() and 0xFF
                    sumG += buf.get(idx + 1).toInt() and 0xFF
                    idx += pixelStride * 2
                    count++
                }
            }
            if (count > 0) {
                onFrame(image.imageInfo.timestamp, sumR.toDouble() / count, sumG.toDouble() / count)
            }
        } finally {
            image.close()
        }
    }
}
