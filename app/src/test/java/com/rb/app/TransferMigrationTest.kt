package com.rb.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = RbApplication::class)
class TransferMigrationTest {
    @Test fun realV1SchemaMigratesWithoutLossAndHistorySurvivesReopening() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "rb.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.rb.app.data.RbDatabase/1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
            }
            sqlite.execSQL("INSERT INTO groups VALUES ('a', '0055', 'عوض'), ('b', '0013', 'أحمد')")
            sqlite.execSQL("INSERT INTO sims VALUES ('s1', '00000012345678901234', 'a'), ('s2', '00009999', NULL)")
            sqlite.version = 1
        }
        fun open() = Room.databaseBuilder(context, RbDatabase::class.java, name).addMigrations(RbDatabase.MIGRATION_1_2).build()
        val db = (context as RbApplication).database
        var recordId = ""
        try {
            val repo = InventoryRepository(db)
            val before = repo.exportSnapshot()
            assertEquals(setOf("0055", "0013"), before.groups.map { it.code }.toSet())
            assertEquals(setOf("00000012345678901234", "00009999"), before.sims.map { it.serial }.toSet())
            assertNull(before.sims.single { it.id == "s2" }.groupId)
            assertTrue(repo.transferHistory.first().isEmpty())
            repo.changeInventory(listOf(before.sims.single { it.id == "s1" }), "b")
            recordId = repo.transferHistory.first().single().record.id
        } finally { db.close() }
        val reopened = open()
        try {
            val repo = InventoryRepository(reopened)
            assertEquals(1, repo.transferHistory.first().size)
            assertEquals("00000012345678901234", repo.transferDetails(recordId).first()!!.items.single().simSerialSnapshot)
            assertEquals("b", repo.findSerials(listOf("00000012345678901234")).single().groupId)
            assertEquals(2, repo.total.first())
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
