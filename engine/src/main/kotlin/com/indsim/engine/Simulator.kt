package com.indsim.engine

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * حلقه‌ی زمان. هر step(dt):
 *  1) قطعات با ولتاژ/جریان فعلی وضعیت خود را به‌روز می‌کنند (بوبین، حرارتی، موتور، حفاظت)
 *  2) مدار با وضعیت جدید دوباره حل می‌شود
 * پس solution همیشه با وضعیت فعلی قطعات سازگار است.
 */
class Simulator(val circuit: Circuit, private val shortCircuitAmps: Double = 200.0) {
    var time = 0.0
        private set

    val events = ArrayList<SimEvent>()

    private var shorts: Set<String> = emptySet()
    val activeShortCircuits: Set<String> get() = shorts

    val warnings: Map<String, String>
        get() = circuit.components.mapNotNull { c -> c.warning?.let { c.id to it } }.toMap()

    /** سیم‌کشی حین اجرا ثابت است؛ شبکه‌ها یک بار ساخته می‌شوند. */
    private val nets = circuit.buildNets()
    private var lastBranches: List<Branch>? = null
    private var cached: Solution? = null

    private var solution: Solution

    init {
        circuit.components.forEach { it.attach(circuit) }
        solution = solve()
    }

    private fun solve(): Solution {
        val br = circuit.components.flatMap { it.branches() }
        val hit = cached
        if (hit != null && br == lastBranches) return hit
        val ac = Mna.solve(nets, br, Domain.AC)
        val dc = Mna.solve(nets, br, Domain.DC)

        val now = HashSet<String>()
        for (b in br) if (b is SourceBranch) {
            val n = nets.netOf(b.pin)
            val emfAc = if (b.domain == Domain.AC) b.emf else Complex.ZERO
            val emfDc = if (b.domain == Domain.DC) b.emf else Complex.ZERO
            val iAc = ((emfAc - ac[n]) / b.rs).abs
            val iDc = ((emfDc - dc[n]) / b.rs).abs
            if (hypot(iAc, iDc) > shortCircuitAmps) now += b.ownerId
        }
        for (id in now - shorts) {
            events += SimEvent(time, EventType.SHORT_CIRCUIT, id, "short circuit on supply $id")
        }
        shorts = now
        val sol = Solution(nets, ac, dc, br)
        lastBranches = br
        cached = sol
        return sol
    }

    fun step(dt: Double = 0.01) {
        val view = SolutionView(solution, time) { events += it }
        circuit.components.forEach { it.step(dt, view) }
        time += dt
        solution = solve()
    }

    fun run(seconds: Double, dt: Double = 0.01) {
        repeat(ceil(seconds / dt - 1e-9).toInt()) { step(dt) }
    }

    /** تا برقراری شرط یا گذشت maxSeconds اجرا می‌کند؛ true یعنی شرط برقرار شد. */
    fun runUntil(maxSeconds: Double, dt: Double = 0.01, cond: () -> Boolean): Boolean {
        val end = time + maxSeconds
        while (time < end) {
            if (cond()) return true
            step(dt)
        }
        return cond()
    }

    // ---- پرس‌وجو برای UI (رنگ‌آمیزی سیم‌ها، حالت Inspection) ----

    fun acVoltage(pin: Pin): Complex = solution.ac[solution.nets.netOf(pin)]
    fun dcVoltage(pin: Pin): Double = solution.dc[solution.nets.netOf(pin)].re

    /** مقدار موثر ولتاژ گره نسبت به زمین. */
    fun voltage(pin: Pin): Double = hypot(acVoltage(pin).abs, dcVoltage(pin))

    /** سیم/گره برق‌دار است؟ (ولتاژ بیشتر از آستانه) */
    fun isLive(pin: Pin, threshold: Double = 5.0): Boolean = voltage(pin) > threshold

    fun sameNet(a: Pin, b: Pin) = solution.nets.sameNet(a, b)

    /** جریان موثر شاخه‌ی بین دو پین یک قطعه (مثلاً "F2:1L1","F2:2T1")؛ null اگر شاخه‌ای نباشد (تیغه‌ی باز). */
    fun current(a: Pin, b: Pin): Double? {
        for (br in solution.branches) {
            if (br is Impedance && ((br.a == a && br.b == b) || (br.a == b && br.b == a))) {
                val va = hypot((acVoltage(br.a) - acVoltage(br.b)).abs, dcVoltage(br.a) - dcVoltage(br.b))
                return va / br.ohms
            }
        }
        return null
    }

    /** مجموع جریان شاخه‌های متصل به گره‌ی این پین؛ برای هایلایت مسیر جریان در UI. */
    fun netCurrent(pin: Pin): Double {
        val n = solution.nets.netOf(pin)
        var sum = 0.0
        for (br in solution.branches) if (br is Impedance) {
            val na = solution.nets.netOf(br.a)
            val nb = solution.nets.netOf(br.b)
            if (na != nb && (na == n || nb == n)) sum += current(br.a, br.b) ?: 0.0
        }
        return sum
    }
}
