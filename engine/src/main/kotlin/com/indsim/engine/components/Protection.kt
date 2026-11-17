package com.indsim.engine.components

import com.indsim.engine.Branch
import com.indsim.engine.Component
import com.indsim.engine.EventType
import com.indsim.engine.Impedance
import com.indsim.engine.SolutionView

/**
 * مدل حرارتی I²t: وقتی i > iRef گرما جمع می‌شود (نرخ ((i/iRef)²-1)/tau)، وگرنه نمایی خنک می‌شود.
 * heat >= 1 یعنی تریپ.
 */
class ThermalModel(val iRef: Double, val tau: Double, val coolTau: Double) {
    var heat = 0.0
        private set

    fun update(i: Double, dt: Double): Boolean {
        if (i > iRef) {
            val r = i / iRef
            heat += (r * r - 1.0) * dt / tau
        } else {
            heat -= heat * minOf(1.0, dt / coolTau)
        }
        if (heat < 0.0) heat = 0.0
        return heat >= 1.0
    }

    fun reset() { heat = 0.0 }
}

enum class ProtectionKind { FUSE, MCB }

/**
 * فیوز یا کلید مینیاتوری (۱ تا ۳ قطب؛ پین‌ها 1-2، 3-4، 5-6).
 * MCB: تریپ مغناطیسی آنی در ≥ ۱۰×In (منحنی C) + تریپ حرارتی؛ با setClosed کلید می‌شود و بعد از تریپ باید reset شود.
 * فیوز: ذوب حرارتی (سریع‌تر از MCB)؛ پس از سوختن فقط replace().
 */
class ProtectiveDevice(
    id: String,
    val kind: ProtectionKind,
    val poles: Int = 1,
    val ratedCurrent: Double,
) : Component(id) {
    init { require(poles in 1..4) }

    override val pinNames: List<String> = (1..poles).flatMap { listOf("${2 * it - 1}", "${2 * it}") }

    var closed = true
        private set
    var tripped = false
        private set

    val conducting: Boolean get() = closed && !tripped

    private val imps = (0 until poles).map {
        Impedance(pin(pinNames[2 * it]), pin(pinNames[2 * it + 1]), 0.005)
    }
    private val thermal =
        if (kind == ProtectionKind.MCB) ThermalModel(1.13 * ratedCurrent, 60.0, 120.0)
        else ThermalModel(1.25 * ratedCurrent, 40.0, 60.0)

    fun setClosed(on: Boolean) { closed = on }

    /** MCB: دوباره مسلح می‌کند (دسته را بالا می‌برد). فیوز: فیوز نو. */
    fun reset() { tripped = false; thermal.reset() }
    fun replace() = reset()

    override fun branches(): List<Branch> = if (conducting) imps else emptyList()

    override fun step(dt: Double, view: SolutionView) {
        if (!conducting) { thermal.update(0.0, dt); return }
        val i = imps.maxOf { view.rmsCurrent(it) }
        val magnetic = kind == ProtectionKind.MCB && i >= 10.0 * ratedCurrent
        if (magnetic || thermal.update(i, dt)) {
            tripped = true
            view.emit(
                EventType.PROTECTION_TRIP, id,
                "$id ${if (kind == ProtectionKind.FUSE) "blown" else "tripped"} at ${"%.0f".format(i)} A",
            )
        }
    }
}

/**
 * رله حرارتی: سه پل قدرت 1L1-2T1، 3L2-4T2، 5L3-6T3 + تیغه‌ی کمکی NC (95-96) و NO (97-98).
 * منحنی تقریبی کلاس ۱۰: ۷٫۲×Ir در حدود ۶ ثانیه، ۱٫۵×Ir در حدود ۴ دقیقه.
 * بعد از تریپ فقط با reset() دستی (و پس از خنک شدن) برمی‌گردد.
 */
class ThermalRelay(id: String, val ratedCurrent: Double) : Component(id) {
    override val pinNames = listOf("1L1", "2T1", "3L2", "4T2", "5L3", "6T3", "95", "96", "97", "98")

    var tripped = false
        private set

    private val thermal = ThermalModel(ratedCurrent, 300.0, 300.0)
    val heat: Double get() = thermal.heat

    private val poles = listOf(
        Impedance(pin("1L1"), pin("2T1"), 0.005),
        Impedance(pin("3L2"), pin("4T2"), 0.005),
        Impedance(pin("5L3"), pin("6T3"), 0.005),
    )
    private val nc = Impedance(pin("95"), pin("96"), CONTACT_R)
    private val no = Impedance(pin("97"), pin("98"), CONTACT_R)

    override fun branches(): List<Branch> = poles + (if (tripped) no else nc)

    /** دکمه‌ی تست تریپ روی رله. */
    fun testTrip(view: SolutionView? = null) {
        tripped = true
        view?.emit(EventType.THERMAL_TRIP, id, "$id manual test trip")
    }

    /** ریست دستی؛ تا وقتی رله گرم است ممکن نیست. */
    fun reset(): Boolean {
        if (!tripped) return true
        if (thermal.heat > 0.8) return false
        tripped = false
        return true
    }

    override fun step(dt: Double, view: SolutionView) {
        val i = poles.maxOf { view.rmsCurrent(it) }
        val over = thermal.update(i, dt)
        if (over && !tripped) {
            tripped = true
            view.emit(EventType.THERMAL_TRIP, id, "$id overload trip at ${"%.1f".format(i)} A")
        }
    }
}
