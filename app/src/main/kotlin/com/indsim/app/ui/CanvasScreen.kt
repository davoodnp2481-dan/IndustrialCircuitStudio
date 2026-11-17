package com.indsim.app.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indsim.app.EditorViewModel
import com.indsim.app.Exporter
import com.indsim.app.Mode
import com.indsim.app.Tool
import com.indsim.app.render.RenderState
import com.indsim.app.render.drawEditor
import com.indsim.canvas.Camera
import com.indsim.editor.Category
import com.indsim.editor.Document
import com.indsim.editor.Edit
import com.indsim.editor.Geo
import com.indsim.editor.NetColors
import com.indsim.editor.Registry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

private enum class Dlg { NONE, OPEN, PRESETS, SAVE, PROPS }

@Composable
fun AppRoot(vm: EditorViewModel = viewModel()) {
    val dir = if (vm.lang == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        MaterialTheme(colorScheme = lightColorScheme()) { EditorScreen(vm) }
    }
}

@Composable
private fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf(Dlg.NONE) }
    var showPalette by remember { mutableStateOf(false) }

    LaunchedEffect(vm.mode) {
        if (vm.mode != Mode.DESIGN) {
            while (isActive) { vm.simFrame(); delay(20) }
        }
    }
    LaunchedEffect(vm.message) {
        if (vm.message != null) { delay(3500); vm.message = null }
    }

    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        TopBar(vm, onDialog = { dialog = it }, onExport = { pdf -> export(vm, ctx, pdf) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CanvasArea(vm)
            if (vm.doc.placed.isEmpty() && vm.mode == Mode.DESIGN) {
                Text(
                    vm.t("برای شروع: دکمه‌ی ＋ برای افزودن تجهیز، یا منو ← مدارهای آماده",
                        "To start: tap ＋ to add equipment, or Menu → Ready circuits"),
                    Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center,
                    color = Color(0xFF607D8B), fontSize = 15.sp,
                )
            }
            vm.message?.let { m ->
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = RoundedCornerShape(10.dp), color = Color(0xFF263238), shadowElevation = 6.dp,
                ) { Text(m, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp) }
            }
        }
        when (vm.mode) {
            Mode.DESIGN -> DesignBar(vm, showPalette, onPalette = { showPalette = !showPalette }, onProps = { dialog = Dlg.PROPS })
            Mode.RUN -> RunBar(vm)
            Mode.INSPECT -> InspectBar(vm)
        }
    }

    when (dialog) {
        Dlg.OPEN -> OpenDialog(vm) { dialog = Dlg.NONE }
        Dlg.PRESETS -> PresetsDialog(vm) { dialog = Dlg.NONE }
        Dlg.SAVE -> SaveDialog(vm) { dialog = Dlg.NONE }
        Dlg.PROPS -> if (vm.singleSelected != null) PropsDialog(vm) { dialog = Dlg.NONE } else dialog = Dlg.NONE
        Dlg.NONE -> {}
    }
}

private fun export(vm: EditorViewModel, ctx: android.content.Context, pdf: Boolean) {
    try {
        val bmp = Exporter.render(vm.doc, vm.realistic)
        if (bmp == null) { vm.message = vm.t("نقشه خالی است", "The drawing is empty"); return }
        val f = if (pdf) Exporter.savePdf(ctx, bmp, vm.doc.name) else Exporter.savePng(ctx, bmp, vm.doc.name)
        Exporter.share(ctx, f, if (pdf) "application/pdf" else "image/png")
    } catch (e: Exception) {
        Toast.makeText(ctx, (e.message ?: "export failed"), Toast.LENGTH_LONG).show()
    }
}

// ---------------- اجزای عمومی ----------------

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFECEFF1)
    val fg = if (selected) Color.White else Color(0xFF263238)
    Box(
        Modifier.padding(horizontal = 3.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = if (enabled) fg else fg.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1) }
}

// ---------------- نوار بالا ----------------

