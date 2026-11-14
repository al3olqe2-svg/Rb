package com.rb.app.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

@Composable
fun SeriesDialog(dismiss: () -> Unit, add: (String, String) -> Unit) {
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val preview = remember(first, last) { runCatching { SerialSeries.generate(first, last) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("إضافة سلسلة") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(first, { first = it }, label = { Text("الرقم الأول") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(textDirection = TextDirection.Ltr),
                trailingIcon = { IconButton(onClick = { scanning = true }) { Icon(Icons.Outlined.QrCodeScanner, "مسح الرقم الأول") } })
            OutlinedTextField(last, { last = it }, label = { Text("آخر أربعة أرقام للنهاية") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(textDirection = TextDirection.Ltr))
            Text("الحد الأقصى 500 شريحة")
            preview.getOrNull()?.let { rows ->
                Text("عدد الشرائح: ${rows.size}", style = MaterialTheme.typography.titleMedium)
                SerialNumber(rows.first()); SerialNumber(rows.last())
                if (rows.any { !SerialRecognition.luhn(it) }) Text("قد تختلف خانة التحقق؛ ستُضاف الأرقام كما هي.", style = MaterialTheme.typography.bodySmall)
            }
            if (first.isNotEmpty() && last.isNotEmpty()) preview.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = preview.isSuccess, onClick = { add(first, last) }) { Text("إضافة السلسلة للمراجعة") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
    if (scanning) CaptureDialog(dismiss = { scanning = false }, confirm = { first = it; scanning = false })
}
