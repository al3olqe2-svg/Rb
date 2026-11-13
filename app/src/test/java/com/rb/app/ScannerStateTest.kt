package com.rb.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.scanner.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RbApplication::class)
class ScannerStateTest {
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private val store = ViewModelStore()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
    }
    @After fun close() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun vm(handle: SavedStateHandle = SavedStateHandle(), initial: String? = null) =
        ScannerViewModel(repo, handle, initial).also { store.put(java.util.UUID.randomUUID().toString(), it) }
    @Test fun sessionSurvivesRecreationAndDoesNotWriteInventoryDuringScanning() = runTest {
        val handle = SavedStateHandle()
        val group = repo.createGroup("0055", "عوض")
        val first = vm(handle, group)
        first.camera()
        first.add("8996603430263612277"); first.add("8996603430263612277")
        first.add("00012345678901234567892277")
        assertEquals(2, first.state.value.serials.size)
        assertEquals(0, repo.total.first())
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = vm(restoredHandle)
        assertEquals(first.state.value.serials, restored.state.value.serials)
        assertEquals(group, restored.state.value.destination)
        assertFalse(restored.state.value.camera) // explicit restart; camera resources are never persisted
        assertTrue(restored.state.value.review)
        restored.review()
        restored.state.first { !it.busy && it.plan != null }
        restored.save(); restored.save() // repeated tap cannot launch a second batch
        val finished = restored.state.first { !it.busy && it.result != null }
        assertEquals(ScanResult(2, 0, 0), finished.result)
        assertEquals(2, repo.total.first())
        assertTrue(restored.state.value.serials.isEmpty())
    }
    @Test fun failedSaveKeepsReviewForRetryAndDestinationChangeClearsApprovals() = runTest {
        val group = repo.createGroup("0055", "عوض")
        repo.addNewSims(listOf("00010000"), null)
        val scanner = vm(initial = group)
        scanner.add("00010000"); scanner.add("00020000"); scanner.review()
        scanner.state.first { !it.busy && it.plan != null }
        scanner.approve("00010000", true)
        scanner.destination(null)
        scanner.state.first { !it.busy && it.plan != null }
        assertTrue(scanner.state.value.approved.isEmpty())
        scanner.destination(group)
        scanner.state.first { !it.busy && it.plan != null }
        scanner.approve("00010000", true)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scan BEFORE INSERT ON sims BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        scanner.save()
        scanner.state.first { !it.busy && it.error != null }
        assertEquals(2, scanner.state.value.serials.size)
        assertNull(repo.findSerials(listOf("00010000")).single().groupId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_scan")
        scanner.save()
        scanner.state.first { !it.busy && it.result != null }
        assertEquals(2, repo.sims(group).first().size)
    }
}
