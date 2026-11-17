package com.indsim.editor

import java.util.ArrayDeque

data class EditResult(val doc: Document, val error: String? = null) {
    val ok: Boolean get() = error == null
}

/** همه‌ی ویرایش‌ها تابع خالص هستند: سند ورودی را تغییر نمی‌دهند و سند جدید می‌دهند. */
object Edit {
    fun nextId(doc: Document, prefix: String): String {
        var n = 1
        val used = doc.placed.map { it.id }.toSet()
        val width = if (prefix == "SB") 2 else 1
        fun tag(value: Int) = prefix + value.toString().padStart(width, '0')
        while (tag(n) in used) n++
        return tag(n)
    }

    /** با شناسه‌ی دلخواه (برای مدارهای آماده). */
    fun addWithId(doc: Document, id: String, type: String, x: Int, y: Int, params: Map<String, String> = emptyMap(), rot: Int = 0): EditResult {
        val def = Registry.find(type) ?: return EditResult(doc, "unknown type $type")
        if (id.isBlank() || ':' in id) return EditResult(doc, "invalid id '$id'")
        if (doc.find(id) != null) return EditResult(doc, "duplicate id '$id'")
        var p = def.defaults + params
        if (def.needsCoil && p["coil"].isNullOrBlank()) {
            val lastCoil = doc.placed.lastOrNull { Registry.get(it.type).isCoilLike }
            if (lastCoil != null) p = p + ("coil" to lastCoil.id)
        }
        return EditResult(doc.copy(placed = doc.placed + Placed(id, type, x, y, rot, p)))
    }

    fun add(doc: Document, type: String, x: Int, y: Int): EditResult {
        val def = Registry.find(type) ?: return EditResult(doc, "unknown type $type")
        return addWithId(doc, nextId(doc, def.prefix), type, x, y)
    }

    fun move(doc: Document, ids: Set<String>, dx: Int, dy: Int): Document =
        doc.copy(placed = doc.placed.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it })

    fun rotate(doc: Document, ids: Set<String>): Document =
        doc.copy(placed = doc.placed.map { if (it.id in ids) it.copy(rot = (it.rot + 90) % 360) else it })

    fun setParam(doc: Document, id: String, key: String, value: String): Document =
        doc.copy(placed = doc.placed.map { if (it.id == id) it.copy(params = it.params + (key to value)) else it })

    private fun pinOwner(pin: String) = pin.substringBefore(':')

    /** حذف قطعه‌ها و سیم‌های وابسته؛ انشعاب‌هایی که هیچ سیمی ندارند هم پاک می‌شوند. */
    fun delete(doc: Document, ids: Set<String>): Document {
        val wires = doc.wires.filter { pinOwner(it.a) !in ids && pinOwner(it.b) !in ids }
        val used = wires.flatMap { listOf(pinOwner(it.a), pinOwner(it.b)) }.toSet()
        val placed = doc.placed.filter { p ->
            p.id !in ids && !(Registry.get(p.type).kind == Kind.JUNCTION && p.id !in used)
        }
        return doc.copy(placed = placed, wires = wires)
    }

    fun deleteWire(doc: Document, index: Int): Document {
        if (index !in doc.wires.indices) return doc
        val wires = doc.wires.toMutableList().also { it.removeAt(index) }
        val used = wires.flatMap { listOf(pinOwner(it.a), pinOwner(it.b)) }.toSet()
        val placed = doc.placed.filter { !(Registry.get(it.type).kind == Kind.JUNCTION && it.id !in used) }
        return doc.copy(placed = placed, wires = wires)
    }

    fun connect(doc: Document, a: String, b: String): EditResult {
        val pa = Geo.pinPos(doc, a) ?: return EditResult(doc, "unknown pin $a")
        val pb = Geo.pinPos(doc, b) ?: return EditResult(doc, "unknown pin $b")
        if (a == b) return EditResult(doc, "same pin")
        if (doc.wires.any { (it.a == a && it.b == b) || (it.a == b && it.b == a) }) return EditResult(doc, "already connected")
        val lane = Router.bestLane(doc, pa, pb, a, b)
        return EditResult(doc.copy(wires = doc.wires + Wire(a, b, lane)))
    }

    fun setWireColor(doc: Document, index: Int, color: Int?): Document =
        doc.copy(wires = doc.wires.mapIndexed { i, w -> if (i == index) w.copy(color = color) else w })

    /** انشعاب: سیم شماره‌ی index را در نقطه‌ی at می‌شکند و یک Junction می‌گذارد. */
    fun splitWire(doc: Document, index: Int, at: GridPt): EditResult {
        val w = doc.wires.getOrNull(index) ?: return EditResult(doc, "no such wire")
        val id = nextId(doc, "J")
        val added = addWithId(doc, id, "junction", at.x, at.y)
        if (!added.ok) return added
        var d = added.doc.copy(wires = added.doc.wires.toMutableList().also { it.removeAt(index) })
        val jp = "$id:J"
        val r1 = connect(d, w.a, jp)
        if (!r1.ok) return r1
        d = r1.doc
        val r2 = connect(d, jp, w.b)
        if (!r2.ok) return r2
        return r2
    }
}

/** تاریخچه‌ی Undo/Redo. */
class History<T>(initial: T, private val limit: Int = 200) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun commit(next: T) {
        if (next == current) return
        past.addLast(current)
        if (past.size > limit) past.removeFirst()
        current = next
        future.clear()
    }

    fun undo(): T {
        if (past.isNotEmpty()) { future.addLast(current); current = past.removeLast() }
        return current
    }

    fun redo(): T {
        if (future.isNotEmpty()) { past.addLast(current); current = future.removeLast() }
        return current
    }

    fun reset(doc: T) { past.clear(); future.clear(); current = doc }
}
