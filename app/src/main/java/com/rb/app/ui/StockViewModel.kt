package com.rb.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rb.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Selection always stores stable IDs, never list positions or last-four digits. */
object StockSelection {
    fun range(ids: List<String>, from: String, to: String): Set<String> {
        val a = ids.indexOf(from); val b = ids.indexOf(to)
        return if (a < 0 || b < 0) emptySet() else ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }
    fun last4Range(rows: List<InventoryRow>, from: String, to: String): Set<String> {
        if (from.length != 4 || to.length != 4 || !InputRules.validSerial(from) || !InputRules.validSerial(to) || from > to) return emptySet()
        return rows.filter { it.sim.last4.length == 4 && it.sim.last4 >= from && it.sim.last4 <= to }.map { it.sim.id }.toSet()
    }
}

data class StockState(val loading: Boolean = true, val rows: List<InventoryRow> = emptyList(), val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class StockViewModel(private val repository: InventoryRepository, val global: Boolean, val groupId: String?) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    val state = _query.flatMapLatest { text -> flow {
        emit(StockState())
        if (text.isNotEmpty()) delay(250)
        emitAll(repository.inventory(global, groupId, text).map { StockState(false, it) })
    }.catch { emit(StockState(false, error = "تعذر قراءة المخزون. أعد فتح الشاشة للمحاولة.")) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StockState())
    val count = repository.count(groupId).catch { emit(0) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()
    private val _descending = MutableStateFlow(false)
    val descending = _descending.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun query(value: String) { if (!_busy.value) { _selected.value = emptySet(); _query.value = value } }
    fun sort() { if (!_busy.value) _descending.value = !_descending.value }
    fun select(ids: Set<String>) { if (!_busy.value) _selected.value = ids.intersect(state.value.rows.map { it.sim.id }.toSet()) }
    fun pruneSelection() { if (!state.value.loading) select(_selected.value) }
    fun toggle(id: String) = select(if (id in _selected.value) _selected.value - id else _selected.value + id)
    fun clearMessage() { _message.value = null }
    fun clearError() { _error.value = null }
    fun change(expected: List<Sim>, destination: String?, delete: Boolean, done: () -> Unit) {
        if (_busy.value || expected.isEmpty()) return
        _busy.value = true; _error.value = null
        viewModelScope.launch {
            try {
                val count = repository.changeInventory(expected, destination, delete)
                _selected.value = emptySet()
                _message.value = when { delete -> "حُذفت $count شريحة نهائيًا"; destination == null -> "أُرشفت $count شريحة"; else -> "نُقلت $count شريحة بنجاح" }
                done()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _error.value = when (e) { is InventoryException, is StaleInventoryException -> e.message; else -> "تعذر تنفيذ العملية. لم تُحفظ تغييرات؛ حاول مجددًا." }
            } finally { _busy.value = false }
        }
    }
}
