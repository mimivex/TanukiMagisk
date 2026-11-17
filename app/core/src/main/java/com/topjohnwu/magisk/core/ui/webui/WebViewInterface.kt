package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.app.Activity
import android.text.TextUtils
import android.util.Base64
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The `window.ksu` bridge exposed to the module WebUI.
 *
 * Every shell-backed call runs on its own fresh root shell, created with
 * [Shell.FLAG_MOUNT_MASTER], no initializers and the default timeout, and
 * closed as soon as the call returns — the shell setup KsuWebUIStandalone
 * uses.
 */
class WebViewInterface(
    private val activity: Activity,
    private val moduleDir: String,
    private val webView: WebView
) {

    private fun createRootShell(): Shell =
        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build()

    @JavascriptInterface
    fun exec(cmd: String): String {
        return try {
            createRootShell().use { shell -> ShellUtils.fastCmd(shell, cmd) }
        } catch (e: Exception) {
            Timber.e(e, "exec failed")
            ""
        }
    }

    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String) {
        exec(cmd, null, callbackFunc)
    }

    private fun processOptions(sb: StringBuilder, options: String?) {
        val opts = if (options == null) JSONObject() else JSONObject(options)

        val cwd = opts.optString("cwd")
        if (!TextUtils.isEmpty(cwd)) {
            sb.append("cd $cwd;")
        }

        opts.optJSONObject("env")?.let { env ->
            env.keys().forEach { key ->
                sb.append("export $key=${env.getString(key)};")
            }
        }
    }

    @JavascriptInterface
    fun exec(cmd: String, options: String?, callbackFunc: String) {
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)
        finalCommand.append(cmd)

        var code = -1
        var stdout = ""
        var stderr = ""
        try {
            val result = createRootShell().use { shell ->
                shell.newJob().add(finalCommand.toString()).to(ArrayList(), ArrayList()).exec()
            }
            code = result.code
            stdout = result.out.joinToString("\n")
            stderr = result.err.joinToString("\n")
        } catch (e: Exception) {
            Timber.e(e, "exec failed")
            stderr = e.message ?: e.toString()
        }
        evalJs(
            "(function() { try { ${callbackFunc}(${code}, " +
                "${JSONObject.quote(stdout)}, ${JSONObject.quote(stderr)}); } " +
                "catch(e) { console.error(e); } })();"
        )
    }

    @JavascriptInterface
    fun spawn(command: String, args: String, options: String?, callbackFunc: String) {
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)

        if (!TextUtils.isEmpty(args)) {
            finalCommand.append(command).append(" ")
            val argsArray = JSONArray(args)
            for (i in 0 until argsArray.length()) {
                finalCommand.append(argsArray.getString(i))
                finalCommand.append(" ")
            }
        } else {
            finalCommand.append(command)
        }

        fun emitData(name: String, data: String) {
            evalJs(
                "(function() { try { ${callbackFunc}.${name}.emit('data', " +
                    "${JSONObject.quote(data)}); } catch(e) { console.error('emitData', e); } })();"
            )
        }

        val stdout = object : CallbackList<String>() {
            override fun onAddElement(s: String) {
                emitData("stdout", s)
            }
        }
        val stderr = object : CallbackList<String>() {
            override fun onAddElement(s: String) {
                emitData("stderr", s)
            }
        }

        fun emitError(message: String) {
            evalJs(
                "(function() { try { var err = new Error(); " +
                    "err.exitCode = -1; " +
                    "err.message = ${JSONObject.quote(message)}; " +
                    "${callbackFunc}.emit('error', err); } " +
                    "catch(e) { console.error('emitErr', e); } })();"
            )
        }

        try {
            val shell = createRootShell()
            shell.newJob().add(finalCommand.toString()).to(stdout, stderr).submit { result ->
                runCatching { shell.close() }
                evalJs(
                    "(function() { try { ${callbackFunc}.emit('exit', ${result.code}); } " +
                        "catch(e) { console.error('emitExit error: ' + e); } })();"
                )
                if (result.code != 0) {
                    val errText = result.err.joinToString("\n")
                    evalJs(
                        "(function() { try { var err = new Error(); " +
                            "err.exitCode = ${result.code}; " +
                            "err.message = ${JSONObject.quote(errText)}; " +
                            "${callbackFunc}.emit('error', err); } " +
                            "catch(e) { console.error('emitErr', e); } })();"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "spawn failed")
            emitError(e.message ?: e.toString())
        }
    }

    @JavascriptInterface
    fun listPackages(type: String?): String = runCatching {
        val names = WebUIPackages.filterNames(WebUIPackages.getApps(activity), type)
        val array = JSONArray()
        for (name in names) {
            array.put(name)
        }
        array.toString()
    }.getOrElse {
        Timber.e(it, "listPackages failed")
        "[]"
    }

    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String?): String = runCatching {
        val array = JSONArray(packageNamesJson ?: "[]")
        val requested = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            requested.add(array.getString(i))
        }
        WebUIPackages.buildPackagesInfoJson(requested, WebUIPackages.getApps(activity)).toString()
    }.getOrElse {
        Timber.e(it, "getPackagesInfo failed")
        "[]"
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        webView.post {
            if (enable) {
                hideSystemUI(activity.window)
            } else {
                showSystemUI(activity.window)
            }
        }
        enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    fun enableEdgeToEdge(enable: Boolean = true) {
        (activity as? ModuleWebUIActivity)?.enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    fun enableInsets(enable: Boolean = true) = enableEdgeToEdge(enable)

    @JavascriptInterface
    fun moduleInfo(): String {
        val info = JSONObject()
        info.put("moduleDir", moduleDir)
        info.put("id", File(moduleDir).name)
        return info.toString()
    }

    @JavascriptInterface
    fun toast(msg: String) {
        webView.post {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun exit() {
        webView.post { activity.finish() }
    }

    val fileOutputStream = FileOutputStreamInterface()

    @JavascriptInterface
    fun fileOutputStream(): FileOutputStreamInterface {
        return fileOutputStream
    }

    fun destroy() {
        fileOutputStream.closeAll()
        WebUIPackages.clearCache()
    }

    private fun evalJs(code: String) {
        webView.post {
            webView.evaluateJavascript(code, null)
        }
    }
}

class FileOutputStreamInterface {

    private val openStreams = ConcurrentHashMap<String, BufferedOutputStream>()

    @JavascriptInterface
    fun open(path: String, append: Boolean): String {
        return try {
            val fos = SuFileOutputStream.open(SuFile(path), append)
            val bos = BufferedOutputStream(fos, 64 * 1024)
            val id = UUID.randomUUID().toString()
            openStreams[id] = bos
            id
        } catch (e: Exception) {
            Timber.e(e, "open failed")
            ""
        }
    }

    @JavascriptInterface
    fun open(path: String): String {
        return open(path, false)
    }

    @JavascriptInterface
    fun writeByte(id: String, b: Int): Boolean {
        return runCatching {
            val bos = openStreams[id] ?: return false
            synchronized(bos) { bos.write(b) }
            true
        }.getOrElse {
            Timber.e(it, "writeByte failed")
            false
        }
    }

    @JavascriptInterface
    fun write(id: String, base64: String): Boolean {
        return runCatching {
            val bos = openStreams[id] ?: return false
            val data = Base64.decode(base64, Base64.NO_WRAP)
            synchronized(bos) { bos.write(data) }
            true
        }.getOrElse {
            Timber.e(it, "write failed")
            false
        }
    }

    @JavascriptInterface
    fun flush(id: String): Boolean {
        return runCatching {
            val bos = openStreams[id] ?: return false
            synchronized(bos) { bos.flush() }
            true
        }.getOrElse {
            Timber.e(it, "flush failed")
            false
        }
    }

    @JavascriptInterface
    fun close(id: String): Boolean {
        val bos = openStreams.remove(id) ?: return false
        return runCatching {
            synchronized(bos) { bos.close() }
            true
        }.getOrElse {
            Timber.e(it, "close failed")
            false
        }
    }

    fun closeAll() {
        openStreams.forEach { (id, bos) ->
            runCatching {
                synchronized(bos) { bos.close() }
            }.onFailure {
                Timber.e(it, "closeAll failed for $id")
            }
        }
        openStreams.clear()
    }
}

private fun hideSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

private fun showSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
