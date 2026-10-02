package com.intelligentdeadreckoning.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import com.intelligentdeadreckoning.app.sessions.CreateSessionDocument
import com.intelligentdeadreckoning.app.sessions.isLocalExportUri
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.xmlpull.v1.XmlPullParser

/** Checks the effective, packaged application manifest, not just source declarations. */
@RunWith(AndroidJUnit4::class)
class AndroidSecurityBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun installedAppRequestsOnlyForegroundLocationAndNoNetworkOrStoragePermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertEquals(
            setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            permissions,
        )
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions)
        assertFalse("android.permission.FOREGROUND_SERVICE" in permissions)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
    }

    @Test
    fun exportPickerIsUserInitiatedLocalOnlyAndCloudAuthoritiesAreRejected() {
        val intent = CreateSessionDocument().createIntent(context, "session_123")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertEquals("application/zip", intent.type)
        assertTrue(isLocalExportUri(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsession.zip")))
        assertTrue(isLocalExportUri(Uri.parse("content://com.android.providers.downloads.documents/document/123")))
        assertFalse(isLocalExportUri(Uri.parse("content://com.google.android.apps.docs.storage/document/123")))
        assertFalse(isLocalExportUri(Uri.parse("content://unknown.provider/document/123")))
        assertFalse(isLocalExportUri(Uri.parse("file:///sdcard/session.zip")))
    }

    @Test
    fun backupAndDeviceTransferAreDisabledForTheInstalledApp() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        try {
            var inCloudBackup = false
            var inDeviceTransfer = false
            val cloudExcludes = mutableSetOf<Pair<String, String>>()
            val deviceExcludes = mutableSetOf<Pair<String, String>>()
            var includes = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup" -> inCloudBackup = true
                        "device-transfer" -> inDeviceTransfer = true
                        "exclude" -> {
                            val entry = parser.getAttributeValue(null, "domain") to
                                parser.getAttributeValue(null, "path")
                            if (inCloudBackup) cloudExcludes += entry
                            if (inDeviceTransfer) deviceExcludes += entry
                        }
                        "include" -> includes++
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "cloud-backup" -> inCloudBackup = false
                        "device-transfer" -> inDeviceTransfer = false
                    }
                }
                event = parser.next()
            }
            val expected = setOf("root", "file", "database", "sharedpref", "external",
                "device_root", "device_file", "device_database", "device_sharedpref")
                .map { it to "." }.toSet()
            assertEquals("cloud-backup must exclude all app-private domains", expected, cloudExcludes)
            assertEquals("device-transfer must exclude all app-private domains", expected, deviceExcludes)
            assertEquals("backup rules must not opt anything back in", 0, includes)
        } finally {
            parser.close()
        }
    }
}
