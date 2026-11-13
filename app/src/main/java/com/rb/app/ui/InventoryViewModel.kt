package com.rb.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rb.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class InventoryState(
    val loading: Boolean = true,
    val groups: List<GroupSummary> = emptyList(),
    val total: Int = 0,
    val error: String? = null,
)

data class SimsState(
    val groupId: String? = null,
    val loading: Boolean = true,
    val rows: List<Sim> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(private val repository: InventoryRepository) : ViewModel() {
    val state = combine(repository.groups, repository.total) { groups, total ->
        InventoryState(loading = false, groups = groups, total = total)
    }.catch { emit(InventoryState(loading = false, error = "تعذر قراءة البيانات المحلية. أعد فتح التطبيق للمحاولة.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryState())
    private val selectedId = MutableStateFlow<String?>(null)
    val sims = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(SimsState()) else repository.sims(id)
            .map { SimsState(groupId = id, loading = false, rows = it) }
            .onStart { emit(SimsState(groupId = id)) }
            .catch { emit(SimsState(groupId = id, loading = false, error = "تعذر تحميل الشرائح. أعد فتح المجموعة للمحاولة.")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SimsState())
    fun select(id: String) { selectedId.value = id }
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = null }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: InventoryException) { _error.value = e.message }
            catch (e: Exception) { _error.value = "تعذر حفظ التغيير. حاول مجددًا." }
            finally { _busy.value = false }
        }
    }
    fun create(code: String, owner: String, done: () -> Unit) = action {
        repository.createGroup(code, owner); done()
    }
    fun rename(id: String, owner: String, done: () -> Unit) = action {
        repository.updateOwner(id, owner); done()
    }
    fun delete(id: String, done: () -> Unit) = action {
        repository.deleteEmptyGroup(id); done()
    }
}
