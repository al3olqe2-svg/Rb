@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.rb.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rb.app.data.*
import com.rb.app.scanner.SerialNumber
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HistoryState(val loading: Boolean = true, val rows: List<TransferSummary> = emptyList(),
    val details: TransferDetails? = null, val error: String? = null)
private class HistoryViewModel(repository: InventoryRepository, id: String?) : ViewModel() {
    val state = (if (id == null) repository.transferHistory.map { HistoryState(loading = false, rows = it) }
        else repository.transferDetails(id).map { HistoryState(loading = false, details = it) })
        .catch { emit(HistoryState(loading = false, error = "تعذر قراءة سجل النقل. أعد فتح الشاشة للمحاولة.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState())
}

@Composable
fun TransferHistoryScreen(repository: InventoryRepository, id: String? = null, back: () -> Unit, open: (String) -> Unit = {}) {
    val vm: HistoryViewModel = viewModel(key = "history/$id", factory = viewModelFactory { initializer { HistoryViewModel(repository, id) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { snackbar.showSnackbar("تم نسخ الرقم الكامل"); copied = false } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (id == null) "سجل النقل" else "تفاصيل النقل") },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") } }) },
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.error != null -> item { Text(requireNotNull(state.error), color = MaterialTheme.colorScheme.error) }
                id == null -> {
                    if (state.rows.isEmpty()) item { Text("لا توجد عمليات نقل مسجلة بعد") }
                    items(state.rows, key = { it.record.id }) { summary ->
                        Surface(onClick = { open(summary.record.id) }, shape = MaterialTheme.shapes.medium) {
                            TransferHeader(summary.record, summary.simCount)
                        }
                    }
                }
                state.details == null -> item { Text("عملية النقل غير متاحة") }
                else -> {
                    val details = requireNotNull(state.details)
                    item { TransferHeader(details.record, details.items.size) }
                    items(details.items.sortedBy { it.simSerialSnapshot }, key = { it.id }) { item ->
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                SerialNumber(item.simSerialSnapshot.takeLast(4), true)
                                SerialNumber(item.simSerialSnapshot)
                                TextButton(onClick = {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText("الرقم الكامل", item.simSerialSnapshot))
                                    copied = true
                                }) { Text("نسخ الرقم الكامل") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferHeader(record: TransferRecord, count: Int) {
    val date = remember(record.timestamp) { SimpleDateFormat("d MMMM yyyy — h:mm a", Locale.forLanguageTag("ar")).format(Date(record.timestamp)) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("نقل $count شريحة", style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("من: ${record.sourceOwnerNameSnapshot}")
            SerialNumber(record.sourceGroupCodeSnapshot)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("إلى: ${record.destinationOwnerNameSnapshot}")
            SerialNumber(record.destinationGroupCodeSnapshot)
        }
        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
