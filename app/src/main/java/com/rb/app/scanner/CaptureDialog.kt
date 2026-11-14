package com.rb.app.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Single identifier capture, reused by group creation and series start. Never writes inventory. */
@Composable
fun CaptureDialog(group: Boolean = false, dismiss: () -> Unit, confirm: (String) -> Unit) {
    val gate = remember(group) { TemporalCapture(group = group) }
    var choices by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = dismiss)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(if (group) "مسح رقم المجموعة" else "مسح أول شريحة", style = MaterialTheme.typography.titleMedium)
                CameraPanel(Modifier.fillMaxWidth().weight(1f), { if (choices.isEmpty()) choices = gate.accept(it) }, { error = it }, group)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (choices.isNotEmpty()) {
                    Text(if (!group && choices.any { it.length !in 19..20 }) "قراءة بطول غير معتاد — طابق الرقم كاملًا" else "جاهز للتأكيد")
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(choices) { value ->
                            TextButton(onClick = { confirm(value) }) { Column { SerialNumber(value.takeLast(4), true); SerialNumber(value); Text("تأكيد الرقم") } }
                        }
                    }
                    OutlinedButton(onClick = { gate.reset(); choices = emptyList() }) { Text("إعادة المسح") }
                }
                TextButton(onClick = dismiss) { Text("إلغاء") }
            }
        }
    }
}
