@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.rb.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rb.app.data.*
import kotlinx.coroutines.launch

private data class StockOperation(val rows: List<Sim>, val type: String)

@Composable
fun StockScreen(repository: InventoryRepository, groups: List<GroupSummary>, groupId: String? = null,
    global: Boolean = false, header: @Composable () -> Unit = {}, footer: @Composable () -> Unit = {}) {
    val vm: StockViewModel = viewModel(key = "stock:$global:$groupId", factory = viewModelFactory {
        initializer { StockViewModel(repository, global, groupId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val descending by vm.descending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val rows = remember(state.rows, descending) { if (descending) state.rows.asReversed() else state.rows }
    val chosen = remember(rows, selected) { rows.filter { it.sim.id in selected }.map { it.sim } }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<StockOperation?>(null) }
    var destination by remember { mutableStateOf<Group?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    // Never retain invisible IDs after a database update. Loading never exposes actionable old rows.
    LaunchedEffect(state) { vm.pruneSelection() }
    BackHandler(chosen.isNotEmpty() && operation == null && !range && detailsId == null) { if (!busy) vm.select(emptySet()) }
    fun prepare(sims: List<Sim>, type: String) {
        vm.clearError(); destination = null; operation = StockOperation(sims.toList(), type)
    }
    Column(Modifier.fillMaxSize()) {
        if (global || groupId == null) TopAppBar(title = { Text(if (global) "البحث عن الشرائح" else "الأرشيف العام", fontWeight = FontWeight.Bold) }, windowInsets = WindowInsets(0, 0, 0, 0))
        OutlinedTextField(value = query, onValueChange = vm::query, enabled = !busy,
            label = { Text(if (global) "الرقم الكامل أو جزء منه" else "البحث في القائمة") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query("") }) { Icon(Icons.Outlined.Close, "مسح البحث") } },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontFamily = NumberFont, textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when { global -> "النتائج · ${rows.size}"; groupId == null -> "الأرشيف · $count"; else -> "الشرائح المرتبطة · $count" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = vm::sort, enabled = !busy) { Text(if (descending) "تنازلي ↓" else "تصاعدي ↑") }
            TextButton(onClick = { range = true }, enabled = !busy && !state.loading && rows.isNotEmpty()) { Text("تحديد نطاق") }
        }
        if (chosen.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${chosen.size} محددة من ${rows.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { vm.select(rows.map { it.sim.id }.toSet()) }, enabled = !busy) { Text("تحديد الكل") }
            TextButton(onClick = { vm.select(emptySet()) }, enabled = !busy) { Text("إلغاء التحديد") }
        } else if (rows.isNotEmpty() && !state.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.select(rows.map { it.sim.id }.toSet()) }, enabled = !busy) { Text("تحديد الكل") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { header() }
            if (!state.loading && state.error == null && rows.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.SimCard, null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (query.isNotEmpty()) "لا توجد نتائج" else "لا توجد شرائح", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                    Text(if (query.isNotEmpty()) "جرّب جزءًا آخر من الرقم أو امسح البحث." else if (global) "ستظهر هنا الشرائح بعد إضافتها إلى المخزون." else if (groupId == null) "الشرائح المؤرشفة تبقى محفوظة ويمكن نقلها لأي مجموعة." else "اضغط مسح وإضافة شرائح لبدء جلسة جديدة.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(rows, key = { it.sim.id }) { row ->
                val sim = row.sim
                Row(Modifier.fillMaxWidth().combinedClickable(enabled = !busy && !state.loading,
                    onClick = { if (chosen.isNotEmpty()) vm.toggle(sim.id) else detailsId = sim.id },
                    onLongClickLabel = "تحديد الشريحة", onLongClick = { vm.toggle(sim.id) })
                    .semantics { this.selected = sim.id in selected }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sim.id in selected, onCheckedChange = { vm.toggle(sim.id) }, enabled = !busy && !state.loading,
                        modifier = Modifier.semantics { contentDescription = "تحديد ${sim.serial}" })
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        SerialText(sim.last4, prominent = true)
                        SerialText(sim.serial)
                        if (global) Text(location(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { detailsId = sim.id }, enabled = !busy && !state.loading) { Icon(Icons.Outlined.Info, "تفاصيل ${sim.serial}") }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { footer() }
        }
        SnackbarHost(snackbar)
        if (chosen.isNotEmpty()) Surface(tonalElevation = 4.dp, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = !busy && !state.loading, onClick = { prepare(chosen, "move") }) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null); Spacer(Modifier.width(4.dp)); Text("نقل") }
                if (groupId != null || global) TextButton(enabled = !busy && !state.loading && chosen.any { it.groupId != null }, onClick = { prepare(chosen, "archive") }) { Icon(Icons.Outlined.Archive, null); Spacer(Modifier.width(4.dp)); Text("أرشفة") }
                TextButton(enabled = !busy && !state.loading, onClick = { prepare(chosen, "delete") }) { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp)); Text("حذف", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    val detail = rows.find { it.sim.id == detailsId }
    if (detail != null && operation == null) {
        val clipboard = LocalClipboard.current
        val clipboardScope = rememberCoroutineScope()
        var copied by remember(detail.sim.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { detailsId = null }, title = { Text("تفاصيل الشريحة") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SerialText(detail.sim.last4, prominent = true); SerialText(detail.sim.serial)
                Text(location(detail))
                OutlinedButton(onClick = { clipboardScope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("الرقم الكامل", detail.sim.serial))); copied = true } }) { Text(if (copied) "تم نسخ الرقم" else "نسخ الرقم الكامل") }
                TextButton(onClick = { prepare(listOf(detail.sim), "move") }) { Text(if (detail.sim.groupId == null) "إعادة إلى مجموعة" else "نقل الشريحة") }
                if (detail.sim.groupId != null) TextButton(onClick = { prepare(listOf(detail.sim), "archive") }) { Text("أرشفة الشريحة") }
                TextButton(onClick = { prepare(listOf(detail.sim), "delete") }) { Text("حذف الشريحة", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { detailsId = null }) { Text("إغلاق") } })
    }
    if (range) RangeDialog(rows, selected, close = { range = false }, apply = { vm.select(it); range = false })
    operation?.let { op ->
        if (op.type == "move" && destination == null) {
            AlertDialog(onDismissRequest = { operation = null }, title = { Text("نقل ${op.rows.size} شريحة") }, text = {
                Column {
                    Text("اختر المجموعة المستقبلة")
                    val eligible = groups.filter { group -> op.rows.any { it.groupId != group.group.id } }
                    if (groups.isEmpty()) Text("لا توجد مجموعات. أنشئ مجموعة من الصفحة الرئيسية أولًا.")
                    else if (eligible.isEmpty()) Text("لا توجد مجموعة أخرى متاحة للنقل.")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(eligible, key = { it.group.id }) { group ->
                            TextButton(onClick = { destination = group.group }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) { SerialText(group.group.code); Text(group.group.ownerName) }
                            }
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { operation = null }) { Text("إلغاء") } })
        } else {
            val target = destination
            val validTarget = op.type != "move" || groups.any { it.group.id == target?.id }
            AlertDialog(onDismissRequest = { if (!busy) { operation = null; vm.clearError() } },
                title = { Text(when (op.type) { "delete" -> "حذف الشرائح نهائيًا؟"; "archive" -> "أرشفة الشرائح"; else -> "تأكيد النقل" }) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (op.type) {
                        "delete" -> "هل تريد حذف ${op.rows.size} شريحة نهائيًا؟ لا يمكن التراجع عن هذه العملية."
                        "archive" -> "أرشفة ${op.rows.size} شريحة؟ ستبقى أرقامها محفوظة دون انتماء إلى مجموعة."
                        else -> "نقل ${op.rows.size} شريحة إلى المجموعة التالية؟"
                    })
                    if (target != null) { SerialText(target.code); Text(target.ownerName) }
                    if (!validTarget) Text("المجموعة لم تعد متاحة. ألغِ العملية واختر وجهة أخرى.", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                } },
                confirmButton = { TextButton(enabled = !busy && validTarget, onClick = {
                    vm.change(op.rows, target?.id, op.type == "delete") { operation = null; detailsId = null }
                }) { Text(if (op.type == "delete") "حذف نهائي" else "تأكيد", color = if (op.type == "delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
                dismissButton = { TextButton(enabled = !busy, onClick = { operation = null; vm.clearError() }) { Text("إلغاء") } })
        }
    }
}

private fun location(row: InventoryRow): String = if (row.sim.groupId == null) "في الأرشيف العام" else "ضمن مجموعة · ${row.ownerName} · \u2066${row.groupCode}\u2069"

@Composable
private fun SerialText(value: String, prominent: Boolean = false) {
    Text(value, fontFamily = NumberFont, fontWeight = if (prominent) FontWeight.Bold else FontWeight.Normal,
        style = TextStyle(textDirection = TextDirection.Ltr, fontSize = if (prominent) 22.sp else 13.sp),
        color = if (prominent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RangeDialog(rows: List<InventoryRow>, selected: Set<String>, close: () -> Unit, apply: (Set<String>) -> Unit) {
    var from by remember { mutableStateOf(rows.firstOrNull { it.sim.id in selected }?.sim?.id) }
    var to by remember { mutableStateOf<String?>(null) }
    var last4 by remember { mutableStateOf(false) }
    var low by remember { mutableStateOf("") }; var high by remember { mutableStateOf("") }
    val ids = if (last4) StockSelection.last4Range(rows, InputRules.code(low), InputRules.code(high))
        else StockSelection.range(rows.map { it.sim.id }, from.orEmpty(), to.orEmpty())
    AlertDialog(onDismissRequest = close, title = { Text("تحديد نطاق من القائمة الحالية") }, text = {
        Column {
            TextButton(onClick = { last4 = !last4 }) { Text(if (last4) "اختيار بداية ونهاية من الصفوف" else "تحديد بحسب آخر 4 خانات") }
            if (last4) {
                Text("يشمل كل المطابقات الموجودة فقط، بما فيها الأرقام المتشابهة.")
                OutlinedTextField(low, { low = it }, label = { Text("من آخر 4") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = TextStyle(textDirection = TextDirection.Ltr, fontFamily = NumberFont))
                OutlinedTextField(high, { high = it }, label = { Text("إلى آخر 4") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = TextStyle(textDirection = TextDirection.Ltr, fontFamily = NumberFont))
            } else {
                Text(if (from == null) "اختر بداية النطاق" else if (to == null) "اختر نهاية النطاق" else "تم اختيار البداية والنهاية. يمكنك بدء نطاق آخر.")
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(rows, key = { it.sim.id }) { row ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                            if (from == null || to != null) { from = row.sim.id; to = null } else to = row.sim.id
                        }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (row.sim.id in ids || row.sim.id == from) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.padding(8.dp))
                            Column { SerialText(row.sim.last4, true); SerialText(row.sim.serial) }
                        }
                    }
                }
            }
            Text("سيُحدد ${ids.size} شريحة موجودة", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
        }
    }, confirmButton = { TextButton(enabled = ids.isNotEmpty(), onClick = { apply(ids) }) { Text("تحديد ${ids.size}") } },
        dismissButton = { TextButton(onClick = close) { Text("إلغاء") } })
}
