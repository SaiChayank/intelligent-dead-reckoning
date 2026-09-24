package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.intelligentdeadreckoning.app.sessions.*
import com.intelligentdeadreckoning.app.ui.*
import org.junit.Rule
import org.junit.Test

class SessionDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun missingSessionCannotExport() {
        compose.setContent { IdrTheme { SessionDialog(SessionPage(listOf(SavedSession("missing",null,null,"Session missing"))),
            null,false,"No exported copy.",{}, { error("must not export") },{}) } }
        compose.onNodeWithText("Session missing").assertExists()
        compose.onNodeWithText("Export copy (.zip)").assertIsNotEnabled()
        compose.onNodeWithText("No exported copy.").assertExists()
    }
}
