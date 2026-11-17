package com.indsim.engine.components

import com.indsim.engine.Branch
import com.indsim.engine.Component
import com.indsim.engine.EventType
import com.indsim.engine.Impedance
import com.indsim.engine.SolutionView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** چراغ سیگنال (X1-X2). روشن از ≈۷۰٪ ولتاژ نامی؛ اضافه‌ولتاژ هشدار می‌دهد. */
class Lamp(
    id: String,
    val ratedVolts: Double = 230.0,
    val colorName: String = "red",
    powerW: Double = 2.0,
) : Component(id) {
    override val pinNames = listOf("X1", "X2")
    private val imp = Impedance(pin("X1"), pin("X2"), ratedVolts * ratedVolts / powerW)

    var brightness = 0.0
        private set
    val lit: Boolean get() = brightness >= 0.5

    private var overvoltage = false
    override val warning: String? get() = if (overvoltage) "overvoltage" else null

    override fun branches(): List<Branch> = listOf(imp)

    override fun step(dt: Double, view: SolutionView) {
        val v = view.rmsVoltage(imp.a, imp.b)
        brightness = if (v < 0.5 * ratedVolts) 0.0 else min(1.0, (v / ratedVolts) * (v / ratedVolts))
        val over = v > 1.3 * ratedVolts
        if (over && !overvoltage) view.emit(EventType.OVERVOLTAGE, id, "lamp $id: ${"%.0f".format(v)} V on ${ratedVolts} V lamp")
        overvoltage = over
    }
}

/** بار مقاومتی ساده (هیتر و غیره). */
class ResistiveLoad(id: String, val ohms: Double) : Component(id) {
    override val pinNames = listOf("1", "2")
    private val imp = Impedance(pin("1"), pin("2"), ohms)
    var powerW = 0.0
        private set

    override fun branches(): List<Branch> = listOf(imp)

    override fun step(dt: Double, view: SolutionView) {
        val v = view.rmsVoltage(imp.a, imp.b)
        powerW = v * v / ohms
    }
}

/**
 * موتور القایی سه‌فاز: سه سیم‌پیچ U1-U2، V1-V2، W1-W2 (ترمینال‌ها طبق IEC: U1 V1 W1 W2 U2 V2).
 * اتصال ستاره/مثلث از سیم‌کشی بیرونی تعیین می‌شود، نه از پارامتر.
 * windingVolts = ولتاژ نامی هر سیم‌پیچ (۴۰۰ ولت یعنی موتور ۴۰۰/۶۹۰ که در شبکه‌ی ۴۰۰ مثلث بسته می‌شود).
 *
 * مدل ساده‌شده‌ی الکترومکانیکی:
 *  - سرعت omega نرمال‌شده در [-1,1] (علامت = جهت). توالی فاز U1→V1 منفی ۱۲۰° = جلو.
 *  - ولتاژ سیم‌پیچ ≥۸۵٪ نامی → سرعت کامل؛ حدود ۵۷٪ (ستاره) → سرعت کاهش‌یافته؛ نامتقارن (قطع فاز) → نمی‌چرخد.
 *  - جریان: راه‌اندازی [startCurrentRatio]×نامی؛ پس از رسیدن به سرعت، mechanicalLoad×نامی.
 */
class Motor3Ph(
    id: String,
    val windingVolts: Double = 400.0,
    val ratedLineCurrent: Double = 10.0,
    val startCurrentRatio: Double = 6.0,
) : Component(id) {
    override val pinNames = listOf("U1", "V1", "W1", "W2", "U2", "V2")

    /** ۱٫۰ = بار نامی؛ بزرگ‌تر = اضافه‌بار مکانیکی. */
    var mechanicalLoad = 1.0

    var omega = 0.0
        private set
    var phaseLoss = false
        private set
    val windingVoltages = DoubleArray(3)
    val running: Boolean get() = abs(omega) > 0.05

    private var direction = 1
    private var overvoltage = false
    override val warning: String? get() = when {
        overvoltage -> "overvoltage"
        phaseLoss -> "phase loss"
        else -> null
    }

    private val rw0 = windingVolts * sqrt(3.0) / ratedLineCurrent
    private val ends = listOf("U1" to "U2", "V1" to "V2", "W1" to "W2")
    private var multiplier = startCurrentRatio
    private var imps = build()

    private fun build() = ends.map { (a, b) -> Impedance(pin(a), pin(b), rw0 / multiplier) }

    override fun branches(): List<Branch> = imps

    override fun step(dt: Double, view: SolutionView) {
        for (k in 0..2) windingVoltages[k] = view.rmsVoltage(imps[k].a, imps[k].b)
        val vMax = windingVoltages.max()
        val vMin = windingVoltages.min()
        val avg = windingVoltages.average()

        val balanced = vMax > 0.0 && (vMax - vMin) / vMax < 0.2
        val powered = avg > 0.2 * windingVolts
        val loss = powered && !balanced
        if (loss && !phaseLoss) view.emit(EventType.PHASE_LOSS, id, "motor $id: phase loss / unbalanced supply")
        phaseLoss = loss

        val over = avg > 1.15 * windingVolts
        if (over && !overvoltage) view.emit(EventType.OVERVOLTAGE, id, "motor $id: ${"%.0f".format(avg)} V per winding")
        overvoltage = over

        val level = when {
            loss -> 0.0
            avg >= 0.85 * windingVolts -> 1.0
            avg >= 0.45 * windingVolts -> 0.85
            else -> 0.0
        }

        // جهت از توالی فاز ولتاژ ترمینال‌های U1 و V1
        val ua = view.ac(imps[0].a)
        val va = view.ac(imps[1].a)
        val minLine = 0.5 * windingVolts / sqrt(3.0)
        if (ua.abs > minLine && va.abs > minLine) {
            direction = if ((va / ua).angleDeg < 0.0) 1 else -1
        }

        val target = if (level > 0.0) direction * level else 0.0
        val tau = if (level > 0.0) 1.0 else 4.0
        omega += (target - omega) * min(1.0, dt / tau)

        val newMult = if (abs(omega) < 0.8) startCurrentRatio else max(0.2, mechanicalLoad)
        if (newMult != multiplier) {
            multiplier = newMult
            imps = build()
        }
    }
}
