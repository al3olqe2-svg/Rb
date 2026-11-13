package com.rb.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "groups", indices = [Index(value = ["code"], unique = true)])
data class Group(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val code: String,
    val ownerName: String,
)

@Entity(
    tableName = "sims",
    indices = [Index(value = ["serial"], unique = true), Index("groupId")],
    foreignKeys = [ForeignKey(entity = Group::class, parentColumns = ["id"],
        childColumns = ["groupId"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.NO_ACTION)],
)
data class Sim(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val serial: String,
    val groupId: String? = null,
) { @get:Ignore val last4: String get() = serial.takeLast(4) }

data class InventoryRow(@Embedded val sim: Sim, val groupCode: String?, val ownerName: String?)

data class GroupSummary(@Embedded val group: Group, val simCount: Int)

@Dao
interface InventoryDao {
    @Query("SELECT groups.*, COUNT(sims.id) AS simCount FROM groups LEFT JOIN sims ON groups.id = sims.groupId GROUP BY groups.id ORDER BY length(code), code, groups.id")
    fun observeGroups(): Flow<List<GroupSummary>>
    @Query("SELECT COUNT(*) FROM sims")
    fun observeTotal(): Flow<Int>
    @Query("SELECT * FROM sims WHERE groupId = :groupId ORDER BY serial, id")
    fun observeSims(groupId: String): Flow<List<Sim>>
    @Query("SELECT * FROM sims WHERE groupId IS NULL ORDER BY serial, id")
    fun observeArchive(): Flow<List<Sim>>
    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun group(id: String): Group?
    @Query("SELECT * FROM sims WHERE serial IN (:serials)")
    suspend fun findSerials(serials: List<String>): List<Sim>
    @Insert suspend fun insertGroup(group: Group)
    @Query("UPDATE groups SET ownerName = :name WHERE id = :id")
    suspend fun updateOwner(id: String, name: String): Int
    // One atomic predicate also protects deletion if inventory changes during confirmation.
    @Query("DELETE FROM groups WHERE id = :id AND NOT EXISTS (SELECT 1 FROM sims WHERE groupId = :id)")
    suspend fun deleteEmptyGroup(id: String): Int
    @Query("UPDATE sims SET groupId = :destination WHERE id = :id")
    suspend fun moveScannedSim(id: String, destination: String?): Int
    @Query("""SELECT sims.*, groups.code AS groupCode, groups.ownerName AS ownerName
        FROM sims LEFT JOIN groups ON sims.groupId = groups.id
        WHERE (:global OR sims.groupId IS :groupId) AND instr(sims.serial, :query) > 0
        ORDER BY length(ltrim(serial, '0')), ltrim(serial, '0'), serial, sims.id""")
    fun observeInventory(global: Boolean, groupId: String?, query: String): Flow<List<InventoryRow>>
    @Query("SELECT COUNT(*) FROM sims WHERE groupId IS :groupId")
    fun observeCount(groupId: String?): Flow<Int>
    @Query("SELECT * FROM sims WHERE id IN (:ids)")
    suspend fun findIds(ids: List<String>): List<Sim>
    @Query("UPDATE sims SET groupId = :destination WHERE id IN (:ids)")
    suspend fun moveInventory(ids: List<String>, destination: String?): Int
    @Query("DELETE FROM sims WHERE id IN (:ids)")
    suspend fun deleteInventory(ids: List<String>): Int
    @Insert suspend fun insertSims(sims: List<Sim>)
}

@Database(entities = [Group::class, Sim::class], version = 1, exportSchema = true)
abstract class RbDatabase : RoomDatabase() { abstract fun inventory(): InventoryDao }
