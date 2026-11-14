package com.rb.app

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
class TransferHistoryTest {
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private lateinit var a: String
    private lateinit var b: String
    @Before fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
        a = repo.createGroup("0055", "عوض"); b = repo.createGroup("0013", "أحمد")
        repo.addNewSims(listOf("00001234", "00011234", "8996603430263612700"), a)
    }
    @After fun close() { db.close() }
    private suspend fun sims(id: String?) = repo.inventory(false, id, "").first().map { it.sim }
    @Test fun singleThenBulkAreOneRecordEachAndSnapshotsOutliveInventoryAndOwners() = runTest {
        val original = sims(a)
        repo.changeInventory(original.take(1), b)
        repo.changeInventory(original.drop(1), b)
        val records = repo.transferHistory.first()
        assertEquals(listOf(2, 1), records.map { it.simCount })
        assertEquals(original.drop(1).map { it.serial }.toSet(), repo.transferDetails(records.first().record.id).first()!!.items.map { it.simSerialSnapshot }.toSet())
        repo.updateOwner(a, "اسم جديد"); repo.updateOwner(b, "مسؤول جديد")
        repo.changeInventory(sims(b), null, delete = true)
        repo.deleteEmptyGroup(a); repo.deleteEmptyGroup(b)
        val record = repo.transferDetails(records.first().record.id).first()!!
        assertEquals("عوض", record.record.sourceOwnerNameSnapshot)
        assertEquals("أحمد", record.record.destinationOwnerNameSnapshot)
        assertEquals("0055", record.record.sourceGroupCodeSnapshot)
        assertEquals("0013", record.record.destinationGroupCodeSnapshot)
        assertEquals(2, record.items.size)
        assertTrue(record.record.timestamp > 0)
    }
    @Test fun mixedScanGroupsGetSeparateRecordsOnlyForApprovedActualGroupTransfers() = runTest {
        val c = repo.createGroup("0020", "ثالث")
        repo.addNewSims(listOf("77777777"), c)
        repo.addNewSims(listOf("88888888"), null)
        repo.addNewSims(listOf("99999999"), b)
        val values = sims(a).map { it.serial } + listOf("77777777", "88888888", "99999999", "00009999")
        val plan = repo.reviewScan(values, b)
        val approved = setOf(values[0], values[1], "77777777", "88888888")
        assertEquals(ScanResult(1, 4, 2), repo.saveScan(plan, approved))
        val records = repo.transferHistory.first()
        assertEquals(2, records.size)
        assertEquals(mapOf(a to 2, c to 1), records.associate { it.record.sourceGroupId to it.simCount })
        val logged = records.flatMap { repo.transferDetails(it.record.id).first()!!.items }.map { it.simSerialSnapshot }.toSet()
        assertEquals(setOf(values[0], values[1], "77777777"), logged)
        assertTrue(runCatching { repo.saveScan(plan, approved) }.exceptionOrNull() is StaleScanException)
        assertEquals(2, repo.transferHistory.first().size)
    }
    @Test fun additionsSameGroupArchiveRestoreDeleteAndRenameNeverCreateHistory() = runTest {
        val original = sims(a)
        repo.changeInventory(original, a)
        repo.changeInventory(original, null)
        repo.changeInventory(sims(null), b)
        repo.saveScan(repo.reviewScan(listOf("00001234", "123123123"), null), setOf("00001234"))
        repo.updateOwner(b, "تعديل")
        repo.changeInventory(sims(b), null, delete = true)
        assertTrue(repo.transferHistory.first().isEmpty())
    }
    @Test fun recordItemFailureRollsBackInventoryAndRecordsAndRetryCannotDuplicate() = runTest {
        val original = sims(a)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history BEFORE INSERT ON transfer_record_items BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repo.changeInventory(original, b) }.isFailure)
        assertEquals(original, sims(a)); assertTrue(repo.transferHistory.first().isEmpty())
        assertTrue(runCatching { repo.saveScan(repo.reviewScan(original.map { it.serial } + "456456456", b), original.map { it.serial }.toSet()) }.isFailure)
        assertEquals(3, repo.total.first()); assertEquals(original, sims(a))
        assertTrue(repo.transferHistory.first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_history")
        repo.changeInventory(original, b)
        assertTrue(runCatching { repo.changeInventory(original, b) }.exceptionOrNull() is StaleInventoryException)
        assertEquals(1, repo.transferHistory.first().size)
    }
    @Test fun failureInSecondSourceRollsBackEarlierHistoryAndAllScanChanges() = runTest {
        val c = repo.createGroup("0020", "ثالث")
        repo.addNewSims(listOf("77777777"), c)
        val original = repo.exportSnapshot()
        val serials = sims(a).map { it.serial } + "77777777" + "123123123"
        val plan = repo.reviewScan(serials, b)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second_source BEFORE INSERT ON transfer_record_items WHEN NEW.simSerialSnapshot = '77777777' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repo.saveScan(plan, serials.toSet()) }.isFailure)
        assertEquals(original, repo.exportSnapshot())
        assertTrue(repo.transferHistory.first().isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transfer_records").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
    @Test fun lateMoveFailureAndMissingDestinationLeaveNoFalseHistory() = runTest {
        val original = sims(a)
        val failing = original.last().id
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_move BEFORE UPDATE ON sims WHEN OLD.id = '$failing' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repo.changeInventory(original, b) }.isFailure)
        assertEquals(original, sims(a)); assertTrue(repo.transferHistory.first().isEmpty())
        assertTrue(runCatching { repo.changeInventory(original, "missing") }.isFailure)
        assertTrue(repo.transferHistory.first().isEmpty())
    }
}
