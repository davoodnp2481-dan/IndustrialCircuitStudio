package com.example.industrialcircuitstudio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.industrialcircuitstudio.domain.model.*
import com.example.industrialcircuitstudio.editor.EditorState
import com.example.industrialcircuitstudio.editor.ToolMode

private val Navy = Color(0xFF16324F)
private val Teal = Color(0xFF00A6A6)
private val Ink = Color(0xFF202A35)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen() {
    val state = remember { EditorState() }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset(0f, 0f)) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var lastDrag by remember { mutableStateOf(Offset.Zero) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(ProjectJson.encode(state.snapshot()))
            }
            message = "پروژه ذخیره شد"
        }.onFailure { message = "خطا در ذخیره: ${it.message}" }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("فایل خوانده نشد")
            state.restore(ProjectJson.decode(raw))
            message = "پروژه باز شد"
        }.onFailure { message = "فایل نامعتبر: ${it.message}" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("INDUSTRIAL CIRCUIT STUDIO", fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(state.projectName.value, fontSize = 11.sp, color = Color.LightGray) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White),
                actions = {
                    TextButton(onClick = { saveLauncher.launch("${state.projectName.value}.fbd") }) { Text("ذخیره", color = Color.White) }
                    TextButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Text("بازکردن", color = Color.White) }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Navy).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolChip("انتخاب", state.mode.value == ToolMode.SELECT) { state.mode.value = ToolMode.SELECT }
                    ToolChip("سیم‌کشی", state.mode.value == ToolMode.WIRE) { state.mode.value = ToolMode.WIRE }
                    ToolChip("↶", false) { state.undo() }
                    ToolChip("↷", false) { state.redo() }
                    ToolChip("چرخش", false) { state.rotateSelected() }
                    ToolChip("حذف", false) { state.deleteSelected() }
                    ToolChip("Fit / 100%", false) { scale = 1f; pan = Offset.Zero }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ComponentType.entries.forEach { type ->
                        OutlinedButton(onClick = { state.add(type) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Text(type.label, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        snackbarHost = { if (message.isNotBlank()) SnackbarHost(hostState = remember { SnackbarHostState() }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color(0xFFE9EDF1))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.mode.value == ToolMode.WIRE) "ترمینال مبدأ و سپس مقصد را لمس کنید" else "بوم طراحی • پس‌زمینه سفید", color = Navy, fontSize = 12.sp)
                Text("${(scale * 100).toInt()}%", color = Navy, fontSize = 12.sp)
            }
            CircuitCanvas(
                state = state, scale = scale, pan = pan,
                onTransform = { zoom, delta -> scale = (scale * zoom).coerceIn(0.25f, 4f); pan += delta },
                onTap = { screenPoint ->
                    val world = Offset((screenPoint.x - pan.x) / scale, (screenPoint.y - pan.y) / scale)
                    val terminal = findTerminal(state, world)
                    if (state.mode.value == ToolMode.WIRE && terminal != null) {
                        state.connectTerminal(terminal.id)
                    } else {
                        val hit = state.components.lastOrNull { world.x in (it.x - 35)..(it.x + 115) && world.y in (it.y - 35)..(it.y + 90) }
                        state.selectedComponentId.value = hit?.id
                    }
                },
                onDragStart = { p ->
                    val w = Offset((p.x - pan.x) / scale, (p.y - pan.y) / scale)
                    val hit = state.components.lastOrNull { w.x in (it.x - 35)..(it.x + 115) && w.y in (it.y - 35)..(it.y + 90) }
                    dragId = if (state.mode.value == ToolMode.SELECT) hit?.id else null
                    if (dragId != null) { state.selectedComponentId.value = dragId; state.beginMove() }
                    lastDrag = p
                },
                onDrag = { delta ->
                    val id = dragId
                    if (id != null) state.move(id, delta.x / scale, delta.y / scale)
                    lastDrag += delta
                },
                onDragEnd = { dragId = null }
            )
            if (state.pendingTerminalId.value != null) {
                Text("ترمینال شروع انتخاب شد؛ ترمینال مقصد را لمس کنید یا همان نقطه را دوباره لمس کنید.",
                    modifier = Modifier.padding(8.dp), fontSize = 11.sp, color = Navy)
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Teal, selectedLabelColor = Color.White,
            containerColor = Color(0xFF294760), labelColor = Color.White
        ))
}

