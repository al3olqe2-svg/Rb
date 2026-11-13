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
    @Insert suspend fun insertSims(sims: List<Sim>)
}

@Database(entities = [Group::class, Sim::class], version = 1, exportSchema = true)
abstract class RbDatabase : RoomDatabase() { abstract fun inventory(): InventoryDao }
