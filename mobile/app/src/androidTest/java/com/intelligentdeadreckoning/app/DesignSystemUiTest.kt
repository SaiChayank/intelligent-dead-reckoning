package com.intelligentdeadreckoning.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.intelligentdeadreckoning.app.acquisition.CaptureState
import com.intelligentdeadreckoning.app.replay.ReplayState
import com.intelligentdeadreckoning.app.sessions.SavedSession
import com.intelligentdeadreckoning.app.sessions.SessionPage
import com.intelligentdeadreckoning.app.ui.IdrTheme
import com.intelligentdeadreckoning.app.ui.RealDiagnostics
import com.intelligentdeadreckoning.app.ui.ReplayPanel
import com.intelligentdeadreckoning.app.ui.SessionDialog
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrChip
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrOverlayChip
import com.intelligentdeadreckoning.app.ui.design.IdrStateTone
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.StatePanel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Design-system contract tests: every state the interface promises — loading, empty,
 * degraded, error, success, disabled, selected — must render, be findable and be
 * honest about what it is. Component-level on purpose: the states are reachable
 * without a device, a recording or a map install.
 */
class DesignSystemUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingStateShowsTitleAndPlaceholderRows() {
        compose.setContent {
            IdrTheme {
                StatePanel("Preparing offline map", "Verifying bundled tiles.", tone = IdrStateTone.LOADING,
                    testTag = "state")
            }
        }
        compose.onNodeWithTag("state").assertIsDisplayed()
        compose.onNodeWithText("Preparing offline map").assertExists()
        compose.onNodeWithText("Verifying bundled tiles.").assertExists()
    }

    @Test fun emptyStateExplainsWhatIsMissing() {
        compose.setContent {
            IdrTheme {
                StatePanel("No saved sessions", "Record with Phone sensors; sessions appear here.",
                    tone = IdrStateTone.EMPTY, testTag = "state")
            }
        }
        compose.onNodeWithTag("state").assertIsDisplayed()
        compose.onNodeWithText("No saved sessions").assertExists()
    }

    @Test fun degradedStateWarnsWithoutClaimingFailure() {
        compose.setContent {
            IdrTheme {
                StatePanel("GNSS fix is stale", "The last fix is older than the stale threshold.",
                    tone = IdrStateTone.DEGRADED, testTag = "state")
            }
        }
        compose.onNodeWithTag("state").assertIsDisplayed()
        compose.onNodeWithText("GNSS fix is stale").assertExists()
    }

    @Test fun errorStateNamesTheFailure() {
        compose.setContent {
            IdrTheme {
                StatePanel("Offline map unavailable", "No network fallback is attempted.",
                    tone = IdrStateTone.ERROR, testTag = "state")
            }
        }
        compose.onNodeWithTag("state").assertIsDisplayed()
        compose.onNodeWithText("Offline map unavailable").assertExists()
    }

    @Test fun successStateConfirmsCompletion() {
        compose.setContent {
            IdrTheme {
                StatePanel("Export copy ready", "The copy was written to app-private storage.",
                    tone = IdrStateTone.SUCCESS, testTag = "state")
            }
        }
        compose.onNodeWithTag("state").assertIsDisplayed()
        compose.onNodeWithText("Export copy ready").assertExists()
    }

    @Test fun disabledActionsAreAnnouncedAsDisabled() {
        var clicks = 0
        compose.setContent {
            IdrTheme {
                Column {
                    IdrButton("Start recording", { clicks++ }, enabled = false, testTag = "off",
                        variant = IdrButtonVariant.PRIMARY)
                    IdrButton("Start recording", { clicks++ }, enabled = true, testTag = "on",
                        variant = IdrButtonVariant.PRIMARY)
                }
            }
        }
        compose.onNodeWithTag("off").assertIsNotEnabled()
        compose.onNodeWithTag("on").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue("enabled button must fire", clicks == 1) }
    }

    @Test fun chipsExposeSelectionAndDisabledState() {
        compose.setContent {
            IdrTheme {
                Column {
                    IdrChip("Synthetic demo", selected = true, onClick = {}, testTag = "chip_on")
                    IdrChip("Recorded session", selected = false, onClick = {}, testTag = "chip_off")
                    IdrChip("Live GNSS", selected = false, onClick = {}, enabled = false, testTag = "chip_blocked")
                }
            }
        }
        compose.onNodeWithTag("chip_on").assertIsSelected()
        compose.onNodeWithTag("chip_off").assertIsNotSelected()
        compose.onNodeWithTag("chip_blocked").assertIsNotEnabled()
    }

    @Test fun mapOverlayChipKeepsItsLabelIntact() {
        compose.setContent {
            IdrTheme {
                IdrOverlayChip(
                    "MAP SOURCE: SYNTHETIC UI FIXTURE — independent of acquisition",
                    IdrTone.WARNING, IdrGlyph.WARNING, testTag = "overlay_chip",
                )
            }
        }
        compose.onNodeWithTag("overlay_chip").assertTextContains("SYNTHETIC UI FIXTURE", substring = true)
    }

    @Test fun savedSessionsDialogShowsEmptyStateAndRecordedProvenance() {
        compose.setContent {
            IdrTheme {
                SessionDialog(SessionPage(), null, false, "No exported copy.",
                    {}, { error("must not export") }, {})
            }
        }
        compose.onNodeWithTag("sessions_empty").assertExists()
        compose.onNodeWithText("RECORDED DATA").assertExists()
        compose.onNodeWithText("No exported copy.").assertExists()
    }

    @Test fun savedSessionsDialogBusyDisablesItsActions() {
        compose.setContent {
            IdrTheme {
                SessionDialog(SessionPage(listOf(SavedSession("busy-session", null, null, null))),
                    null, true, "", {}, { error("must not export while busy") }, {})
            }
        }
        compose.onNodeWithText("Export copy (.zip)").assertIsNotEnabled()
        compose.onNodeWithText("Replay session").assertIsNotEnabled()
        compose.onNodeWithText("Working… export and replay stay disabled until it finishes.").assertExists()
        compose.onNodeWithText("Next page").assertIsNotEnabled()
    }

    @Test fun replayPanelStatesRecordedProvenance() {
        compose.setContent { IdrTheme { ReplayPanel(ReplayState(), {}, {}, {}) } }
        compose.onNodeWithTag("replay_provenance").assertExists()
        compose.onNodeWithText("RECORDED").assertExists()
    }

    @Test fun rawGnssSectionIsNamedHonestlyAndNeverFabricatesNumbers() {
        compose.setContent { IdrTheme { RealDiagnostics(CaptureState(), {}, {}, {}, {}) } }
        compose.onNodeWithText("RAW GNSS FIXES (AS REPORTED)").assertExists()
        compose.onNodeWithTag("gnss_empty").assertExists()
        // Missing values must read as unavailable, never as zero.
        compose.onNodeWithText("Unavailable").assertExists()
    }
}
