package com.indsim.editor

import com.indsim.engine.Component
import com.indsim.engine.Domain
import com.indsim.engine.components.Coil
import com.indsim.engine.components.DualPushButton
import com.indsim.engine.components.GroundTerminal
import com.indsim.engine.components.Junction
import com.indsim.engine.components.Lamp
import com.indsim.engine.components.LinkedContact
import com.indsim.engine.components.Motor1Ph
import com.indsim.engine.components.Motor3Ph
import com.indsim.engine.components.PowerPoles
import com.indsim.engine.components.ProtectionKind
import com.indsim.engine.components.ProtectiveDevice
import com.indsim.engine.components.PushButton
import com.indsim.engine.components.ResistiveLoad
import com.indsim.engine.components.Selector
import com.indsim.engine.components.Source
import com.indsim.engine.components.ThermalRelay
import com.indsim.engine.components.TimerMode
import com.indsim.engine.components.TimerRelay
import com.indsim.engine.components.ToggleSwitch

data class PinDef(val name: String, val dx: Int, val dy: Int)

enum class Category { SOURCE, PROTECTION, COMMAND, CONTACTOR, TIMER, LOAD, MOTOR, WIRING }

/** نوع کلی؛ نقاش رابط کاربری بر اساس آن شکل را می‌کشد. */
enum class Kind {
    SOURCE3, SOURCE1, SOURCE_DC, BREAKER, PUSH, PUSH_DUAL, ESTOP, TOGGLE, SELECTOR,
    COIL, POLES, AUX, THERMAL, TIMER, LAMP, BUZZER, LOAD, MOTOR3, MOTOR1, JUNCTION, GROUND,
}

enum class PType { NUMBER, COIL_LINK, CHOICE }

data class ParamDef(
    val key: String,
    val fa: String,
    val en: String,
    val default: String,
    val type: PType = PType.NUMBER,
    val options: List<String> = emptyList(),
)

class TypeDef(
    val type: String,
    val kind: Kind,
    val category: Category,
    val fa: String,
    val en: String,
    val prefix: String,
    val pins: List<PinDef>,
    val params: List<ParamDef> = emptyList(),
    val build: (id: String, p: Map<String, String>) -> Component,
) {
    val defaults: Map<String, String> = params.associate { it.key to it.default }
    val pinNames: List<String> get() = pins.map { it.name }
    fun pin(name: String): PinDef = pins.first { it.name == name }

    /** پین بالا و پایین برای قطعات دوپایه (برای چیدمان خودکار مدارهای آماده). */
    val topPin: String get() = pins.minWith(compareBy({ it.dy }, { it.dx })).name
    val bottomPin: String get() = pins.maxWith(compareBy({ it.dy }, { it.dx })).name
    val needsCoil: Boolean get() = kind == Kind.POLES || kind == Kind.AUX
    val isCoilLike: Boolean get() = kind == Kind.COIL || kind == Kind.TIMER
}

private fun Map<String, String>.num(k: String, d: Double) = this[k]?.toDoubleOrNull() ?: d

object Registry {
    private fun pins(vararg p: Triple<String, Int, Int>) = p.map { PinDef(it.first, it.second, it.third) }
    private fun pn(n: String, x: Int, y: Int) = Triple(n, x, y)

    private fun breakerPins(poles: Int) =
        (0 until poles).flatMap { i -> listOf(PinDef("${2 * i + 1}", 2 * i, 0), PinDef("${2 * i + 2}", 2 * i, 4)) }

    private val amps = ParamDef("amps", "جریان نامی (A)", "Rated current (A)", "16")
    private val coilLink = ParamDef("coil", "بوبین مرتبط", "Linked coil", "", PType.COIL_LINK)

    private fun lamp(type: String, color: String, fa: String, en: String) = TypeDef(
        type, Kind.LAMP, Category.COMMAND, fa, en, "H", pins(pn("X1", 0, 0), pn("X2", 0, 3)),
        listOf(ParamDef("volts", "ولتاژ نامی (V)", "Rated voltage (V)", "230")),
    ) { id, p -> Lamp(id, p.num("volts", 230.0), color) }

    private fun toggle(type: String, no: Boolean, fa: String, en: String, prefix: String) = TypeDef(
        type, Kind.TOGGLE, Category.COMMAND, fa, en, prefix,
        if (no) pins(pn("3", 0, 0), pn("4", 0, 3)) else pins(pn("1", 0, 0), pn("2", 0, 3)),
    ) { id, _ -> ToggleSwitch(id, no) }

