package com.indsim.engine

/** پین به شکل "K1:A1" = شناسه‌ی قطعه : نام پین. */
typealias Pin = String

enum class Domain { AC, DC }

sealed interface Branch

/** مقاومت بین دو پین (تیغه‌ی بسته، بوبین، لامپ، سیم‌پیچ…). */
data class Impedance(val a: Pin, val b: Pin, val ohms: Double) : Branch {
    init { require(ohms > 0.0) { "ohms must be > 0 ($a-$b)" } }
}

/** منبع تونن: emf سری با rs به زمین مرجع. emf فقط در حوزه‌ی [domain] اثر دارد. */
data class SourceBranch(
    val pin: Pin,
    val domain: Domain,
    val emf: Complex,
    val rs: Double,
    val ownerId: String,
) : Branch

enum class EventType { SHORT_CIRCUIT, PROTECTION_TRIP, THERMAL_TRIP, OVERVOLTAGE, PHASE_LOSS }

data class SimEvent(val time: Double, val type: EventType, val componentId: String, val message: String)

/** هر قطعه‌ی الکتریکی. شاخه‌های الکتریکی را از روی وضعیت داخلی می‌دهد و در هر گام وضعیت را به‌روز می‌کند. */
abstract class Component(val id: String) {
    init { require(id.isNotBlank() && ':' !in id) { "invalid component id '$id'" } }

    abstract val pinNames: List<String>

    fun pin(name: String): Pin {
        require(name in pinNames) { "$id has no pin '$name'" }
        return "$id:$name"
    }

    /** شاخه‌های الکتریکی فعلی (تابعی از وضعیت داخلی). */
    abstract fun branches(): List<Branch>

    /** اتصال به قطعات دیگر (مثلاً تیغه به بوبین). قبل از اولین حل صدا زده می‌شود. */
    open fun attach(circuit: Circuit) {}

    /** به‌روزرسانی وضعیت از روی ولتاژ/جریان حل‌شده. */
    open fun step(dt: Double, view: SolutionView) {}

    /** هشدار قابل نمایش (مثلاً اضافه‌ولتاژ)؛ null یعنی سالم. */
    open val warning: String? get() = null
}

/** هر چیزی که تیغه‌ها را می‌راند (بوبین کنتاکتور، رله، تایمر). */
interface CoilLike {
    val energized: Boolean
}

class Circuit {
    private val comps = LinkedHashMap<String, Component>()
    private val wireList = ArrayList<Pair<Pin, Pin>>()

    val components: List<Component> get() = comps.values.toList()
    val wires: List<Pair<Pin, Pin>> get() = wireList

    fun <T : Component> add(c: T): T {
        require(c.id !in comps) { "duplicate component id '${c.id}'" }
        comps[c.id] = c
        return c
    }

    fun component(id: String): Component = comps[id] ?: error("unknown component '$id'")

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> get(id: String): T = component(id) as T

    fun hasPin(pin: Pin): Boolean {
        val i = pin.indexOf(':')
        if (i <= 0) return false
        val c = comps[pin.substring(0, i)] ?: return false
        return pin.substring(i + 1) in c.pinNames
    }

    /** سیم: اتصال الکتریکی دو پین. */
    fun connect(a: Pin, b: Pin) {
        require(hasPin(a)) { "unknown pin '$a'" }
        require(hasPin(b)) { "unknown pin '$b'" }
        wireList += a to b
    }

    fun disconnect(a: Pin, b: Pin): Boolean = wireList.remove(a to b) || wireList.remove(b to a)

    fun allPins(): List<Pin> = comps.values.flatMap { c -> c.pinNames.map { c.pin(it) } }

    fun buildNets(): NetMap {
        val pins = allPins()
        val index = HashMap<Pin, Int>(pins.size * 2)
        pins.forEachIndexed { i, p -> index[p] = i }
        val parent = IntArray(pins.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        for ((a, b) in wireList) {
            val ra = find(index.getValue(a)); val rb = find(index.getValue(b))
            if (ra != rb) parent[ra] = rb
        }
        val netId = HashMap<Int, Int>()
        val result = HashMap<Pin, Int>(pins.size * 2)
        for (p in pins) {
            val root = find(index.getValue(p))
            result[p] = netId.getOrPut(root) { netId.size }
        }
        return NetMap(result, netId.size)
    }
}

class NetMap(private val idx: Map<Pin, Int>, val count: Int) {
    fun netOf(pin: Pin): Int = idx[pin] ?: error("unknown pin '$pin'")
    fun sameNet(a: Pin, b: Pin) = netOf(a) == netOf(b)
}
