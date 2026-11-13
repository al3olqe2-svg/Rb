package com.rb.app

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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventoryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RbDatabase
    private lateinit var repo: InventoryRepository
    private lateinit var source: String
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        repo = InventoryRepository(db)
        runBlocking {
            source = repo.createGroup("0055", "عوض")
            repo.createGroup("0066", "أحمد")
            repo.addNewSims((2277..2282).map { "000000000000$it" }, source)
        }
        compose.setContent { RbApp(repo) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("0055").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun close() { db.close() }
    private fun waitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun cancelDeleteDoesNotWriteThenConfirmDeletesOnlyCheckedRows() {
        compose.onNodeWithText("0055").performClick()
        waitText("2277")
        compose.onNodeWithContentDescription("تحديد 0000000000002277").performClick()
        compose.onNodeWithContentDescription("تحديد 0000000000002278").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("2 محددة من 6").assertExists()
        compose.onNodeWithText("حذف", substring = false).performClick()
        compose.onNodeWithText("هل تريد حذف 2 شريحة نهائيًا؟ لا يمكن التراجع عن هذه العملية.").assertExists()
        compose.onNodeWithText("إلغاء", substring = false).performClick()
        assertEquals(6, runBlocking { repo.total.first() })
        compose.onNodeWithText("2 محددة من 6").assertExists()
        compose.onNodeWithText("حذف", substring = false).performClick()
        compose.onNodeWithText("حذف نهائي", substring = false).performClick()
        compose.waitUntil(10_000) { runBlocking { repo.total.first() } == 4 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2277").fetchSemanticsNodes().isEmpty() }
        assertEquals(setOf("0000000000002279", "0000000000002280", "0000000000002281", "0000000000002282"), runBlocking { repo.sims(source).first().map { it.serial }.toSet() })
    }
    @Test fun longPressRangeThenArchiveAndRestoreToChosenGroup() {
        compose.onNodeWithText("0055").performClick(); waitText("2278")
        compose.onNodeWithText("2278").performTouchInput { longClick() }
        compose.onNodeWithText("1 محددة من 6").assertExists()
        compose.onNodeWithText("تحديد نطاق").performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText("2281"))
        compose.onNode(hasText("2281") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("سيُحدد 4 شريحة موجودة").assertExists()
        compose.onNodeWithText("تحديد 4", substring = false).performClick()
        compose.onNodeWithText("4 محددة من 6").assertExists()
        compose.onNodeWithText("أرشفة", substring = false).performClick()
        compose.onNodeWithText("تأكيد", substring = false).performClick()
        compose.waitUntil(10_000) { runBlocking { repo.archive().first().size } == 4 }
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("الأرشيف", useUnmergedTree = true).performClick(); waitText("الأرشيف · 4")
        compose.onNodeWithText("تحديد الكل", substring = false).performClick()
        compose.onNodeWithText("نقل", substring = false).performClick()
        compose.onNodeWithText("0066").performClick()
        compose.onNodeWithText("نقل 4 شريحة إلى المجموعة التالية؟").assertExists()
        assertEquals(4, runBlocking { repo.archive().first().size })
        compose.onNodeWithText("تأكيد", substring = false).performClick()
        compose.waitUntil(10_000) { runBlocking { repo.archive().first().isEmpty() } }
        assertEquals(6, runBlocking { repo.total.first() })
        assertEquals(4, runBlocking { repo.groups.first().single { it.group.code == "0066" }.simCount })
    }
    @Test fun liveSearchReturnsDuplicateLast4AndDetailsShowCurrentLocation() {
        runBlocking { repo.addNewSims(listOf("99992277"), null) }
        compose.onNodeWithText("البحث", useUnmergedTree = true).performClick()
        compose.onNodeWithText("الرقم الكامل أو جزء منه").performTextInput("2277")
        waitText("النتائج · 2")
        compose.onAllNodesWithText("2277").assertCountEquals(3) // field plus two actual rows
        compose.onNodeWithContentDescription("تفاصيل 99992277").performClick()
        compose.onNode(hasText("في الأرشيف العام") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("نسخ الرقم الكامل").performClick()
        compose.onNodeWithText("تم نسخ الرقم").assertExists()
        compose.onNodeWithText("إغلاق").performClick()
        compose.onNodeWithText("تحديد الكل", substring = false).performClick()
        compose.onNodeWithText("2 محددة من 2").assertExists()
        compose.onNodeWithContentDescription("مسح البحث").performClick()
        waitText("النتائج · 7")
        compose.onNodeWithText("2 محددة من 2").assertDoesNotExist()
        compose.onNodeWithText("الرقم الكامل أو جزء منه").performTextInput("123456")
        waitText("لا توجد نتائج")
    }
}
