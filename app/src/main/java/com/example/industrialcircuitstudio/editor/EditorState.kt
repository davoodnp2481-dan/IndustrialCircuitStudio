package com.example.industrialcircuitstudio.editor

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.industrialcircuitstudio.domain.model.*
import java.util.UUID

enum class ToolMode { SELECT, WIRE }

class EditorState {
    var projectName = mutableStateOf("پروژه جدید")
    val components = mutableStateListOf<CircuitComponent>()
    val wires = mutableStateListOf<CircuitWire>()
    var selectedComponentId = mutableStateOf<String?>(null)
    var pendingTerminalId = mutableStateOf<String?>(null)
    var mode = mutableStateOf(ToolMode.SELECT)
    private val undoStack = ArrayDeque<CircuitProject>()
    private val redoStack = ArrayDeque<CircuitProject>()

    fun snapshot() = CircuitProject(projectName.value, components.toList(), wires.toList())
    private fun checkpoint() {
        undoStack.addLast(snapshot())
        if (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
    }
    fun add(type: ComponentType) {
        checkpoint()
        val prefix = type.prefix
        val next = (components.mapNotNull {
            if (it.reference.startsWith(prefix)) it.reference.removePrefix(prefix).toIntOrNull() else null
        }.maxOrNull() ?: 0) + 1
        val terms = CircuitComponent.defaultTerminals(type)
        val offset = 140f + components.size * 34f
        components.add(CircuitComponent(reference = "$prefix$next", type = type,
            x = offset, y = offset, terminals = terms))
        selectedComponentId.value = components.last().id
    }
    fun move(id: String, dx: Float, dy: Float) {
        val index = components.indexOfFirst { it.id == id }
        if (index < 0) return
        val c = components[index]
        components[index] = c.copy(x = c.x + dx, y = c.y + dy)
    }
    fun beginMove() = checkpoint()
    fun rotateSelected() {
        val id = selectedComponentId.value ?: return
        val i = components.indexOfFirst { it.id == id }; if (i < 0) return
        checkpoint(); components[i] = components[i].copy(rotation = (components[i].rotation + 90) % 360)
    }
    fun deleteSelected() {
        val id = selectedComponentId.value ?: return
        val c = components.firstOrNull { it.id == id } ?: return
        checkpoint()
        val terminalIds = c.terminals.map { it.id }.toSet()
        components.removeAll { it.id == id }
        wires.removeAll { it.startTerminalId in terminalIds || it.endTerminalId in terminalIds }
        selectedComponentId.value = null
    }
    fun terminalPosition(id: String): Point? {
        val c = components.firstOrNull { it.terminals.any { t -> t.id == id } } ?: return null
        val t = c.terminals.first { it.id == id }
        val angle = Math.toRadians(c.rotation.toDouble())
        val rx = t.offset.x * kotlin.math.cos(angle).toFloat() - t.offset.y * kotlin.math.sin(angle).toFloat()
        val ry = t.offset.x * kotlin.math.sin(angle).toFloat() + t.offset.y * kotlin.math.cos(angle).toFloat()
        return Point(c.x + rx, c.y + ry)
    }
    fun connectTerminal(id: String) {
        val first = pendingTerminalId.value
        if (first == null) { pendingTerminalId.value = id; return }
        if (first == id) { pendingTerminalId.value = null; return }
        val a = terminalPosition(first) ?: return
        val b = terminalPosition(id) ?: return
        checkpoint()
        val midX = (a.x + b.x) / 2f
        wires.add(CircuitWire(startTerminalId = first, endTerminalId = id,
            points = listOf(a, Point(midX, a.y), Point(midX, b.y), b)))
        pendingTerminalId.value = null
    }
    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
    }
    fun restore(project: CircuitProject) {
        projectName.value = project.name
        components.clear(); components.addAll(project.components)
        wires.clear(); wires.addAll(project.wires)
        selectedComponentId.value = null; pendingTerminalId.value = null
    }
}
