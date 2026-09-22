package com.intelligentdeadreckoning.app.sessions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class CreateSessionDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_TITLE, "idr-$input.zip")
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Reject remote/unknown providers even if they ignore EXTRA_LOCAL_ONLY. */
fun isLocalExportUri(uri: Uri): Boolean = uri.scheme == "content" && uri.authority in setOf(
    "com.android.externalstorage.documents", "com.android.providers.downloads.documents",
)
