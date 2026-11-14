package com.rb.app.export

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rb.app.data.InventoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Owned by the app's root composition so navigation/rotation cannot start duplicate exports. */
class ExportViewModel(private val repository: InventoryRepository, application: Application) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    private val _state = MutableStateFlow(ExportState())
    val state = _state.asStateFlow()
    fun choose(): Boolean {
        if (_state.value.busy) return false
        _state.value = ExportState(busy = true, choosing = true)
        return true
    }
    fun pickerFailed() { _state.value = ExportState(error = "تعذر فتح منتقي الملفات. حاول مجددًا.") }
    fun save(uri: Uri?) {
        if (_state.value.busy && !_state.value.choosing) return
        if (uri == null) { _state.value = ExportState(); return }
        _state.value = ExportState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("rb-export-", ".xlsx", context.cacheDir)
                    try {
                        val snapshot = repository.exportSnapshot()
                        temp.outputStream().buffered().use { InventoryXlsx.write(snapshot, it) }
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Output stream unavailable")
                        stream.use { output -> temp.inputStream().use { it.copyTo(output) } }
                    } finally { temp.delete() }
                }
                _state.value = ExportState(uri = uri)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    // ACTION_CREATE_DOCUMENT creates a new document; remove incomplete output if supported.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                _state.value = ExportState(error = exportError(e))
            }
        }
    }
}
data class ExportState(val busy: Boolean = false, val choosing: Boolean = false, val uri: Uri? = null, val error: String? = null)

internal fun exportError(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(20).toList()
    return if (causes.any { (it is ErrnoException && it.errno in listOf(OsConstants.ENOSPC, OsConstants.EDQUOT)) ||
            it.message.orEmpty().contains("ENOSPC", true) || it.message.orEmpty().contains("No space", true) })
        "لا توجد مساحة كافية لحفظ الملف. حرّر مساحة أو اختر مكانًا آخر ثم أعد التصدير."
    else if (error is XlsxLimitException) error.message ?: "تعذر إنشاء ملف Excel."
    else "تعذر حفظ ملف Excel. تحقق من المساحة وإتاحة موقع الحفظ ثم حاول مجددًا. قد تحتاج إلى حذف الملف غير المكتمل إن بقي في الوجهة."
}
