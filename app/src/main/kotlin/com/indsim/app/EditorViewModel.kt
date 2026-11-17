package com.indsim.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.indsim.app.render.WORLD
import com.indsim.canvas.Camera
import com.indsim.canvas.Grid
import com.indsim.editor.Compiler
import com.indsim.editor.Document
import com.indsim.editor.Edit
import com.indsim.editor.Geo
import com.indsim.editor.GridPt
import com.indsim.editor.Hit
import com.indsim.editor.History
import com.indsim.editor.Interact
import com.indsim.editor.Presets
import com.indsim.editor.ProjectStore
import com.indsim.editor.Registry
import com.indsim.engine.Circuit
import com.indsim.engine.Component
import com.indsim.engine.EventType
import com.indsim.engine.Simulator
import com.indsim.engine.components.Motor1Ph
import com.indsim.engine.components.Motor3Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Mode { DESIGN, RUN, INSPECT }
enum class Tool { SELECT, WIRE, JUNCTION, ERASE }

private enum class Drag { NONE, PAN, MOVE, WIRE }

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(File(app.filesDir, "projects"))
    private val history: History<Document>

    var doc by mutableStateOf(Document("Project 1")); private set
    var histVersion by mutableIntStateOf(0); private set

    var camera by mutableStateOf(Camera(0.8, 20.0, 20.0)); private set
    var viewW = 0.0; var viewH = 0.0

    var mode by mutableStateOf(Mode.DESIGN); private set
    var tool by mutableStateOf(Tool.SELECT)
    var realistic by mutableStateOf(false)
    var multiSelect by mutableStateOf(false)
    var selection by mutableStateOf(setOf<String>()); private set
    var selectedWire by mutableStateOf<Int?>(null); private set
    var lang by mutableStateOf(if (Locale.getDefault().language == "fa") "fa" else "en")

    var wireStart by mutableStateOf<String?>(null); private set
    var rubber by mutableStateOf<Offset?>(null); private set
    var snapPin by mutableStateOf<String?>(null); private set

    var message by mutableStateOf<String?>(null)
    val alerts = mutableStateListOf<String>()

    // ---- شبیه‌سازی ----
    var sim: Simulator? = null; private set
    var circuit: Circuit? = null; private set
    var tick by mutableIntStateOf(0); private set
    var wireStates: IntArray? = null; private set
    val spins = HashMap<String, Float>()
    var inspectId by mutableStateOf<String?>(null); private set
    var inspectPin by mutableStateOf<String?>(null); private set
    private var eventsSeen = 0
    private var held: Component? = null
    private var lastNanos = 0L

    // ---- ژست ----
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var dragBase: Document? = null
    private var wireFrom: String? = null
    private var downComp: String? = null
    private var selectedAtDown = false
    private var movedDuringDrag = false

    init {
        val last = store.lastName()?.let { store.load(it) }
        val initial = last ?: Document("Project 1")
        history = History(initial)
        doc = initial
    }

    fun t(fa: String, en: String) = if (lang == "fa") fa else en
    fun nameOf(type: String): String = Registry.get(type).let { if (lang == "fa") it.fa else it.en }

    // ---------------- سند، Undo، ذخیره ----------------

    private fun commit(next: Document) {
        history.commit(next)
        doc = history.current
        histVersion++
        autosave()
    }

    private fun autosave() {
        val d = doc
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(d); store.setLast(d.name) }
        }
    }

    val canUndo: Boolean get() { histVersion; return history.canUndo }
    val canRedo: Boolean get() { histVersion; return history.canRedo }

    fun undo() { if (mode != Mode.DESIGN) return; doc = history.undo(); histVersion++; clearSel(); autosave() }
    fun redo() { if (mode != Mode.DESIGN) return; doc = history.redo(); histVersion++; clearSel(); autosave() }

    private fun clearSel() { selection = emptySet(); selectedWire = null; wireStart = null }

    private fun loadDoc(d: Document) {
        stopRun()
        history.reset(d)
        doc = d
        histVersion++
        clearSel()
        autosave()
        fit()
    }

    fun newProject() {
        var n = 1
        val existing = store.list().toSet()
        while ("Project $n" in existing) n++
        loadDoc(Document("Project $n"))
    }

    fun openProject(name: String) {
        val d = store.load(name)
        if (d == null) message = t("باز کردن پروژه ممکن نشد", "Could not open the project") else loadDoc(d)
    }

    fun deleteProject(name: String) { store.delete(name); histVersion++ }
    fun projectNames(): List<String> { histVersion; return store.list() }

    fun openPreset(id: String) {
        val p = Presets.get(id) ?: return
        var name = if (lang == "fa") p.fa else p.en
        var n = 2
        val existing = store.list().toSet()
        while (ProjectStore.safeName(name) in existing) { name = (if (lang == "fa") p.fa else p.en) + " $n"; n++ }
        loadDoc(p.doc.copy(name = name))
    }

    fun saveAs(name: String) {
        val clean = name.trim().ifEmpty { return }
        stopRun()
        history.commit(doc.copy(name = clean))
        doc = history.current
        histVersion++
        autosave()
        message = t("ذخیره شد: $clean", "Saved: $clean")
    }

    // ---------------- نما ----------------

    fun fit() {
        val b = Geo.contentBounds(doc)
        if (b == null || viewW <= 0.0) { camera = Camera(0.8, 20.0, 20.0).clamped(WORLD, viewW, viewH); return }
        val margin = 3
        val w = (b.maxX - b.minX + 2 * margin) * Grid.BASE_STEP
        val h = (b.maxY - b.minY + 2 * margin) * Grid.BASE_STEP
        val s = min(viewW / w, viewH / h).coerceIn(Camera.MIN_SCALE, 1.6)
        val ox = -((b.minX - margin) * Grid.BASE_STEP) * s + (viewW - w * s) / 2.0
        val oy = -((b.minY - margin) * Grid.BASE_STEP) * s + (viewH - h * s) / 2.0
        camera = Camera(s, ox, oy).clamped(WORLD, viewW, viewH)
    }

    fun transform(cx: Float, cy: Float, panX: Float, panY: Float, zoom: Float) {
        camera = camera.zoomAt(zoom.toDouble(), cx.toDouble(), cy.toDouble())
            .panBy(panX.toDouble(), panY.toDouble()).clamped(WORLD, viewW, viewH)
    }

    private fun gx(sx: Float) = camera.toWorldX(sx.toDouble()) / Grid.BASE_STEP
    private fun gy(sy: Float) = camera.toWorldY(sy.toDouble()) / Grid.BASE_STEP
    private fun pinRadius() = (44.0 / (camera.scale * Grid.BASE_STEP)).coerceIn(0.7, 3.0)

    // ---------------- ویرایش ----------------

    fun addComponent(type: String) {
        if (mode != Mode.DESIGN) return
        val x = gx((viewW / 2).toFloat()).roundToInt().coerceIn(1, 240)
        val y = gy((viewH / 2).toFloat()).roundToInt().coerceIn(1, 165)
        val r = Edit.add(doc, type, x, y)
        if (!r.ok) { message = r.error; return }
        commit(r.doc)
        selection = setOf(r.doc.placed.last().id)
        selectedWire = null
        tool = Tool.SELECT
    }

    fun deleteSelection() {
        if (mode != Mode.DESIGN) return
        var d = doc
        val sw = selectedWire
        if (sw != null) d = Edit.deleteWire(d, sw)
        if (selection.isNotEmpty()) d = Edit.delete(d, selection)
        if (d != doc) commit(d)
        clearSel()
    }

    fun rotateSelection() {
        if (mode != Mode.DESIGN || selection.isEmpty()) return
        commit(Edit.rotate(doc, selection))
    }

    fun selectTool(t: Tool) {
        tool = t
        wireStart = null; rubber = null; snapPin = null
    }

    /** چند ویژگی را یکجا و با یک گام Undo اعمال می‌کند. */
    fun setParams(id: String, values: Map<String, String>) {
        if (mode != Mode.DESIGN) return
        var d = doc
        for ((k, v) in values) d = Edit.setParam(d, id, k, v)
        if (d != doc) commit(d)
    }

    fun setParam(id: String, key: String, value: String) {
        if (mode != Mode.DESIGN) return
        commit(Edit.setParam(doc, id, key, value))
    }

    fun setWireColor(color: Int?) {
        val i = selectedWire ?: return
        commit(Edit.setWireColor(doc, i, color))
    }

    // ---------------- اجرا و بازرسی ----------------

    fun startRun(inspect: Boolean) {
        if (mode == Mode.DESIGN) {
            val c = Compiler.compile(doc)
            val circ = c.circuit
            if (circ == null) {
                message = t("خطا در مدار: ", "Circuit error: ") + c.errors.joinToString("; ")
                return
            }
            try {
                sim = Simulator(circ)
            } catch (e: Exception) {
                message = t("شبیه‌سازی آغاز نشد: ", "Could not start: ") + (e.message ?: "")
                return
            }
            circuit = circ
            alerts.clear(); spins.clear(); eventsSeen = 0
            lastNanos = 0L
            wireStates = IntArray(doc.wires.size)
            clearSel()
        }
        mode = if (inspect) Mode.INSPECT else Mode.RUN
        if (!inspect) { inspectId = null; inspectPin = null }
    }

    fun stopRun() {
        held?.let { Interact.up(it) }
        held = null
        sim = null; circuit = null; wireStates = null
        mode = Mode.DESIGN
        inspectId = null; inspectPin = null
        tick++
    }

    fun resetRun() {
        if (mode == Mode.DESIGN) return
        val inspect = mode == Mode.INSPECT
        stopRun()
        startRun(inspect)
    }

    /** یک گام زمان واقعی؛ از حلقه‌ی رابط هر ~۲۰ میلی‌ثانیه صدا زده می‌شود. */
    fun simFrame() {
        val s = sim ?: return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.02 else ((now - lastNanos) / 1e9).coerceIn(0.0, 0.1)
        lastNanos = now
        try {
            s.run(dt, 0.02)
        } catch (e: Exception) {
            message = t("خطای شبیه‌سازی: ", "Simulation error: ") + (e.message ?: "")
            stopRun()
            return
        }
        for (c in s.circuit.components) {
            val om = when (c) { is Motor3Ph -> c.omega; is Motor1Ph -> c.omega; else -> continue }
            spins[c.id] = ((spins[c.id] ?: 0f) + (om * 720.0 * dt).toFloat()) % 360f
        }
        val ws = wireStates ?: IntArray(doc.wires.size).also { wireStates = it }
        doc.wires.forEachIndexed { i, w ->
            ws[i] = if (s.isLive(w.a)) 1 else if (s.netCurrent(w.a) > 0.002) 2 else 0
        }
        while (eventsSeen < s.events.size) {
            val e = s.events[eventsSeen++]
            val text = when (e.type) {
                EventType.SHORT_CIRCUIT -> t("⚠ اتصال کوتاه در منبع ${e.componentId}", "⚠ Short circuit on ${e.componentId}")
                EventType.PROTECTION_TRIP -> t("⚡ ${e.componentId} قطع شد", "⚡ ${e.componentId} tripped")
                EventType.THERMAL_TRIP -> t("🔥 اضافه‌بار: رله ${e.componentId} تریپ کرد", "🔥 Overload: ${e.componentId} tripped")
                EventType.OVERVOLTAGE -> t("⚠ ولتاژ بیش از حد روی ${e.componentId}", "⚠ Overvoltage on ${e.componentId}")
                EventType.PHASE_LOSS -> t("⚠ قطع فاز / نامتقارن روی ${e.componentId}", "⚠ Phase loss on ${e.componentId}")
            }
            alerts.add(0, text)
            while (alerts.size > 4) alerts.removeAt(alerts.size - 1)
        }
        tick++
    }

    fun inspectLines(): List<String> {
        tick
        val s = sim ?: return emptyList()
        val out = ArrayList<String>()
        val pin = inspectPin
        if (pin != null) {
            val v = s.acVoltage(pin)
            out += "$pin: ${"%.1f".format(s.voltage(pin))} V" +
                (if (v.abs > 1.0) "  ∠ ${"%.0f".format(v.angleDeg)}°" else "") +
                (if (s.dcVoltage(pin) > 0.5) "  DC ${"%.1f".format(s.dcVoltage(pin))} V" else "")
            out += if (s.isLive(pin)) t("برق‌دار", "Live") else t("بدون ولتاژ", "Dead")
            out += t("جریان شبکه: ", "Net current: ") + "${"%.2f".format(s.netCurrent(pin))} A"
        }
        val id = inspectId
        val p = id?.let { doc.find(it) }
        if (p != null) {
            out += "${p.id} — ${nameOf(p.type)}"
            for (pn in Registry.get(p.type).pins) out += "  ${pn.name}: ${"%.1f".format(s.voltage("${p.id}:${pn.name}"))} V"
            s.warnings[p.id]?.let { out += "⚠ $it" }
            when (val c = circuit?.component(p.id)) {
                is com.indsim.engine.components.CoilLike -> out += t("بوبین: ", "Coil: ") + (if (c.energized) t("تحریک", "energized") else t("آزاد", "released"))
                is Motor3Ph -> out += t("دور نسبی ", "Speed ") + "${"%.2f".format(c.omega)}  " + (if (c.omega > 0.05) "↻" else if (c.omega < -0.05) "↺" else "")
                else -> {}
            }
        }
        if (out.isEmpty()) out += t("روی یک ترمینال یا قطعه بزنید", "Tap a terminal or a component")
        return out
    }

    // ---------------- ژست‌ها ----------------

    fun onDown(sx: Float, sy: Float) {
        downX = sx; downY = sy
        movedDuringDrag = false
        dragBase = null; wireFrom = null; downComp = null
        drag = Drag.PAN
        val x = gx(sx); val y = gy(sy)
        when (mode) {
            Mode.DESIGN -> when (tool) {
                Tool.SELECT -> {
                    val c = Hit.componentAt(doc, x, y)
                    if (c != null) {
                        downComp = c.id
                        selectedAtDown = c.id in selection
                        selectedWire = null
                        if (!multiSelect && c.id !in selection) selection = setOf(c.id)
                        if (multiSelect && c.id !in selection) selection = selection + c.id
                        drag = Drag.MOVE
                        dragBase = doc
                    } else {
                        val w = Hit.wireAt(doc, x, y, 0.6)
                        selectedWire = w?.index
                        if (!multiSelect) selection = emptySet()
                    }
                }
                Tool.WIRE -> {
                    val pin = Hit.pinAt(doc, x, y, pinRadius())
                    if (pin != null) {
                        val pending = wireStart
                        if (pending != null && pending != pin) {
                            val r = Edit.connect(doc, pending, pin)
                            if (r.ok) commit(r.doc) else message = wireError(r.error)
                            wireStart = null; rubber = null; snapPin = null
                            drag = Drag.NONE
                        } else {
                            wireFrom = pin; wireStart = pin; drag = Drag.WIRE
                            rubber = Offset(x.toFloat(), y.toFloat())
                        }
                    } else {
                        wireStart = null
                    }
                }
                Tool.JUNCTION, Tool.ERASE -> {}
            }
            Mode.RUN -> {
                val c = Hit.componentAt(doc, x, y)
                if (c != null) {
                    val comp = circuit?.component(c.id)
                    if (comp != null) {
                        val m = Interact.down(comp)
                        if (m != null) message = t("رله هنوز داغ است؛ کمی صبر کنید", "Relay is still hot; wait a moment")
                        held = comp
                        drag = Drag.NONE
                    }
                }
            }
            Mode.INSPECT -> {
                val pin = Hit.pinAt(doc, x, y, pinRadius())
                if (pin != null) { inspectPin = pin; inspectId = null }
                else {
                    val c = Hit.componentAt(doc, x, y)
                    if (c != null) { inspectId = c.id; inspectPin = null }
                }
            }
        }
    }

    private fun wireError(e: String?) = when (e) {
        "already connected" -> t("این دو ترمینال قبلاً وصل شده‌اند", "These terminals are already connected")
        "same pin" -> t("یک ترمینال را نمی‌توان به خودش وصل کرد", "A terminal cannot connect to itself")
        else -> e
    }

    fun onCancelDrag() {
        if (drag == Drag.MOVE) dragBase?.let { doc = it }
        drag = Drag.NONE
        rubber = null; snapPin = null
        if (tool == Tool.WIRE && wireFrom != null) wireStart = null
    }

    fun onDrag(sx: Float, sy: Float, dx: Float, dy: Float) {
        movedDuringDrag = true
        when (drag) {
            Drag.PAN -> transform(sx, sy, dx, dy, 1f)
            Drag.MOVE -> {
                val base = dragBase ?: return
                val ids = if (selection.isNotEmpty()) selection else (downComp?.let { setOf(it) } ?: return)
                val step = camera.scale * Grid.BASE_STEP
                var mx = ((sx - downX) / step).roundToInt()
                var my = ((sy - downY) / step).roundToInt()
                val boxes = base.placed.filter { it.id in ids }.map { Geo.bounds(it) }
                if (boxes.isNotEmpty()) {
                    mx = mx.coerceIn(-boxes.minOf { it.minX } + 1, 248 - boxes.maxOf { it.maxX })
                    my = my.coerceIn(-boxes.minOf { it.minY } + 1, 173 - boxes.maxOf { it.maxY })
                }
                doc = Edit.move(base, ids, mx, my)
            }
            Drag.WIRE -> {
                val x = gx(sx); val y = gy(sy)
                rubber = Offset(x.toFloat(), y.toFloat())
                val p = Hit.pinAt(doc, x, y, pinRadius())
                snapPin = if (p != null && p != wireFrom) p else null
            }
            Drag.NONE -> {}
        }
    }

    fun onUp(wasTap: Boolean, sx: Float, sy: Float) {
        when (mode) {
            Mode.RUN -> { held?.let { Interact.up(it) }; held = null }
            Mode.INSPECT -> {}
            Mode.DESIGN -> when (drag) {
                Drag.MOVE -> {
                    val base = dragBase
                    if (base != null && doc != base) { val moved = doc; doc = base; commit(moved) }
                    if (wasTap && multiSelect) {
                        val id = downComp
                        if (id != null && selectedAtDown) selection = selection - id
                    }
                }
                Drag.WIRE -> {
                    val from = wireFrom
                    val target = snapPin
                    if (from != null && target != null) {
                        val r = Edit.connect(doc, from, target)
                        if (r.ok) commit(r.doc) else message = wireError(r.error)
                        wireStart = null
                    } else if (!movedDuringDrag) {
                        wireStart = from   // لمس ساده: منتظر ترمینال دوم می‌ماند
                    } else wireStart = null
                    rubber = null; snapPin = null
                }
                Drag.PAN, Drag.NONE -> if (wasTap) designTap(sx, sy)
            }
        }
        drag = Drag.NONE
    }

    private fun designTap(sx: Float, sy: Float) {
        val x = gx(sx); val y = gy(sy)
        when (tool) {
            Tool.JUNCTION -> {
                val w = Hit.wireAt(doc, x, y, 0.8)
                if (w == null) { message = t("روی یک سیم بزنید", "Tap on a wire"); return }
                val r = Edit.splitWire(doc, w.index, w.point)
                if (r.ok) {
                    commit(r.doc)
                    wireStart = "${r.doc.placed.last().id}:J"
                    tool = Tool.WIRE
                    message = t("حالا ترمینال مقصد را لمس کنید", "Now tap the destination terminal")
                } else message = r.error
            }
            Tool.ERASE -> {
                val c = Hit.componentAt(doc, x, y)
                if (c != null) { commit(Edit.delete(doc, setOf(c.id))); return }
                val w = Hit.wireAt(doc, x, y, 0.8)
                if (w != null) commit(Edit.deleteWire(doc, w.index))
            }
            else -> {}
        }
    }

    /** برای خروجی: هر قطعه‌ی منفرد انتخاب‌شده. */
    val singleSelected: String? get() = selection.singleOrNull()
}
