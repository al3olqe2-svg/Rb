package com.rb.app.scanner

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rb.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ScannerState(
    val serials: List<String> = emptyList(), val destination: String? = null,
    val camera: Boolean = false, val review: Boolean = false,
    val suggestions: List<String> = emptyList(), val message: String = "وجّه الكاميرا إلى شريحة واحدة",
    val last: String? = null, val plan: ScanPlan? = null, val approved: Set<String> = emptySet(),
    val busy: Boolean = false, val saving: Boolean = false, val error: String? = null, val result: ScanResult? = null,
)
class ScannerViewModel(
    private val repository: InventoryRepository, private val saved: SavedStateHandle, initial: String?,
) : ViewModel() {
    private val session = ScanSession(saved.get<ArrayList<String>>("serials") ?: emptyList())
    private val _state = MutableStateFlow(ScannerState(serials = session.serials,
        destination = if (saved.contains("destination")) saved["destination"] else initial,
        review = (saved.get<Boolean>("review") ?: false) || session.serials.isNotEmpty()))
    val state = _state.asStateFlow()
    private val stable = TemporalCapture()
    private var lastFeedback: String? = null
    private var feedbackTime = 0L
    init { if (_state.value.review && session.serials.isNotEmpty()) refresh() }
    private fun set(value: ScannerState) {
        _state.value = value
        saved["serials"] = ArrayList(value.serials)
        saved["destination"] = value.destination
        saved["review"] = value.review
    }
    fun destination(id: String?) {
        if (state.value.busy) return
        set(state.value.copy(destination = id, plan = null, approved = emptySet(), error = null))
        if (state.value.review && session.serials.isNotEmpty()) refresh()
    }
    fun camera() { if (!state.value.busy) { stable.reset(); set(state.value.copy(camera = true, review = false, result = null, suggestions = emptyList(), message = "وجّه الكاميرا إلى شريحة واحدة", error = null)) } }
    fun review() { if (!state.value.busy) { set(state.value.copy(camera = false, review = true, suggestions = emptyList())); if (session.serials.isNotEmpty()) refresh() } }
    fun reading(reading: SerialRecognition.Reading) {
        if (!state.value.camera || state.value.busy) return
        val choices = stable.accept(reading, session.serials.toSet())
        if (choices.isNotEmpty()) set(state.value.copy(suggestions = choices,
            message = "الرقم جاهز للتأكيد — طابق جميع الخانات", error = null))
    }
    fun rescan() {
        stable.reset()
        set(state.value.copy(suggestions = emptyList(), message = "وجّه الكاميرا إلى شريحة واحدة"))
    }
    fun confirm(serial: String) {
        if (serial !in state.value.suggestions) return
        add(serial)
        rescan()
    }
    fun series(first: String, last4: String): Boolean {
        if (state.value.busy) return false
        val values = try { SerialSeries.generate(first, last4) } catch (e: IllegalArgumentException) {
            error(e.message.orEmpty()); return false
        }
        val added = values.count { session.add(it) }
        set(state.value.copy(serials = session.serials, plan = null, approved = emptySet(),
            message = "أضيفت $added؛ تكرار في الجلسة: ${values.size - added}", error = null))
        review()
        return true
    }
    fun add(raw: String) {
        if (state.value.busy) return
        val serial = SerialRecognition.manual(raw)
        if (serial == null) { error("أدخل الرقم كاملًا بأرقام فقط (حتى 128 خانة)"); return }
        if (!session.add(serial)) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastFeedback != serial || now - feedbackTime > 2500) {
                set(state.value.copy(message = "موجودة في قائمة الجلسة · ${serial.takeLast(4)}"))
                lastFeedback = serial; feedbackTime = now
            }
            return
        }
        lastFeedback = serial; feedbackTime = android.os.SystemClock.elapsedRealtime()
        set(state.value.copy(serials = session.serials, last = serial, suggestions = emptyList(), plan = null,
            approved = emptySet(), message = "تمت الإضافة · ${serial.takeLast(4)}", error = null))
        if (state.value.review) refresh()
    }
    fun edit(old: String, raw: String): Boolean {
        if (state.value.busy) return false
        if (!session.edit(old, raw)) { error("الرقم غير صالح أو موجود في القائمة"); return false }
        set(state.value.copy(serials = session.serials, plan = null, approved = emptySet(), error = null)); refresh()
        return true
    }
    fun remove(serial: String) {
        if (state.value.busy) return
        session.remove(serial)
        set(state.value.copy(serials = session.serials, plan = null, approved = emptySet(), error = null))
        if (session.serials.isNotEmpty()) refresh()
    }
    fun approve(serial: String, value: Boolean) {
        if (!state.value.busy) set(state.value.copy(approved = if (value) state.value.approved + serial else state.value.approved - serial))
    }
    fun error(message: String) { set(state.value.copy(error = message)) }
    fun refresh() {
        if (state.value.busy) return
        val snapshot = state.value
        set(snapshot.copy(busy = true, plan = null, approved = emptySet(), error = null))
        viewModelScope.launch {
            try { set(state.value.copy(plan = repository.reviewScan(snapshot.serials, snapshot.destination))) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(if (e is InventoryException) e.message.orEmpty() else "تعذر التحقق من البيانات. حاول تحديث الحالات.") }
            finally { set(state.value.copy(busy = false, saving = false)) }
        }
    }
    fun save() {
        val snapshot = state.value
        val plan = snapshot.plan ?: return
        if (snapshot.busy || snapshot.result != null) return
        set(snapshot.copy(busy = true, saving = true, error = null))
        viewModelScope.launch {
            try {
                val result = repository.saveScan(plan, snapshot.approved)
                session.serials.forEach(session::remove)
                set(state.value.copy(serials = emptyList(), result = result, plan = null, approved = emptySet(), camera = false))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is StaleScanException || e is InventoryException.MissingGroup)
                    set(state.value.copy(plan = null, approved = emptySet()))
                error(when (e) { is StaleScanException, is InventoryException -> e.message.orEmpty(); else -> "تعذر حفظ الدفعة. لم تُحفظ تغييرات جزئية؛ أعد المحاولة." })
            } finally { set(state.value.copy(busy = false, saving = false)) }
        }
    }
}
