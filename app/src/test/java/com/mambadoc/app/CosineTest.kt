package com.mambadoc.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class CosineTest {

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-12) 0f else (dot / denom).toFloat()
    }

    @Test
    fun identical_vectors_score_one() {
        val v = FloatArray(768) { 0.01f * it }
        assertEquals(1f, cosine(v, v), 1e-5f)
    }

    @Test
    fun orthogonal_score_near_zero() {
        val a = FloatArray(768) { if (it % 2 == 0) 1f else 0f }
        val b = FloatArray(768) { if (it % 2 == 1) 1f else 0f }
        assertEquals(0f, cosine(a, b), 1e-5f)
    }
}
