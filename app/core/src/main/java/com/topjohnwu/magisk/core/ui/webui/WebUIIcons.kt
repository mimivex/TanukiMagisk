package com.topjohnwu.magisk.core.ui.webui

/*
 * Ported from KsuWebUIStandalone (originally by 5ec1cff, maintained by
 * KOWX712), GPL-3.0. See THIRD_PARTY_LICENSES.md.
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceResponse
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Answers the WebUI's `ksu://icon/<package>` requests with the app's icon.
 *
 * The icon is looked up through [Context.getPackageManager] instead of an
 * app list, cached as a scaled bitmap and encoded on every request, so an
 * unknown package falls through to the page's own placeholder.
 */
object WebUIIcons {

    private const val SCHEME = "ksu"
    private const val HOST = "icon"
    private const val ICON_SIZE = 512
    private const val CACHE_SIZE = 200
    private const val PNG_QUALITY = 100

    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

    fun response(context: Context, url: Uri): WebResourceResponse? {
        if (!url.scheme.equals(SCHEME, ignoreCase = true)) {
            return null
        }
        if (!url.host.equals(HOST, ignoreCase = true)) {
            return null
        }
        val packageName = url.path?.substring(1)
        if (packageName.isNullOrEmpty()) {
            return null
        }
        val icon = loadIcon(context, packageName) ?: return null
        val stream = ByteArrayOutputStream()
        icon.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
        return WebResourceResponse("image/png", null, ByteArrayInputStream(stream.toByteArray()))
    }

    @Synchronized
    private fun loadIcon(context: Context, packageName: String): Bitmap? {
        cache.get(packageName)?.let { return it }
        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationInfo(packageName, 0).loadIcon(pm)
            val icon = drawableToBitmap(drawable).scale(ICON_SIZE, ICON_SIZE)
            cache.put(packageName, icon)
            icon
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else ICON_SIZE
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else ICON_SIZE
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
