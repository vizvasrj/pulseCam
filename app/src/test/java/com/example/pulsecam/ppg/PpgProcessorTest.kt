package com.example.pulsecam.ppg

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PpgProcessorTest {

    private fun measure(bpm: Double, seconds: Double = 12.0, noise: Double = 0.1): Double? {
        val p = PpgProcessor()
        val rnd = Random(1)
        val fs = 30.0
        val f = bpm / 60.0
        var i = 0
        while (i / fs <= seconds) {
            val ts = i / fs
            val value = 200.0 -
                0.8 * sin(2 * PI * f * ts) -
                0.35 * sin(4 * PI * f * ts + 1.0) +
                0.6 * sin(2 * PI * 0.07 * ts) +          // slow drift
                rnd.nextDouble(-1.0, 1.0) * noise
            p.add((ts * 1e9).toLong(), value)
            i++
        }
        return p.analyze()?.bpm
    }

    @Test fun detects72() = assertEquals(72.0, measure(72.0)!!, 3.0)
    @Test fun detects95() = assertEquals(95.0, measure(95.0)!!, 3.0)
    @Test fun detects120() = assertEquals(120.0, measure(120.0)!!, 3.0)

    @Test fun flatSignalGivesNoReading() {
        val p = PpgProcessor()
        for (i in 0 until 360) p.add((i / 30.0 * 1e9).toLong(), 200.0)
        assertNull(p.analyze()?.bpm)
    }

    @Test fun tooShortGivesNoResult() {
        val p = PpgProcessor()
        for (i in 0 until 60) p.add((i / 30.0 * 1e9).toLong(), 200.0 + sin(i / 5.0))
        assertNull(p.analyze())
    }
}
