package com.rb.app

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.ui.StockViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RbApplication::class)
class StockStateTest {
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private val store = ViewModelStore()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
    }
    @After fun close() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun vm(group: String) = StockViewModel(repo, false, group).also { store.put("stock", it) }
    @Test fun selectionIsLimitedToVisibleIdsAndQueryChangeClearsItImmediately() = runTest {
        val group = repo.createGroup("0055", "عوض")
        repo.addNewSims(listOf("00012277", "00022277", "00012278"), group)
        val vm = vm(group)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        vm.state.first { !it.loading }
        val ids = vm.state.value.rows.map { it.sim.id }.toSet()
        vm.select(ids + "not-a-visible-row")
        assertEquals(ids, vm.selected.value)
        vm.sort(); assertEquals(ids, vm.selected.value)
        vm.query("2277")
        assertTrue(vm.selected.value.isEmpty())
        vm.state.first { !it.loading && it.rows.size == 2 }
        vm.select(ids)
        assertEquals(2, vm.selected.value.size)
        vm.query(""); assertTrue(vm.selected.value.isEmpty())
    }
    @Test fun repeatedConfirmationLaunchesOnlyOneTransactionAndFailureRetainsSelection() = runTest {
        val group = repo.createGroup("0055", "عوض")
        repo.addNewSims(listOf("00012277", "00012278"), group)
        val vm = vm(group)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        val rows = vm.state.first { !it.loading }.rows.map { it.sim }
        vm.select(rows.map { it.id }.toSet())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_archive BEFORE UPDATE ON sims BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        vm.change(rows, null, false) { fail("Failure must not invoke success callback") }
        vm.busy.first { !it }
        assertNotNull(vm.error.value); assertEquals(2, vm.selected.value.size)
        assertEquals(2, repo.sims(group).first().size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_archive")
        var completions = 0
        vm.change(rows, null, false) { completions++ }
        vm.change(rows, null, false) { completions++ }
        vm.busy.first { !it }
        assertEquals(1, completions)
        assertEquals(2, repo.archive().first().size)
        assertTrue(vm.selected.value.isEmpty()); assertNull(vm.error.value)
    }
}
