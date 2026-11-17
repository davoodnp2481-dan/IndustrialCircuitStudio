package com.indsim.engine

import com.indsim.engine.components.Coil
import com.indsim.engine.components.Lamp
import com.indsim.engine.components.LinkedContact
import com.indsim.engine.components.Motor3Ph
import com.indsim.engine.components.PowerPoles
import com.indsim.engine.components.ProtectionKind
import com.indsim.engine.components.ProtectiveDevice
import com.indsim.engine.components.PushButton
import com.indsim.engine.components.Source
import com.indsim.engine.components.ThermalRelay
import kotlin.math.abs
import kotlin.system.exitProcess

// ---------- ابزار تست (بدون وابستگی خارجی) ----------

private var failures = 0
private var passed = 0

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS  $name")
    } catch (e: Throwable) {
        failures++
        println("FAIL  $name -> ${e.message ?: e}")
    }
}

private fun ok(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }
private fun near(actual: Double, expected: Double, tol: Double, what: String) =
    ok(abs(actual - expected) <= tol, "$what: expected $expected ± $tol, got $actual")

private fun Circuit.w(a: Pin, b: Pin) = connect(a, b)

private fun Motor3Ph.deltaLinks(c: Circuit) {
    c.w("$id:U1", "$id:W2"); c.w("$id:V1", "$id:U2"); c.w("$id:W1", "$id:V2")
}

// ---------- مدارهای نمونه ----------

/** راه‌انداز مستقیم (DOL) کامل: قدرت + فرمان استارت/استپ/خودنگهدار + رله حرارتی. */
private class DolRig {
    val c = Circuit()
    val ps = c.add(Source.ac3("PS1"))
    val q1 = c.add(ProtectiveDevice("Q1", ProtectionKind.MCB, 3, 16.0))
    val f1 = c.add(ProtectiveDevice("F1", ProtectionKind.MCB, 1, 6.0))
    val s0 = c.add(PushButton("S0", normallyOpen = false))
    val s1 = c.add(PushButton("S1", normallyOpen = true))
    val k1 = c.add(Coil("K1", 230.0))
    val k1p = c.add(PowerPoles("K1P", "K1"))
    val k1a = c.add(LinkedContact("K1A", "K1", normallyOpen = true))
    val f2 = c.add(ThermalRelay("F2", 10.0))
    val m1 = c.add(Motor3Ph("M1", 400.0, 10.0))

    init {
        c.w("PS1:L1", "Q1:1"); c.w("PS1:L2", "Q1:3"); c.w("PS1:L3", "Q1:5")
        c.w("Q1:2", "K1P:1L1"); c.w("Q1:4", "K1P:3L2"); c.w("Q1:6", "K1P:5L3")
        c.w("K1P:2T1", "F2:1L1"); c.w("K1P:4T2", "F2:3L2"); c.w("K1P:6T3", "F2:5L3")
        c.w("F2:2T1", "M1:U1"); c.w("F2:4T2", "M1:V1"); c.w("F2:6T3", "M1:W1")
        m1.deltaLinks(c)
        // فرمان: L1 -> F1 -> استپ(NC) -> [استارت || تیغه خودنگهدار] -> 95-96 -> A1 ... A2 -> N
        c.w("PS1:L1", "F1:1"); c.w("F1:2", "S0:21"); c.w("S0:22", "S1:13"); c.w("S0:22", "K1A:13")
        c.w("S1:14", "F2:95"); c.w("K1A:14", "F2:95")
        c.w("F2:96", "K1:A1"); c.w("K1:A2", "PS1:N")
    }

    val sim = Simulator(c)
}

/** چپ‌گرد/راست‌گرد: KF و KR با جابه‌جایی دو فاز (L1↔L3). بدون اینترلاک، برای تست اتصال کوتاه. */
private class FwdRevRig {
    val c = Circuit()
    val ps = c.add(Source.ac3("PS1"))
    val q1 = c.add(ProtectiveDevice("Q1", ProtectionKind.MCB, 3, 16.0))
    val kf = c.add(Coil("KF", 230.0)); val kfp = c.add(PowerPoles("KFP", "KF"))
    val kr = c.add(Coil("KR", 230.0)); val krp = c.add(PowerPoles("KRP", "KR"))
    val sf = c.add(PushButton("SF", true)); val sr = c.add(PushButton("SR", true))
    val m1 = c.add(Motor3Ph("M1"))

