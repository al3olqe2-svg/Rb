package com.rb.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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
class DatabaseTest {
    private lateinit var db: RbDatabase
    private lateinit var repository: InventoryRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repository = InventoryRepository(db)
    }
    @After fun close() { db.close() }

    @Test fun createNormalizeAndRenameWithoutChangingIdentity() = runTest {
        val id = repository.createGroup("٠٠٥٥", "  أحمد  ")
        assertEquals("0055", db.inventory().group(id)?.code)
        repository.updateOwner(id, "سارة")
        assertEquals(Group(id, "0055", "سارة"), db.inventory().group(id))
        assertEquals(0, repository.groups.first().single().simCount)
        assertEquals(0, repository.total.first())
    }

    @Test fun duplicateGroupIsRejectedByRepositoryAndDatabase() = runTest {
        repository.createGroup("0055", "أحمد")
        expect<InventoryException.DuplicateCode> { repository.createGroup("۰۰۵۵", "سارة") }
        expect<SQLiteConstraintException> { db.inventory().insertGroup(Group(code = "0055", ownerName = "علي")) }
        assertEquals(1, repository.groups.first().size)
        repository.createGroup("55", "سارة") // textual identity preserves leading zero distinction
        assertEquals(2, repository.groups.first().size)
    }

    @Test fun validatesInputBeforeWriting() = runTest {
        for (invalid in listOf("", "-55", "5.5", "5 5", "5a", "1".repeat(65))) {
            expect<InventoryException.InvalidCode> { repository.createGroup(invalid, "أحمد") }
        }
        expect<InventoryException.InvalidOwner> { repository.createGroup("55", "   ") }
        val id = repository.createGroup("55", "أحمد")
        expect<InventoryException.InvalidOwner> { repository.updateOwner(id, "") }
        assertEquals("أحمد", db.inventory().group(id)?.ownerName)
    }

    @Test fun countsReflectRowsIncludingArchiveAndLast4IsNotUnique() = runTest {
        val id = repository.createGroup("0055", "أحمد")
        val other = repository.createGroup("0066", "سارة")
        val serials = listOf("00012345678901234567895555", "00022345678901234567895555")
        repository.addNewSims(serials, id)
        repository.addNewSims(listOf("00000000000000000000001"), null)
        val rows = repository.sims(id).first()
        assertEquals(serials, rows.map { it.serial })
        assertEquals(listOf("5555", "5555"), rows.map { it.last4 })
        val counts = repository.groups.first().associate { it.group.id to it.simCount }
        assertEquals(2, counts[id]); assertEquals(0, counts[other])
        assertEquals(3, repository.total.first())
        assertEquals(1, repository.archive().first().size)
        assertEquals(2, repository.findSerials(serials).size)
    }

    @Test fun uniqueSerialForeignKeysAndBatchRollbackAreEnforced() = runTest {
        val id = repository.createGroup("0055", "أحمد")
        repository.addNewSims(listOf("0001"), id)
        expect<SQLiteConstraintException> { repository.addNewSims(listOf("0002", "0001"), null) }
        assertEquals(emptyList<Sim>(), repository.findSerials(listOf("0002")))
        expect<SQLiteConstraintException> { db.inventory().insertSims(listOf(Sim(serial = "0003", groupId = "missing"))) }
        expect<IllegalArgumentException> { repository.addNewSims(listOf("0004", "0004"), id) }
        expect<SQLiteConstraintException> { db.openHelper.writableDatabase.execSQL("DELETE FROM groups WHERE id = ?", arrayOf(id)) }
        assertEquals(id, repository.findSerials(listOf("0001")).single().groupId)
        assertEquals(1, repository.total.first())
    }

    @Test fun onlyEmptyGroupsCanBeDeleted() = runTest {
        val id = repository.createGroup("0055", "أحمد")
        repository.addNewSims(listOf("0001"), id)
        expect<InventoryException.GroupNotEmpty> { repository.deleteEmptyGroup(id) }
        val empty = repository.createGroup("0066", "سارة")
        repository.deleteEmptyGroup(empty)
        assertNull(db.inventory().group(empty))
        assertNotNull(db.inventory().group(id))
    }

    @Test fun survivesDatabaseCloseAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, RbDatabase::class.java, name).build()
        var disk = open()
        try {
            val first = InventoryRepository(disk)
            val id = first.createGroup("0055", "أحمد")
            first.addNewSims(listOf("00012345678901234567890"), id)
            first.updateOwner(id, "سارة")
            disk.close()
            disk = open()
            val second = InventoryRepository(disk)
            assertEquals(Group(id, "0055", "سارة"), disk.inventory().group(id))
            assertEquals("00012345678901234567890", second.sims(id).first().single().serial)
            assertEquals(1, second.groups.first().single().simCount)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
