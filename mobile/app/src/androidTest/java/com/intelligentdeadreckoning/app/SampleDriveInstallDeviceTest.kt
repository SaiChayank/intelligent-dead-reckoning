package com.intelligentdeadreckoning.app

import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.recording.FileRecordingStorage
import com.intelligentdeadreckoning.app.sessions.SampleDriveInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The bundled demo drives must install from the PACKAGED apk. The Android build strips ".gz" from assets, so the installer
 * once asked for a file that did not exist: the demo drives never installed and a stray folder made the recorder report
 * "failed" on every launch. Disposable cache directory only.
 */
class SampleDriveInstallDeviceTest {
    @Test fun everyBundledDemoSessionInstallsFromThePackagedApkAndRecoveryAcceptsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sample-install-${UUID.randomUUID()}")
        try {
            assertTrue(SampleDriveInstaller.installIfMissing(context, root))
            for (id in SampleDriveInstaller.ALL_DEMO_SESSIONS) {
                assertTrue("$id metadata", File(root, "$id/metadata.json").isFile)
                assertTrue("$id measurements", File(root, "$id/measurements.jsonl").length() > 0)
                assertEquals("$id leaves no temporary files", emptyList<String>(),
                    File(root, id).list().orEmpty().filter { it.endsWith(".tmp") })
            }
            assertEquals(0, FileRecordingStorage(root).recover().failed)
        } finally {
            root.deleteRecursively()
        }
    }
}
