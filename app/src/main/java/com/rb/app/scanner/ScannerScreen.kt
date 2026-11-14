@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.rb.app.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rb.app.data.*
import com.rb.app.ui.NumberFont

@Composable
fun ScannerScreen(repository: InventoryRepository, groups: List<GroupSummary>, initial: String? = null, done: (String?, ScanResult) -> Unit) {
    val vm: ScannerViewModel = viewModel(factory = viewModelFactory { initializer { ScannerViewModel(repository, createSavedStateHandle(), initial) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var series by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.result) { state.result?.let { done(state.destination, it) } }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val reviewed = remember(state.plan) { state.plan?.rows?.associateBy { it.serial }.orEmpty() }
    val target = state.destination?.let { id -> groups.find { it.group.id == id }?.group?.let { "${it.ownerName} — ${it.code}" } ?: "مجموعة غير متاحة" } ?: "الأرشيف العام"
    BackHandler(enabled = state.camera || state.busy) { if (!state.busy) vm.review() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("مسح الشرائح", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!state.camera) TextButton(onClick = { series = true }, enabled = !state.busy) { Text("إضافة سلسلة") }
        }
        OutlinedButton(onClick = { selecting = true }, enabled = !state.busy && state.result == null, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("الوجهة: $target · تغيير")
        }
        if (state.camera) {
            CameraPanel(Modifier.fillMaxWidth().weight(1f), vm::reading, vm::error)
            val duplicate = state.message.startsWith("موجودة في قائمة الجلسة")
            Surface(color = if (duplicate) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${state.serials.size} شريحة في الجلسة", fontWeight = FontWeight.Bold)
                    if (state.suggestions.isEmpty()) state.last?.let { Text("آخر إضافة · ${it.takeLast(4)}", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { duplicate -> Icons.Outlined.Info; state.suggestions.isNotEmpty() -> Icons.Outlined.CheckCircle; state.last == null -> Icons.Outlined.QrCodeScanner; else -> Icons.Outlined.CheckCircle }, null, Modifier.size(20.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            if (state.suggestions.isNotEmpty()) {
                Text("جاهز للتأكيد · طابق الرقم الكامل:", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.heightIn(max = (state.suggestions.size * 100).coerceAtMost(180).dp)) {
                    items(state.suggestions, key = { it }) { serial -> TextButton(onClick = { vm.confirm(serial) }, modifier = Modifier.fillMaxWidth()) { Column { SerialNumber(serial.takeLast(4), true); SerialNumber(serial); Text("تأكيد وإضافة") } } }
                }
            }
            if (state.suggestions.isNotEmpty()) OutlinedButton(onClick = vm::rescan, modifier = Modifier.padding(horizontal = 12.dp)) { Text("إعادة المسح") }
            state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = vm::review, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("إيقاف ومراجعة · ${state.serials.size}") }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(if (state.review) "مراجعة الجلسة · ${state.serials.size} شريحة" else "امسح الشرائح تباعًا", style = MaterialTheme.typography.titleMedium)
                }
                if (state.message.startsWith("أضيفت")) item { Text(state.message, style = MaterialTheme.typography.bodySmall) }
                if (state.serials.isEmpty()) item { Text("القائمة فارغة. اختر الوجهة ثم ابدأ الكاميرا.", Modifier.padding(vertical = 16.dp)) }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.review && state.serials.isNotEmpty() && (state.plan == null || state.error != null)) item { OutlinedButton(onClick = vm::refresh, enabled = !state.busy) { Text("تحديث حالات الشرائح") } }
                items(state.serials, key = { it }) { serial ->
                    val row = reviewed[serial]
                    Surface(shape = MaterialTheme.shapes.medium, color = if (row?.existing != null && row.existing.groupId != state.destination) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SerialNumber(serial.takeLast(4), true)
                            SerialNumber(serial)
                            val conflict = row?.existing != null && row.existing.groupId != state.destination
                            Text(when {
                                row == null -> "بانتظار التحقق"
                                row.existing == null -> "جديدة ← $target"
                                !conflict -> "مسجلة مسبقًا في الوجهة نفسها — ستُتجاهل"
                                else -> "الموقع الحالي: ${row.source?.let { "${it.ownerName} — ${it.code}" } ?: "الأرشيف العام"}\nالوجهة المطلوبة: $target"
                            }, style = MaterialTheme.typography.bodySmall)
                            if (conflict) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = serial in state.approved, onCheckedChange = { vm.approve(serial, it) }, enabled = !state.busy)
                                Text(if (serial in state.approved) "أوافق على نقل هذه الشريحة" else "تجاهل النقل (حدد للموافقة)", style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                TextButton(onClick = { edit = serial }, enabled = !state.busy) { Text("تعديل") }
                                TextButton(onClick = { vm.remove(serial) }, enabled = !state.busy) { Text("إزالة من الجلسة") }
                            }
                        }
                    }
                }
                item { TextButton(onClick = { adding = true }, enabled = !state.busy) { Text("إدخال يدوي عند تعذر القراءة") } }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::camera, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.serials.isEmpty()) "بدء الكاميرا" else "إضافة بالكاميرا") }
                    Button(onClick = vm::save, enabled = !state.busy && state.plan != null && state.serials.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(if (state.saving) "جارٍ الحفظ…" else if (state.busy) "جارٍ التحقق…" else "حفظ الدفعة") }
                }
            }
        }
    }
    if (series) SeriesDialog(dismiss = { series = false }, add = { first, last -> if (vm.series(first, last)) series = false })
    if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("وجهة الدفعة") },
        text = { LazyColumn(Modifier.heightIn(max = 380.dp)) {
            item { TextButton(onClick = { vm.destination(null); selecting = false }) { Text("الأرشيف العام") } }
            items(groups, key = { it.group.id }) { summary -> TextButton(onClick = { vm.destination(summary.group.id); selecting = false }) { Text("${summary.group.ownerName} — ${summary.group.code}") } }
        } }, confirmButton = { TextButton(onClick = { selecting = false }) { Text("إغلاق") } })
    if (edit != null || adding) {
        var text by rememberSaveable(edit, adding) { mutableStateOf(edit ?: "") }
        var invalid by rememberSaveable(edit, adding) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { edit = null; adding = false }, title = { Text(if (adding) "إضافة رقم مؤكد" else "تصحيح الرقم") },
            text = { Column {
                Text("طابق جميع الخانات مع الشريحة؛ لا تعتمد على آخر أربع فقط.")
                OutlinedTextField(value = text, onValueChange = { text = it; invalid = false }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(textDirection = TextDirection.Ltr, fontFamily = NumberFont), label = { Text("الرقم الكامل") }, isError = invalid)
                if (invalid) Text("الرقم غير صالح أو موجود في الجلسة", color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { TextButton(onClick = {
                if (adding) {
                    val serial = SerialRecognition.manual(text)
                    if (serial == null || serial in state.serials) invalid = true
                    else { vm.add(serial); vm.review(); adding = false }
                } else if (vm.edit(requireNotNull(edit), text)) edit = null else invalid = true
            }) { Text("اعتماد الرقم") } }, dismissButton = { TextButton(onClick = { edit = null; adding = false }) { Text("إلغاء") } })
    }
}

@Composable
internal fun SerialNumber(value: String, large: Boolean = false) {
    Text(value, fontFamily = NumberFont, fontWeight = if (large) FontWeight.Bold else FontWeight.Normal,
        style = TextStyle(textDirection = TextDirection.Ltr, fontSize = if (large) 28.sp else 14.sp))
}
