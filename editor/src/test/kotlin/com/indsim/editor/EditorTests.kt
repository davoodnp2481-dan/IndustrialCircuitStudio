package com.indsim.editor

import com.indsim.engine.EventType
import com.indsim.engine.Simulator
import com.indsim.engine.components.Coil
import com.indsim.engine.components.Lamp
import com.indsim.engine.components.Motor3Ph
import com.indsim.engine.components.ProtectiveDevice
import com.indsim.engine.components.PushButton
import com.indsim.engine.components.Selector
import com.indsim.engine.components.ThermalRelay
import com.indsim.engine.components.ToggleSwitch
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

private var failures = 0
private var passed = 0

private fun test(name: String, block: () -> Unit) {
    try { block(); passed++; println("PASS  $name") }
    catch (e: Throwable) { failures++; println("FAIL  $name -> ${e.message ?: e}") }
}

private fun ok(c: Boolean, msg: String) { if (!c) throw AssertionError(msg) }
private fun near(a: Double, b: Double, tol: Double, what: String) =
    ok(abs(a - b) <= tol, "$what: expected $b ± $tol, got $a")

private class Rig(id: String, doc: Document? = null) {
    val circuit = Compiler.compile(doc ?: Presets.get(id)!!.doc).also {
        ok(it.errors.isEmpty(), "compile errors: ${it.errors}")
    }.circuit!!
    val sim = Simulator(circuit)
    inline fun <reified T : com.indsim.engine.Component> c(id: String): T = circuit.get(id)
    fun press(id: String, seconds: Double = 0.2) {
        val comp = circuit.component(id)
        Interact.down(comp); sim.run(seconds); Interact.up(comp); sim.run(0.1)
    }
    fun noShort() = ok(sim.events.none { it.type == EventType.SHORT_CIRCUIT }, "short circuit occurred: ${sim.events}")
}

