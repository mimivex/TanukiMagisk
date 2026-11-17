package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Serves files from [directory] as read through root by [fs].
 *
 * Two paths never touch the module's files: `internal/insets.css` answers
 * with the current window insets and tells the activity the page wants
 * edge-to-edge, and `internal/colors.css` answers with the synthesized
 * dynamic palette.
 *
 * Requests that resolve outside of the directory (including path traversal
 * attempts) and files that cannot be opened answer with an empty 404 response
 * instead of null, so the WebView never falls back to the network for them.
 */
class RemoteFsPathHandler(
    directory: File,
    private val fs: FileSystemManager,
    private val insets: () -> Insets,
    private val onInsetsRequested: (Boolean) -> Unit,
    private val colorsCss: () -> String
) : WebViewAssetLoader.PathHandler {

    private val directory: File = File(getCanonicalDirPath(directory))

    @WorkerThread
    override fun handle(path: String): WebResourceResponse {
        if (path == INSETS_CSS) {
            onInsetsRequested(true)
            return cssResponse(insets().css)
        }
        if (path == COLORS_CSS) {
            return cssResponse(colorsCss())
        }
        try {
            val file = getCanonicalFileIfChild(directory, path)
            if (file != null) {
                return WebResourceResponse(guessMimeType(path), null, openFile(file))
            }
        } catch (_: IOException) {
        }
        return WebResourceResponse(null, null, null)
    }

    private fun cssResponse(css: String): WebResourceResponse =
        WebResourceResponse(CSS_MIME_TYPE, CSS_ENCODING, ByteArrayInputStream(css.toByteArray()))

    private fun openFile(file: File): InputStream {
        val stream = fs.getFile(file.absolutePath).newInputStream()
        return if (file.path.endsWith(".svgz")) GZIPInputStream(stream) else stream
    }

    companion object {
        private const val DEFAULT_MIME_TYPE = "text/plain"
        private const val INSETS_CSS = "internal/insets.css"
        private const val COLORS_CSS = "internal/colors.css"
        private const val CSS_MIME_TYPE = "text/css"
        private const val CSS_ENCODING = "utf-8"

        private fun getCanonicalDirPath(file: File): String {
            val canonicalPath = file.canonicalPath
            return if (canonicalPath.endsWith("/")) canonicalPath else "$canonicalPath/"
        }

        private fun getCanonicalFileIfChild(parent: File, child: String): File? {
            val parentCanonicalPath = getCanonicalDirPath(parent)
            val childCanonicalPath = File(parent, child).canonicalPath
            return if (childCanonicalPath.startsWith(parentCanonicalPath)) {
                File(childCanonicalPath)
            } else {
                null
            }
        }

        private fun guessMimeType(filePath: String): String {
            return MimeUtil.getMimeFromFileName(filePath) ?: DEFAULT_MIME_TYPE
        }
    }
}