    private fun breaker(type: String, kind: ProtectionKind, poles: Int, fa: String, en: String, prefix: String, def: String) =
        TypeDef(
            type, Kind.BREAKER, Category.PROTECTION, fa, en, prefix, breakerPins(poles),
            listOf(amps.copy(default = def)),
        ) { id, p -> ProtectiveDevice(id, kind, poles, p.num("amps", def.toDouble())) }

    val defs: List<TypeDef> = listOf(
        // ---- منابع ----
        TypeDef("src3", Kind.SOURCE3, Category.SOURCE, "منبع AC سه‌فاز", "3-phase AC source", "PS",
            pins(pn("L1", 0, 2), pn("L2", 2, 2), pn("L3", 4, 2), pn("N", 6, 2), pn("PE", 8, 2)),
            listOf(ParamDef("volts", "ولتاژ فاز-نول (V)", "Phase-neutral voltage (V)", "230")),
        ) { id, p -> Source.ac3(id, p.num("volts", 230.0)) },
        TypeDef("src1", Kind.SOURCE1, Category.SOURCE, "منبع AC تک‌فاز", "1-phase AC source", "PS",
            pins(pn("L", 0, 2), pn("N", 2, 2), pn("PE", 4, 2)),
            listOf(ParamDef("volts", "ولتاژ (V)", "Voltage (V)", "230")),
        ) { id, p -> Source.ac1(id, p.num("volts", 230.0)) },
        TypeDef("srcdc", Kind.SOURCE_DC, Category.SOURCE, "منبع DC", "DC supply", "PS",
            pins(pn("+", 0, 2), pn("-", 2, 2)),
            listOf(ParamDef("volts", "ولتاژ (V)", "Voltage (V)", "24")),
        ) { id, p -> Source.dc(id, p.num("volts", 24.0)) },

        // ---- حفاظت ----
        breaker("fuse1", ProtectionKind.FUSE, 1, "فیوز تک‌فاز", "Fuse 1P", "F", "10"),
        breaker("fuse3", ProtectionKind.FUSE, 3, "فیوز سه‌فاز", "Fuse 3P", "F", "16"),
        breaker("mcb1", ProtectionKind.MCB, 1, "کلید مینیاتوری ۱ پل (MCB)", "MCB 1P", "Q", "6"),
        breaker("mcb3", ProtectionKind.MCB, 3, "کلید مینیاتوری ۳ پل (MCB)", "MCB 3P", "Q", "16"),
        breaker("mccb3", ProtectionKind.MCB, 3, "کلید اتوماتیک (MCCB)", "MCCB 3P", "QM", "63"),
        breaker("mpcb3", ProtectionKind.MCB, 3, "کلید محافظ موتور (MPCB)", "MPCB 3P", "QS", "10"),
        TypeDef("thermal", Kind.THERMAL, Category.PROTECTION, "رله حرارتی", "Thermal overload relay", "FR",
            pins(pn("1L1", 0, 0), pn("3L2", 2, 0), pn("5L3", 4, 0), pn("2T1", 0, 4), pn("4T2", 2, 4), pn("6T3", 4, 4),
                pn("95", 6, 1), pn("96", 6, 3), pn("97", 8, 1), pn("98", 8, 3)),
            listOf(amps.copy(default = "10")),
        ) { id, p -> ThermalRelay(id, p.num("amps", 10.0)) },

        // ---- فرمان ----
        TypeDef("pb_no", Kind.PUSH, Category.COMMAND, "شستی استارت NO", "Start pushbutton NO", "SB",
            pins(pn("13", 0, 0), pn("14", 0, 3))) { id, _ -> PushButton(id, true) },
        TypeDef("pb_nc", Kind.PUSH, Category.COMMAND, "شستی استپ NC", "Stop pushbutton NC", "SB",
            pins(pn("21", 0, 0), pn("22", 0, 3))) { id, _ -> PushButton(id, false) },
        TypeDef("pb_dual", Kind.PUSH_DUAL, Category.COMMAND, "شستی دوبل استارت/استپ", "Dual start/stop button", "SB",
            pins(pn("11", 0, 0), pn("12", 0, 3), pn("13", 2, 0), pn("14", 2, 3))) { id, _ -> DualPushButton(id) },
        TypeDef("estop", Kind.ESTOP, Category.COMMAND, "شستی اضطراری", "Emergency stop", "SB",
            pins(pn("21", 0, 0), pn("22", 0, 3))) { id, _ -> PushButton(id, false, latching = true) },
        TypeDef("sel2", Kind.SELECTOR, Category.COMMAND, "کلید سلکتوری ۲ حالته", "Selector 2-pos", "SA",
            pins(pn("13", 0, 0), pn("14", 0, 3))) { id, _ -> Selector(id, 2) },
        TypeDef("sel3", Kind.SELECTOR, Category.COMMAND, "کلید سلکتوری ۳ حالته", "Selector 3-pos", "SA",
            pins(pn("13", 0, 0), pn("14", 0, 3), pn("23", 2, 0), pn("24", 2, 3))) { id, _ -> Selector(id, 3) },
        toggle("micro_no", true, "میکروسوئیچ NO", "Microswitch NO", "SQ"),
        toggle("micro_nc", false, "میکروسوئیچ NC", "Microswitch NC", "SQ"),
        toggle("limit_no", true, "لیمیت سوئیچ NO", "Limit switch NO", "SQ"),
        toggle("limit_nc", false, "لیمیت سوئیچ NC", "Limit switch NC", "SQ"),
        toggle("sensor_ind", true, "سنسور القایی (دوسیمه)", "Inductive sensor (2-wire)", "B"),
        toggle("sensor_cap", true, "سنسور خازنی (دوسیمه)", "Capacitive sensor (2-wire)", "B"),
        toggle("photocell", true, "فتوسل (دوسیمه)", "Photocell (2-wire)", "B"),
        lamp("lamp_r", "red", "چراغ سیگنال قرمز", "Signal lamp red"),
        lamp("lamp_g", "green", "چراغ سیگنال سبز", "Signal lamp green"),
        lamp("lamp_y", "yellow", "چراغ سیگنال زرد", "Signal lamp yellow"),
        lamp("lamp_w", "white", "چراغ سیگنال سفید", "Signal lamp white"),
        TypeDef("buzzer", Kind.BUZZER, Category.COMMAND, "بیزر", "Buzzer", "HA", pins(pn("X1", 0, 0), pn("X2", 0, 3)),
            listOf(ParamDef("volts", "ولتاژ نامی (V)", "Rated voltage (V)", "230")),
        ) { id, p -> Lamp(id, p.num("volts", 230.0), "buzzer", 5.0) },
        TypeDef("bell", Kind.BUZZER, Category.COMMAND, "زنگ هشدار", "Alarm bell", "HA", pins(pn("X1", 0, 0), pn("X2", 0, 3)),
            listOf(ParamDef("volts", "ولتاژ نامی (V)", "Rated voltage (V)", "230")),
        ) { id, p -> Lamp(id, p.num("volts", 230.0), "bell", 5.0) },

        // ---- کنتاکتور و رله ----
        TypeDef("coil", Kind.COIL, Category.CONTACTOR, "بوبین کنتاکتور (A1-A2)", "Contactor coil (A1-A2)", "K",
            pins(pn("A1", 0, 0), pn("A2", 0, 4)),
            listOf(
                ParamDef("volts", "ولتاژ بوبین (V)", "Coil voltage (V)", "230"),
                ParamDef("domain", "نوع ولتاژ", "Supply type", "AC", PType.CHOICE, listOf("AC", "DC")),
            ),
        ) { id, p -> Coil(id, p.num("volts", 230.0), if (p["domain"] == "DC") Domain.DC else Domain.AC) },
        TypeDef("relay", Kind.COIL, Category.CONTACTOR, "رله کمکی (بوبین)", "Auxiliary relay (coil)", "KA",
            pins(pn("A1", 0, 0), pn("A2", 0, 4)),
            listOf(
                ParamDef("volts", "ولتاژ بوبین (V)", "Coil voltage (V)", "230"),
                ParamDef("domain", "نوع ولتاژ", "Supply type", "AC", PType.CHOICE, listOf("AC", "DC")),
            ),
        ) { id, p -> Coil(id, p.num("volts", 230.0), if (p["domain"] == "DC") Domain.DC else Domain.AC, 2.0) },
        TypeDef("poles3", Kind.POLES, Category.CONTACTOR, "تیغه‌های قدرت کنتاکتور", "Contactor power poles", "KP",
            pins(pn("1L1", 0, 0), pn("3L2", 2, 0), pn("5L3", 4, 0), pn("2T1", 0, 4), pn("4T2", 2, 4), pn("6T3", 4, 4)),
            listOf(coilLink),
        ) { id, p -> PowerPoles(id, p["coil"].orEmpty()) },
        TypeDef("aux_no", Kind.AUX, Category.CONTACTOR, "تیغه‌ی کمکی NO", "Auxiliary contact NO", "KC",
            pins(pn("13", 0, 0), pn("14", 0, 3)), listOf(coilLink),
        ) { id, p -> LinkedContact(id, p["coil"].orEmpty(), true) },
        TypeDef("aux_nc", Kind.AUX, Category.CONTACTOR, "تیغه‌ی کمکی NC", "Auxiliary contact NC", "KN",
            pins(pn("21", 0, 0), pn("22", 0, 3)), listOf(coilLink),
        ) { id, p -> LinkedContact(id, p["coil"].orEmpty(), false) },

        // ---- تایمرها ----
        TypeDef("ton", Kind.TIMER, Category.TIMER, "تایمر تأخیر در وصل (TON)", "On-delay timer (TON)", "KT",
            pins(pn("A1", 0, 0), pn("A2", 0, 4), pn("15", 3, 0), pn("16", 2, 4), pn("18", 4, 4)),
            listOf(ParamDef("delay", "زمان تأخیر (s)", "Delay (s)", "5"), ParamDef("volts", "ولتاژ (V)", "Voltage (V)", "230")),
        ) { id, p -> TimerRelay(id, TimerMode.TON, p.num("delay", 5.0), p.num("volts", 230.0)) },
        TypeDef("tof", Kind.TIMER, Category.TIMER, "تایمر تأخیر در قطع (TOF)", "Off-delay timer (TOF)", "KT",
            pins(pn("A1", 0, 0), pn("A2", 0, 4), pn("15", 3, 0), pn("16", 2, 4), pn("18", 4, 4)),
            listOf(ParamDef("delay", "زمان تأخیر (s)", "Delay (s)", "5"), ParamDef("volts", "ولتاژ (V)", "Voltage (V)", "230")),
        ) { id, p -> TimerRelay(id, TimerMode.TOF, p.num("delay", 5.0), p.num("volts", 230.0)) },
        TypeDef("star_delta", Kind.TIMER, Category.TIMER, "تایمر ستاره-مثلث", "Star-delta timer", "KT",
            pins(pn("A1", 0, 0), pn("A2", 0, 4), pn("17", 2, 0), pn("18", 2, 4), pn("27", 4, 0), pn("28", 4, 4)),
            listOf(ParamDef("delay", "زمان ستاره (s)", "Star time (s)", "5"), ParamDef("volts", "ولتاژ (V)", "Voltage (V)", "230")),
        ) { id, p -> TimerRelay(id, TimerMode.STAR_DELTA, p.num("delay", 5.0), p.num("volts", 230.0)) },

        // ---- بار و موتور ----
        TypeDef("load", Kind.LOAD, Category.LOAD, "مقاومت / بار", "Resistive load", "R",
            pins(pn("1", 0, 0), pn("2", 0, 3)),
            listOf(ParamDef("ohms", "مقاومت (Ω)", "Resistance (Ω)", "50")),
        ) { id, p -> ResistiveLoad(id, p.num("ohms", 50.0).coerceAtLeast(0.01)) },
        TypeDef("motor3", Kind.MOTOR3, Category.MOTOR, "موتور سه‌فاز القایی", "3-phase induction motor", "M",
            pins(pn("U1", 0, 0), pn("V1", 2, 0), pn("W1", 4, 0), pn("W2", 0, 4), pn("U2", 2, 4), pn("V2", 4, 4)),
            listOf(
                ParamDef("amps", "جریان نامی خط (A)", "Rated line current (A)", "10"),
                ParamDef("load", "ضریب بار (۱=نامی)", "Load factor (1=rated)", "1"),
            ),
        ) { id, p -> Motor3Ph(id, 400.0, p.num("amps", 10.0)).also { it.mechanicalLoad = p.num("load", 1.0) } },
        TypeDef("motor1", Kind.MOTOR1, Category.MOTOR, "موتور تک‌فاز", "Single-phase motor", "M",
            pins(pn("U1", 0, 0), pn("U2", 0, 4)),
            listOf(ParamDef("amps", "جریان نامی (A)", "Rated current (A)", "5")),
        ) { id, p -> Motor1Ph(id, 230.0, p.num("amps", 5.0)) },

        // ---- سیم‌کشی ----
        TypeDef("junction", Kind.JUNCTION, Category.WIRING, "نقطه‌ی انشعاب", "Junction", "J", pins(pn("J", 0, 0))) { id, _ -> Junction(id) },
        TypeDef("ground", Kind.GROUND, Category.WIRING, "اتصال زمین PE", "PE earth", "PE", pins(pn("PE", 0, 0))) { id, _ -> GroundTerminal(id) },
    )

    private val byType = defs.associateBy { it.type }

    fun get(type: String): TypeDef = byType[type] ?: error("unknown component type '$type'")
    fun find(type: String): TypeDef? = byType[type]
    fun byCategory(c: Category) = defs.filter { it.category == c && it.type != "junction" }
}
