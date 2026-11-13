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
    val groups = dao.observeGroups()
    val total = dao.observeTotal()
    fun sims(groupId: String) = dao.observeSims(groupId)
    fun archive() = dao.observeArchive()
    suspend fun findSerials(serials: List<String>): List<Sim> =
        serials.chunked(900).flatMap { dao.findSerials(it) }

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
    /** Stage 2 foundation: insert new serials only; conflicts abort the entire batch.
     * Existing SIMs are never moved implicitly. No transfer/archive mutation is exposed yet.
     */
    suspend fun addNewSims(serials: List<String>, groupId: String?) = db.withTransaction {
        require(serials.all(InputRules::validSerial)) { "Invalid serial" }
        require(serials.distinct().size == serials.size) { "Duplicate serial in batch" }
        if (groupId != null && dao.group(groupId) == null) throw InventoryException.MissingGroup()
        dao.insertSims(serials.map { Sim(serial = it, groupId = groupId) })
    }
}
