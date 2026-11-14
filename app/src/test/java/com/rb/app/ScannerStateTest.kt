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
        assertNull(scanner.state.value.result)
        assertTrue(scanner.state.value.review)
        assertNull(repo.findSerials(listOf("00010000")).single().groupId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_scan")
        scanner.save()
        scanner.state.first { !it.busy && it.result != null }
        assertEquals(2, repo.sims(group).first().size)
    }
    @Test fun stableCaptureRequiresConfirmationAndSuppressesConfirmedSim() = runTest {
        val scanner = vm()
        val serial = "8996603421269968985"
        val reading = SerialRecognition.read(emptyList(), listOf(serial))
        scanner.camera()
        repeat(3) { scanner.reading(reading) }
        assertEquals(listOf(serial), scanner.state.value.suggestions)
        assertTrue(scanner.state.value.serials.isEmpty())
        repeat(5) { scanner.reading(SerialRecognition.read(emptyList(), listOf("8996603421269968999"))) }
        assertEquals(listOf(serial), scanner.state.value.suggestions)
        scanner.confirm(serial); scanner.confirm(serial)
        repeat(10) { scanner.reading(reading) }
        assertEquals(listOf(serial), scanner.state.value.serials)
        assertTrue(scanner.state.value.suggestions.isEmpty())
        assertEquals(0, repo.total.first())
        val next = "8996603421269968986"
        repeat(3) { scanner.reading(SerialRecognition.read(emptyList(), listOf(next))) }
        assertEquals(listOf(next), scanner.state.value.suggestions)
    }
    @Test fun seriesSharesReviewAndTransferApprovalsWithScannedValues() = runTest {
        val target = repo.createGroup("000055", "سارة")
        val source = repo.createGroup("000056", "أحمد")
        val rows = SerialSeries.generate("8996603421269968985", "8999")
        repo.addNewSims(rows.take(2), source)
        val scanner = vm(initial = target)
        scanner.add(rows.last())
        assertTrue(scanner.series(rows.first(), "8999"))
        scanner.state.first { !it.busy && it.plan != null }
        assertEquals(15, scanner.state.value.serials.size)
        scanner.approve(rows.first(), true) // second conflict deliberately refused
        scanner.remove(rows[2])
        scanner.state.first { !it.busy && it.plan != null }
        scanner.approve(rows.first(), true)
        scanner.save()
        val result = scanner.state.first { !it.busy && it.result != null }.result
        assertEquals(ScanResult(12, 1, 1), result)
        assertEquals(source, repo.findSerials(listOf(rows[1])).single().groupId)
        assertEquals(13, repo.sims(target).first().size)
    }

    @Test fun scannedGroupCodeRemainsUniqueTextWithLeadingZeros() = runTest {
        val gate = TemporalCapture(group = true)
        val frame = SerialRecognition.readGroup(listOf("00000055"), listOf("00000055"))
        gate.accept(frame)
        val code = gate.accept(frame).single()
        val id = repo.createGroup(code, "أحمد")
        assertEquals(code, repo.groups.first().single().group.code)
        assertEquals(id, repo.groups.first().single().group.id)
        val duplicate = runCatching { repo.createGroup(code, "سارة") }.exceptionOrNull()
        assertTrue(duplicate is InventoryException.DuplicateCode)
        repo.createGroup("55", "سارة")
        assertEquals(2, repo.groups.first().size)
    }

}
