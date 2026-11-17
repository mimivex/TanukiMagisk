package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.superuser.nio.FileSystemManager
import timber.log.Timber
import java.io.File

/**
 * Hosts a module's `webroot` directory in an in-app WebView.
 *
 * The page is served from the virtual `https://mui.kernelsu.org` origin by
 * [RemoteFsPathHandler]; the files are read through root by [RootUtils.fs].
 * The activity is not exported and every navigation away from the virtual
 * domain is handed to the system browser, so the `window.ksu` bridge is only
 * ever exposed to the module's own page.
 *
 * The page can ask for edge-to-edge by importing `internal/insets.css`; the
 * container then stops padding itself and the insets are pushed to the page
 * instead.
 */
@SuppressLint("SetJavaScriptEnabled")
class ModuleWebUIActivity : ComponentActivity() {

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var bridge: WebViewInterface
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var download: DownloadInterface
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveData: ByteArray? = null
    private var insets = Insets(0, 0, 0, 0)
    private var edgeToEdge = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: id
        val moduleDir = "${Const.MODULE_PATH}/$id"
        setTaskDescription(name)

        webView = WebView(this)
        webView.setBackgroundColor(0)
        progressBar = ProgressBar(this)
        container = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(progressBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(container)

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris: Array<Uri>? = when (result.resultCode) {
                RESULT_OK -> result.data?.let { data ->
                    when {
                        data.clipData != null -> Array(data.clipData!!.itemCount) { i ->
                            data.clipData!!.getItemAt(i).uri
                        }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                }
                else -> null
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

        saveFileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
            val data = pendingSaveData
            pendingSaveData = null
            if (uri != null && data != null) {
                // The page already holds the bytes; writing them out is a plain
                // blocking write, so it does not belong on the main thread.
                Thread {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    }.onFailure { Timber.e(it, "save failed") }
                }.start()
            }
        }

        // RootUtils.fs blocks until the root service is connected, which is not
        // allowed on the main thread; the webroot check runs on a worker.
        Thread {
            val fs = runCatching { RootUtils.fs }.getOrNull()
            val available = fs != null && fs.getFile("$moduleDir/webroot").isDirectory
            runOnUiThread {
                if (available) {
                    initWebView(fs!!, moduleDir)
                } else {
                    Toast.makeText(this, R.string.webui_unavailable, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    private fun setTaskDescription(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(name))
        } else {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(name).build())
        }
    }

    private fun initWebView(fs: FileSystemManager, moduleDir: String) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WEB_DOMAIN)
            .addPathHandler(
                "/",
                RemoteFsPathHandler(
                    File("$moduleDir/webroot"),
                    fs,
                    { insets },
                    { enable -> enableEdgeToEdge(enable) },
                    { WebUIColors.synthesizeCss(this) }
                )
            )
            .build()

        bridge = WebViewInterface(this, moduleDir, webView)
        webView.addJavascriptInterface(bridge, "ksu")

        // save() runs on the JavaBridge thread, but launching the document
        // picker has to happen on the main thread.
        download = DownloadInterface(this, webView) { data, fileName, mimeType ->
            pendingSaveData = data
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            runOnUiThread {
                try {
                    saveFileLauncher.launch(intent)
                } catch (e: Exception) {
                    pendingSaveData = null
                    Timber.e(e, "save picker failed")
                }
            }
        }
        webView.addJavascriptInterface(download, "ksu_download")
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            download.download(url, contentDisposition, mimetype)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            textZoom = (resources.configuration.fontScale * 100).toInt()
        }

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
            val inset = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            val density = resources.displayMetrics.density
            val newInsets = Insets(
                top = (inset.top / density).toInt(),
                bottom = (inset.bottom / density).toInt(),
                left = (inset.left / density).toInt(),
                right = (inset.right / density).toInt()
            )
            if (insets != newInsets) {
                insets = newInsets
                if (edgeToEdge) {
                    webView.evaluateJavascript(insets.js, null)
                }
            }
            if (edgeToEdge) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(inset.left, inset.top, inset.right, inset.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == WEB_DOMAIN) {
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                WebUIIcons.response(this@ModuleWebUIActivity, request.url)?.let { return it }
                return loader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
                // Idempotent: the script bails out once the flag is set.
                view.evaluateJavascript(DOWNLOAD_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                this@ModuleWebUIActivity.filePathCallback?.onReceiveValue(null)
                this@ModuleWebUIActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    if (intent.type?.startsWith(".") == true) {
                        intent.type = "*/*"
                    }
                }
                if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    this@ModuleWebUIActivity.filePathCallback?.onReceiveValue(null)
                    this@ModuleWebUIActivity.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@ModuleWebUIActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@ModuleWebUIActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@ModuleWebUIActivity).apply {
                    if (defaultValue != null) setText(defaultValue)
                }
                AlertDialog.Builder(this@ModuleWebUIActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.loadUrl("https://$WEB_DOMAIN/index.html")
    }

    /**
     * Drops the window padding and lets the page draw under the system bars.
     *
     * Called by the bridge (`ksu.enableEdgeToEdge`) and by the handler when the
     * page imports `internal/insets.css`.
     */
    fun enableEdgeToEdge(enable: Boolean = true) {
        runOnUiThread {
            if (enable) {
                (this as ComponentActivity).enableEdgeToEdge()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            edgeToEdge = enable
            if (this::container.isInitialized) {
                ViewCompat.requestApplyInsets(container)
            }
            if (enable && this::webView.isInitialized) {
                webView.evaluateJavascript(insets.js, null)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (this::webView.isInitialized) {
            webView.settings.textZoom = (newConfig.fontScale * 100).toInt()
        }
    }

    override fun onDestroy() {
        if (this::bridge.isInitialized) {
            bridge.destroy()
        }
        if (this::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val WEB_DOMAIN = "mui.kernelsu.org"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, id: String, name: String): Intent {
            return Intent(context, ModuleWebUIActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NAME, name)
        }
    }
}
