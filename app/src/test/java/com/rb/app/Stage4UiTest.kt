package com.rb.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.rb.app.data.*
import com.rb.app.ui.RbApp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Stage4UiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RbDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        val repo = InventoryRepository(db)
        runBlocking {
            val id = repo.createGroup("0055", "عوض")
            repo.createGroup("0066", "أحمد")
            repo.addNewSims(listOf("0008996603430263612277", "0008996603430263612278"), id)
            repo.addNewSims(listOf("0008996603430263612279"), null)
        }
        compose.setContent { RbApp(repo) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("0055").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun close() { db.close() }
    private fun screenshot(name: String) = compose.runOnIdle {
        val view = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/screenshots").mkdirs()
        File("build/reports/screenshots/stage4-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun exportNavigationAndCompactListsRemainUsableOnNarrowScreen() {
        screenshot("groups-320")
        compose.onNodeWithContentDescription("قائمة التطبيق").performClick()
        compose.onNodeWithText("تصدير إلى Excel").performClick()
        compose.onNodeWithText("اختيار مكان الحفظ والتصدير").assertIsDisplayed().assertIsEnabled()
        screenshot("export-320")
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("0055").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2277").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("تحديد 0008996603430263612277").performClick()
        compose.onNodeWithText("نقل", substring = false).assertIsDisplayed()
        compose.onNodeWithText("أرشفة", substring = false).assertIsDisplayed()
        compose.onNodeWithText("حذف", substring = false).assertIsDisplayed()
        screenshot("selected-320")
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("الأرشيف", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2279").fetchSemanticsNodes().isNotEmpty() }
        screenshot("archive-320")
        compose.onNodeWithText("المسح", useUnmergedTree = true).performClick()
        screenshot("scanner-320")
    }
    // A focused text field with explicit display dimensions loops in Robolectric (#8460).
    @Config(qualifiers = "xhdpi")
    @Test fun groupFilterFindsCodesAndOwnersAndCanBeCleared() {
        compose.onNodeWithText("بحث المجموعات").performTextInput("٠٠٥٥")
        compose.onNodeWithText("0055").assertExists()
        compose.onNodeWithText("0066").assertDoesNotExist()
        compose.onNodeWithContentDescription("مسح فلتر المجموعات").performClick()
        compose.onNodeWithText("بحث المجموعات").performTextInput("أحمد")
        compose.onNodeWithText("0066").assertExists()
        compose.onNodeWithText("0055").assertDoesNotExist()
        compose.onNodeWithContentDescription("مسح فلتر المجموعات").performClick()
        compose.onNodeWithText("0055").assertExists()
    }
}
