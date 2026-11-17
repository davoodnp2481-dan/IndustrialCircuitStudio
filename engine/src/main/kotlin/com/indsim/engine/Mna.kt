package com.indsim.engine

import kotlin.math.abs
import kotlin.math.hypot

/** نتیجه‌ی حل: ولتاژ هر گره نسبت به زمین، جدا برای AC (فازور) و DC. */
class Solution(
    val nets: NetMap,
    val ac: Array<Complex>,
    val dc: Array<Complex>,
    val branches: List<Branch>,
)

/** نمای فقط‌خواندنی که قطعات در step() از آن ولتاژ و جریان می‌خوانند. */
class SolutionView(
    private val sol: Solution,
    val time: Double,
    private val sink: (SimEvent) -> Unit,
) {
    fun ac(pin: Pin): Complex = sol.ac[sol.nets.netOf(pin)]
    fun dc(pin: Pin): Double = sol.dc[sol.nets.netOf(pin)].re

    fun voltage(a: Pin, b: Pin, domain: Domain): Double = when (domain) {
        Domain.AC -> (ac(a) - ac(b)).abs
        Domain.DC -> abs(dc(a) - dc(b))
    }

    /** مقدار موثر کل (AC و DC با هم). */
    fun rmsVoltage(a: Pin, b: Pin): Double = hypot(voltage(a, b, Domain.AC), voltage(a, b, Domain.DC))

    fun rmsCurrent(imp: Impedance): Double = rmsVoltage(imp.a, imp.b) / imp.ohms

    fun emit(type: EventType, componentId: String, message: String) {
        sink(SimEvent(time, type, componentId, message))
    }
}

object Mna {
    /** رسانایی ناچیز هر گره به زمین؛ جلوگیری از تکین شدن ماتریس برای گره‌های شناور. */
    const val GMIN = 1e-9

    /**
     * حل گره‌ای. چون همه‌ی امپدانس‌ها حقیقی‌اند، ماتریس رسانایی حقیقی است و فقط سمت راست (منابع فازوری) مختلط است؛
     * بنابراین یک حذف گاوس روی ماتریس حقیقی، قسمت حقیقی و موهومی را با هم حل می‌کند.
     */
    fun solve(nets: NetMap, branches: List<Branch>, domain: Domain): Array<Complex> {
        val n = nets.count
        if (branches.none { it is SourceBranch && it.domain == domain }) return Array(n) { Complex.ZERO }

        val a = Array(n) { DoubleArray(n) }
        val br = DoubleArray(n)
        val bi = DoubleArray(n)
        for (i in 0 until n) a[i][i] = GMIN

        for (b in branches) when (b) {
            is Impedance -> {
                val x = nets.netOf(b.a)
                val y = nets.netOf(b.b)
                if (x != y) {
                    val g = 1.0 / b.ohms
                    a[x][x] += g; a[y][y] += g
                    a[x][y] -= g; a[y][x] -= g
                }
            }
            is SourceBranch -> {
                val x = nets.netOf(b.pin)
                val g = 1.0 / b.rs
                a[x][x] += g
                if (b.domain == domain) { br[x] += b.emf.re * g; bi[x] += b.emf.im * g }
            }
        }
        gauss(a, br, bi)
        return Array(n) { Complex(br[it], bi[it]) }
    }

    /** حذف گاوس با محور جزئی؛ جواب در br و bi جایگزین می‌شود. */
    private fun gauss(a: Array<DoubleArray>, br: DoubleArray, bi: DoubleArray) {
        val n = br.size
        for (col in 0 until n) {
            var piv = col
            var best = Math.abs(a[col][col])
            for (r in col + 1 until n) {
                val m = Math.abs(a[r][col])
                if (m > best) { best = m; piv = r }
            }
            check(best > 1e-30) { "singular matrix" }
            if (piv != col) {
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                val tr = br[piv]; br[piv] = br[col]; br[col] = tr
                val ti = bi[piv]; bi[piv] = bi[col]; bi[col] = ti
            }
            val rowC = a[col]
            val d = rowC[col]
            for (r in col + 1 until n) {
                val rowR = a[r]
                val f = rowR[col] / d
                if (f == 0.0) continue
                for (c in col until n) rowR[c] -= f * rowC[c]
                br[r] -= f * br[col]
                bi[r] -= f * bi[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var sr = br[r]
            var si = bi[r]
            val row = a[r]
            for (c in r + 1 until n) { sr -= row[c] * br[c]; si -= row[c] * bi[c] }
            br[r] = sr / row[r]
            bi[r] = si / row[r]
        }
    }
}