    init {
        c.w("PS1:L1", "Q1:1"); c.w("PS1:L2", "Q1:3"); c.w("PS1:L3", "Q1:5")
        for ((q, p) in listOf("Q1:2" to "1L1", "Q1:4" to "3L2", "Q1:6" to "5L3")) {
            c.w(q, "KFP:$p"); c.w(q, "KRP:$p")
        }
        c.w("KFP:2T1", "M1:U1"); c.w("KFP:4T2", "M1:V1"); c.w("KFP:6T3", "M1:W1")
        c.w("KRP:2T1", "M1:W1"); c.w("KRP:4T2", "M1:V1"); c.w("KRP:6T3", "M1:U1")
        m1.deltaLinks(c)
        c.w("PS1:L1", "SF:13"); c.w("SF:14", "KF:A1"); c.w("KF:A2", "PS1:N")
        c.w("PS1:L1", "SR:13"); c.w("SR:14", "KR:A1"); c.w("KR:A2", "PS1:N")
    }

    val sim = Simulator(c)
}

/** ستاره-مثلث (قدرت): K1 اصلی، K2 ستاره، K3 مثلث؛ هر بوبین با شستی تستی. */
private class StarDeltaRig {
    val c = Circuit()
    val ps = c.add(Source.ac3("PS1"))
    val q1 = c.add(ProtectiveDevice("Q1", ProtectionKind.MCB, 3, 16.0))
    val k1 = c.add(Coil("K1")); val k1p = c.add(PowerPoles("K1P", "K1"))
    val k2 = c.add(Coil("K2")); val k2p = c.add(PowerPoles("K2P", "K2"))
    val k3 = c.add(Coil("K3")); val k3p = c.add(PowerPoles("K3P", "K3"))
    val s1 = c.add(PushButton("S1", true)); val s2 = c.add(PushButton("S2", true)); val s3 = c.add(PushButton("S3", true))
    val m1 = c.add(Motor3Ph("M1", 400.0, 10.0))

    init {
        c.w("PS1:L1", "Q1:1"); c.w("PS1:L2", "Q1:3"); c.w("PS1:L3", "Q1:5")
        c.w("Q1:2", "K1P:1L1"); c.w("Q1:4", "K1P:3L2"); c.w("Q1:6", "K1P:5L3")
        c.w("K1P:2T1", "M1:U1"); c.w("K1P:4T2", "M1:V1"); c.w("K1P:6T3", "M1:W1")
        // ستاره: K2 سه سر U2,V2,W2 را به هم وصل می‌کند
        c.w("K2P:1L1", "M1:U2"); c.w("K2P:3L2", "M1:V2"); c.w("K2P:5L3", "M1:W2")
        c.w("K2P:2T1", "K2P:4T2"); c.w("K2P:4T2", "K2P:6T3")
        // مثلث: K3 → U1-W2، V1-U2، W1-V2
        c.w("K3P:1L1", "M1:U1"); c.w("K3P:2T1", "M1:W2")
        c.w("K3P:3L2", "M1:V1"); c.w("K3P:4T2", "M1:U2")
        c.w("K3P:5L3", "M1:W1"); c.w("K3P:6T3", "M1:V2")
        for ((s, k) in listOf("S1" to "K1", "S2" to "K2", "S3" to "K3")) {
            c.w("PS1:L1", "$s:13"); c.w("$s:14", "$k:A1"); c.w("$k:A2", "PS1:N")
        }
    }

    val sim = Simulator(c)
}

// ---------- تست‌ها ----------

