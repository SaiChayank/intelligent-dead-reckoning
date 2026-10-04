package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import com.intelligentdeadreckoning.app.sessions.*
import com.intelligentdeadreckoning.app.ui.*
import org.junit.Rule
import org.junit.Test

class SessionDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun deletionRequiresExplicitConfirmationAndInvokesOnlySelectedSession() {
        var deleted: String? = null
        compose.setContent {
            IdrTheme {
                SessionDialog(SessionPage(listOf(SavedSession("trip-1", null, null, null))),
                    null, false, "No exported copy.", {}, {}, {}, delete = { deleted = it })
            }
        }
        compose.onNodeWithText("Delete session").performClick()
        compose.onNodeWithText("Delete session permanently?").assertExists()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithText("Keep session").performClick()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithText("Delete session").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.runOnIdle { assertEquals("trip-1", deleted) }
    }

    @Test fun missingSessionCannotExport() {
        compose.setContent { IdrTheme { SessionDialog(SessionPage(listOf(SavedSession("missing",null,null,"SESSION_NOT_FOUND"))),
            null,false,"No exported copy.",{}, { error("must not export") },{}) } }
        compose.onNodeWithText("SESSION_NOT_FOUND").assertExists()
        compose.onNodeWithText("Export copy (.zip)").assertIsNotEnabled()
        compose.onNodeWithText("Delete session").assertIsNotEnabled()
        compose.onNodeWithText("No exported copy.").assertExists()
    }

    @Test fun busyOperationDisablesSessionDeletion() {
        var deleted: String? = null
        compose.setContent {
            IdrTheme {
                SessionDialog(SessionPage(listOf(SavedSession("trip-1", null, null, null))),
                    null, true, "No exported copy.", {}, {}, {}, delete = { deleted = it })
            }
        }
        compose.onNodeWithText("Delete session").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(null, deleted) }
    }
}
