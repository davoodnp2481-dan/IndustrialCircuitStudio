package com.indsim.canvas

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** اندازه‌ی صفحه‌ی طراحی در واحد جهان (مبدأ در گوشه‌ی بالا-چپ). */
data class WorldBounds(val width: Double, val height: Double)

data class WorldRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * دوربین بوم: screen = world * scale + offset.
 * همه‌ی منطق زوم/پن اینجاست تا بدون اندروید تست شود؛ UI فقط آن را صدا می‌زند.
 */
data class Camera(
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
) {
    fun toScreenX(wx: Double) = wx * scale + offsetX
    fun toScreenY(wy: Double) = wy * scale + offsetY
    fun toWorldX(sx: Double) = (sx - offsetX) / scale
    fun toWorldY(sy: Double) = (sy - offsetY) / scale

    fun panBy(dx: Double, dy: Double) = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** زوم حول نقطه‌ی (fx, fy) روی صفحه؛ نقطه‌ی جهانیِ زیر انگشت ثابت می‌ماند. */
    fun zoomAt(
        factor: Double,
        fx: Double,
        fy: Double,
        minScale: Double = MIN_SCALE,
        maxScale: Double = MAX_SCALE,
    ): Camera {
        require(factor > 0.0) { "factor must be > 0" }
        val ns = (scale * factor).coerceIn(minScale, maxScale)
        val r = ns / scale
        return Camera(ns, fx - (fx - offsetX) * r, fy - (fy - offsetY) * r)
    }

    /** مانع از گم شدن صفحه: اگر محتوا بزرگ‌تر از نما باشد از لبه‌ها بیرون نمی‌رود، وگرنه وسط می‌ماند. */
    fun clamped(world: WorldBounds, viewW: Double, viewH: Double): Camera {
        fun axis(off: Double, content: Double, view: Double) =
            if (content <= view) (view - content) / 2.0 else off.coerceIn(view - content, 0.0)
        return copy(
            offsetX = axis(offsetX, world.width * scale, viewW),
            offsetY = axis(offsetY, world.height * scale, viewH),
        )
    }

    fun visibleWorld(viewW: Double, viewH: Double) =
        WorldRect(toWorldX(0.0), toWorldY(0.0), toWorldX(viewW), toWorldY(viewH))

    companion object {
        const val MIN_SCALE = 0.1
        const val MAX_SCALE = 8.0
    }
}

object Grid {
    const val BASE_STEP = 20.0

    /** گام شبکه؛ هنگام کوچک‌نمایی ×۵ می‌شود تا خطوط از minPx پیکسل نزدیک‌تر نشوند. */
    fun adaptiveStep(scale: Double, base: Double = BASE_STEP, minPx: Double = 14.0): Double {
        var s = base
        while (s * scale < minPx) s *= 5.0
        return s
    }

    /** نزدیک‌ترین نقطه‌ی شبکه (برای چسبیدن المان‌ها و سیم‌ها). */
    fun snap(v: Double, step: Double = BASE_STEP): Double = round(v / step) * step

    /** اندیس خطوط شبکه‌ی داخل بازه [from, to] (خط k در موقعیت k*step). */
    fun lines(from: Double, to: Double, step: Double): LongRange =
        ceil(from / step).toLong()..floor(to / step).toLong()
}
