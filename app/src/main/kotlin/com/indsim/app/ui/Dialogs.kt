package com.indsim.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indsim.app.EditorViewModel
import com.indsim.editor.PType
import com.indsim.editor.Presets
import com.indsim.editor.Registry

@Composable
fun OpenDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val names = vm.projectNames()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.t("پروژه‌های ذخیره‌شده", "Saved projects")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (names.isEmpty()) Text(vm.t("هنوز پروژه‌ای ذخیره نشده است.", "No saved projects yet."))
                for (n in names) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, Modifier.weight(1f).clickable { vm.openProject(n); onDismiss() }.padding(vertical = 12.dp))
                        TextButton(onClick = { vm.deleteProject(n) }) { Text(vm.t("حذف", "Delete"), color = Color(0xFFC62828)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(vm.t("بستن", "Close")) } },
    )
}

@Composable
fun PresetsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.t("مدارهای آماده", "Ready circuits")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (p in Presets.all) {
                    Column(Modifier.fillMaxWidth().clickable { vm.openPreset(p.id); onDismiss() }.padding(vertical = 8.dp)) {
                        Text(if (vm.lang == "fa") p.fa else p.en, fontSize = 15.sp)
                        Text(if (vm.lang == "fa") p.descFa else p.descEn, fontSize = 12.sp, color = Color(0xFF607D8B))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(vm.t("بستن", "Close")) } },
    )
}

@Composable
fun SaveDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(vm.doc.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.t("ذخیره با نام", "Save as")) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.saveAs(name); onDismiss() }) { Text(vm.t("ذخیره", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(vm.t("انصراف", "Cancel")) } },
    )
}

@Composable
fun PropsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val id = vm.singleSelected ?: return
    val placed = vm.doc.find(id) ?: return
    val def = Registry.get(placed.type)
    val values = remember(id) { mutableStateMapOf<String, String>().apply { putAll(def.defaults + placed.params) } }
    val coils = vm.doc.placed.filter { Registry.get(it.type).isCoilLike }.map { it.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$id — ${vm.nameOf(placed.type)}", fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (def.params.isEmpty()) Text(vm.t("این قطعه ویژگی قابل تنظیم ندارد.", "This part has no adjustable properties."))
                for (pd in def.params) {
                    val label = if (vm.lang == "fa") pd.fa else pd.en
                    when (pd.type) {
                        PType.NUMBER -> OutlinedTextField(
                            value = values[pd.key].orEmpty(), onValueChange = { values[pd.key] = it },
                            label = { Text(label) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        PType.COIL_LINK -> Column(Modifier.padding(vertical = 4.dp)) {
                            Text(label, fontSize = 12.sp, color = Color(0xFF607D8B))
                            if (coils.isEmpty()) Text(vm.t("هنوز بوبینی در نقشه نیست", "No coil on the drawing yet"), fontSize = 13.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                for (c in coils) ChoiceChip(c, values[pd.key] == c) { values[pd.key] = c }
                            }
                        }
                        PType.CHOICE -> Column(Modifier.padding(vertical = 4.dp)) {
                            Text(label, fontSize = 12.sp, color = Color(0xFF607D8B))
                            Row {
                                for (o in pd.options) ChoiceChip(o, values[pd.key] == o) { values[pd.key] = o }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.setParams(id, values.toMap()); onDismiss() }) { Text(vm.t("اعمال", "Apply")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(vm.t("انصراف", "Cancel")) } },
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            if (selected) "● $label" else "○ $label",
            color = if (selected) Color(0xFF1976D2) else Color(0xFF455A64),
        )
    }
}
