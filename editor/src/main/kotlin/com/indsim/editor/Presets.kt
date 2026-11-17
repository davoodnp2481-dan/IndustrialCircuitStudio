package com.indsim.editor

class Preset(
    val id: String,
    val fa: String,
    val en: String,
    val descFa: String,
    val descEn: String,
    val doc: Document,
)

private class Item(val id: String, val type: String, vararg val params: Pair<String, String>)

/** سازنده‌ی نقشه با شناسه‌های دلخواه؛ هر سیم‌کشی ناموفق فوراً خطا می‌دهد (مدار آماده باید همیشه سالم باشد). */
private class B(val name: String) {
    var doc = Document(name)

    fun p(id: String, type: String, x: Int, y: Int, vararg params: Pair<String, String>) {
        val r = Edit.addWithId(doc, id, type, x, y, params.toMap())
        check(r.ok) { "$name: ${r.error}" }
        doc = r.doc
    }

    fun w(a: String, b: String) {
        val r = Edit.connect(doc, a, b)
        check(r.ok) { "$name: ${r.error} ($a - $b)" }
        doc = r.doc
    }

    fun top(id: String) = "$id:${Registry.get(doc.find(id)!!.type).topPin}"
    fun bot(id: String) = "$id:${Registry.get(doc.find(id)!!.type).bottomPin}"

    private fun height(type: String): Int {
        val ys = Registry.get(type).pins.map { it.dy }
        return ys.max() - ys.min()
    }

    /** قطعات دوپایه را زیر هم می‌چیند و پایین هر یک را به بالای بعدی وصل می‌کند؛ y بعدی را می‌دهد. */
    fun chain(x: Int, y0: Int, items: List<Item>): Int {
        var y = y0
        var prev: String? = null
        for (item in items) {
            p(item.id, item.type, x, y, *item.params)
            if (prev != null) w(bot(prev), top(item.id))
            prev = item.id
            y += height(item.type) + 3
        }
        return y
    }

    fun delta(m: String) {
        w("$m:U1", "$m:W2"); w("$m:V1", "$m:U2"); w("$m:W1", "$m:V2")
    }

    /** خروجی قدرت مستقیم: MCB → تیغه‌های کنتاکتور K{n} → رله حرارتی → موتور (مثلث). */
    fun feeder(n: Int, x: Int, motorAmps: String = "10", load: String = "1") {
        p("Q$n", "mcb3", x, 8, "amps" to "16")
        p("K${n}P", "poles3", x, 16, "coil" to "K$n")
        p("FR$n", "thermal", x, 24, "amps" to motorAmps)
        p("M$n", "motor3", x, 34, "amps" to motorAmps, "load" to load)
        w("PS1:L1", "Q$n:1"); w("PS1:L2", "Q$n:3"); w("PS1:L3", "Q$n:5")
        w("Q$n:2", "K${n}P:1L1"); w("Q$n:4", "K${n}P:3L2"); w("Q$n:6", "K${n}P:5L3")
        w("K${n}P:2T1", "FR$n:1L1"); w("K${n}P:4T2", "FR$n:3L2"); w("K${n}P:6T3", "FR$n:5L3")
        w("FR$n:2T1", "M$n:U1"); w("FR$n:4T2", "M$n:V1"); w("FR$n:6T3", "M$n:W1")
        delta("M$n")
    }
}

object Presets {
    private fun lampOnOff(): Document {
        val b = B("lamp-on-off")
        b.p("PS1", "src1", 2, 2)
        b.p("SA1", "sel2", 2, 8)
        b.p("H1", "lamp_g", 2, 16)
        b.w("PS1:L", "SA1:13"); b.w("SA1:14", "H1:X1"); b.w("H1:X2", "PS1:N")
        return b.doc
    }

