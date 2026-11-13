package com.rb.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RbApplication::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityRestartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<RbApplication>()

    @After fun close() { application.database.close() }

    @Test fun launchesActualActivityAndRetainsSavedGroupAfterRecreation() {
        runBlocking { application.repository.createGroup("0055", "سارة") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("0055").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("0055").assertIsDisplayed()
        compose.onNodeWithText("سارة").assertIsDisplayed()
    }
}
