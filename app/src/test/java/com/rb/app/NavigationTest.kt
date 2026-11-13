package com.rb.app

import androidx.compose.ui.test.*
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.ui.RbApp
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RbDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        compose.setContent { RbApp(InventoryRepository(db)) }
    }
    @After fun close() { db.close() }

    // Robolectric #8460: explicit w/h qualifiers loop with a focused dialog text field.
    @Config(qualifiers = "xhdpi")
    @Test fun navigationScannerAndGroupLifecycle() {
        compose.onNodeWithText("المسح").performClick()
        compose.onNodeWithText("امسح الشرائح تباعًا").assertIsDisplayed()
        compose.onNodeWithText("الأرشيف", useUnmergedTree = true).performClick()
        compose.onNodeWithText("الأرشيف العام").assertIsDisplayed()
        compose.onNodeWithText("البحث", useUnmergedTree = true).performClick()
        compose.onNodeWithText("البحث عن الشرائح").assertIsDisplayed()
        compose.onNodeWithText("المجموعات", useUnmergedTree = true).performClick()
        compose.onNodeWithText("إنشاء مجموعة").performClick()
        compose.onNode(hasSetTextAction() and hasText("رقم المجموعة")).performTextInput("0055")
        compose.onNode(hasSetTextAction() and hasText("اسم المسؤول")).performTextInput("أحمد")
        compose.onNodeWithText("حفظ المجموعة").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("حفظ المجموعة").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("أحمد").fetchSemanticsNodes().isNotEmpty() }
        screenshot("groups")
        compose.onNodeWithText("0055").performClick()
        compose.onNodeWithText("تفاصيل المجموعة").assertIsDisplayed()
        compose.onNodeWithText("تعديل المسؤول").performClick()
        compose.onNode(hasSetTextAction() and hasText("اسم المسؤول")).performTextReplacement("سارة")
        compose.onNodeWithText("حفظ", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("تعديل اسم المسؤول").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("سارة").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("حذف المجموعة"))
        compose.onNodeWithText("حذف المجموعة").performClick()
        compose.onNodeWithText("إلغاء").performClick()
        compose.onNodeWithText("تفاصيل المجموعة").assertIsDisplayed()
        compose.onNodeWithText("حذف المجموعة").performClick()
        compose.onNodeWithText("حذف نهائي", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("ابدأ بمجموعتك الأولى").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun populatedGroupShowsFullSerialsAndPreventsDeletion() {
        val repo = InventoryRepository(db)
        runBlocking {
            val id = repo.createGroup("0055", "سارة")
            repo.addNewSims(listOf("00012345678901234567895555", "00022345678901234567895555"), id)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("0055").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0055").performClick()
        compose.onNodeWithText("الشرائح المرتبطة · 2").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("5555").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("00012345678901234567895555").assertExists()
        compose.onNodeWithText("00022345678901234567895555").assertExists()
        screenshot("group-details")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("حذف المجموعة"))
        compose.onNodeWithText("حذف المجموعة").assertIsNotEnabled()
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/screenshots").mkdirs()
        File("build/reports/screenshots/$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

}