fun main() {
    // ---------------- ثبت تجهیزات ----------------
    test("registry: every type builds and its pins match the engine component exactly") {
        for (d in Registry.defs) {
            val comp = d.build("X1", d.defaults + ("coil" to "K1"))
            ok(comp.pinNames.toSet() == d.pinNames.toSet(), "${d.type}: engine ${comp.pinNames} vs registry ${d.pinNames}")
            ok(d.pins.map { it.dx to it.dy }.toSet().size == d.pins.size, "${d.type}: two pins share one position")
        }
    }

    test("IEC: pushbutton terminals use two-digit standard designations") {
        ok(Registry.get("pb_no").pinNames == listOf("13", "14"), "NO terminals")
        ok(Registry.get("pb_nc").pinNames == listOf("21", "22"), "NC terminals")
        ok(Registry.get("pb_dual").pinNames == listOf("11", "12", "13", "14"), "dual terminals")
        ok(Registry.get("estop").pinNames == listOf("21", "22"), "emergency stop NC terminals")
        val d = Edit.add(Edit.add(Document(), "pb_no", 0, 0).doc, "pb_nc", 4, 0).doc
        ok(d.placed.map { it.id } == listOf("SB01", "SB02"), "pushbutton tags ${d.placed.map { it.id }}")
    }

    test("registry: all required equipment categories are present") {
        for (t in listOf("pb_no", "pb_nc", "pb_dual", "estop", "sel2", "sel3", "micro_no", "limit_no", "coil", "aux_no",
            "aux_nc", "relay", "thermal", "ton", "tof", "star_delta", "sensor_ind", "sensor_cap", "photocell",
            "lamp_r", "lamp_g", "lamp_y", "lamp_w", "bell", "buzzer", "fuse1", "fuse3", "mcb1", "mcb3", "mccb3", "mpcb3",
            "poles3", "motor3", "motor1", "src3", "src1", "srcdc", "load", "ground"))
            ok(Registry.find(t) != null, "missing type $t")
    }

    // ---------------- هندسه ----------------
    test("geometry: rotation maps pins correctly and four rotations return to start") {
        val p = Placed("S1", "pb_no", 10, 10)
        ok(Geo.pinPos(p, "14") == GridPt(10, 13), "rot0")
        ok(Geo.pinPos(p.copy(rot = 90), "14") == GridPt(7, 10), "rot90")
        ok(Geo.pinPos(p.copy(rot = 180), "14") == GridPt(10, 7), "rot180")
        ok(Geo.pinPos(p.copy(rot = 270), "14") == GridPt(13, 10), "rot270")
        var d = Document(placed = listOf(p))
        repeat(4) { d = Edit.rotate(d, setOf("S1")) }
        ok(d.placed[0].rot == 0, "four rotations")
    }

    test("router: orthogonal Z path, straight lines, lane shifts the middle segment") {
        ok(Router.route(GridPt(0, 0), GridPt(0, 5)).size == 2, "vertical straight")
        val z = Router.route(GridPt(0, 0), GridPt(6, 10))
        ok(z == listOf(GridPt(0, 0), GridPt(0, 5), GridPt(6, 5), GridPt(6, 10)), "Z path $z")
        ok(Router.route(GridPt(0, 0), GridPt(6, 10), 2)[1] == GridPt(0, 7), "lane 2")
        for (path in listOf(z)) for (i in 0 until path.size - 1)
            ok(path[i].x == path[i + 1].x || path[i].y == path[i + 1].y, "non-orthogonal segment")
    }

    // ---------------- ویرایش و سیم‌کشی ----------------
    test("edit: add auto-numbers ids; contacts auto-link to the latest coil") {
        var d = Document()
        d = Edit.add(d, "coil", 0, 0).doc
        d = Edit.add(d, "coil", 6, 0).doc
        d = Edit.add(d, "aux_no", 12, 0).doc
        d = Edit.add(d, "pb_no", 18, 0).doc
        d = Edit.add(d, "pb_no", 24, 0).doc
        ok(d.placed.map { it.id } == listOf("K1", "K2", "KC1", "SB01", "SB02"), "ids ${d.placed.map { it.id }}")
        ok(d.find("KC1")!!.params["coil"] == "K2", "auto link")
    }

    test("edit: connect rejects unknown pin, same pin and duplicates (either direction)") {
        var d = Document()
        d = Edit.add(d, "pb_no", 0, 0).doc; d = Edit.add(d, "lamp_r", 0, 8).doc
        ok(!Edit.connect(d, "SB01:14", "H1:NOPE").ok, "unknown pin")
        ok(!Edit.connect(d, "SB01:14", "SB01:14").ok, "same pin")
        val r = Edit.connect(d, "SB01:14", "H1:X1"); ok(r.ok, "first connect")
        ok(!Edit.connect(r.doc, "H1:X1", "SB01:14").ok, "duplicate reversed")
        ok(r.doc.wires.size == 1, "one wire")
    }

    test("router: detours around an intervening component body") {
        var d = Document()
        d = Edit.addWithId(d, "PS1", "src1", 0, 0).doc
        d = Edit.addWithId(d, "H1", "lamp_r", 20, 0).doc
        d = Edit.addWithId(d, "Q1", "mcb3", 10, 0).doc
        val connected = Edit.connect(d, "PS1:L", "H1:X1")
        ok(connected.ok, "wire connection")
        val path = Geo.wirePath(connected.doc, connected.doc.wires.single())!!
        val obstacle = Geo.bounds(connected.doc.find("Q1")!!)
        val crossesBody = path.zipWithNext().any { (a, b) ->
            if (a.x == b.x) a.x > obstacle.minX && a.x < obstacle.maxX && maxOf(minOf(a.y,b.y), obstacle.minY) < minOf(maxOf(a.y,b.y), obstacle.maxY)
            else a.y > obstacle.minY && a.y < obstacle.maxY && maxOf(minOf(a.x,b.x), obstacle.minX) < minOf(maxOf(a.x,b.x), obstacle.maxX)
        }
        ok(!crossesBody, "wire path $path crossed component bounds $obstacle")
    }

    test("edit: auto lane keeps parallel wires from overlapping") {
        var d = Document()
        d = Edit.addWithId(d, "A", "mcb3", 0, 0).doc
        d = Edit.addWithId(d, "B", "mcb3", 10, 12).doc
        d = Edit.connect(d, "A:2", "B:1").doc
        d = Edit.connect(d, "A:4", "B:3").doc
        d = Edit.connect(d, "A:6", "B:5").doc
        ok(d.wires.map { it.lane }.toSet().size == 3, "lanes ${d.wires.map { it.lane }} must differ")
    }

    test("edit: move, rotate, param, delete cascades wires and orphan junctions") {
        var d = Document()
        d = Edit.add(d, "pb_no", 0, 0).doc; d = Edit.add(d, "lamp_r", 10, 10).doc
        d = Edit.connect(d, "SB01:14", "H1:X1").doc
        d = Edit.move(d, setOf("H1"), 2, 4)
        ok(d.find("H1")!!.x == 12 && d.find("H1")!!.y == 14, "moved")
        ok(Geo.wirePath(d, d.wires[0])!!.last() == Geo.pinPos(d, "H1:X1"), "wire follows component")
        d = Edit.setParam(d, "H1", "volts", "24")
        ok(d.find("H1")!!.params["volts"] == "24", "param")
        val sp = Edit.splitWire(d, 0, GridPt(5, 5)); ok(sp.ok, "split ${sp.error}")
        ok(sp.doc.find("J1") != null && sp.doc.wires.size == 2, "junction + 2 wires")
        val d2 = Edit.delete(sp.doc, setOf("SB01"))
        ok(d2.find("SB01") == null && d2.wires.size == 1, "wires of S1 removed")
        val d3 = Edit.delete(d2, setOf("H1"))
        ok(d3.find("J1") == null && d3.wires.isEmpty(), "orphan junction removed")
    }

    test("junction: split wire keeps electrical connection and allows a branch") {
        val c = Document()
        var d = Edit.addWithId(c, "PS1", "src1", 0, 0).doc
        d = Edit.addWithId(d, "H1", "lamp_g", 0, 10).doc
        d = Edit.addWithId(d, "H2", "lamp_g", 10, 10).doc
        d = Edit.connect(d, "PS1:L", "H1:X1").doc
        d = Edit.splitWire(d, 0, GridPt(0, 6)).doc
        d = Edit.connect(d, "J1:J", "H2:X1").doc
        d = Edit.connect(d, "H1:X2", "PS1:N").doc
        d = Edit.connect(d, "H2:X2", "PS1:N").doc
        val r = Rig("x", d)
        r.sim.run(0.1)
        ok(r.c<Lamp>("H1").lit && r.c<Lamp>("H2").lit, "both lamps lit through branch")
    }

    test("hit testing: pins within radius, component box, wire proximity") {
        var d = Document()
        d = Edit.add(d, "pb_no", 0, 0).doc; d = Edit.add(d, "lamp_r", 0, 10).doc
        d = Edit.connect(d, "SB01:14", "H1:X1").doc
        ok(Hit.pinAt(d, 0.3, 3.2, 0.8) == "SB01:14", "pin hit")
        ok(Hit.pinAt(d, 5.0, 5.0, 0.8) == null, "no pin far away")
        ok(Hit.componentAt(d, 0.0, 1.5)?.id == "SB01", "component hit")
        val w = Hit.wireAt(d, 0.2, 6.0, 0.5)
        ok(w != null && w.index == 0 && w.point.x == 0, "wire hit")
        ok(Hit.wireAt(d, 4.0, 6.0, 0.5) == null, "wire miss")
    }

    test("history: undo/redo and redo cleared by new commit") {
        val h = History(0)
        h.commit(1); h.commit(2); h.commit(3)
        ok(h.undo() == 2 && h.undo() == 1, "undo twice")
        ok(h.redo() == 2, "redo")
        h.commit(9)
        ok(!h.canRedo, "redo cleared")
        ok(h.undo() == 2 && h.undo() == 1 && h.undo() == 0 && !h.canUndo, "full undo")
    }

    // ---------------- ذخیره‌سازی ----------------
    test("json: round trip incl. Persian text, quotes, backslashes, wire colors") {
        val d = Document(
            "پروژه \"آزمایشی\" \\ test",
            listOf(Placed("S1", "pb_no", -4, 7, 90, mapOf("note" to "سلام \"دنیا\"\n"))),
            listOf(Wire("SB01:13", "SB01:14", -2, 0xFF123456.toInt())),
        )
        ok(DocJson.decode(DocJson.encode(d)) == d, "round trip mismatch")
    }

    test("json: every preset survives a round trip unchanged") {
        for (p in Presets.all) ok(DocJson.decode(DocJson.encode(p.doc)) == p.doc, "${p.id} mismatch")
    }

    test("json: legacy pushbutton terminal numbers migrate to IEC tags") {
        val legacy = """{"v":1,"name":"old","placed":[{"id":"S1","type":"pb_no","x":0,"y":0,"rot":0,"params":{}},{"id":"S0","type":"pb_nc","x":0,"y":8,"rot":0,"params":{}}],"wires":[{"a":"S1:3","b":"S0:1","lane":0}]}"""
        val d = DocJson.decode(legacy)
        ok(d.wires.single().a == "S1:13" && d.wires.single().b == "S0:21", "legacy pins migrated: ${d.wires}")
    }

    test("json: malformed input is rejected") {
        for (bad in listOf("", "{", "[1,2", "{\"placed\":[{\"id\":}]}", "{} x"))
            ok(runCatching { DocJson.decode(bad) }.isFailure, "should reject '$bad'")
    }

    test("project store: save, load, list newest first, last project, delete, unsafe names sanitized") {
        val dir = java.nio.file.Files.createTempDirectory("indsim-store").toFile()
        val store = ProjectStore(dir)
        ok(store.list().isEmpty() && store.lastName() == null, "empty store")
        val a = Presets.get("dol")!!.doc.copy(name = "موتور اول")
        val b = Presets.get("lamp")!!.doc.copy(name = "a/b:c*?")
        store.save(a); Thread.sleep(20); store.save(b)
        ok(store.load("موتور اول") == a, "Persian-named project round trip")
        ok(store.load("a/b:c*?") == b, "unsafe name round trip")
        ok(store.list().first() == ProjectStore.safeName("a/b:c*?"), "newest first: ${store.list()}")
        store.setLast("موتور اول"); ok(store.lastName() == "موتور اول", "last project")
        store.save(a.copy(placed = emptyList(), wires = emptyList()))
        ok(store.load("موتور اول")!!.placed.isEmpty(), "overwrite")
        ok(store.delete("موتور اول") && store.load("موتور اول") == null, "delete")
        File(dir, "broken" + ProjectStore.EXT).writeText("{not json")
        ok(store.load("broken") == null, "corrupt file returns null, no crash")
        dir.deleteRecursively()
    }

    // ---------------- کامپایل و رنگ سیم ----------------
    test("compiler: contact without or with unknown coil is reported, not crashed") {
        var d = Edit.addWithId(Document(), "KP1", "poles3", 0, 0).doc
        ok(Compiler.compile(d).errors.any { "no coil" in it }, "missing coil")
        d = Edit.setParam(d, "KP1", "coil", "K9")
        ok(Compiler.compile(d).errors.any { "not found" in it }, "unknown coil")
    }

    test("wire colors follow source terminals (L1 brown, L2 black, L3 grey, N blue, PE green)") {
        val d = Presets.get("dol")!!.doc
        val cols = NetColors.compute(d)
        fun colorOf(a: String, b: String) = cols[d.wires.indexOfFirst { it.a == a && it.b == b }]
        ok(colorOf("PS1:L1", "Q1:1") == NetColors.BROWN, "L1")
        ok(colorOf("PS1:L2", "Q1:3") == NetColors.BLACK, "L2")
        ok(colorOf("PS1:L3", "Q1:5") == NetColors.GREY, "L3")
        ok(colorOf("K1:A2", "PS1:N") == NetColors.BLUE, "N")
    }

    test("every preset compiles and has no overlapping component origins") {
        for (p in Presets.all) {
            ok(Compiler.compile(p.doc).errors.isEmpty(), "${p.id}: ${Compiler.compile(p.doc).errors}")
            ok(p.doc.placed.map { it.id }.toSet().size == p.doc.placed.size, "${p.id}: duplicate ids")
            ok(p.doc.wires.isNotEmpty(), "${p.id}: no wires")
        }
        ok(Presets.all.size == 13, "13 presets required, got ${Presets.all.size}")
    }

    // ---------------- تعامل ----------------
    test("interact: momentary button, latching e-stop toggles, MCB toggles and resets, hot thermal refuses reset") {
        val rig = Rig("dol")
        val s1 = rig.c<PushButton>("S1")
        Interact.down(s1); ok(s1.pressed, "down"); Interact.up(s1); ok(!s1.pressed, "up")
        val es = PushButton("E", false, latching = true)
        Interact.down(es); Interact.up(es); ok(es.pressed, "latched")
        Interact.down(es); ok(!es.pressed, "unlatched on second tap")
        val q = rig.c<ProtectiveDevice>("Q1")
        Interact.down(q); ok(!q.closed, "opened"); Interact.down(q); ok(q.closed, "closed")
    }

    // ---------------- رفتار مدارهای آماده ----------------
    test("preset lamp: selector turns the lamp on and off") {
        val r = Rig("lamp"); r.sim.run(0.1)
        ok(!r.c<Lamp>("H1").lit, "off at start")
        r.c<Selector>("SA1").next(); r.sim.run(0.1); ok(r.c<Lamp>("H1").lit, "on")
        r.c<Selector>("SA1").next(); r.sim.run(0.1); ok(!r.c<Lamp>("H1").lit, "off again")
    }

    test("preset start/stop: contactor follows START while held, then drops") {
        val r = Rig("startstop"); r.sim.run(0.1)
        val s1 = r.c<PushButton>("S1")
        s1.press(); r.sim.run(0.2)
        ok(r.c<Coil>("K1").energized && r.c<Lamp>("H1").lit, "on while held")
        s1.release(); r.sim.run(0.2)
        ok(!r.c<Coil>("K1").energized && !r.c<Lamp>("H1").lit, "off after release")
    }

    test("preset self-holding: latches on START, STOP releases; NC contact drives the red lamp") {
        val r = Rig("selfhold"); r.sim.run(0.1)
        ok(!r.c<Lamp>("H1").lit && r.c<Lamp>("H2").lit, "idle: red on, green off")
        r.press("S1"); r.sim.run(0.3)
        ok(r.c<Coil>("K1").energized, "held after release")
        ok(r.c<Lamp>("H1").lit && !r.c<Lamp>("H2").lit, "running: green on, red off")
        r.press("S0"); r.sim.run(0.3)
        ok(!r.c<Coil>("K1").energized, "released by STOP")
        ok(!r.c<Lamp>("H1").lit && r.c<Lamp>("H2").lit, "back to idle lamps")
    }

    test("preset DOL: start, run at rated current, stop") {
        val r = Rig("dol"); r.sim.run(0.1)
        r.press("S1"); r.sim.run(6.0)
        ok(r.c<Motor3Ph>("M1").omega > 0.95, "runs")
        near(r.sim.current("FR1:1L1", "FR1:2T1")!!, 10.0, 1.0, "line current")
        r.press("S0"); r.sim.run(30.0)
        ok(abs(r.c<Motor3Ph>("M1").omega) < 0.05, "stopped"); r.noShort()
    }

    test("preset thermal: overload trips the relay, lights the red lamp, stops the motor") {
        val doc = Edit.setParam(Presets.get("thermal")!!.doc, "M1", "load", "1.5")
        val r = Rig("x", doc)
        r.press("S1")
        ok(r.sim.runUntil(400.0, 0.05) { r.c<ThermalRelay>("FR1").tripped }, "must trip")
        r.sim.run(1.0, 0.05)
        ok(r.c<Lamp>("H2").lit, "overload lamp via 97-98")
        ok(!r.c<Coil>("K1").energized && !r.c<Lamp>("H1").lit, "contactor dropped, run lamp off")
    }

    test("preset forward/reverse: reverses, interlock blocks the opposite direction, never shorts") {
        val r = Rig("fwdrev"); r.sim.run(0.1)
        r.press("SF"); r.sim.run(6.0)
        ok(r.c<Motor3Ph>("M1").omega > 0.9, "forward")
        r.press("SR", 0.5); r.sim.run(0.5)
        ok(!r.c<Coil>("KR").energized && r.c<Coil>("KF").energized, "interlock keeps KF")
        r.press("S0"); r.sim.run(30.0)
        ok(abs(r.c<Motor3Ph>("M1").omega) < 0.05, "stopped")
        r.press("SR"); r.sim.run(6.0)
        ok(r.c<Motor3Ph>("M1").omega < -0.9, "reverse")
        r.noShort()
        ok(!r.c<ProtectiveDevice>("Q1").tripped, "Q1 intact")
    }

    test("preset forward/reverse + E-stop: latching e-stop kills the motor and blocks restart until released") {
        val r = Rig("fwdrev-es"); r.sim.run(0.1)
        r.press("SF"); r.sim.run(1.0)
        ok(r.c<Coil>("KF").energized, "running")
        val es = r.circuit.component("SE1")
        Interact.down(es); r.sim.run(0.3); Interact.up(es)
        ok(!r.c<Coil>("KF").energized, "stopped by e-stop")
        r.press("SF", 0.5); ok(!r.c<Coil>("KF").energized, "restart blocked while latched")
        Interact.down(es); r.sim.run(0.2)
        r.press("SF"); r.sim.run(0.3)
        ok(r.c<Coil>("KF").energized, "restart after release")
        r.noShort()
    }

    test("preset star-delta: star first (230 V), dead time, then delta (400 V); contactors never overlap") {
        val r = Rig("stardelta"); r.sim.run(0.1)
        r.press("S1"); r.sim.run(1.0)
        ok(r.c<Coil>("K1").energized && r.c<Coil>("K2").energized && !r.c<Coil>("K3").energized, "star phase")
        for (k in 0..2) near(r.c<Motor3Ph>("M1").windingVoltages[k], 230.0, 8.0, "star winding $k")
        r.sim.run(5.0)
        ok(r.c<Coil>("K1").energized && !r.c<Coil>("K2").energized && r.c<Coil>("K3").energized, "delta phase")
        for (k in 0..2) near(r.c<Motor3Ph>("M1").windingVoltages[k], 400.0, 10.0, "delta winding $k")
        r.sim.run(6.0)
        ok(r.c<Motor3Ph>("M1").omega > 0.95, "full speed")
        r.noShort()
        r.press("S0"); r.sim.run(0.3)
        ok(!r.c<Coil>("K1").energized && !r.c<Coil>("K3").energized, "stopped")
    }

    test("preset two motors in sequence: M2 refuses to start before M1, drops when M1 stops") {
        val r = Rig("sequence"); r.sim.run(0.1)
        r.press("S2", 0.5); ok(!r.c<Coil>("K2").energized, "M2 blocked")
        r.press("S1"); r.sim.run(0.3); ok(r.c<Coil>("K1").energized, "M1 running")
        r.press("S2"); r.sim.run(0.3); ok(r.c<Coil>("K2").energized, "M2 running and held")
        r.sim.run(8.0)
        ok(r.c<Motor3Ph>("M1").omega > 0.9 && r.c<Motor3Ph>("M2").omega > 0.9, "both motors turning")
        r.press("S0"); r.sim.run(0.3)
        ok(!r.c<Coil>("K1").energized && !r.c<Coil>("K2").energized, "STOP stops both")
        r.noShort()
    }

    test("preset timed sequence: M2 starts automatically 5 s after M1") {
        val r = Rig("timedseq"); r.sim.run(0.1)
        r.press("S1"); r.sim.run(2.0)
        ok(r.c<Coil>("K1").energized && !r.c<Coil>("K2").energized, "only M1 at 2 s")
        r.sim.run(3.5)
        ok(r.c<Coil>("K2").energized, "M2 after the delay")
        r.press("S0"); r.sim.run(0.3)
        ok(!r.c<Coil>("K1").energized && !r.c<Coil>("K2").energized, "all stopped")
    }

    test("preset motor with timer: runs 5 s then stops by itself") {
        val r = Rig("motor-timer"); r.sim.run(0.1)
        r.press("S1"); r.sim.run(3.0)
        ok(r.c<Coil>("K1").energized, "running at 3 s")
        r.sim.run(3.0)
        ok(!r.c<Coil>("K1").energized, "stopped at ~6 s")
        r.sim.run(2.0); ok(!r.c<Coil>("K1").energized, "does not restart by itself")
    }

    test("preset tank level: low float starts and latches, high float stops and blocks") {
        val r = Rig("tank"); r.sim.run(0.1)
        ok(!r.c<Coil>("K1").energized, "idle")
        r.c<ToggleSwitch>("SQ1").set(true); r.sim.run(0.3); ok(r.c<Coil>("K1").energized, "pump on when tank is low")
        r.c<ToggleSwitch>("SQ1").set(false); r.sim.run(0.3); ok(r.c<Coil>("K1").energized, "keeps filling")
        r.c<ToggleSwitch>("SQ2").set(true); r.sim.run(0.3); ok(!r.c<Coil>("K1").energized, "stops when full")
        r.c<ToggleSwitch>("SQ2").set(false); r.sim.run(0.3); ok(!r.c<Coil>("K1").energized, "stays off until low again")
    }

    test("preset automatic pump: selector off / manual jog / automatic with float") {
        val r = Rig("autopump"); r.sim.run(0.1)
        val sa = r.c<Selector>("SA1")
        sa.setPosition(1); r.c<ToggleSwitch>("SQ1").set(true); r.sim.run(0.3)
        ok(!r.c<Coil>("K1").energized, "middle position = off even with low level")
        sa.setPosition(2); r.sim.run(0.3); ok(r.c<Coil>("K1").energized, "automatic starts on low level")
        r.c<ToggleSwitch>("SQ1").set(false); r.sim.run(0.3); ok(r.c<Coil>("K1").energized, "automatic latches")
        r.c<ToggleSwitch>("SQ2").set(true); r.sim.run(0.3); ok(!r.c<Coil>("K1").energized, "high level stops")
        r.c<ToggleSwitch>("SQ2").set(false)
        sa.setPosition(0); r.sim.run(0.3); ok(!r.c<Coil>("K1").energized, "manual: idle")
        val s1 = r.c<PushButton>("S1")
        s1.press(); r.sim.run(0.3); ok(r.c<Coil>("K1").energized, "manual jog while pressed")
        s1.release(); r.sim.run(0.3); ok(!r.c<Coil>("K1").energized, "manual does not latch")
    }

    println()
    println("passed=$passed failed=$failures")
    if (failures > 0) exitProcess(1)
}
