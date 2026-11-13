package com.rb.app

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = RbApplication::class)
class ScanBatchTest {
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build(); repo = InventoryRepository(db) }
    @After fun close() { db.close() }
    @Test fun batchAddsNewIgnoresExistingAndMovesOnlyExplicitlyApproved() = runTest {
        val old = repo.createGroup("0055", "عوض")
        val destination = repo.createGroup("0066", "سارة")
        repo.addNewSims(listOf("8996603430263612277", "8996603430263612278"), old)
        repo.addNewSims(listOf("00012345678901234567895555"), null)
        repo.addNewSims(listOf("00022345678901234567895555"), destination)
        val serials = listOf("8996603430263612277", "8996603430263612278", "00012345678901234567895555", "00022345678901234567895555", "0000000001", "0000000002")
        val before = repo.findSerials(serials).associateBy { it.serial }
        val plan = repo.reviewScan(serials, destination)
        assertEquals("0055", plan.rows.first().source?.code)
        assertEquals("عوض", plan.rows.first().source?.ownerName)
        assertEquals(ScanResult(2, 2, 2), repo.saveScan(plan, setOf(serials[0], serials[2])))
        val after = repo.findSerials(serials).associateBy { it.serial }
        assertEquals(old, after[serials[1]]?.groupId) // refused: stays at source
        assertEquals(destination, after[serials[0]]?.groupId)
        assertEquals(before[serials[0]]?.id, after[serials[0]]?.id) // identity preserved
        assertEquals(5, repo.groups.first().first { it.group.id == destination }.simCount)
        assertEquals(6, repo.total.first())
    }
    @Test fun archiveTransferAlsoRequiresApprovalAndSameDestinationIsIgnored() = runTest {
        val group = repo.createGroup("0001", "عوض")
        repo.addNewSims(listOf("00010000"), group)
        repo.addNewSims(listOf("00020000"), null)
        val plan = repo.reviewScan(listOf("00010000", "00020000"), null)
        assertEquals(ScanResult(0, 0, 2), repo.saveScan(plan, emptySet()))
        assertEquals(group, repo.findSerials(listOf("00010000")).single().groupId)
        assertEquals(ScanResult(0, 1, 1), repo.saveScan(plan, setOf("00010000")))
        assertEquals(0, repo.groups.first().single().simCount)
        assertEquals(2, repo.archive().first().size)
    }
    @Test fun failedWriteRollsBackEarlierInsertAndTransfer() = runTest {
        val old = repo.createGroup("0055", "عوض")
        val destination = repo.createGroup("0066", "سارة")
        repo.addNewSims(listOf("00010000"), old)
        val plan = repo.reviewScan(listOf("00020000", "00010000", "00030000"), destination)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scan BEFORE INSERT ON sims WHEN NEW.serial = '00030000' BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
        expect<SQLiteException> { repo.saveScan(plan, setOf("00010000")) }
        assertEquals(1, repo.total.first())
        assertEquals(old, repo.findSerials(listOf("00010000")).single().groupId)
        assertTrue(repo.findSerials(listOf("00020000", "00030000")).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_scan")
        assertEquals(ScanResult(2, 1, 0), repo.saveScan(plan, setOf("00010000")))
    }
    @Test fun staleLocationAndNewConcurrentSerialAbortWholeBatch() = runTest {
        val old = repo.createGroup("0055", "عوض")
        val destination = repo.createGroup("0066", "سارة")
        repo.addNewSims(listOf("00010000"), old)
        val plan = repo.reviewScan(listOf("00020000", "00010000"), destination)
        db.inventory().moveScannedSim(plan.rows[1].existing!!.id, null)
        expect<StaleScanException> { repo.saveScan(plan, setOf("00010000")) }
        assertTrue(repo.findSerials(listOf("00020000")).isEmpty())
        assertNull(repo.findSerials(listOf("00010000")).single().groupId)
        val fresh = repo.reviewScan(listOf("00020000", "00010000"), destination)
        repo.addNewSims(listOf("00020000"), null)
        expect<StaleScanException> { repo.saveScan(fresh, setOf("00010000")) }
        assertEquals(0, repo.groups.first().first { it.group.id == destination }.simCount)
    }
    @Test fun missingDestinationAndDuplicatePlanNeverWriteOrReplaceExisting() = runTest {
        val group = repo.createGroup("0055", "عوض")
        val plan = repo.reviewScan(listOf("00010000"), group)
        repo.deleteEmptyGroup(group)
        expect<InventoryException.MissingGroup> { repo.saveScan(plan, emptySet()) }
        expect<IllegalArgumentException> { repo.reviewScan(listOf("0001", "0001"), null) }
        assertEquals(0, repo.total.first())
        val archive = repo.reviewScan(listOf("00010000"), null)
        repo.saveScan(archive, emptySet())
        expect<StaleScanException> { repo.saveScan(archive, emptySet()) }
        assertEquals(1, repo.total.first())
    }
    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }
}