@Composable
private fun TopBar(vm: EditorViewModel, onDialog: (Dlg) -> Unit, onExport: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = Color(0xFFF1F3F5), shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Chip("☰") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable fun item(text: String, action: () -> Unit) =
                        DropdownMenuItem(text = { Text(text) }, onClick = { menu = false; action() })
                    item(vm.t("پروژه‌ی جدید", "New project")) { vm.newProject() }
                    item(vm.t("بازکردن پروژه…", "Open project…")) { onDialog(Dlg.OPEN) }
                    item(vm.t("مدارهای آماده…", "Ready circuits…")) { onDialog(Dlg.PRESETS) }
                    item(vm.t("ذخیره با نام…", "Save as…")) { onDialog(Dlg.SAVE) }
                    item(vm.t("خروجی تصویر (PNG)", "Export image (PNG)")) { onExport(false) }
                    item(vm.t("خروجی PDF", "Export PDF")) { onExport(true) }
                    item(vm.t("نمایش کامل نقشه", "Fit drawing to screen")) { vm.fit() }
                    item(if (vm.lang == "fa") "English" else "فارسی") { vm.lang = if (vm.lang == "fa") "en" else "fa" }
                }
            }
            Text(
                vm.doc.name, Modifier.clickable { onDialog(Dlg.SAVE) }.padding(horizontal = 6.dp).width(96.dp),
                fontSize = 13.sp, maxLines = 1, color = Color(0xFF37474F),
            )
            Chip("✎ " + vm.t("طراحی", "Design"), vm.mode == Mode.DESIGN) { if (vm.mode != Mode.DESIGN) vm.stopRun() }
            Chip("▶ " + vm.t("اجرا", "Run"), vm.mode == Mode.RUN) { vm.startRun(false) }
            Chip("🔍 " + vm.t("بازرسی", "Inspect"), vm.mode == Mode.INSPECT) { vm.startRun(true) }
            Chip(if (vm.realistic) vm.t("تصویر واقعی", "Realistic") else vm.t("نماد IEC", "IEC symbols"), vm.realistic) {
                vm.realistic = !vm.realistic
            }
            Chip("↶", enabled = vm.canUndo && vm.mode == Mode.DESIGN) { vm.undo() }
            Chip("↷", enabled = vm.canRedo && vm.mode == Mode.DESIGN) { vm.redo() }
        }
    }
}

// ---------------- بوم و ژست‌ها ----------------

@Composable
private fun CanvasArea(vm: EditorViewModel) {
    var first by remember { mutableStateOf(true) }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged {
                vm.viewW = it.width.toDouble(); vm.viewH = it.height.toDouble()
                if (first) { first = false; vm.fit() }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    vm.onDown(down.position.x, down.position.y)
                    val slop = viewConfiguration.touchSlop
                    var multi = false
                    var moved = false
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            if (!multi) { multi = true; vm.onCancelDrag() }
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid()
                            if (c.isSpecified) vm.transform(c.x, c.y, pan.x, pan.y, z)
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = event.changes.first()
                            if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                            if (moved) {
                                val d = ch.position - last
                                vm.onDrag(ch.position.x, ch.position.y, d.x, d.y)
                                ch.consume()
                            }
                            last = ch.position
                        }
                    } while (event.changes.any { it.pressed })
                    vm.onUp(!moved && !multi, down.position.x, down.position.y)
                }
            },
    ) {
        vm.tick // اشتراک در تغییر حالت شبیه‌سازی تا هر فریم دوباره رسم شود
        drawEditor(
            RenderState(
                doc = vm.doc, camera = vm.camera, real = vm.realistic,
                circuit = vm.circuit, sim = vm.sim, spins = vm.spins, wireStates = vm.wireStates,
                selection = vm.selection, selectedWire = vm.selectedWire,
                wireTool = vm.mode == Mode.DESIGN && vm.tool == Tool.WIRE,
                wireStart = vm.wireStart, rubber = vm.rubber, snapPin = vm.snapPin,
                inspectPin = vm.inspectPin, inspectId = vm.inspectId,
            ),
        )
    }
}

// ---------------- نوار پایین ----------------

@Composable
private fun DesignBar(vm: EditorViewModel, showPalette: Boolean, onPalette: () -> Unit, onProps: () -> Unit) {
    Surface(color = Color(0xFFF1F3F5), shadowElevation = 4.dp) {
        Column {
            if (showPalette) Palette(vm)
            if (vm.selectedWire != null) WireColors(vm)
            val hint = when (vm.tool) {
                Tool.WIRE -> if (vm.wireStart == null) vm.t("سیم‌کشی: از یک ترمینال بکشید یا آن را لمس کنید، بعد ترمینال دوم",
                    "Wiring: drag from a terminal, or tap it, then tap the second terminal")
                else vm.t("ترمینال دوم را لمس کنید", "Tap the second terminal")
                Tool.JUNCTION -> vm.t("روی یک سیم بزنید تا انشعاب بسازید", "Tap a wire to branch from it")
                Tool.ERASE -> vm.t("روی قطعه یا سیم بزنید تا حذف شود", "Tap a part or wire to delete it")
                Tool.SELECT -> if (vm.multiSelect) vm.t("انتخاب چندتایی: قطعات را لمس کنید، سپس بکشید", "Multi-select: tap parts, then drag") else null
            }
            if (hint != null) Text(hint, Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 12.sp, color = Color(0xFF455A64))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                Chip("＋ " + vm.t("تجهیز", "Add"), showPalette) { onPalette() }
                Chip("↖ " + vm.t("انتخاب", "Select"), vm.tool == Tool.SELECT) { vm.selectTool(Tool.SELECT) }
                Chip("▣ " + vm.t("چندتایی", "Multi"), vm.multiSelect) { vm.multiSelect = !vm.multiSelect }
                Chip("╱ " + vm.t("سیم", "Wire"), vm.tool == Tool.WIRE) { vm.selectTool(Tool.WIRE) }
                Chip("● " + vm.t("انشعاب", "Junction"), vm.tool == Tool.JUNCTION) { vm.selectTool(Tool.JUNCTION) }
                Chip("⌫ " + vm.t("پاک‌کن", "Erase"), vm.tool == Tool.ERASE) { vm.selectTool(Tool.ERASE) }
                Chip("⟳ " + vm.t("چرخش", "Rotate"), enabled = vm.selection.isNotEmpty()) { vm.rotateSelection() }
                Chip("🗑 " + vm.t("حذف", "Delete"), enabled = vm.selection.isNotEmpty() || vm.selectedWire != null) { vm.deleteSelection() }
                Chip("⚙ " + vm.t("ویژگی‌ها", "Properties"), enabled = vm.singleSelected != null) { onProps() }
            }
        }
    }
}

