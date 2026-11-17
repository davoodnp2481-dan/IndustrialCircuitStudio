package com.indsim.engine.components

import com.indsim.engine.Branch
import com.indsim.engine.CoilLike
import com.indsim.engine.Component
import com.indsim.engine.Domain
import com.indsim.engine.Impedance
import com.indsim.engine.SolutionView
import com.indsim.engine.SourceBranch
import com.indsim.engine.Complex
import kotlin.math.abs
import kotlin.math.min

/** شستی دوبل استارت/استپ: NC روی 1-2 و NO روی 3-4؛ با فشردن NC باز و NO بسته می‌شود. */
class DualPushButton(id: String) : Component(id) {
    override val pinNames = listOf("11", "12", "13", "14")
    var pressed = false
        private set
    fun press() { pressed = true }
    fun release() { pressed = false }

    private val nc = Impedance(pin("11"), pin("12"), CONTACT_R)
    private val no = Impedance(pin("13"), pin("14"), CONTACT_R)
    override fun branches(): List<Branch> = if (pressed) listOf(no) else listOf(nc)
}

/** کلید ماندگار (لیمیت سوئیچ، میکروسوئیچ، فلوتر): با toggle() وضعیت عوض می‌شود. */
class ToggleSwitch(id: String, val normallyOpen: Boolean) : Component(id) {
    override val pinNames = if (normallyOpen) listOf("3", "4") else listOf("1", "2")
    var active = false
        private set
    fun toggle() { active = !active }
    fun set(on: Boolean) { active = on }

    val closed: Boolean get() = if (normallyOpen) active else !active
    private val imp = Impedance(pin(pinNames[0]), pin(pinNames[1]), CONTACT_R)
    override fun branches(): List<Branch> = if (closed) listOf(imp) else emptyList()
}

/**
 * کلید سلکتوری. ۲ حالته: وضعیت ۱ تیغه‌ی 13-14 را می‌بندد.
 * ۳ حالته (۰=چپ، ۱=وسط، ۲=راست): چپ 13-14 و راست 23-24 را می‌بندد.
 */
class Selector(id: String, val positions: Int) : Component(id) {
    init { require(positions == 2 || positions == 3) }
    override val pinNames = if (positions == 2) listOf("13", "14") else listOf("13", "14", "23", "24")

    /** ۳ حالته از وسط (مرده) شروع می‌شود؛ ۲ حالته از صفر. */
    var position = if (positions == 3) 1 else 0
        private set
    fun setPosition(p: Int) { position = p.coerceIn(0, positions - 1) }
    fun next() { position = (position + 1) % positions }

    private val c1 = Impedance(pin("13"), pin("14"), CONTACT_R)
    private val c2 = if (positions == 3) Impedance(pin("23"), pin("24"), CONTACT_R) else null

    val closed1: Boolean get() = if (positions == 2) position == 1 else position == 0
    val closed2: Boolean get() = positions == 3 && position == 2

    override fun branches(): List<Branch> {
        val l = ArrayList<Branch>(2)
        if (closed1) l += c1
        if (closed2 && c2 != null) l += c2
        return l
    }
}

enum class TimerMode { TON, TOF, STAR_DELTA }

/**
 * رله زمانی.
 *  - TON: تأخیر در وصل. پس از [delay] ثانیه برق‌داری، تیغه‌ی 15-18 بسته و 15-16 باز می‌شود؛ با قطع برق فوراً برمی‌گردد.
 *  - TOF: تأخیر در قطع. با برق‌دار شدن فوراً عمل می‌کند؛ پس از قطع برق، بعد از [delay] ثانیه برمی‌گردد.
 *  - STAR_DELTA: در شروع تیغه‌ی ستاره 17-18 بسته؛ پس از [delay] باز می‌شود و پس از ۰٫۱ ثانیه (زمان مرده) تیغه‌ی مثلث 27-28 بسته می‌شود.
 */
