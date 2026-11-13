package com.rb.app

import android.app.Application
import androidx.room.Room
import com.rb.app.data.InventoryRepository
import com.rb.app.data.RbDatabase

class RbApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, RbDatabase::class.java, "rb.db").build() }
    val repository by lazy { InventoryRepository(database) }
}
