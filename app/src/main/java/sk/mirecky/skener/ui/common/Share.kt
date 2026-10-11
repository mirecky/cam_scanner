package sk.mirecky.skener.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import sk.mirecky.skener.R
import sk.mirecky.skener.data.DocumentItem

/** Zdieľanie PDF dokumentu cez systémový dialóg. */
fun sharePdf(context: Context, doc: DocumentItem) {
    val uri = doc.pdfUri
    if (uri == null) {
        Toast.makeText(context, R.string.pdf_not_ready, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .putExtra(Intent.EXTRA_TITLE, doc.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, doc.name))
}