class TimerRelay(
    id: String,
    val mode: TimerMode,
    var delay: Double,
    val ratedVolts: Double = 230.0,
    val domain: Domain = Domain.AC,
    holdPowerW: Double = 3.0,
) : Component(id), CoilLike {
    override val pinNames =
        if (mode == TimerMode.STAR_DELTA) listOf("A1", "A2", "17", "18", "27", "28")
        else listOf("A1", "A2", "15", "16", "18")

    private val coil = Impedance(pin("A1"), pin("A2"), ratedVolts * ratedVolts / holdPowerW)

    var supplied = false
        private set
    override val energized: Boolean get() = supplied

    var elapsed = 0.0
        private set
    var outputOn = false
        private set
    val starOn: Boolean get() = mode == TimerMode.STAR_DELTA && supplied && elapsed < delay
    val deltaOn: Boolean get() = mode == TimerMode.STAR_DELTA && supplied && elapsed >= delay + DEAD_TIME

    override fun branches(): List<Branch> {
        val l = ArrayList<Branch>(2)
        l += coil
        if (mode == TimerMode.STAR_DELTA) {
            if (starOn) l += Impedance(pin("17"), pin("18"), CONTACT_R)
            if (deltaOn) l += Impedance(pin("27"), pin("28"), CONTACT_R)
        } else {
            if (outputOn) l += Impedance(pin("15"), pin("18"), CONTACT_R)
            else l += Impedance(pin("15"), pin("16"), CONTACT_R)
        }
        return l
    }

    override fun step(dt: Double, view: SolutionView) {
        val v = view.voltage(coil.a, coil.b, domain)
        supplied = if (supplied) v >= 0.4 * ratedVolts else v >= 0.85 * ratedVolts
        when (mode) {
            TimerMode.TON -> {
                if (supplied) { elapsed += dt; outputOn = elapsed >= delay }
                else { elapsed = 0.0; outputOn = false }
            }
            TimerMode.TOF -> {
                if (supplied) { elapsed = 0.0; outputOn = true }
                else if (outputOn) { elapsed += dt; if (elapsed >= delay) { outputOn = false; elapsed = 0.0 } }
                else elapsed = 0.0
            }
            TimerMode.STAR_DELTA -> {
                if (supplied) elapsed += dt else elapsed = 0.0
            }
        }
    }

    companion object { const val DEAD_TIME = 0.1 }
}

/** موتور تک‌فاز ساده (U1-U2). */
class Motor1Ph(
    id: String,
    val ratedVolts: Double = 230.0,
    val ratedCurrent: Double = 5.0,
    val startCurrentRatio: Double = 5.0,
) : Component(id) {
    override val pinNames = listOf("U1", "U2")
    var mechanicalLoad = 1.0
    var omega = 0.0
        private set
    val running: Boolean get() = abs(omega) > 0.05

    private val r0 = ratedVolts / ratedCurrent
    private var multiplier = startCurrentRatio
    private var imp = Impedance(pin("U1"), pin("U2"), r0 / multiplier)

    override fun branches(): List<Branch> = listOf(imp)

    override fun step(dt: Double, view: SolutionView) {
        val v = view.rmsVoltage(imp.a, imp.b)
        val target = if (v >= 0.85 * ratedVolts) 1.0 else 0.0
        val tau = if (target > 0.0) 0.8 else 3.0
        omega += (target - omega) * min(1.0, dt / tau)
        val m = if (omega < 0.8) startCurrentRatio else maxOf(0.2, mechanicalLoad)
        if (m != multiplier) { multiplier = m; imp = Impedance(pin("U1"), pin("U2"), r0 / m) }
    }
}

/** نقطه‌ی انشعاب: یک پین مجازی که چند سیم را به هم وصل می‌کند و خودش شاخه‌ی الکتریکی ندارد. */
class Junction(id: String) : Component(id) {
    override val pinNames = listOf("J")
    override fun branches(): List<Branch> = emptyList()
}

/** اتصال زمین مرجع (PE) به‌صورت یک نماد مستقل. */
class GroundTerminal(id: String) : Component(id) {
    override val pinNames = listOf("PE")
    override fun branches(): List<Branch> = listOf(SourceBranch(pin("PE"), Domain.AC, Complex.ZERO, 0.01, id))
}
