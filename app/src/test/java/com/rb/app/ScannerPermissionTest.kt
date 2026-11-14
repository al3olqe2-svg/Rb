package com.rb.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rb.app.data.*
import com.rb.app.scanner.ScannerScreen
import com.rb.app.ui.RbTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi", application = RbApplication::class)
class ScannerPermissionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RbDatabase
    @Before fun setup() {
        shadowOf(ApplicationProvider.getApplicationContext<RbApplication>()).denyPermissions(Manifest.permission.CAMERA)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RbDatabase::class.java).build()
        compose.setContent { RbTheme { ScannerScreen(InventoryRepository(db), emptyList(), done = { _, _ -> }) } }
    }
    @After fun close() { db.close() }
    @Test fun cameraPermissionIsRequestedOnlyOnStartAndReviewRemainsAvailable() {
        compose.onNodeWithText("بدء الكاميرا").assertIsDisplayed()
        compose.onNodeWithText("السماح بالكاميرا").assertDoesNotExist()
        compose.onNodeWithText("بدء الكاميرا").performClick()
        compose.onNodeWithText("السماح بالكاميرا").assertIsDisplayed()
        compose.onNodeWithText("إيقاف ومراجعة · 0").performClick()
        compose.onNodeWithText("مراجعة الجلسة · 0 شريحة").assertIsDisplayed()
        compose.onNodeWithText("حفظ الدفعة").assertIsNotEnabled()
    }
}