    private fun jog(): Document {
        val b = B("start-stop")
        b.p("PS1", "src1", 2, 2)
        b.chain(2, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc"), Item("S1", "pb_no"), Item("K1", "coil")))
        b.w("PS1:L", b.top("F1")); b.w(b.bot("K1"), "PS1:N")
        b.chain(12, 8, listOf(Item("K1A", "aux_no", "coil" to "K1"), Item("H1", "lamp_g")))
        b.w("PS1:L", b.top("K1A")); b.w(b.bot("H1"), "PS1:N")
        return b.doc
    }

    private fun selfHold(): Document {
        val b = B("self-holding")
        b.p("PS1", "src1", 2, 2)
        val y = b.chain(2, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc")))
        b.w("PS1:L", b.top("F1"))
        b.p("S1", "pb_no", 2, y); b.p("K1A", "aux_no", 6, y, "coil" to "K1")
        b.w(b.bot("S0"), b.top("S1")); b.w(b.bot("S0"), b.top("K1A"))
        b.p("K1", "coil", 4, y + 6)
        b.w(b.bot("S1"), "K1:A1"); b.w(b.bot("K1A"), "K1:A1"); b.w("K1:A2", "PS1:N")
        b.chain(14, 8, listOf(Item("K1B", "aux_no", "coil" to "K1"), Item("H1", "lamp_g")))
        b.w("PS1:L", b.top("K1B")); b.w(b.bot("H1"), "PS1:N")
        b.chain(20, 8, listOf(Item("K1C", "aux_nc", "coil" to "K1"), Item("H2", "lamp_r")))
        b.w("PS1:L", b.top("K1C")); b.w(b.bot("H2"), "PS1:N")
        return b.doc
    }

    private fun dol(lamps: Boolean): Document {
        val b = B(if (lamps) "motor-thermal" else "dol-motor")
        b.p("PS1", "src3", 2, 2)
        b.feeder(1, 2)
        val y = b.chain(16, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc")))
        b.w("PS1:L1", b.top("F1"))
        b.p("S1", "pb_no", 16, y); b.p("K1A", "aux_no", 20, y, "coil" to "K1")
        b.w(b.bot("S0"), b.top("S1")); b.w(b.bot("S0"), b.top("K1A"))
        b.w(b.bot("S1"), "FR1:95"); b.w(b.bot("K1A"), "FR1:95")
        b.p("K1", "coil", 18, y + 7)
        b.w("FR1:96", "K1:A1"); b.w("K1:A2", "PS1:N")
        if (lamps) {
            b.chain(28, 8, listOf(Item("K1B", "aux_no", "coil" to "K1"), Item("H1", "lamp_g")))
            b.w("PS1:L1", b.top("K1B")); b.w(b.bot("H1"), "PS1:N")
            b.p("H2", "lamp_r", 34, 16)
            b.w("PS1:L1", "FR1:97"); b.w("FR1:98", b.top("H2")); b.w(b.bot("H2"), "PS1:N")
        }
        return b.doc
    }

    private fun fwdRev(estop: Boolean): Document {
        val b = B(if (estop) "fwd-rev-estop" else "fwd-rev")
        b.p("PS1", "src3", 2, 2)
        b.p("Q1", "mcb3", 2, 8, "amps" to "16")
        b.p("KFP", "poles3", 2, 16, "coil" to "KF")
        b.p("KRP", "poles3", 12, 16, "coil" to "KR")
        b.p("FR1", "thermal", 2, 26, "amps" to "10")
        b.p("M1", "motor3", 2, 36)
        b.w("PS1:L1", "Q1:1"); b.w("PS1:L2", "Q1:3"); b.w("PS1:L3", "Q1:5")
        for ((q, pole) in listOf("Q1:2" to "1L1", "Q1:4" to "3L2", "Q1:6" to "5L3")) {
            b.w(q, "KFP:$pole"); b.w(q, "KRP:$pole")
        }
        b.w("KFP:2T1", "FR1:1L1"); b.w("KFP:4T2", "FR1:3L2"); b.w("KFP:6T3", "FR1:5L3")
        b.w("KRP:2T1", "FR1:5L3"); b.w("KRP:4T2", "FR1:3L2"); b.w("KRP:6T3", "FR1:1L1")
        b.w("FR1:2T1", "M1:U1"); b.w("FR1:4T2", "M1:V1"); b.w("FR1:6T3", "M1:W1")
        b.delta("M1")

        val items = mutableListOf(Item("F1", "mcb1"))
        if (estop) items += Item("SE1", "estop")
        items += Item("S0", "pb_nc")
        val y = b.chain(22, 8, items)
        b.w("PS1:L1", b.top("F1"))
        b.w(b.bot("S0"), "FR1:95")
        val t = "FR1:96"

        b.p("SF", "pb_no", 22, y); b.p("KFA", "aux_no", 26, y, "coil" to "KF")
        b.w(t, b.top("SF")); b.w(t, b.top("KFA"))
        b.p("KRN", "aux_nc", 24, y + 6, "coil" to "KR")
        b.w(b.bot("SF"), b.top("KRN")); b.w(b.bot("KFA"), b.top("KRN"))
        b.p("KF", "coil", 24, y + 12)
        b.w(b.bot("KRN"), "KF:A1"); b.w("KF:A2", "PS1:N")

        b.p("SR", "pb_no", 34, y); b.p("KRA", "aux_no", 38, y, "coil" to "KR")
        b.w(t, b.top("SR")); b.w(t, b.top("KRA"))
        b.p("KFN", "aux_nc", 36, y + 6, "coil" to "KF")
        b.w(b.bot("SR"), b.top("KFN")); b.w(b.bot("KRA"), b.top("KFN"))
        b.p("KR", "coil", 36, y + 12)
        b.w(b.bot("KFN"), "KR:A1"); b.w("KR:A2", "PS1:N")
        return b.doc
    }

    private fun starDelta(): Document {
        val b = B("star-delta")
        b.p("PS1", "src3", 2, 2)
        b.p("Q1", "mcb3", 2, 8, "amps" to "16")
        b.p("K1P", "poles3", 2, 16, "coil" to "K1")
        b.p("FR1", "thermal", 2, 24, "amps" to "10")
        b.p("M1", "motor3", 2, 34)
        b.p("K2P", "poles3", 14, 40, "coil" to "K2")
        b.p("K3P", "poles3", 26, 40, "coil" to "K3")
        b.w("PS1:L1", "Q1:1"); b.w("PS1:L2", "Q1:3"); b.w("PS1:L3", "Q1:5")
        b.w("Q1:2", "K1P:1L1"); b.w("Q1:4", "K1P:3L2"); b.w("Q1:6", "K1P:5L3")
        b.w("K1P:2T1", "FR1:1L1"); b.w("K1P:4T2", "FR1:3L2"); b.w("K1P:6T3", "FR1:5L3")
        b.w("FR1:2T1", "M1:U1"); b.w("FR1:4T2", "M1:V1"); b.w("FR1:6T3", "M1:W1")
        // ستاره (K2): سه سر انتهایی موتور به هم می‌رسند
        b.w("K2P:1L1", "M1:U2"); b.w("K2P:3L2", "M1:V2"); b.w("K2P:5L3", "M1:W2")
        b.w("K2P:2T1", "K2P:4T2"); b.w("K2P:4T2", "K2P:6T3")
        // مثلث (K3)
        b.w("K3P:1L1", "M1:U1"); b.w("K3P:2T1", "M1:W2")
        b.w("K3P:3L2", "M1:V1"); b.w("K3P:4T2", "M1:U2")
        b.w("K3P:5L3", "M1:W1"); b.w("K3P:6T3", "M1:V2")

        val y = b.chain(36, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc")))
        b.w("PS1:L1", b.top("F1")); b.w(b.bot("S0"), "FR1:95")
        b.p("S1", "pb_no", 36, y); b.p("K1A", "aux_no", 40, y, "coil" to "K1")
        b.w("FR1:96", b.top("S1")); b.w("FR1:96", b.top("K1A"))
        val y2 = y + 7
        b.p("K1", "coil", 36, y2)
        b.p("T1", "star_delta", 42, y2, "delay" to "5")
        b.w(b.bot("S1"), "K1:A1"); b.w(b.bot("K1A"), "K1:A1")
        b.w(b.bot("S1"), "T1:A1"); b.w(b.bot("S1"), "T1:17"); b.w(b.bot("S1"), "T1:27")
        b.p("K3N", "aux_nc", 52, y2, "coil" to "K3")
        b.p("K2", "coil", 52, y2 + 6)
        b.p("K2N", "aux_nc", 58, y2, "coil" to "K2")
        b.p("K3", "coil", 58, y2 + 6)
        b.w("T1:18", b.top("K3N")); b.w(b.bot("K3N"), "K2:A1")
        b.w("T1:28", b.top("K2N")); b.w(b.bot("K2N"), "K3:A1")
        for (c in listOf("K1", "T1", "K2", "K3")) b.w("$c:A2", "PS1:N")
        return b.doc
    }

    private fun sequence(): Document {
        val b = B("two-motors-sequence")
        b.p("PS1", "src3", 2, 2)
        b.feeder(1, 2); b.feeder(2, 16)
        val y = b.chain(30, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc")))
        b.w("PS1:L1", b.top("F1"))
        val t = b.bot("S0")
        b.p("S1", "pb_no", 30, y); b.p("K1A", "aux_no", 34, y, "coil" to "K1")
        b.w(t, b.top("S1")); b.w(t, b.top("K1A"))
        b.w(b.bot("S1"), "FR1:95"); b.w(b.bot("K1A"), "FR1:95")
        b.p("K1", "coil", 32, y + 7); b.w("FR1:96", "K1:A1"); b.w("K1:A2", "PS1:N")
        b.p("K1B", "aux_no", 44, y, "coil" to "K1")
        b.w(t, b.top("K1B"))
        b.p("S2", "pb_no", 44, y + 6); b.p("K2A", "aux_no", 48, y + 6, "coil" to "K2")
        b.w(b.bot("K1B"), b.top("S2")); b.w(b.bot("K1B"), b.top("K2A"))
        b.w(b.bot("S2"), "FR2:95"); b.w(b.bot("K2A"), "FR2:95")
        b.p("K2", "coil", 46, y + 13); b.w("FR2:96", "K2:A1"); b.w("K2:A2", "PS1:N")
        return b.doc
    }

    private fun timedSequence(): Document {
        val b = B("timed-sequence")
        b.p("PS1", "src3", 2, 2)
        b.feeder(1, 2); b.feeder(2, 16)
        val y = b.chain(30, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc")))
        b.w("PS1:L1", b.top("F1"))
        b.p("S1", "pb_no", 30, y); b.p("K1A", "aux_no", 34, y, "coil" to "K1")
        b.w(b.bot("S0"), b.top("S1")); b.w(b.bot("S0"), b.top("K1A"))
        b.w(b.bot("S1"), "FR1:95"); b.w(b.bot("K1A"), "FR1:95")
        b.p("K1", "coil", 30, y + 7)
        b.p("T1", "ton", 38, y + 7, "delay" to "5")
        b.w("FR1:96", "K1:A1"); b.w("FR1:96", "T1:A1"); b.w("FR1:96", "T1:15")
        b.w("T1:18", "FR2:95")
        b.p("K2", "coil", 48, y + 7); b.w("FR2:96", "K2:A1")
        b.w("K1:A2", "PS1:N"); b.w("T1:A2", "PS1:N"); b.w("K2:A2", "PS1:N")
        return b.doc
    }

    private fun motorTimer(): Document {
        val b = B("motor-timer")
        b.p("PS1", "src3", 2, 2)
        b.feeder(1, 2)
        val y = b.chain(16, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc")))
        b.w("PS1:L1", b.top("F1"))
        b.p("T1", "ton", 16, y, "delay" to "5")
        b.w(b.bot("S0"), "T1:15")
        b.p("S1", "pb_no", 16, y + 8); b.p("K1A", "aux_no", 20, y + 8, "coil" to "K1")
        b.w("T1:16", b.top("S1")); b.w("T1:16", b.top("K1A"))
        b.w(b.bot("S1"), "FR1:95"); b.w(b.bot("K1A"), "FR1:95")
        b.p("K1", "coil", 18, y + 15)
        b.w("FR1:96", "K1:A1"); b.w("FR1:96", "T1:A1")
        b.w("K1:A2", "PS1:N"); b.w("T1:A2", "PS1:N")
        return b.doc
    }

    private fun tankLevel(): Document {
        val b = B("tank-level")
        b.p("PS1", "src3", 2, 2)
        b.feeder(1, 2)
        val y = b.chain(16, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc"), Item("SQ2", "limit_nc")))
        b.w("PS1:L1", b.top("F1")); b.w(b.bot("SQ2"), "FR1:95")
        b.p("SQ1", "limit_no", 16, y); b.p("K1A", "aux_no", 20, y, "coil" to "K1")
        b.w("FR1:96", b.top("SQ1")); b.w("FR1:96", b.top("K1A"))
        b.p("K1", "coil", 18, y + 7)
        b.w(b.bot("SQ1"), "K1:A1"); b.w(b.bot("K1A"), "K1:A1"); b.w("K1:A2", "PS1:N")
        return b.doc
    }

    private fun autoPump(): Document {
        val b = B("auto-pump")
        b.p("PS1", "src3", 2, 2)
        b.feeder(1, 2)
        val y = b.chain(16, 8, listOf(Item("F1", "mcb1"), Item("S0", "pb_nc"), Item("SQ2", "limit_nc")))
        b.w("PS1:L1", b.top("F1")); b.w(b.bot("SQ2"), "FR1:95")
        b.p("SA1", "sel3", 16, y)
        b.w("FR1:96", "SA1:13"); b.w("FR1:96", "SA1:23")
        // دستی: شستی جاگ فقط هنگام فشردن
        b.p("S1", "pb_no", 16, y + 6)
        b.w("SA1:14", b.top("S1"))
        // اتوماتیک: شناور سطح پایین + خودنگهدار
        b.p("SQ1", "limit_no", 22, y + 6); b.p("K1A", "aux_no", 26, y + 6, "coil" to "K1")
        b.w("SA1:24", b.top("SQ1")); b.w("SA1:24", b.top("K1A"))
        b.p("K1", "coil", 20, y + 13)
        b.w(b.bot("S1"), "K1:A1"); b.w(b.bot("SQ1"), "K1:A1"); b.w(b.bot("K1A"), "K1:A1")
        b.w("K1:A2", "PS1:N")
        return b.doc
    }

    val all: List<Preset> by lazy {
        listOf(
            Preset("lamp", "روشن و خاموش کردن لامپ", "Lamp on/off",
                "کلید سلکتوری ۲ حالته و چراغ سیگنال. در حالت اجرا روی کلید بزنید.",
                "2-position selector and a signal lamp. Tap the selector in run mode.", lampOnOff()),
            Preset("startstop", "استارت و استپ (جاگ)", "Start / stop (jog)",
                "کنتاکتور فقط تا وقتی شستی استارت نگه داشته شود وصل است.",
                "The contactor is on only while START is held.", jog()),
            Preset("selfhold", "خودنگهدار کنتاکتور", "Contactor self-holding",
                "تیغه‌ی کمکی NO موازی استارت. چراغ سبز=روشن، قرمز=خاموش.",
                "NO auxiliary contact in parallel with START. Green = on, red = off.", selfHold()),
            Preset("dol", "راه‌اندازی مستقیم موتور سه‌فاز", "3-phase DOL starter",
                "مدار قدرت و فرمان کامل با رله حرارتی.", "Complete power and control with thermal relay.", dol(false)),
            Preset("fwdrev", "چپ‌گرد و راست‌گرد", "Forward / reverse",
                "جابه‌جایی دو فاز و قفل الکتریکی با تیغه‌های NC.", "Two phases swapped; electrical interlock with NC contacts.", fwdRev(false)),
            Preset("stardelta", "ستاره مثلث", "Star-delta",
                "سه کنتاکتور و تایمر ستاره-مثلث با زمان مرده و قفل متقابل.", "Three contactors, star-delta timer with dead time and mutual interlock.", starDelta()),
            Preset("sequence", "دو موتور با ترتیب مشخص", "Two motors in sequence",
                "موتور ۲ فقط وقتی موتور ۱ روشن است استارت می‌شود.", "Motor 2 can only start while motor 1 runs.", sequence()),
            Preset("timedseq", "یکی پس از دیگری (تایمردار)", "One after another (timed)",
                "موتور ۲ پنج ثانیه پس از موتور ۱ به‌صورت خودکار روشن می‌شود.", "Motor 2 starts automatically 5 s after motor 1.", timedSequence()),
            Preset("fwdrev-es", "چپ‌گرد/راست‌گرد با توقف اضطراری", "Forward / reverse with E-stop",
                "شستی قارچی قفل‌شونده؛ برای آزاد کردن دوباره لمس کنید.", "Latching mushroom button; tap again to release.", fwdRev(true)),
            Preset("motor-timer", "کنترل موتور با تایمر", "Timed motor run",
                "موتور پس از ۵ ثانیه خودکار خاموش می‌شود.", "Motor stops automatically after 5 s.", motorTimer()),
            Preset("thermal", "موتور با رله حرارتی", "Motor with thermal relay",
                "ضریب بار موتور را در ویژگی‌ها به ۱٫۵ برسانید تا رله تریپ کند؛ چراغ قرمز با تیغه‌ی 97-98.",
                "Set the motor load factor to 1.5 to trip the relay; red lamp on contact 97-98.", dol(true)),
            Preset("tank", "کنترل سطح مخزن", "Tank level control",
                "SQ1 سطح پایین (روشن شدن پمپ)، SQ2 سطح بالا (خاموش شدن). با لمس وضعیتشان را عوض کنید.",
                "SQ1 low level starts the pump, SQ2 high level stops it. Tap to toggle them.", tankLevel()),
            Preset("autopump", "فرمان پمپ آب اتوماتیک", "Automatic water pump",
                "سلکتور ۳ حالته: چپ=دستی (جاگ)، وسط=خاموش، راست=اتوماتیک با شناور.",
                "3-position selector: left = manual jog, middle = off, right = automatic with float.", autoPump()),
        )
    }

    fun get(id: String): Preset? = all.firstOrNull { it.id == id }
}