@Composable
private fun WireColors(vm: EditorViewModel) {
    val colors = listOf(
        NetColors.BROWN, NetColors.BLACK, NetColors.GREY, NetColors.BLUE,
        NetColors.GREEN_YELLOW, NetColors.RED, 0xFFFFB300.toInt(),
    )
    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vm.t("رنگ سیم:", "Wire color:"), fontSize = 12.sp)
        for (c in colors) {
            Box(
                Modifier.padding(horizontal = 4.dp).size(28.dp).clip(CircleShape).background(Color(c)).clickable { vm.setWireColor(c) },
            )
        }
        Chip(vm.t("خودکار", "Auto")) { vm.setWireColor(null) }
    }
}

private fun categoryName(vm: EditorViewModel, c: Category): String = when (c) {
    Category.SOURCE -> vm.t("منابع", "Sources")
    Category.PROTECTION -> vm.t("حفاظت", "Protection")
    Category.COMMAND -> vm.t("فرمان", "Control")
    Category.CONTACTOR -> vm.t("کنتاکتور و رله", "Contactors")
    Category.TIMER -> vm.t("تایمر", "Timers")
    Category.LOAD -> vm.t("بار", "Loads")
    Category.MOTOR -> vm.t("موتور", "Motors")
    Category.WIRING -> vm.t("سایر", "Other")
}

@Composable
private fun Palette(vm: EditorViewModel) {
    var cat by remember { mutableStateOf(Category.COMMAND) }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            for (c in Category.values()) Chip(categoryName(vm, c), cat == c) { cat = c }
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            items(Registry.byCategory(cat)) { def ->
                Column(
                    Modifier.width(96.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.addComponent(def.type) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TypePreview(def.type, vm.realistic)
                    Text(if (vm.lang == "fa") def.fa else def.en, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TypePreview(type: String, real: Boolean) {
    val doc: Document = remember(type) {
        Edit.addWithId(Document(), Registry.get(type).prefix + "1", type, 0, 0).doc
    }
    Canvas(Modifier.size(64.dp)) {
        val b = Geo.bounds(doc.placed[0])
        val wWorld = (b.maxX - b.minX + 1) * 20.0
        val hWorld = (b.maxY - b.minY + 2) * 20.0
        val s = min(size.width / wWorld, size.height / hWorld)
        val cam = Camera(
            s,
            (size.width - (b.maxX - b.minX) * 20.0 * s) / 2.0 - b.minX * 20.0 * s,
            (size.height - (b.maxY - b.minY) * 20.0 * s) / 2.0 - b.minY * 20.0 * s + 6.0,
        )
        drawEditor(RenderState(doc = doc, camera = cam, real = real, showGrid = false, background = Color.Transparent))
    }
}

@Composable
private fun RunBar(vm: EditorViewModel) {
    Surface(color = Color(0xFFF1F3F5), shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                vm.t("حالت اجرا: روی شستی‌ها بزنید و نگه دارید؛ سلکتورها و کلیدها با یک لمس تغییر می‌کنند.",
                    "Run mode: press and hold buttons; tap selectors and switches to change them."),
                fontSize = 12.sp, color = Color(0xFF455A64),
            )
            for (a in vm.alerts) Text(a, fontSize = 13.sp, color = Color(0xFFC62828))
            Row { Chip("⟲ " + vm.t("شروع دوباره", "Restart")) { vm.resetRun() } }
        }
    }
}

@Composable
private fun InspectBar(vm: EditorViewModel) {
    Surface(color = Color(0xFFF1F3F5), shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text(vm.t("بازرسی: ترمینال یا قطعه را لمس کنید (سیم‌های زرد برق‌دارند، آبی فقط جریان برگشتی)",
                "Inspect: tap a terminal or part (amber wires are live, cyan carry only return current)"),
                fontSize = 12.sp, color = Color(0xFF455A64))
            for (a in vm.alerts) Text(a, fontSize = 13.sp, color = Color(0xFFC62828))
            for (l in vm.inspectLines()) Text(l, fontSize = 13.sp)
        }
    }
}
