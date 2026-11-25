package com.example.pulsecam.ppg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a stream of (timestamp, red-channel brightness) samples into a heart rate.
 *
 * Pipeline: resample to a fixed rate -> invert -> remove slow drift -> smooth
 * -> normalise -> peak detection -> median beat interval.
 *
 * Pure Kotlin (no Android classes) so it can be unit tested on the JVM.
 */
class PpgProcessor(
    private val windowSeconds: Double = 12.0,
    private val fs: Double = 30.0,
) {
    data class Result(
        /** Last few seconds of the filtered, normalised signal (for drawing). */
        val waveform: List<Float>,
        /** Heart rate in beats per minute, or null if the signal is not trustworthy. */
        val bpm: Double?,
        /** 0..1, share of beat intervals that agree with the median interval. */
        val quality: Double,
    )

    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()

    fun reset() {
        times.clear()
        values.clear()
    }

    fun add(timeNanos: Long, value: Double) {
        times.addLast(timeNanos)
        values.addLast(value)
        val limit = (windowSeconds * 1e9).toLong()
        while (times.size > 1 && timeNanos - times.first() > limit) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    fun analyze(): Result? {
        if (times.size < 2) return null
        val t = times.toLongArray()
        val v = values.toDoubleArray()
        val durationSec = (t.last() - t.first()) / 1e9
        if (durationSec < MIN_SECONDS) return null

        // Blood volume up = more absorption = darker red channel, so invert.
        val x = resample(t, v).also { for (i in it.indices) it[i] = -it[i] }
        val n = x.size

        val trend = movingAverage(x, oddWindow((1.5 * fs).toInt()))
        val detrended = DoubleArray(n) { x[it] - trend[it] }
        val y = movingAverage(detrended, 5)

        // Ignore the edges, where the moving averages are less reliable.
        val edge = (0.5 * fs).toInt()
        if (n <= 2 * edge + 2) return null
        var sumSq = 0.0
        for (i in edge until n - edge) sumSq += y[i] * y[i]
        val std = sqrt(sumSq / (n - 2 * edge))
        if (std < MIN_AMPLITUDE) return null
        for (i in y.indices) y[i] = y[i] / std

        val peaks = findPeaks(y, edge, n - edge)
        val waveStart = max(0, n - (WAVEFORM_SECONDS * fs).toInt())
        val wave = List(n - waveStart) { y[waveStart + it].coerceIn(-3.0, 3.0).toFloat() }

        val intervals = ArrayList<Double>()
        for (i in 1 until peaks.size) {
            val s = (peaks[i] - peaks[i - 1]) / fs
            if (s in 60.0 / MAX_BPM..60.0 / MIN_BPM) intervals.add(s)
        }
        if (intervals.size < 4) return Result(wave, null, 0.0)

        val med = median(intervals)
        val quality = intervals.count { abs(it - med) <= 0.15 * med }.toDouble() / intervals.size
        val bpm = if (quality >= MIN_QUALITY) 60.0 / med else null
        return Result(wave, bpm, quality)
    }

    private fun findPeaks(y: DoubleArray, from: Int, to: Int): List<Int> {
        val minDist = (fs * 60.0 / MAX_BPM).toInt()
        val peaks = ArrayList<Int>()
        for (i in max(from, 1) until min(to, y.size - 1)) {
            if (y[i] > PEAK_THRESHOLD && y[i] > y[i - 1] && y[i] >= y[i + 1]) {
                if (peaks.isNotEmpty() && i - peaks.last() < minDist) {
                    if (y[i] > y[peaks.last()]) peaks[peaks.size - 1] = i
                } else {
                    peaks.add(i)
                }
            }
        }
        return peaks
    }

    /** Linear interpolation onto a uniform grid at [fs] Hz. */
    private fun resample(t: LongArray, v: DoubleArray): DoubleArray {
        val t0 = t[0]
        val n = ((t.last() - t0) / 1e9 * fs).toInt()
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val target = t0 + (i / fs * 1e9).toLong()
            while (j < t.size - 2 && t[j + 1] < target) j++
            val dt = (t[j + 1] - t[j]).toDouble()
            val a = if (dt > 0) ((target - t[j]) / dt).coerceIn(0.0, 1.0) else 0.0
            out[i] = v[j] + a * (v[j + 1] - v[j])
        }
        return out
    }

    /** Centered moving average; the window shrinks near the edges. */
    private fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        val half = window / 2
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        return DoubleArray(x.size) { i ->
            val lo = max(0, i - half)
            val hi = min(x.size - 1, i + half)
            (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
    }

    private fun oddWindow(w: Int) = if (w % 2 == 0) w + 1 else w

    companion object {
        const val MIN_SECONDS = 6.0
        const val WAVEFORM_SECONDS = 5.0
        const val MIN_BPM = 45.0
        const val MAX_BPM = 180.0
        const val MIN_AMPLITUDE = 0.01
        const val PEAK_THRESHOLD = 0.25
        const val MIN_QUALITY = 0.6

        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }
    }
}
