package com.rb.app

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import org.robolectric.shadows.ShadowContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ExportStateTest {
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private val store = ViewModelStore()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, RbDatabase::class.java).build()
        repo = InventoryRepository(db)
    }
    @After fun close() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun vm() = ExportViewModel(repo, ApplicationProvider.getApplicationContext()).also { store.put("export", it) }
    @Test fun exportSnapshotReflectsCurrentLocationsAndExcludesDeletedRows() = runTest {
        val id = repo.createGroup("0055", "عوض")
        repo.addNewSims(listOf("0008996603430263612277", "0008996603430263612278"), id)
        repo.addNewSims(listOf("0008996603430263612279"), null)
        val initial = repo.sims(id).first()
        repo.changeInventory(listOf(initial[0]), null)
        repo.changeInventory(listOf(initial[1]), null, delete = true)
        val snapshot = repo.exportSnapshot()
        assertEquals("0055", snapshot.groups.single().code)
        assertEquals(2, snapshot.sims.size)
        assertTrue(snapshot.sims.all { it.groupId == null })
        assertEquals(2, repo.total.first())
        val out = File(context.cacheDir, "snapshot.xlsx")
        val vm = vm()
        assertTrue(vm.choose()); assertFalse(vm.choose())
        val provider = ExportTestProvider(out)
        provider.attachInfo(context, ProviderInfo().apply { authority = "rb.export.test" })
        ShadowContentResolver.registerProviderInternal("rb.export.test", provider)
        val uri = Uri.parse("content://rb.export.test/inventory.xlsx")
        vm.save(uri)
        assertFalse(vm.choose())
        withContext(Dispatchers.Default) { withTimeout(30_000) { vm.state.first { !it.busy } } }
        assertNull(vm.state.value.error); assertEquals(uri, vm.state.value.uri)
        assertEquals("wt", provider.mode)
        ZipFile(out).use { zip ->
            val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).bufferedReader().readText()
            assertTrue(sheet.contains("0008996603430263612277"))
            assertTrue(sheet.contains("0008996603430263612279"))
            assertFalse(sheet.contains("0008996603430263612278"))
        }
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("rb-export-") })
        assertEquals(snapshot, repo.exportSnapshot()) // export cannot modify data or IDs
        out.delete()
    }
    @Test fun cancellingPickerAndWriteFailureAllowRetryWithoutSuccess() = runTest {
        val vm = vm()
        assertTrue(vm.choose()); vm.save(null)
        assertFalse(vm.state.value.busy); assertNull(vm.state.value.error); assertNull(vm.state.value.uri)
        assertTrue(vm.choose())
        // A directory is not a writable document.
        vm.save(Uri.fromFile(context.cacheDir))
        withContext(Dispatchers.Default) { withTimeout(30_000) { vm.state.first { !it.busy } } }
        assertNotNull(vm.state.value.error); assertNull(vm.state.value.uri)
        assertTrue(vm.choose()); vm.save(null)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("rb-export-") })
    }
    @Test fun fullStorageErrorIsActionableIncludingWrappedProviderErrors() {
        val message = exportError(IOException("provider", IOException("ENOSPC: No space left on device")))
        assertTrue(message.contains("لا توجد مساحة كافية"))
        assertTrue(exportError(SecurityException()).contains("موقع الحفظ"))
    }
}

private class ExportTestProvider(private val file: File) : ContentProvider() {
    var mode: String? = null
    override fun onCreate() = true
    override fun getType(uri: Uri) = InventoryXlsx.MIME
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        this.mode = mode
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
