package com.rb.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Historical snapshots deliberately have no FK to mutable/deletable inventory or groups.
@Entity(tableName = "transfer_records", indices = [Index("timestamp")])
data class TransferRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sourceGroupId: String,
    val destinationGroupId: String,
    val sourceGroupCodeSnapshot: String,
    val sourceOwnerNameSnapshot: String,
    val destinationGroupCodeSnapshot: String,
    val destinationOwnerNameSnapshot: String,
    val timestamp: Long,
)

@Entity(tableName = "transfer_record_items", indices = [Index("transferRecordId")],
    foreignKeys = [ForeignKey(entity = TransferRecord::class, parentColumns = ["id"],
        childColumns = ["transferRecordId"], onDelete = ForeignKey.CASCADE)])
data class TransferRecordItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val transferRecordId: String,
    val simSerialSnapshot: String,
)

data class TransferSummary(@Embedded val record: TransferRecord, val simCount: Int)
data class TransferDetails(
    @Embedded val record: TransferRecord,
    @Relation(parentColumn = "id", entityColumn = "transferRecordId") val items: List<TransferRecordItem>,
)

@Dao
interface TransferDao {
    @Insert suspend fun insertRecord(record: TransferRecord)
    @Insert suspend fun insertItems(items: List<TransferRecordItem>)
    @Query("""SELECT transfer_records.*, COUNT(transfer_record_items.id) AS simCount
        FROM transfer_records JOIN transfer_record_items ON transfer_records.id = transfer_record_items.transferRecordId
        GROUP BY transfer_records.id ORDER BY timestamp DESC, transfer_records.rowid DESC""")
    fun observeHistory(): Flow<List<TransferSummary>>
    @Transaction
    @Query("SELECT * FROM transfer_records WHERE id = :id")
    fun observeDetails(id: String): Flow<TransferDetails?>
}
