package com.example.pulsecam

import androidx.lifecycle.ViewModel
import com.example.pulsecam.ppg.PpgProcessor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val measuring: Boolean = false,
    val fingerDetected: Boolean = false,
    val progress: Float = 0f,
    val liveBpm: Int? = null,
    val finalBpm: Int? = null,
    val waveform: List<Float> = emptyList(),
    val message: String = MSG_IDLE,
)

const val MSG_IDLE = "Tap Start, then cover the rear camera and flash with your fingertip."
const val MSG_NO_FINGER = "Cover the rear camera AND the flash with your fingertip. Press gently."
const val MSG_CALIBRATING = "Hold still\u2026 getting a signal."
const val MSG_MEASURING = "Measuring\u2026 keep your finger still."

class HeartRateViewModel : ViewModel() {

    private val processor = PpgProcessor()
    private val estimates = ArrayList<Double>()
    private var contactStartNs: Long? = null
    private var lastAnalysisNs = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Synchronized
    fun start() {
        resetSession()
        _state.value = UiState(measuring = true, message = MSG_NO_FINGER)
    }

    @Synchronized
    fun stop() {
        if (!_state.value.measuring) return
        resetSession()
        _state.value = UiState()
    }

    @Synchronized
    fun onCameraError(message: String) {
        resetSession()
        _state.value = UiState(message = message)
    }

    /** Called on the camera analysis thread for every frame. */
    @Synchronized
    fun onFrame(timeNs: Long, red: Double, green: Double) {
        if (!_state.value.measuring) return

        // Fingertip over a lit lens: bright and strongly red.
        val fingerOn = red > MIN_RED && red > RED_GREEN_RATIO * green
        if (!fingerOn) {
            if (contactStartNs != null) resetSession()
            _state.update {
                it.copy(
                    fingerDetected = false, progress = 0f, liveBpm = null,
                    waveform = emptyList(), message = MSG_NO_FINGER,
                )
            }
            return
        }

        val start = contactStartNs ?: timeNs.also { contactStartNs = it }
        processor.add(timeNs, red)
        val elapsed = (timeNs - start) / 1e9
        val progress = (elapsed / MEASURE_SECONDS).toFloat().coerceIn(0f, 1f)

        if (timeNs - lastAnalysisNs >= ANALYSIS_INTERVAL_NS) {
            lastAnalysisNs = timeNs
            val result = processor.analyze()
            val bpm = result?.bpm
            if (bpm != null && elapsed >= WARMUP_SECONDS) {
                estimates.add(bpm)
                if (estimates.size > 8) estimates.removeAt(0)
            }
            val live = if (estimates.isEmpty()) null
            else PpgProcessor.median(estimates.takeLast(5)).roundToInt()
            _state.update {
                it.copy(
                    fingerDetected = true,
                    progress = progress,
                    liveBpm = live,
                    waveform = result?.waveform ?: it.waveform,
                    message = if (elapsed < WARMUP_SECONDS) MSG_CALIBRATING else MSG_MEASURING,
                )
            }
        }

        if (elapsed >= MEASURE_SECONDS) finish()
    }

    private fun finish() {
        val bpm = if (estimates.isEmpty()) null
        else PpgProcessor.median(estimates.takeLast(8)).roundToInt()
        resetSession()
        _state.value = UiState(
            finalBpm = bpm,
            message = if (bpm != null) {
                "Done. Rest for a minute between readings for the best accuracy."
            } else {
                "Couldn't get a steady reading. Sit still, press lightly, and try again."
            },
        )
    }

    private fun resetSession() {
        processor.reset()
        estimates.clear()
        contactStartNs = null
        lastAnalysisNs = 0L
    }

    private companion object {
        const val MEASURE_SECONDS = 20.0
        const val WARMUP_SECONDS = 6.0
        const val ANALYSIS_INTERVAL_NS = 250_000_000L
        const val MIN_RED = 90.0
        const val RED_GREEN_RATIO = 1.4
    }
}