fun main() {
    test("wiring: shared pin merges nets, separate wires stay separate (crossing = no connection)") {
        val c = Circuit()
        c.add(Lamp("H1")); c.add(Lamp("H2"))
        c.w("H1:X1", "H2:X1")
        val nets = c.buildNets()
        ok(nets.sameNet("H1:X1", "H2:X1"), "joined pins must share a net")
        ok(!nets.sameNet("H1:X2", "H2:X2"), "unwired pins must not share a net")
    }

    test("wiring: unknown pin / duplicate id rejected") {
        val c = Circuit()
        c.add(Lamp("H1"))
        var threw = false
        try { c.connect("H1:BAD", "H1:X1") } catch (e: IllegalArgumentException) { threw = true }
        ok(threw, "unknown pin must be rejected")
        threw = false
        try { c.add(Lamp("H1")) } catch (e: IllegalArgumentException) { threw = true }
        ok(threw, "duplicate id must be rejected")
    }

    test("lamp via NO pushbutton: lit only while pressed; wire liveness follows") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val s = c.add(PushButton("S1", true)); val h = c.add(Lamp("H1"))
        c.w("PS1:L", "S1:13"); c.w("S1:14", "H1:X1"); c.w("H1:X2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.1)
        ok(!h.lit, "lamp must be off")
        ok(!sim.isLive("H1:X1"), "wire after open contact must not be live")
        ok(sim.isLive("S1:13"), "wire before contact is live")
        s.press(); sim.run(0.1)
        ok(h.lit, "lamp must be on while pressed")
        ok(sim.isLive("H1:X1"), "wire live when pressed")
        near(sim.voltage("H1:X1"), 230.0, 1.0, "lamp feed voltage")
        ok(sim.netCurrent("H1:X1") > 0.005, "current must flow")
        s.release(); sim.run(0.1)
        ok(!h.lit, "lamp must turn off after release")
    }

    test("series loads share voltage (nodal solver): two 230V lamps in series get ~115V each") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); c.add(Lamp("H1")); c.add(Lamp("H2"))
        c.w("PS1:L", "H1:X1"); c.w("H1:X2", "H2:X1"); c.w("H2:X2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.05)
        near(sim.voltage("H1:X2"), 115.0, 1.0, "midpoint voltage")
        ok(!c.get<Lamp>("H1").lit, "lamps in series must not reach rated brightness")
    }

    test("start/stop with self-holding contact") {
        val r = DolRig()
        r.sim.run(0.1)
        ok(!r.k1.energized, "idle")
        r.s1.press(); r.sim.run(0.1)
        ok(r.k1.energized, "start energizes K1")
        r.s1.release(); r.sim.run(0.2)
        ok(r.k1.energized, "self-holding keeps K1 after release")
        r.s0.press(); r.sim.run(0.1)
        ok(!r.k1.energized, "stop releases K1")
        r.s0.release(); r.sim.run(0.2)
        ok(!r.k1.energized, "K1 stays off after stop released")
    }

    test("contactor: power poles close with coil; motor runs forward at ~rated current") {
        val r = DolRig()
        r.s1.press(); r.sim.run(0.1); r.s1.release()
        ok(r.k1p.closed, "poles closed")
        r.sim.run(6.0)
        ok(r.m1.omega > 0.95, "motor reaches full speed, omega=${r.m1.omega}")
        ok(!r.m1.phaseLoss, "no phase loss")
        for (k in 0..2) near(r.m1.windingVoltages[k], 400.0, 8.0, "winding $k voltage (delta)")
        near(r.sim.current("F2:1L1", "F2:2T1")!!, 10.0, 1.0, "line current at rated load")
        ok(!r.f2.tripped, "thermal relay not tripped at rated load")
        r.s0.press(); r.sim.run(0.1); r.s0.release()
        ok(!r.k1p.closed, "poles open after stop")
        r.sim.run(30.0)
        ok(abs(r.m1.omega) < 0.05, "motor stopped, omega=${r.m1.omega}")
    }

    test("thermal overload: trips in realistic time, drops contactor, 97-98 closes, restart blocked until reset") {
        val r = DolRig()
        r.m1.mechanicalLoad = 1.5
        r.s1.press(); r.sim.run(0.1); r.s1.release()
        val t0 = r.sim.time
        ok(r.sim.runUntil(400.0, 0.05) { r.f2.tripped }, "relay must trip within 400 s")
        val dt = r.sim.time - t0
        ok(dt in 100.0..300.0, "trip time $dt s outside 100..300 s")
        r.sim.run(1.0, 0.05)
        ok(!r.k1.energized, "contactor must drop after trip")
        ok(r.sim.current("F2:97", "F2:98") != null, "97-98 must close")
        ok(r.sim.current("F2:95", "F2:96") == null, "95-96 must open")
        r.sim.run(30.0, 0.05)
        ok(abs(r.m1.omega) < 0.05, "motor stopped after trip")
        r.s1.press(); r.sim.run(0.3, 0.05); r.s1.release()
        ok(!r.k1.energized, "restart blocked while tripped")
        ok(!r.f2.reset(), "reset must be refused while hot")
        r.sim.run(150.0, 0.5)
        ok(r.f2.reset(), "reset allowed after cooling")
        r.m1.mechanicalLoad = 1.0
        r.s1.press(); r.sim.run(0.3, 0.05); r.s1.release(); r.sim.run(0.2, 0.05)
        ok(r.k1.energized, "restart works after reset")
    }

    test("forward/reverse: swapping two phases reverses rotation") {
        val r = FwdRevRig()
        r.sf.press(); r.sim.run(6.0); r.sf.release()
        ok(r.m1.omega > 0.9, "forward, omega=${r.m1.omega}")
        r.sim.run(30.0)
        ok(abs(r.m1.omega) < 0.05, "stopped between directions")
        r.sr.press(); r.sim.run(6.0); r.sr.release()
        ok(r.m1.omega < -0.9, "reverse, omega=${r.m1.omega}")
    }

    test("forward+reverse together (no interlock) = phase-to-phase short, MCB trips on magnetic trip") {
        val r = FwdRevRig()
        r.sf.press(); r.sr.press(); r.sim.run(0.5)
        ok(r.sim.events.any { it.type == EventType.SHORT_CIRCUIT }, "short circuit event expected")
        ok(r.q1.tripped, "Q1 must trip")
        ok(r.sim.activeShortCircuits.isEmpty(), "short cleared after trip")
        ok(r.sim.events.any { it.type == EventType.PROTECTION_TRIP && it.componentId == "Q1" }, "trip event")
    }

    test("star-delta power wiring: star = 230 V per winding, delta = 400 V (floating star point solved)") {
        val r = StarDeltaRig()
        r.s1.press(); r.s2.press(); r.sim.run(0.3)
        for (k in 0..2) near(r.m1.windingVoltages[k], 230.0, 6.0, "star winding $k")
        r.sim.run(8.0)
        near(r.m1.omega, 0.85, 0.05, "reduced speed in star")
        r.s2.release(); r.sim.run(0.1)
        r.s3.press(); r.sim.run(0.3)
        for (k in 0..2) near(r.m1.windingVoltages[k], 400.0, 8.0, "delta winding $k")
        r.sim.run(6.0)
        ok(r.m1.omega > 0.95, "full speed in delta")
    }

    test("star and delta contactors together = short circuit, MCB trips") {
        val r = StarDeltaRig()
        r.s1.press(); r.s2.press(); r.s3.press(); r.sim.run(0.5)
        ok(r.sim.events.any { it.type == EventType.SHORT_CIRCUIT }, "short expected")
        ok(r.q1.tripped, "Q1 must trip")
    }

    test("direct L-N short: MCB trips; without protection the short stays flagged") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val q = c.add(ProtectiveDevice("Q1", ProtectionKind.MCB, 1, 16.0))
        c.w("PS1:L", "Q1:1"); c.w("Q1:2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.1)
        ok(q.tripped, "MCB must trip")
        q.reset(); sim.run(0.1)
        ok(q.tripped, "short still present: trips again")

        val c2 = Circuit()
        c2.add(Source.ac1("PS1")); c2.w("PS1:L", "PS1:N")
        val sim2 = Simulator(c2)
        sim2.run(0.5)
        ok("PS1" in sim2.activeShortCircuits, "unprotected short stays flagged")
    }

    test("fuse blows on short circuit and stays open until replaced") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val f = c.add(ProtectiveDevice("F1", ProtectionKind.FUSE, 1, 10.0))
        c.w("PS1:L", "F1:1"); c.w("F1:2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.1)
        ok(f.tripped, "fuse blown")
        sim.run(1.0)
        ok(f.tripped, "fuse stays blown")
        f.replace()
        ok(!f.tripped, "fuse replaced")
    }

    test("overload on MCB (2x In) trips thermally, not instantly") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val q = c.add(ProtectiveDevice("Q1", ProtectionKind.MCB, 1, 10.0))
        // بار 11.5 اهم ≈ 20 آمپر = 2×In
        c.add(com.indsim.engine.components.ResistiveLoad("R1", 11.5))
        c.w("PS1:L", "Q1:1"); c.w("Q1:2", "R1:1"); c.w("R1:2", "PS1:N")
        val sim = Simulator(c)
        sim.run(5.0)
        ok(!q.tripped, "must not trip within 5 s at 2×In")
        ok(sim.runUntil(120.0) { q.tripped }, "must trip thermally within 120 s")
    }

    test("coil type: 24V DC coil ignores 230V AC and picks up on 24V DC; AC coil picks up on AC") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val kdc = c.add(Coil("KDC", 24.0, Domain.DC)); val kac = c.add(Coil("KAC", 230.0))
        c.w("PS1:L", "KDC:A1"); c.w("KDC:A2", "PS1:N"); c.w("PS1:L", "KAC:A1"); c.w("KAC:A2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.2)
        ok(!kdc.energized, "DC coil must not pick up on AC")
        ok(kac.energized, "AC coil picks up on AC")
        ok(sim.events.any { it.type == EventType.OVERVOLTAGE && it.componentId == "KDC" }, "overvoltage reported")

        val c2 = Circuit()
        c2.add(Source.dc("PS2", 24.0)); val k2 = c2.add(Coil("K2", 24.0, Domain.DC))
        c2.w("PS2:+", "K2:A1"); c2.w("K2:A2", "PS2:-")
        val sim2 = Simulator(c2)
        sim2.run(0.2)
        ok(k2.energized, "DC coil picks up on 24 V DC")
        ok(sim2.warnings.isEmpty(), "no warnings at rated voltage")
    }

    test("lamp 24V on 230V reports overvoltage warning") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); c.add(Lamp("H1", 24.0))
        c.w("PS1:L", "H1:X1"); c.w("H1:X2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.1)
        ok(sim.warnings["H1"] == "overvoltage", "warning expected, got ${sim.warnings}")
    }

    test("emergency stop (latching NC) stays open after release until unlatched") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val es = c.add(PushButton("ES", normallyOpen = false, latching = true)); val h = c.add(Lamp("H1"))
        c.w("PS1:L", "ES:21"); c.w("ES:22", "H1:X1"); c.w("H1:X2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.1); ok(h.lit, "lit initially")
        es.press(); sim.run(0.1); ok(!h.lit, "off when pressed")
        es.release(); sim.run(0.1); ok(!h.lit, "still latched")
        es.unlatch(); sim.run(0.1); ok(h.lit, "on after unlatch")
    }

    test("phase loss (one line open on delta motor) is detected; motor does not start") {
        val c = Circuit()
        c.add(Source.ac3("PS1")); val m = c.add(Motor3Ph("M1"))
        c.w("PS1:L1", "M1:U1"); c.w("PS1:L2", "M1:V1")   // W1 باز
        m.deltaLinks(c)
        val sim = Simulator(c)
        sim.run(2.0)
        ok(m.phaseLoss, "phase loss expected")
        ok(abs(m.omega) < 0.05, "motor must not run on two phases")
        ok(sim.events.any { it.type == EventType.PHASE_LOSS }, "event expected")
    }

    fun timerRig(mode: com.indsim.engine.components.TimerMode, delay: Double): Triple<Simulator, PushButton, com.indsim.engine.components.TimerRelay> {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val s = c.add(PushButton("S1", true))
        val t = c.add(com.indsim.engine.components.TimerRelay("T1", mode, delay))
        c.add(Lamp("H1")); c.add(Lamp("H2"))
        c.w("PS1:L", "S1:13"); c.w("S1:14", "T1:A1"); c.w("T1:A2", "PS1:N")
        if (mode == com.indsim.engine.components.TimerMode.STAR_DELTA) {
            c.w("PS1:L", "T1:17"); c.w("T1:18", "H1:X1"); c.w("PS1:L", "T1:27"); c.w("T1:28", "H2:X1")
        } else {
            c.w("PS1:L", "T1:15"); c.w("T1:18", "H1:X1")
        }
        c.w("H1:X2", "PS1:N"); c.w("H2:X2", "PS1:N")
        return Triple(Simulator(c), s, t)
    }

    test("timer TON: output after delay, drops immediately on power loss") {
        val (sim, s, _) = timerRig(com.indsim.engine.components.TimerMode.TON, 2.0)
        val h = sim.circuit.get<Lamp>("H1")
        s.press(); sim.run(1.5); ok(!h.lit, "not yet at 1.5 s")
        sim.run(1.0); ok(h.lit, "on after 2 s")
        s.release(); sim.run(0.1); ok(!h.lit, "off right after power loss")
    }

    test("timer TOF: on immediately, off after delay following power loss") {
        val (sim, s, _) = timerRig(com.indsim.engine.components.TimerMode.TOF, 2.0)
        val h = sim.circuit.get<Lamp>("H1")
        s.press(); sim.run(0.2); ok(h.lit, "on immediately")
        s.release(); sim.run(1.5); ok(h.lit, "still on during delay")
        sim.run(1.0); ok(!h.lit, "off after delay")
    }

    test("star-delta timer: star, then dead time, then delta") {
        val (sim, s, _) = timerRig(com.indsim.engine.components.TimerMode.STAR_DELTA, 3.0)
        val star = sim.circuit.get<Lamp>("H1"); val delta = sim.circuit.get<Lamp>("H2")
        s.press(); sim.run(0.3)
        ok(star.lit && !delta.lit, "star first")
        sim.run(2.75) // t ~ 3.05
        ok(!star.lit && !delta.lit, "dead time between star and delta")
        sim.run(0.5)
        ok(!star.lit && delta.lit, "delta after dead time")
        s.release(); sim.run(0.2)
        ok(!star.lit && !delta.lit, "reset on power loss")
    }

    test("3-position selector closes left/right contacts only in end positions") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val sel = c.add(com.indsim.engine.components.Selector("SA1", 3))
        c.add(Lamp("H1")); c.add(Lamp("H2"))
        c.w("PS1:L", "SA1:13"); c.w("SA1:14", "H1:X1"); c.w("PS1:L", "SA1:23"); c.w("SA1:24", "H2:X1")
        c.w("H1:X2", "PS1:N"); c.w("H2:X2", "PS1:N")
        val sim = Simulator(c)
        val h1 = c.get<Lamp>("H1"); val h2 = c.get<Lamp>("H2")
        sim.run(0.1); ok(!h1.lit && !h2.lit, "center: both off")
        sel.setPosition(0); sim.run(0.1); ok(h1.lit && !h2.lit, "left")
        sel.setPosition(2); sim.run(0.1); ok(!h1.lit && h2.lit, "right")
    }

    test("dual pushbutton: NC closed at rest, NO closed when pressed") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val b = c.add(com.indsim.engine.components.DualPushButton("S1"))
        c.add(Lamp("H1")); c.add(Lamp("H2"))
        c.w("PS1:L", "S1:11"); c.w("S1:12", "H1:X1"); c.w("PS1:L", "S1:13"); c.w("S1:14", "H2:X1")
        c.w("H1:X2", "PS1:N"); c.w("H2:X2", "PS1:N")
        val sim = Simulator(c)
        sim.run(0.1); ok(c.get<Lamp>("H1").lit && !c.get<Lamp>("H2").lit, "rest")
        b.press(); sim.run(0.1); ok(!c.get<Lamp>("H1").lit && c.get<Lamp>("H2").lit, "pressed")
    }

    test("single-phase motor runs on 230 V; toggle switch (limit switch) works") {
        val c = Circuit()
        c.add(Source.ac1("PS1")); val sw = c.add(com.indsim.engine.components.ToggleSwitch("SQ1", true))
        val m = c.add(com.indsim.engine.components.Motor1Ph("M1"))
        c.w("PS1:L", "SQ1:3"); c.w("SQ1:4", "M1:U1"); c.w("M1:U2", "PS1:N")
        val sim = Simulator(c)
        sim.run(3.0); ok(!m.running, "limit switch open")
        sw.toggle(); sim.run(4.0); ok(m.omega > 0.9, "motor runs, omega=${m.omega}")
    }

    println()
    println("passed=$passed failed=$failures")
    if (failures > 0) exitProcess(1)
}
