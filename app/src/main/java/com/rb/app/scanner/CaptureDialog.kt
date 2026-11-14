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
    val labelGate = remember { GroupLabelCapture() }
    var upper by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var choices by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = dismiss)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(if (group) "مسح رقم المجموعة" else "مسح أول شريحة", style = MaterialTheme.typography.titleMedium)
                CameraPanel(Modifier.fillMaxWidth().weight(1f), {
                    if (choices.isEmpty() && !confirmed) {
                        if (group) labelGate.accept(it)?.let { selection -> choices = selection.candidates; upper = selection.upper }
                        else choices = gate.accept(it)
                    }
                }, { error = it }, group)
                if (group && choices.isEmpty()) Text("أظهر باركودي الملصق والرقمين كاملين. ثبّت الكاميرا.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (choices.isNotEmpty()) {
                    Text(if (group) { if (upper != null) "تم تحديد الرقم العلوي — راجعه وأكّده" else "تعذر تحديد الأعلى بثقة — اختر الرقم الصحيح كاملًا" } else if (choices.any { it.length !in 19..20 }) "قراءة بطول غير معتاد — طابق الرقم كاملًا" else "جاهز للتأكيد")
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(choices) { value ->
                            TextButton(enabled = !confirmed, onClick = { if (!confirmed) { confirmed = true; confirm(value) } }) {
                                Column { if (value == upper) Text("الرقم العلوي")
                                    SerialNumber(value.takeLast(4), true); SerialNumber(value); Text("تأكيد الرقم") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { gate.reset(); labelGate.reset(); choices = emptyList(); upper = null; confirmed = false }) { Text("إعادة المسح") }
                }
                TextButton(onClick = dismiss) { Text("إلغاء") }
            }
        }
    }
}
