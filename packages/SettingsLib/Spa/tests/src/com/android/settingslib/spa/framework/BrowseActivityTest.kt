/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.framework

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.LogEvent
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.android.settingslib.spa.tests.testutils.SpaLoggerForTest
import com.android.settingslib.spa.testutils.waitUntil
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

const val WAIT_UNTIL_TIMEOUT = 1000L

@RunWith(AndroidJUnit4::class)
class BrowseActivityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaLogger = SpaLoggerForTest()
    private val spaEnvironment = SpaEnvironmentForTest(context, logger = spaLogger)

    @Test
    fun testBrowsePage() {
        spaLogger.reset()
        SpaEnvironmentFactory.reset(spaEnvironment)

        val sppRepository by spaEnvironment.pageProviderRepository
        val sppHome = sppRepository.getProviderOrNull("SppHome")!!
        val pageHome = sppHome.createSettingsPage()
        val sppLayer1 = sppRepository.getProviderOrNull("SppLayer1")!!
        val pageLayer1 = sppLayer1.createSettingsPage()

        composeTestRule.setContent {
            BrowseContent(
                allProviders = listOf(sppHome, sppLayer1),
                initialDestination = pageHome.buildRoute(),
                initialEntryId = null
            )
        }

        composeTestRule.onNodeWithText(sppHome.getTitle(null)).assertIsDisplayed()
        spaLogger.verifyPageEvent(pageHome.id, 1, 0)
        spaLogger.verifyPageEvent(pageLayer1.id, 0, 0)

        // click to layer1 page
        composeTestRule.onNodeWithText("SppHome to Layer1").assertIsDisplayed().performClick()
        waitUntil(WAIT_UNTIL_TIMEOUT) {
            composeTestRule.onAllNodesWithText(sppLayer1.getTitle(null))
                .fetchSemanticsNodes().size == 1
        }
        spaLogger.verifyPageEvent(pageHome.id, 1, 1)
        spaLogger.verifyPageEvent(pageLayer1.id, 1, 0)
    }
}

private fun SpaLoggerForTest.verifyPageEvent(id: String, entryCount: Int, leaveCount: Int) {
    Truth.assertThat(getEventCount(id, LogEvent.PAGE_ENTER, LogCategory.FRAMEWORK))
        .isEqualTo(entryCount)
    Truth.assertThat(getEventCount(id, LogEvent.PAGE_LEAVE, LogCategory.FRAMEWORK))
        .isEqualTo(leaveCount)
}
