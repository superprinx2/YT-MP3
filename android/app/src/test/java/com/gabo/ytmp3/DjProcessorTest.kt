package com.gabo.ytmp3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Pruebas de la consola del DJ (sin celular): ecualizador, loop a tempo y eco. */
class DjProcessorTest {
    private val rate = 44100

    private fun proc(): DjProcessor = DjProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT)); flush()
    }

    /** Pasa [samples] por la consola en bloques de 1024 y devuelve la salida. */
    private fun run(p: DjProcessor, samples: FloatArray, onBlock: (Int) -> Unit = {}): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < samples.size) {
            onBlock(i)
            val n = minOf(1024, samples.size - i)
            val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) b.putShort((samples[i + k].coerceIn(-1f, 1f) * 32767).toInt().toShort())
            b.flip()
            p.queueInput(b)
            val o = p.output.order(ByteOrder.LITTLE_ENDIAN)
            while (o.remaining() >= 2) out += o.short / 32768f
            i += n
        }
        return out.toFloatArray()
    }

    private fun sine(f: Double, sec: Double, amp: Float = 0.4f) = FloatArray((rate * sec).toInt()) { (amp * sin(2 * PI * f * it / rate)).toFloat() }
    private fun rms(x: FloatArray, from: Int = x.size / 2) = sqrt(x.drop(from).map { it * it }.average()).toFloat()

    @Test fun idleIsTransparent() {
        val x = sine(440.0, 0.5)
        val y = run(proc(), x)
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals(x[i], y[i], 1f / 16000)
    }

    @Test fun lowCutRemovesBassKeepsVoice() {
        val p = proc().apply { low = 0f }
        val bass = rms(run(p, sine(60.0, 1.0)))
        val p2 = proc().apply { low = 0f }
        val voice = rms(run(p2, sine(1000.0, 1.0)))
        println("graves tras cortar: $bass · voz: $voice (original ${rms(sine(60.0, 1.0))})")
        assertTrue("los graves deben bajar mucho", bass < 0.08f)
        assertTrue("la voz debe quedar", voice > 0.24f)
    }

    @Test fun midCutLowersVoice() {
        val p = proc().apply { mid = 0.3f }
        val voice = rms(run(p, sine(1000.0, 1.0)))
        val p2 = proc().apply { mid = 0.3f }
        val bass = rms(run(p2, sine(60.0, 1.0)))
        println("voz con medios al 30%: $voice · graves: $bass")
        assertTrue(voice < 0.15f)
        assertTrue(bass > 0.22f)
    }

    @Test fun loopRepeatsExactLength() {
        // señal que nunca se repite (rampa); al activar el loop de 500 ms, la salida se repite cada 500 ms
        val n = rate * 3
        val x = FloatArray(n) { ((it % 30000) / 30000f) * 0.8f - 0.4f }
        val p = proc()
        val loopLen = rate / 2
        val y = run(p, x) { i -> if (i >= rate) p.loopMs = 500f }
        val a = rate + 4096; val b = a + loopLen
        var maxDiff = 0f
        for (k in 0 until 2000) maxDiff = maxOf(maxDiff, abs(y[a + 300 + k] - y[b + 300 + k]))
        println("diferencia entre repeticiones del loop: $maxDiff")
        assertTrue(maxDiff < 0.002f)
    }

    @Test fun echoRepeatsAfterDelay() {
        val p = proc().apply { delayMs = 250f; feedback = 0.5f; send = 1f; dry = 1f }
        val x = FloatArray(rate) { if (it in 1000 until 1100) 0.8f * sin(2 * PI * 2000 * it / rate).toFloat() else 0f }
        val y = run(p, x)
        val d = rate / 4
        val first = (1000 until 1100).maxOf { abs(y[it + d]) }
        val quiet = (20000 until 20500).maxOf { abs(y[it]) }
        println("eco a 250 ms: $first · silencio: $quiet")
        assertTrue(first > 0.2f)
        assertTrue(quiet < 0.01f)
    }
}
