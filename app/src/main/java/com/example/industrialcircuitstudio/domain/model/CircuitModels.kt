package com.example.industrialcircuitstudio.domain.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ComponentType(val prefix: String, val label: String) {
    SOURCE("L", "منبع فاز"),
    NEUTRAL("N", "نول"),
    PUSH_BUTTON_NO("S", "شستی NO"),
    PUSH_BUTTON_NC("S", "شستی NC"),
    CONTACTOR("K", "کنتاکتور"),
    LAMP("H", "چراغ"),
    MOTOR("M", "موتور"),
    FUSE("F", "فیوز"),
    TERMINAL("X", "ترمینال")
}

data class Point(val x: Float, val y: Float)

data class Terminal(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val offset: Point
)

data class CircuitComponent(
    val id: String = UUID.randomUUID().toString(),
    val reference: String,
    val type: ComponentType,
    val x: Float,
    val y: Float,
    val rotation: Int = 0,
    val scale: Float = 1f,
    val terminals: List<Terminal> = defaultTerminals(type)
) {
    companion object {
        fun defaultTerminals(type: ComponentType): List<Terminal> = when (type) {
            ComponentType.SOURCE, ComponentType.NEUTRAL -> listOf(
                Terminal(name = if (type == ComponentType.SOURCE) "L" else "N", offset = Point(0f, 34f))
            )
            ComponentType.PUSH_BUTTON_NO, ComponentType.PUSH_BUTTON_NC -> listOf(
                Terminal(name = "1", offset = Point(0f, 0f)),
                Terminal(name = "2", offset = Point(80f, 0f))
            )
            ComponentType.CONTACTOR -> listOf(
                Terminal(name = "A1", offset = Point(0f, 0f)),
                Terminal(name = "A2", offset = Point(0f, 64f)),
                Terminal(name = "13", offset = Point(80f, 0f)),
                Terminal(name = "14", offset = Point(80f, 64f))
            )
            ComponentType.LAMP, ComponentType.MOTOR, ComponentType.FUSE, ComponentType.TERMINAL ->
                listOf(
                    Terminal(name = "1", offset = Point(0f, 0f)),
                    Terminal(name = "2", offset = Point(80f, 0f))
                )
        }
    }
}

data class CircuitWire(
    val id: String = UUID.randomUUID().toString(),
    val startTerminalId: String,
    val endTerminalId: String,
    val points: List<Point>
)

data class CircuitProject(
    val name: String = "پروژه جدید",
    val components: List<CircuitComponent> = emptyList(),
    val wires: List<CircuitWire> = emptyList()
)

object ProjectJson {
    fun encode(project: CircuitProject): String {
        val root = JSONObject().put("format", "industrial-circuit-studio")
            .put("version", 1).put("projectName", project.name)
        val components = JSONArray()
        project.components.forEach { c ->
            val terminals = JSONArray()
            c.terminals.forEach { t ->
                terminals.put(JSONObject().put("id", t.id).put("name", t.name)
                    .put("x", t.offset.x).put("y", t.offset.y))
            }
            components.put(JSONObject().put("id", c.id).put("reference", c.reference)
                .put("type", c.type.name).put("x", c.x).put("y", c.y)
                .put("rotation", c.rotation).put("scale", c.scale).put("terminals", terminals))
        }
        val wires = JSONArray()
        project.wires.forEach { w ->
            val pts = JSONArray()
            w.points.forEach { p -> pts.put(JSONObject().put("x", p.x).put("y", p.y)) }
            wires.put(JSONObject().put("id", w.id).put("start", w.startTerminalId)
                .put("end", w.endTerminalId).put("points", pts))
        }
        root.put("components", components).put("wires", wires)
        return root.toString(2)
    }

    fun decode(raw: String): CircuitProject {
        val root = JSONObject(raw)
        require(root.optString("format") == "industrial-circuit-studio") { "فرمت فایل معتبر نیست." }
        val components = mutableListOf<CircuitComponent>()
        val ca = root.getJSONArray("components")
        for (i in 0 until ca.length()) {
            val o = ca.getJSONObject(i)
            val type = ComponentType.valueOf(o.getString("type"))
            val ts = o.getJSONArray("terminals")
            val terminals = (0 until ts.length()).map { j ->
                val t = ts.getJSONObject(j)
                Terminal(t.getString("id"), t.getString("name"), Point(t.getDouble("x").toFloat(), t.getDouble("y").toFloat()))
            }
            components += CircuitComponent(
                id = o.getString("id"), reference = o.getString("reference"), type = type,
                x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
                rotation = o.optInt("rotation"), scale = o.optDouble("scale", 1.0).toFloat(),
                terminals = terminals
            )
        }
        val wires = mutableListOf<CircuitWire>()
        val wa = root.getJSONArray("wires")
        for (i in 0 until wa.length()) {
            val o = wa.getJSONObject(i)
            val pa = o.getJSONArray("points")
            val points = (0 until pa.length()).map { j ->
                val p = pa.getJSONObject(j)
                Point(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
            }
            wires += CircuitWire(o.getString("id"), o.getString("start"), o.getString("end"), points)
        }
        return CircuitProject(root.optString("projectName", "پروژه بازیابی‌شده"), components, wires)
    }
}
