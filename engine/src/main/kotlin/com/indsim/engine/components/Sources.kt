package com.indsim.engine.components

import com.indsim.engine.Branch
import com.indsim.engine.Complex
import com.indsim.engine.Component
import com.indsim.engine.Domain
import com.indsim.engine.SourceBranch

/**
 * منبع تغذیه. هر پین خروجی یک منبع تونن نسبت به زمین مرجع است.
 * N و PE و منفی DC با emf صفر، به زمین وصل هستند (سیستم TN).
 */
class Source(
    id: String,
    private val outputs: Map<String, Pair<Domain, Complex>>,
    private val rs: Double = 0.01,
) : Component(id) {
    override val pinNames: List<String> = outputs.keys.toList()

    /** کلید کلی منبع؛ خاموش = همه‌ی پین‌ها شناور. */
    var on = true

    override fun branches(): List<Branch> =
        if (!on) emptyList()
        else outputs.map { (name, v) -> SourceBranch(pin(name), v.first, v.second, rs, id) }

    companion object {
        /** سه‌فاز: ولتاژ فاز-نول [phaseVolts] (۲۳۰ ولت ⇒ فاز-فاز ۴۰۰ ولت)، توالی مثبت L1→L2→L3. */
        fun ac3(id: String, phaseVolts: Double = 230.0) = Source(
            id,
            linkedMapOf(
                "L1" to (Domain.AC to Complex.polar(phaseVolts, 0.0)),
                "L2" to (Domain.AC to Complex.polar(phaseVolts, -120.0)),
                "L3" to (Domain.AC to Complex.polar(phaseVolts, -240.0)),
                "N" to (Domain.AC to Complex.ZERO),
                "PE" to (Domain.AC to Complex.ZERO),
            ),
        )

        fun ac1(id: String, volts: Double = 230.0) = Source(
            id,
            linkedMapOf(
                "L" to (Domain.AC to Complex.polar(volts, 0.0)),
                "N" to (Domain.AC to Complex.ZERO),
                "PE" to (Domain.AC to Complex.ZERO),
            ),
        )

        fun dc(id: String, volts: Double = 24.0) = Source(
            id,
            linkedMapOf(
                "+" to (Domain.DC to Complex(volts, 0.0)),
                "-" to (Domain.DC to Complex.ZERO),
            ),
        )
    }
}
