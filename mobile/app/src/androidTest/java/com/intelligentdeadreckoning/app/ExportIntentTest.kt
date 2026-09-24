package com.intelligentdeadreckoning.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.sessions.*
import org.junit.Assert.*
import org.junit.Test

class ExportIntentTest {
    @Test fun explicitLocalDocumentIntentAndCancellation() {
        val contract = CreateSessionDocument()
        val intent = contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext,"sample")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action)
        assertEquals("application/zip",intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false))
        assertEquals("idr-sample.zip",intent.getStringExtra(Intent.EXTRA_TITLE))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED,Intent()))
        assertTrue(isLocalExportUri(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsample.zip")))
        assertFalse(isLocalExportUri(Uri.parse("content://cloud.example/document/1")))
        assertFalse(isLocalExportUri(Uri.parse("file:///sdcard/sample.zip")))
    }
}
