package com.indsim.canvas

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

fun main() {
    test("screen/world round trip") {
        val c = Camera(2.5, -130.0, 77.0)
        near(c.toWorldX(c.toScreenX(321.0)), 321.0, 1e-9, "x")
        near(c.toWorldY(c.toScreenY(-45.5)), -45.5, 1e-9, "y")
    }

    test("zoomAt keeps the world point under the finger fixed") {
        val c = Camera(1.3, 40.0, -20.0)
        val fx = 210.0; val fy = 330.0
        val wx = c.toWorldX(fx); val wy = c.toWorldY(fy)
        val z = c.zoomAt(1.7, fx, fy)
        near(z.toWorldX(fx), wx, 1e-9, "world x under finger")
        near(z.toWorldY(fy), wy, 1e-9, "world y under finger")
        near(z.scale, 1.3 * 1.7, 1e-9, "scale")
    }

    test("zoom is clamped to min/max and focal point still fixed") {
        val c = Camera(7.0, 10.0, 10.0)
        val fx = 100.0; val fy = 100.0
        val wx = c.toWorldX(fx)
        val z = c.zoomAt(100.0, fx, fy)
        near(z.scale, Camera.MAX_SCALE, 1e-12, "max scale")
        near(z.toWorldX(fx), wx, 1e-9, "focal fixed at clamp")
        near(Camera(0.2).zoomAt(0.001, 0.0, 0.0).scale, Camera.MIN_SCALE, 1e-12, "min scale")
    }

    test("pan moves offset") {
        val c = Camera().panBy(15.0, -8.0)
        near(c.offsetX, 15.0, 1e-12, "dx"); near(c.offsetY, -8.0, 1e-12, "dy")
    }

    test("clamped: large world cannot be dragged off-screen") {
        val world = WorldBounds(5000.0, 3500.0)
        val far = Camera(1.0, 900.0, 900.0).clamped(world, 1080.0, 1800.0)
        near(far.offsetX, 0.0, 1e-9, "left edge"); near(far.offsetY, 0.0, 1e-9, "top edge")
        val away = Camera(1.0, -99999.0, -99999.0).clamped(world, 1080.0, 1800.0)
        near(away.offsetX, 1080.0 - 5000.0, 1e-9, "right edge")
        near(away.offsetY, 1800.0 - 3500.0, 1e-9, "bottom edge")
    }

    test("clamped: world smaller than view is centered") {
        val c = Camera(0.1, 500.0, 500.0).clamped(WorldBounds(5000.0, 3500.0), 1080.0, 1800.0)
        near(c.offsetX, (1080.0 - 500.0) / 2.0, 1e-9, "centered x")
        near(c.offsetY, (1800.0 - 350.0) / 2.0, 1e-9, "centered y")
    }

    test("visibleWorld matches viewport") {
        val c = Camera(2.0, -100.0, -50.0)
        val r = c.visibleWorld(400.0, 800.0)
        near(r.left, 50.0, 1e-9, "left"); near(r.top, 25.0, 1e-9, "top")
        near(r.right, 250.0, 1e-9, "right"); near(r.bottom, 425.0, 1e-9, "bottom")
    }

    test("grid: adaptive step keeps lines >= 14 px apart") {
        near(Grid.adaptiveStep(1.0), 20.0, 1e-9, "scale 1")
        near(Grid.adaptiveStep(0.5), 100.0, 1e-9, "scale 0.5: 10px -> x5")
        near(Grid.adaptiveStep(0.1), 500.0, 1e-9, "scale 0.1")
        for (s in listOf(0.1, 0.3, 0.5, 1.0, 3.0, 8.0)) {
            ok(Grid.adaptiveStep(s) * s >= 14.0, "spacing at scale $s too dense")
        }
    }

    test("grid: snap and line range") {
        near(Grid.snap(29.0), 20.0, 1e-9, "snap down")
        near(Grid.snap(31.0), 40.0, 1e-9, "snap up")
        val r = Grid.lines(-5.0, 105.0, 20.0)
        ok(r.first == 0L && r.last == 5L, "lines 0..5 expected, got $r")
    }

    println()
    println("passed=$passed failed=$failures")
    if (failures > 0) exitProcess(1)
}
