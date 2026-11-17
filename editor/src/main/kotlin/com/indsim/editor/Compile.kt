package com.indsim.editor

import com.indsim.engine.Circuit
import com.indsim.engine.Component
import com.indsim.engine.components.DualPushButton
import com.indsim.engine.components.ProtectiveDevice
import com.indsim.engine.components.PushButton
import com.indsim.engine.components.Selector
import com.indsim.engine.components.Source
import com.indsim.engine.components.ThermalRelay
import com.indsim.engine.components.ToggleSwitch

class Compiled(val circuit: Circuit?, val errors: List<String>)

object Compiler {
    /** سند را به مدار موتور شبیه‌سازی تبدیل می‌کند؛ پیش از آن، خطاهای قابل‌گزارش به کاربر را بررسی می‌کند. */
    fun compile(doc: Document): Compiled {
        val errors = ArrayList<String>()
        val coilIds = doc.placed.filter { Registry.get(it.type).isCoilLike }.map { it.id }.toSet()
        for (p in doc.placed) {
            val def = Registry.get(p.type)
            if (def.needsCoil) {
                val c = p.params["coil"].orEmpty()
                if (c.isBlank()) errors += "${p.id}: no coil linked"
                else if (c !in coilIds) errors += "${p.id}: linked coil '$c' not found"
            }
        }
        for (w in doc.wires) {
            if (Geo.pinPos(doc, w.a) == null) errors += "wire ${w.a}-${w.b}: unknown pin ${w.a}"
            if (Geo.pinPos(doc, w.b) == null) errors += "wire ${w.a}-${w.b}: unknown pin ${w.b}"
        }
        if (errors.isNotEmpty()) return Compiled(null, errors)

        val c = Circuit()
        for (p in doc.placed) {
            val def = Registry.get(p.type)
            c.add(def.build(p.id, def.defaults + p.params))
        }
        for (w in doc.wires) c.connect(w.a, w.b)
        return Compiled(c, emptyList())
    }
}

/** اثر لمس روی قطعه در حالت شبیه‌سازی. */
object Interact {
    /** پیام (در صورت نیاز) برمی‌گرداند. */
    fun down(c: Component): String? {
        when (c) {
            is PushButton -> if (c.latching) { if (c.pressed) c.unlatch() else c.press() } else c.press()
            is DualPushButton -> c.press()
            is ToggleSwitch -> c.toggle()
            is Selector -> c.next()
            is ProtectiveDevice -> if (c.tripped) c.reset() else c.setClosed(!c.closed)
            is ThermalRelay -> if (!c.reset()) return "thermal relay is still hot"
            is Source -> c.on = !c.on
            else -> {}
        }
        return null
    }

    fun up(c: Component) {
        when (c) {
            is PushButton -> if (!c.latching) c.release()
            is DualPushButton -> c.release()
            else -> {}
        }
    }
}

/** رنگ پیش‌فرض سیم‌ها بر اساس خروجی منبعی که روی همان شبکه است. */
object NetColors {
    const val BROWN = 0xFF8B5A2B.toInt()
    const val BLACK = 0xFF111111.toInt()
    const val GREY = 0xFF8A8F98.toInt()
    const val BLUE = 0xFF1E6FD9.toInt()
    const val GREEN_YELLOW = 0xFF4CAF50.toInt()
    const val RED = 0xFFD32F2F.toInt()
    const val DEFAULT = 0xFF37474F.toInt()

    private fun colorOfPin(pin: String): Int? = when (pin.substringAfter(':')) {
        "L1" -> BROWN
        "L2" -> BLACK
        "L3" -> GREY
        "N" -> BLUE
        "PE" -> GREEN_YELLOW
        "+" -> RED
        "-" -> BLUE
        "L" -> BROWN
        else -> null
    }

    /** برای هر سیم یک رنگ؛ رنگ دستی کاربر اولویت دارد. */
    fun compute(doc: Document): List<Int> {
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while ((parent[r] ?: r) != r) r = parent[r]!!
            parent.putIfAbsent(x, x)
            return r
        }
        for (w in doc.wires) {
            val ra = find(w.a); val rb = find(w.b)
            if (ra != rb) parent[ra] = rb
        }
        // رنگ هر شبکه: از پین‌های منبع
        val netColor = HashMap<String, Int>()
        for (p in doc.placed) {
            val def = Registry.get(p.type)
            if (def.kind != Kind.SOURCE3 && def.kind != Kind.SOURCE1 && def.kind != Kind.SOURCE_DC) continue
            for (pin in def.pins) {
                val id = "${p.id}:${pin.name}"
                val col = colorOfPin(id) ?: continue
                netColor[find(id)] = col
            }
        }
        return doc.wires.map { w -> w.color ?: netColor[find(w.a)] ?: DEFAULT }
    }
}
