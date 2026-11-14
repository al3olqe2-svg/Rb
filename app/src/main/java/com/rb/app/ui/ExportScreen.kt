@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.rb.app.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rb.app.export.*

@Composable
fun ExportScreen(vm: ExportViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(InventoryXlsx.MIME), vm::save)
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Text("تصدير إلى Excel") }, navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
        }, windowInsets = WindowInsets(0, 0, 0, 0))
        androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.TableView, null, tint = MaterialTheme.colorScheme.primary)
                        Text("كل مخزونك في ملف واحد", style = MaterialTheme.typography.titleLarge)
                        Text("ورقتان منظمتان للمجموعات وجميع الشرائح، بما فيها الأرشيف العام. تُحفظ الأرقام كاملة مع أصفار البداية.")
                    }
                }
            }
            item { Text("اختر مكان الحفظ من منتقي ملفات Android. يعكس الملف البيانات وقت بدء إنشائه، وهو تقرير للعرض والفرز وليس نسخة قابلة للاستيراد.", style = MaterialTheme.typography.bodyMedium) }
            item {
                Button(enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                    if (vm.choose()) { launchError = null; try { picker.launch(InventoryXlsx.filename()) } catch (_: Exception) { vm.pickerFailed() } }
                }) {
                    Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp))
                    Text(if (state.busy) "جارٍ التصدير…" else "اختيار مكان الحفظ والتصدير")
                }
            }
            if (state.busy && !state.choosing) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("جارٍ إنشاء الملف وحفظه…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
            state.uri?.let { uri -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("تم حفظ ملف Excel بنجاح", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, InventoryXlsx.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (_: Exception) { launchError = "لا يتوفر تطبيق لفتح Excel. يمكنك مشاركة الملف أو فتحه من مدير الملفات." }
                        }) { Text("فتح الملف") }
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_SEND).setType(InventoryXlsx.MIME).putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Excel", uri) }
                                context.startActivity(Intent.createChooser(intent, "مشاركة ملف Excel"))
                            } catch (_: Exception) { launchError = "تعذر فتح المشاركة. يمكنك الوصول للملف في مكان الحفظ." }
                        }) { Text("مشاركة") }
                    }
                }
            } }
            launchError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
