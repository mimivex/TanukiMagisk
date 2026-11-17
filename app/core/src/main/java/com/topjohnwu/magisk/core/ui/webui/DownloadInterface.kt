package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.topjohnwu.magisk.core.R
import timber.log.Timber

/**
 * Injected into every loaded page. Clicking a link that carries `download`
 * routes here: blob/data/same-origin payloads are read in the page and handed
 * to [DownloadInterface.save] as base64, anything else goes straight to
 * [DownloadInterface.download] and the DownloadManager.
 */
const val DOWNLOAD_JS = """
    (function() {
        if (window.ksu_download_enabled) return;
        window.ksu_download_enabled = true;
        const blobMap = new Map();
        const orgCreate = URL.createObjectURL;
        URL.createObjectURL = (obj) => {
            const url = orgCreate(obj);
            if (obj instanceof Blob) blobMap.set(url, obj);
            return url;
        };
        const orgRevoke = URL.revokeObjectURL;
        URL.revokeObjectURL = (url) => {
            setTimeout(() => blobMap.delete(url), 10000);
            orgRevoke(url);
        };
        const handleDownload = async (a) => {
            const urlParsed = new URL(a.href, location.href);
            const url = urlParsed.href;
            const fileName = a.download || url.split("/").pop().split("?")[0] || "download.bin";
            const isInternal = urlParsed.hostname === 'mui.kernelsu.org';
            if (url.startsWith('blob:') || url.startsWith('data:') || isInternal) {
                const blob = (url.startsWith('blob:') && blobMap.has(url)) ? blobMap.get(url) : await (await fetch(url)).blob();
                if (blob.size > 16 * 1024 * 1024) {
                    console.error("File too large, please use FileOutputStreamInterface instead.");
                    return;
                }
                const reader = new FileReader();
                reader.onload = () => {
                    ksu_download.save(reader.result.split(',')[1], fileName, blob.type);
                };
                reader.readAsDataURL(blob);
            } else {
                ksu_download.download(url, null, null);
            }
        };
        document.addEventListener("click", (e) => {
            const a = e.target.closest("a[download]");
            if (a) {
                e.preventDefault();
                handleDownload(a);
            }
        }, true);
        const orgClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function () {
            this.hasAttribute("download") ? handleDownload(this) : orgClick.apply(this, arguments);
        };
    })();
"""

/**
 * The `window.ksu_download` bridge.
 *
 * Downloads started from a link go through the system DownloadManager, with
 * the page's cookies and the WebView's user agent attached; payloads the page
 * already holds in memory are decoded and handed to the activity, which shows
 * the system "create document" picker.
 */
class DownloadInterface(
    private val activity: Activity,
    private val webView: WebView,
    private val onSaveFileRequest: (ByteArray, String, String) -> Unit
) {

    @JavascriptInterface
    fun download(url: String, contentDisposition: String?, mimetype: String?) {
        try {
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url))

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle(fileName)
            if (!mimetype.isNullOrEmpty()) {
                request.setMimeType(mimetype)
            }

            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("Cookie", cookies)
            }
            request.addRequestHeader("User-Agent", WebSettings.getDefaultUserAgent(activity))

            dm.enqueue(request)
            webView.post {
                Toast.makeText(activity, R.string.start_downloading, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "download failed")
            webView.post {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.download_failed, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun save(base64: String, fileName: String, mimetype: String) {
        try {
            val data = Base64.decode(base64, Base64.DEFAULT)
            onSaveFileRequest(data, fileName, mimetype)
        } catch (e: Exception) {
            Timber.e(e, "save failed")
        }
    }
}
