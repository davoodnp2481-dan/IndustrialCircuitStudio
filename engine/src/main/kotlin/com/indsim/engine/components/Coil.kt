package com.indsim.engine.components

import com.indsim.engine.Branch
import com.indsim.engine.Circuit
import com.indsim.engine.CoilLike
import com.indsim.engine.Component
import com.indsim.engine.Domain
import com.indsim.engine.EventType
import com.indsim.engine.Impedance
import com.indsim.engine.SolutionView

/** مقاومت تیغه‌ی بسته (اهم). */
const val CONTACT_R = 0.002

/**
 * بوبین کنتاکتور/رله (A1-A2). وصل در ۸۵٪ ولتاژ نامی، قطع زیر ۴۰٪، با تأخیر مکانیکی.
 * بوبین AC فقط به ولتاژ AC و بوبین DC فقط به ولتاژ DC پاسخ می‌دهد.
 */
class Coil(
    id: String,
    val ratedVolts: Double = 230.0,
    val domain: Domain = Domain.AC,
    holdPowerW: Double = 5.0,
    val operateTime: Double = 0.02,
    val releaseTime: Double = 0.01,
) : Component(id), CoilLike {
    override val pinNames = listOf("A1", "A2")
    private val imp = Impedance(pin("A1"), pin("A2"), ratedVolts * ratedVolts / holdPowerW)

    override var energized = false
        private set
    private var timer = 0.0
    private var overvoltage = false

    override val warning: String? get() = if (overvoltage) "overvoltage" else null

    override fun branches(): List<Branch> = listOf(imp)

    override fun step(dt: Double, view: SolutionView) {
        val v = view.voltage(imp.a, imp.b, domain)
        val want = if (energized) v >= 0.4 * ratedVolts else v >= 0.85 * ratedVolts
        if (want != energized) {
            timer += dt
            if (timer >= (if (want) operateTime else releaseTime) - 1e-9) {
                energized = want
                timer = 0.0
            }
        } else {
            timer = 0.0
        }
        // اضافه‌ولتاژ: هر دو حوزه چک می‌شود (مثلاً بوبین ۲۴V روی ۲۳۰V)
        val vAny = view.rmsVoltage(imp.a, imp.b)
        val over = vAny > 1.2 * ratedVolts
        if (over && !overvoltage) view.emit(EventType.OVERVOLTAGE, id, "coil $id: ${"%.0f".format(vAny)} V on ${ratedVolts} V coil")
        overvoltage = over
    }
}

/** تیغه‌ی کمکی NO/NC که تابع یک بوبین است (مثل CADe_SIMU، می‌تواند هرجای نقشه باشد). */
class LinkedContact(
    id: String,
    val coilId: String,
    val normallyOpen: Boolean,
    pinA: String = if (normallyOpen) "13" else "21",
    pinB: String = if (normallyOpen) "14" else "22",
) : Component(id) {
    override val pinNames = listOf(pinA, pinB)
    private lateinit var coil: CoilLike

    override fun attach(circuit: Circuit) {
        coil = circuit.component(coilId) as? CoilLike ?: error("$coilId is not a coil")
    }

    /** NO: بسته وقتی بوبین تحریک است. NC: بسته وقتی بوبین تحریک نیست. */
    val closed: Boolean get() = coil.energized == normallyOpen

    private val imp = Impedance(pin(pinNames[0]), pin(pinNames[1]), CONTACT_R)

    override fun branches(): List<Branch> = if (closed) listOf(imp) else emptyList()
}

/** تیغه‌های قدرت سه‌قطبی کنتاکتور: 1L1-2T1، 3L2-4T2، 5L3-6T3. */
class PowerPoles(id: String, val coilId: String) : Component(id) {
    override val pinNames = listOf("1L1", "2T1", "3L2", "4T2", "5L3", "6T3")
    private lateinit var coil: CoilLike

    override fun attach(circuit: Circuit) {
        coil = circuit.component(coilId) as? CoilLike ?: error("$coilId is not a coil")
    }

    val closed: Boolean get() = coil.energized

    private val imps = listOf(
        Impedance(pin("1L1"), pin("2T1"), CONTACT_R),
        Impedance(pin("3L2"), pin("4T2"), CONTACT_R),
        Impedance(pin("5L3"), pin("6T3"), CONTACT_R),
    )

    override fun branches(): List<Branch> = if (closed) imps else emptyList()
}
