package com.kafisir.wetalks.screens

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

class ShapeTest {
    private val rnd = Random(1)
    private fun jitter(o: Offset, a: Float = 3f) = Offset(o.x + (rnd.nextFloat() - .5f) * a, o.y + (rnd.nextFloat() - .5f) * a)

    @Test fun roughCircleBecomesACircle() {
        val pts = (0..60).map { i -> val t = i / 60f * 2 * Math.PI.toFloat() * 1.04f; jitter(Offset(300 + 120 * cos(t), 300 + 118 * sin(t)), 8f) }
        val r = recognise(pts); assertNotNull(r); assertEquals(0, r!!.first)
        assertEquals(r.second.x - r.third.x, r.second.y - r.third.y, 1.0f)           // a real circle: width == height
    }
    @Test fun wobblyRectangleBecomesARectangle() {
        val c = listOf(Offset(100f, 100f), Offset(400f, 105f), Offset(398f, 300f), Offset(102f, 296f), Offset(101f, 108f))
        val pts = ArrayList<Offset>(); for (k in 0 until 4) for (i in 0 until 20) { val a = c[k]; val b = c[k + 1]; pts.add(jitter(Offset(a.x + (b.x - a.x) * i / 20, a.y + (b.y - a.y) * i / 20), 5f)) }
        val r = recognise(pts); assertNotNull(r); assertEquals(1, r!!.first)
    }
    @Test fun straightStrokeBecomesALine() {
        val pts = (0..40).map { jitter(Offset(50f + it * 8, 80f + it * 3), 4f) }
        val r = recognise(pts); assertNotNull(r); assertEquals(2, r!!.first)
    }
    @Test fun ovalBecomesAnOval() {
        val pts = (0..60).map { i -> val t = i / 60f * 2 * Math.PI.toFloat(); jitter(Offset(300 + 200 * cos(t), 300 + 80 * sin(t)), 4f) }
        val r = recognise(pts); assertNotNull(r); assertEquals(0, r!!.first)
        assertTrue(Math.abs((r.third.x - r.second.x) - (r.third.y - r.second.y)) > 100)
    }
    @Test fun scribbleStaysAFreehandStroke() {
        val pts = (0..80).map { i -> Offset(100f + (rnd.nextFloat()) * 250, 100f + rnd.nextFloat() * 250) }
        assertNull(recognise(pts))
    }
    @Test fun tinyMarksAreIgnored() { assertNull(recognise((0..20).map { Offset(10f + it, 10f) })) }
}
