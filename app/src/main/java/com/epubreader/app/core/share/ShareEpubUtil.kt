package com.epubreader.app.core.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.epubreader.app.R
import java.io.File

object ShareEpubUtil {

    fun shareEpub(context: Context, filePath: String?) {
        if (filePath == null) {
            Toast.makeText(
                context,
                context.getString(R.string.reader_error_open),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(
                context,
                context.getString(R.string.reader_error_open),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(
            sendIntent,
            context.getString(R.string.reader_share_epub)
        )
        context.startActivity(chooser)
    }
}