package com.rb.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.ui.RbApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi", application = RbApplication::class)
class TransferHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private val serial = "8996603430263612700"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
    }
    @After fun close() { db.close() }
    @Test fun historyMenuDetailsCopyAndBackShowHistoricalData() {
        runBlocking {
            val a = repo.createGroup("0055", "عوض")
            val b = repo.createGroup("0013", "أحمد")
            repo.addNewSims(listOf(serial), a)
            repo.changeInventory(repo.sims(a).first(), b)
            repo.updateOwner(a, "تعديل المسؤول")
        }
        compose.setContent { RbApp(repo) }
        compose.onNodeWithContentDescription("قائمة التطبيق").performClick()
        compose.onNodeWithText("سجل النقل").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("نقل 1 شريحة").fetchSemanticsNodes().isNotEmpty() }
        screenshot("01-history")
        compose.onNodeWithText("نقل 1 شريحة").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(serial).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("من: عوض").assertExists()
        compose.onNodeWithText("إلى: أحمد").assertExists()
        compose.onNodeWithText("2700").assertExists()
        screenshot("02-details")
        compose.onNodeWithText("نسخ الرقم الكامل").performClick()
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(serial, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("سجل النقل").assertExists()
    }
    @Test fun emptyHistoryHasNoInventedPastRecords() {
        compose.setContent { RbApp(repo) }
        compose.onNodeWithContentDescription("قائمة التطبيق").performClick()
        compose.onNodeWithText("سجل النقل").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("لا توجد عمليات نقل مسجلة بعد").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val view = android.view.inspector.WindowInspector.getGlobalWindowViews()
            .last { it.isAttachedToWindow && it.visibility == android.view.View.VISIBLE }
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/screenshots/stage6").mkdirs()
        java.io.File("build/reports/screenshots/stage6/$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
