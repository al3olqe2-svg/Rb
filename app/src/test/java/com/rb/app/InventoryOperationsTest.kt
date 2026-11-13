package com.rb.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.ui.StockSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = RbApplication::class)
class InventoryOperationsTest {
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private lateinit var a: String
    private lateinit var b: String
    @Before fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
        a = repo.createGroup("0055", "عوض"); b = repo.createGroup("0066", "أحمد")
        repo.addNewSims(listOf("00012277", "00022277", "00002278", "00002279", "00002280", "00002281", "00002282"), a)
    }
    @After fun close() { db.close() }
    private suspend fun rows(id: String? = a) = repo.inventory(false, id, "").first().map { it.sim }
    private suspend fun counts() = repo.groups.first().associate { it.group.id to it.simCount }

    @Test fun individualAndBulkMoveKeepIdentityAndUpdateCounts() = runTest {
        val original = rows()
        repo.changeInventory(listOf(original.first()), b)
        assertEquals(6, counts()[a]); assertEquals(1, counts()[b])
        repo.changeInventory(original.drop(1).take(3), b)
        assertEquals(3, counts()[a]); assertEquals(4, counts()[b])
        val all = repo.inventory(true, null, "").first().map { it.sim }
        assertEquals(original.map { it.id }.toSet(), all.map { it.id }.toSet())
        assertEquals(original.map { it.serial }.toSet(), all.map { it.serial }.toSet())
        assertEquals(7, repo.total.first())
    }
    @Test fun archiveAndRestoreAreSameRowsAndSearchShowsCurrentLocation() = runTest {
        val original = rows().take(2)
        repo.changeInventory(original, null)
        assertEquals(original.map { it.copy(groupId = null) }, rows(null))
        assertEquals(5, counts()[a]); assertEquals(2, repo.count(null).first())
        val archived = repo.inventory(true, null, original.first().serial).first().single()
        assertNull(archived.groupCode); assertNull(archived.ownerName)
        repo.changeInventory(rows(null), b)
        assertEquals(0, repo.count(null).first()); assertEquals(2, counts()[b])
        val restored = repo.inventory(true, null, original.first().serial).first().single()
        assertEquals("0066", restored.groupCode); assertEquals("أحمد", restored.ownerName)
    }
    @Test fun searchFindsEverySuffixSubstringAndPreservesZeros() = runTest {
        repo.addNewSims(listOf("99992277"), null)
        val found = repo.inventory(true, null, "٢٢٧٧").first()
        assertEquals(3, found.size)
        assertEquals(setOf("00012277", "00022277", "99992277"), found.map { it.sim.serial }.toSet())
        assertEquals(2, repo.inventory(false, a, "277").first().size)
        assertEquals(0, repo.inventory(false, b, "2277").first().size)
        assertEquals(1, repo.inventory(true, null, "00012277").first().size)
        assertEquals(7, repo.inventory(true, null, "000").first().size)
        assertTrue(repo.inventory(true, null, "%").first().isEmpty())
        assertTrue(repo.inventory(true, null, "123456").first().isEmpty())
    }
    @Test fun numericSortDoesNotOverflowOrLoseLeadingZeros() = runTest {
        repo.addNewSims(listOf("10", "2", "0002", "0", "000", "9".repeat(60)), b)
        assertEquals(listOf("0", "000", "0002", "2", "10", "9".repeat(60)), rows(b).map { it.serial })
    }
    @Test fun rangeUsesVisibleRowsAndIncludesMissingNumbersAndDuplicatesCorrectly() = runTest {
        val current = repo.inventory(false, a, "").first()
        val ids = current.map { it.sim.id }
        assertEquals(ids.subList(1, 5).toSet(), StockSelection.range(ids, ids[4], ids[1]))
        assertTrue(StockSelection.range(ids, "missing", ids[0]).isEmpty())
        assertEquals(4, StockSelection.last4Range(current, "2278", "2281").size)
        assertEquals(2, StockSelection.last4Range(current, "2277", "2277").size)
        assertTrue(StockSelection.last4Range(current, "2281", "2278").isEmpty())
        val filtered = repo.inventory(false, a, "228").first()
        assertEquals(2, StockSelection.last4Range(filtered, "2277", "2281").size)
    }
    @Test fun deleteSingleAndBatchLeaveUnselectedInventoryIntact() = runTest {
        val original = rows()
        repo.changeInventory(original.take(1), null, delete = true)
        repo.changeInventory(original.drop(1).take(3), null, delete = true)
        assertEquals(original.drop(4), rows())
        assertEquals(3, counts()[a]); assertEquals(3, repo.total.first())
        repo.changeInventory(rows(), null, delete = true)
        assertTrue(rows().isEmpty()); assertEquals(0, counts()[a])
        repo.deleteEmptyGroup(a)
        assertNull(db.inventory().group(a))
    }
    @Test fun missingDestinationAndStaleRecordsAbortWholeBatch() = runTest {
        val original = rows()
        repo.deleteEmptyGroup(b)
        expect<InventoryException.MissingGroup> { repo.changeInventory(original, b) }
        assertEquals(original, rows())
        repo.changeInventory(original.takeLast(1), null)
        expect<StaleInventoryException> { repo.changeInventory(original, null) }
        expect<StaleInventoryException> { repo.changeInventory(original, null, true) }
        assertEquals(6, counts()[a]); assertEquals(7, repo.total.first())
        repo.changeInventory(rows(null), null, true)
        expect<StaleInventoryException> { repo.changeInventory(original, null, true) }
        assertEquals(6, repo.total.first())
    }
    @Test fun largeBatchesChunkQueriesButRemainAtomicOnLateMoveFailure() = runTest {
        repo.addNewSims((1..1100).map { "88" + it.toString().padStart(6, '0') }, b)
        val original = rows(b)
        val failing = original.last().id
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_move BEFORE UPDATE ON sims WHEN OLD.id = '$failing' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expect<android.database.sqlite.SQLiteConstraintException> { repo.changeInventory(original, a) }
        assertEquals(original, rows(b)); assertEquals(7, counts()[a])
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_move")
        assertEquals(1100, repo.changeInventory(original, a))
        assertEquals(1107, counts()[a]); assertEquals(0, counts()[b])
        assertEquals(1100, repo.changeInventory(rows().filter { it.serial.startsWith("88") }, null, true))
        assertEquals(7, repo.total.first())
    }
    @Test fun lateDeleteFailureRollsBackEveryDeletion() = runTest {
        val original = rows()
        val failing = original.last().id
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON sims WHEN OLD.id = '$failing' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expect<android.database.sqlite.SQLiteConstraintException> { repo.changeInventory(original, null, true) }
        assertEquals(original, rows()); assertEquals(7, repo.total.first())
    }
    @Test fun invalidSelectionCannotChangeData() = runTest {
        val original = rows()
        expect<IllegalArgumentException> { repo.changeInventory(emptyList(), b) }
        expect<IllegalArgumentException> { repo.changeInventory(listOf(original.first(), original.first()), b) }
        assertEquals(original, rows())
        assertTrue(rows(b).isEmpty())
    }
    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (e: Throwable) { if (e !is T) throw e }
    }
}
