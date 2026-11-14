package com.rb.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction

object InputRules {
    // Normalize Arabic and Persian keyboards to ASCII without numeric conversion.
    fun code(raw: String): String = raw.trim().map { c ->
        when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c }
    }.joinToString("")
    fun validCode(code: String) = code.length in 1..64 && code.all { it in '0'..'9' }
    fun validOwner(name: String) = name.trim().length in 1..120
    fun validSerial(serial: String) = serial.isNotEmpty() && serial.all { it in '0'..'9' }
}

sealed class InventoryException(message: String) : Exception(message) {
    class InvalidCode : InventoryException("أدخل رقم مجموعة من 1 إلى 64 خانة")
    class InvalidOwner : InventoryException("أدخل اسم المسؤول من 1 إلى 120 حرفًا")
    class DuplicateCode : InventoryException("رقم المجموعة مستخدم بالفعل")
    class GroupNotEmpty : InventoryException("لا يمكن حذف مجموعة تحتوي على شرائح")
    class MissingGroup : InventoryException("المجموعة غير موجودة")
}

class InventoryRepository(private val db: RbDatabase) {
    private val dao = db.inventory()
    // A single read transaction keeps both worksheets consistent while inventory changes.
    suspend fun exportSnapshot(): InventorySnapshot = db.withTransaction {
        InventorySnapshot(dao.exportGroups(), dao.exportSims())
    }
    val groups = dao.observeGroups()
    val total = dao.observeTotal()
    fun sims(groupId: String) = dao.observeSims(groupId)
    fun archive() = dao.observeArchive()
    suspend fun findSerials(serials: List<String>): List<Sim> =
        serials.chunked(900).flatMap { dao.findSerials(it) }

    fun inventory(global: Boolean, groupId: String?, query: String) =
        dao.observeInventory(global, groupId, InputRules.code(query))
    fun count(groupId: String?) = dao.observeCount(groupId)

    /** Exact reviewed records only; changed/deleted rows abort the whole operation.
     * Chunk every IN clause below SQLite's parameter limit, within ONE transaction.
     */
    suspend fun changeInventory(expected: List<Sim>, destination: String?, delete: Boolean = false): Int = db.withTransaction {
        require(expected.isNotEmpty() && expected.map { it.id }.distinct().size == expected.size)
        if (!delete && destination != null && dao.group(destination) == null) throw InventoryException.MissingGroup()
        val current = expected.map { it.id }.chunked(900).flatMap { dao.findIds(it) }.associateBy { it.id }
        if (expected.any { current[it.id] != it }) throw StaleInventoryException()
        expected.map { it.id }.chunked(900).sumOf { ids ->
            val changed = if (delete) dao.deleteInventory(ids) else dao.moveInventory(ids, destination)
            check(changed == ids.size)
            changed
        }
    }

    suspend fun createGroup(rawCode: String, owner: String): String {
        val code = InputRules.code(rawCode)
        if (!InputRules.validCode(code)) throw InventoryException.InvalidCode()
        if (!InputRules.validOwner(owner)) throw InventoryException.InvalidOwner()
        val group = Group(code = code, ownerName = owner.trim())
        try { dao.insertGroup(group) } catch (e: SQLiteConstraintException) {
            throw InventoryException.DuplicateCode()
        }
        return group.id
    }
    suspend fun updateOwner(id: String, owner: String) {
        if (!InputRules.validOwner(owner)) throw InventoryException.InvalidOwner()
        if (dao.updateOwner(id, owner.trim()) == 0) throw InventoryException.MissingGroup()
    }
    suspend fun deleteEmptyGroup(id: String) = db.withTransaction {
        if (dao.group(id) == null) throw InventoryException.MissingGroup()
        if (dao.deleteEmptyGroup(id) == 0) throw InventoryException.GroupNotEmpty()
    }
    suspend fun reviewScan(serials: List<String>, destination: String?): ScanPlan = db.withTransaction {
        require(serials.isNotEmpty() && serials.distinct().size == serials.size)
        require(serials.all(InputRules::validSerial))
        if (destination != null && dao.group(destination) == null) throw InventoryException.MissingGroup()
        val existing = findSerials(serials).associateBy { it.serial }
        ScanPlan(destination, serials.map { serial ->
            val sim = existing[serial]
            ScanRow(serial, sim, sim?.groupId?.let { dao.group(it) })
        })
    }

    /** Re-check the exact reviewed locations under the same write transaction.
     * A stale plan aborts ALL changes, including inserts. Approval never follows a SIM to a new location.
     */
    suspend fun saveScan(plan: ScanPlan, approvedTransfers: Set<String>): ScanResult = db.withTransaction {
        require(plan.rows.isNotEmpty() && plan.rows.map { it.serial }.distinct().size == plan.rows.size)
        require(plan.rows.all { InputRules.validSerial(it.serial) })
        if (plan.destination != null && dao.group(plan.destination) == null) throw InventoryException.MissingGroup()
        val current = findSerials(plan.rows.map { it.serial }).associateBy { it.serial }
        if (plan.rows.any { current[it.serial] != it.existing }) throw StaleScanException()
        var added = 0; var moved = 0; var ignored = 0
        for (row in plan.rows) {
            val existing = row.existing
            when {
                existing == null -> { dao.insertSims(listOf(Sim(serial = row.serial, groupId = plan.destination))); added++ }
                existing.groupId == plan.destination -> ignored++
                row.serial in approvedTransfers -> { check(dao.moveScannedSim(existing.id, plan.destination) == 1); moved++ }
                else -> ignored++
            }
        }
        ScanResult(added, moved, ignored)
    }

    /** Insert-only API retained for Stage 1 callers; conflicts abort the entire batch.
     * The scanner uses reviewScan/saveScan for reviewed, explicitly approved transfers.
     */
    suspend fun addNewSims(serials: List<String>, groupId: String?) = db.withTransaction {
        require(serials.all(InputRules::validSerial)) { "Invalid serial" }
        require(serials.distinct().size == serials.size) { "Duplicate serial in batch" }
        if (groupId != null && dao.group(groupId) == null) throw InventoryException.MissingGroup()
        dao.insertSims(serials.map { Sim(serial = it, groupId = groupId) })
    }
}


data class ScanRow(val serial: String, val existing: Sim?, val source: Group?)
data class ScanPlan(val destination: String?, val rows: List<ScanRow>)
data class ScanResult(val added: Int, val moved: Int, val ignored: Int)
class StaleScanException : Exception("تغير موقع إحدى الشرائح منذ المراجعة. حدّث الحالات ووافق على النقل من جديد.")

class StaleInventoryException : Exception("تغيرت الشرائح منذ التحديد. حدّث القائمة وحددها مجددًا؛ لم يُنفذ أي تغيير.")

data class InventorySnapshot(val groups: List<Group>, val sims: List<Sim>)
