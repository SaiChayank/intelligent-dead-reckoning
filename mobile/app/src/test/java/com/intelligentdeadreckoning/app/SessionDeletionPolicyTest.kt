package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.recording.RecorderPhase
import com.intelligentdeadreckoning.app.recording.RecorderState
import com.intelligentdeadreckoning.app.replay.ReplayPhase
import com.intelligentdeadreckoning.app.replay.ReplayState
import com.intelligentdeadreckoning.app.sessions.ExportPhase
import com.intelligentdeadreckoning.app.sessions.ExportState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDeletionPolicyTest {
    @Test fun deletionIsBlockedForActiveOrBusyLibraryOperations() {
        assertFalse(canDeleteSession(
            "trip", RecorderState(RecorderPhase.RECORDING, recordingId = "trip"),
            ReplayState(), ExportState(), libraryBusy = false,
        ))
        assertFalse(canDeleteSession(
            "trip", RecorderState(RecorderPhase.FINALIZING, recordingId = "trip"),
            ReplayState(), ExportState(), libraryBusy = false,
        ))
        assertFalse(canDeleteSession(
            "trip", RecorderState(), ReplayState(ReplayPhase.PLAYING, id = "trip"),
            ExportState(), libraryBusy = false,
        ))
        assertFalse(canDeleteSession(
            "trip", RecorderState(), ReplayState(ReplayPhase.LOADING, id = "trip"),
            ExportState(), libraryBusy = false,
        ))
        assertFalse(canDeleteSession(
            "trip", RecorderState(), ReplayState(),
            ExportState(ExportPhase.WRITING, id = "trip"), libraryBusy = false,
        ))
        assertFalse(canDeleteSession(
            "trip", RecorderState(), ReplayState(),
            ExportState(ExportPhase.CHOOSING, id = "trip"), libraryBusy = false,
        ))
        assertFalse(canDeleteSession(
            "trip", RecorderState(), ReplayState(), ExportState(), libraryBusy = true,
        ))
    }

    @Test fun unrelatedBusySessionsDoNotBlockDeleteAndIdleSessionMayBeDeleted() {
        assertTrue(canDeleteSession(
            "trip-a", RecorderState(RecorderPhase.RECORDING, recordingId = "trip-b"),
            ReplayState(ReplayPhase.PLAYING, id = "trip-c"),
            ExportState(ExportPhase.WRITING, id = "trip-d"), libraryBusy = false,
        ))
        assertTrue(canDeleteSession("trip", RecorderState(), ReplayState(), ExportState(), libraryBusy = false))
    }
}