@Composable
private fun CircuitCanvas(
    state: EditorState, scale: Float, pan: Offset,
    onTransform: (Float, Offset) -> Unit,
    onTap: (Offset) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    Canvas(Modifier.fillMaxSize().background(Color.White)
        .pointerInput(Unit) {
            detectTransformGestures { centroid, panChange, zoom, _ ->
                onTransform(zoom, panChange)
            }
        }
        .pointerInput(state.mode.value, scale, pan) {
            detectTapGestures { onTap(it) }
        }
        .pointerInput(state.mode.value, scale, pan) {
            detectDragGestures(onDragStart = { onDragStart(it) },
                onDrag = { change, amount -> change.consume(); onDrag(amount) },
                onDragEnd = onDragEnd)
        }
    ) {
        val canvasScale = scale
        fun screen(p: Point) = Offset(p.x * canvasScale + pan.x, p.y * canvasScale + pan.y)
        // Wires render first so terminals and symbols remain visible.
        state.wires.forEach { wire ->
            val path = Path()
            wire.points.forEachIndexed { i, p ->
                val q = screen(p)
                if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            drawPath(path, Color(0xFF34495E), style = Stroke(width = 2.5f))
        }
        state.components.forEach { c ->
            val center = screen(Point(c.x, c.y))
            val w = 80f * canvasScale
            val h = 64f * canvasScale
            val selected = c.id == state.selectedComponentId.value
            val left = center.x
            val top = center.y
            when (c.type) {
                ComponentType.SOURCE, ComponentType.NEUTRAL -> {
                    drawLine(if (c.type == ComponentType.SOURCE) Color(0xFFC0392B) else Color(0xFF2471A3),
                        Offset(left, top), Offset(left, top + 34 * canvasScale), 3f)
                    drawCircle(Color.Black, 4f, Offset(left, top + 34 * canvasScale))
                }
                ComponentType.PUSH_BUTTON_NO, ComponentType.PUSH_BUTTON_NC -> {
                    drawLine(Ink, Offset(left, top), Offset(left + w, top), 2.5f)
                    drawLine(Ink, Offset(left + 28 * canvasScale, top), Offset(left + 42 * canvasScale, top - 13 * canvasScale), 2.5f)
                    if (c.type == ComponentType.PUSH_BUTTON_NC)
                        drawLine(Ink, Offset(left + 30 * canvasScale, top - 17 * canvasScale), Offset(left + 43 * canvasScale, top - 4 * canvasScale), 2f)
                }
                ComponentType.CONTACTOR -> {
                    drawCircle(Ink, 15 * canvasScale, Offset(left + 40 * canvasScale, top + 32 * canvasScale), style = Stroke(2.2f))
                    drawLine(Ink, Offset(left + 40 * canvasScale, top + 17 * canvasScale), Offset(left + 40 * canvasScale, top + 47 * canvasScale), 1.5f)
                }
                ComponentType.LAMP -> {
                    drawCircle(Color(0xFFF1C40F), 17 * canvasScale, Offset(left + 40 * canvasScale, top), style = Stroke(2.2f))
                    drawLine(Ink, Offset(left + 29 * canvasScale, top - 11 * canvasScale), Offset(left + 51 * canvasScale, top + 11 * canvasScale), 1.5f)
                    drawLine(Ink, Offset(left + 29 * canvasScale, top + 11 * canvasScale), Offset(left + 51 * canvasScale, top - 11 * canvasScale), 1.5f)
                }
                ComponentType.MOTOR -> {
                    drawCircle(Color(0xFF566573), 22 * canvasScale, Offset(left + 40 * canvasScale, top), style = Stroke(2.2f))
                }
                ComponentType.FUSE, ComponentType.TERMINAL -> {
                    drawRect(Color(0xFF566573), Offset(left + 22 * canvasScale, top - 9 * canvasScale),
                        androidx.compose.ui.geometry.Size(36 * canvasScale, 18 * canvasScale), style = Stroke(2f))
                }
            }
            if (selected) drawRoundRect(Teal, Offset(left - 27 * canvasScale, top - 30 * canvasScale),
                androidx.compose.ui.geometry.Size(135 * canvasScale, 105 * canvasScale), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
                style = Stroke(width = 2f))
            drawContext.canvas.nativeCanvas.drawText(c.reference, left - 8 * canvasScale, top - 23 * canvasScale,
                android.graphics.Paint().apply { color = android.graphics.Color.rgb(32,42,53); textSize = 12f * canvasScale; isFakeBoldText = true })
            c.terminals.forEach { t ->
                val angle = Math.toRadians(c.rotation.toDouble())
                val tx = t.offset.x * kotlin.math.cos(angle).toFloat() - t.offset.y * kotlin.math.sin(angle).toFloat()
                val ty = t.offset.x * kotlin.math.sin(angle).toFloat() + t.offset.y * kotlin.math.cos(angle).toFloat()
                val pos = screen(Point(c.x + tx, c.y + ty))
                drawCircle(if (state.pendingTerminalId.value == t.id) Teal else Color.Black, 4.5f, pos)
                drawContext.canvas.nativeCanvas.drawText(t.name, pos.x + 5f, pos.y - 5f,
                    android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 10f * canvasScale })
            }
        }
    }
}

private fun findTerminal(state: EditorState, point: Offset): Terminal? {
    return state.components.asSequence().flatMap { it.terminals.asSequence() }
        .firstOrNull { t ->
            val p = state.terminalPosition(t.id) ?: return@firstOrNull false
            kotlin.math.hypot((p.x - point.x).toDouble(), (p.y - point.y).toDouble()) < 18.0
        }
}
