package com.rb.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.ui.RbApp
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
class Stage5UiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
    }
    @After fun close() { db.close() }
    private fun series() {
        compose.onNodeWithText("إضافة سلسلة").performClick()
        compose.onNode(hasSetTextAction() and hasText("الرقم الأول")).performTextInput("8996603421269968985")
        compose.onNode(hasSetTextAction() and hasText("آخر أربعة أرقام للنهاية")).performTextInput("8999")
        compose.onNodeWithText("عدد الشرائح: 15").assertExists()
        screenshot("02-series-preview")
        compose.onNodeWithText("إضافة السلسلة للمراجعة").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("حفظ الدفعة").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.waitForIdle()
        screenshot("03-series-review")
    }
    @Test fun archiveSaveNavigatesAutomaticallyAndBackDoesNotReturnToSavedReview() {
        compose.setContent { RbApp(repo) }
        compose.onNodeWithText("المسح").performClick()
        series()
        compose.onNodeWithText("حفظ الدفعة").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("الأرشيف العام").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("تم حفظ الدفعة").assertDoesNotExist()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("الأرشيف · 15").fetchSemanticsNodes().isNotEmpty() }
        screenshot("04-saved-archive")
        compose.onNodeWithText("المسح").performClick()
        compose.onNodeWithText("امسح الشرائح تباعًا").assertExists()
        Assert.assertEquals(15, runBlocking { repo.exportSnapshot().sims.size })
    }
    @Test fun failedTransactionKeepsReviewAndDoesNotNavigate() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scan BEFORE INSERT ON sims BEGIN SELECT RAISE(ABORT, 'test'); END")
        compose.setContent { RbApp(repo) }
        compose.onNodeWithText("المسح").performClick()
        series()
        compose.onNodeWithText("حفظ الدفعة").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("تعذر حفظ الدفعة.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("مراجعة الجلسة · 15 شريحة").assertExists()
        Assert.assertEquals(0, runBlocking { repo.exportSnapshot().sims.size })
    }
    @Test fun createFormIsCompactAndGroupSuffixIsVisibleAlongsideFullIdentifier() {
        runBlocking { repo.createGroup("0000001234", "أحمد") }
        compose.setContent { RbApp(repo) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("1234").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0000001234").assertExists()
        screenshot("01-groups")
        compose.onNodeWithText("إنشاء مجموعة").performClick()
        compose.onNodeWithContentDescription("مسح رقم المجموعة").assertExists()
        compose.onNodeWithText("تُحفظ الأصفار في بداية الرقم").assertDoesNotExist()
        compose.onNodeWithText("الشخص المسؤول عن هذه المجموعة").assertDoesNotExist()
        screenshot("05-create-group")
    }
    @Test fun frozenChoicesStaySelectableWhileNewFramesArrive() {
        val owner = object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
        }
        val scanner = com.rb.app.scanner.ScannerViewModel(repo, androidx.lifecycle.SavedStateHandle(), null)
        owner.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:com.rb.app.scanner.ScannerViewModel", scanner)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner) {
                com.rb.app.ui.RbTheme { com.rb.app.scanner.ScannerScreen(repo, emptyList(), done = { _, _ -> }) }
            }
        }
        val serial = "8996603421269968985"
        compose.runOnIdle {
            scanner.camera()
            repeat(3) { scanner.reading(com.rb.app.scanner.SerialRecognition.read(emptyList(), listOf(serial))) }
        }
        compose.onNodeWithText(serial).assertExists()
        screenshot("06-frozen-candidate-simulated-frames")
        compose.runOnIdle {
            repeat(10) { scanner.reading(com.rb.app.scanner.SerialRecognition.read(emptyList(), listOf("8996603421269968999"))) }
        }
        compose.onNodeWithText(serial).assertExists()
        compose.onNodeWithText("تأكيد وإضافة").performClick()
        compose.onNodeWithText("تأكيد وإضافة").assertDoesNotExist()
        compose.runOnIdle {
            Assert.assertEquals(listOf(serial), scanner.state.value.serials)
            owner.viewModelStore.clear()
        }
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
        val view = android.view.inspector.WindowInspector.getGlobalWindowViews()
            .lastOrNull { it.isAttachedToWindow && it.visibility == android.view.View.VISIBLE }
            ?: activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/screenshots/stage5").mkdirs()
        java.io.File("build/reports/screenshots/stage5/$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

}
