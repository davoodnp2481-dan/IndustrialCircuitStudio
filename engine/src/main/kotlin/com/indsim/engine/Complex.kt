package com.indsim.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** عدد مختلط برای فازورهای AC (مقدار موثر RMS). */
data class Complex(val re: Double, val im: Double) {
    operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
    operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
    operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun times(k: Double) = Complex(re * k, im * k)
    operator fun div(k: Double) = Complex(re / k, im / k)
    operator fun div(o: Complex): Complex {
        val d = o.re * o.re + o.im * o.im
        return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }

    val abs: Double get() = hypot(re, im)
    val angleDeg: Double get() = Math.toDegrees(atan2(im, re))

    companion object {
        val ZERO = Complex(0.0, 0.0)
        fun polar(magnitude: Double, degrees: Double): Complex {
            val r = Math.toRadians(degrees)
            return Complex(magnitude * cos(r), magnitude * sin(r))
        }
    }
}
