package com.topjohnwu.magisk.core.ui.webui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUIPackagesTest {

    private val apps = listOf(
        WebUIPackageInfo("com.example.user", "1.0", 2147483648L, "User App", false, 10086),
        WebUIPackageInfo("android", "14", 34L, "Android System", true, 1000),
        WebUIPackageInfo("com.example.system", null, 7L, "System App", true, 1000),
        WebUIPackageInfo("com.example.noversion", null, 1L, "No Version", false, 10100)
    )

    private val allNames = listOf(
        "android", "com.example.noversion", "com.example.system", "com.example.user"
    )

    @Test
    fun filterNames_unknownTypeReturnsEveryPackageSorted() {
        assertEquals(allNames, WebUIPackages.filterNames(apps, null))
        assertEquals(allNames, WebUIPackages.filterNames(apps, ""))
        assertEquals(allNames, WebUIPackages.filterNames(apps, "all"))
        assertEquals(allNames, WebUIPackages.filterNames(apps, "xyz"))
    }

    @Test
    fun filterNames_splitsSystemAndUser() {
        assertEquals(
            listOf("android", "com.example.system"),
            WebUIPackages.filterNames(apps, "system")
        )
        assertEquals(
            listOf("com.example.noversion", "com.example.user"),
            WebUIPackages.filterNames(apps, "user")
        )
        assertEquals(
            listOf("android", "com.example.system"),
            WebUIPackages.filterNames(apps, "SYSTEM")
        )
    }

    @Test
    fun buildPackagesInfoJson_emitsTheReferenceFields() {
        val out = WebUIPackages.buildPackagesInfoJson(listOf("com.example.user"), apps)
        assertEquals(1, out.length())
        val obj = out.getJSONObject(0)
        assertEquals("com.example.user", obj.getString("packageName"))
        assertEquals("1.0", obj.getString("versionName"))
        assertEquals(2147483648L, obj.getLong("versionCode"))
        assertEquals("User App", obj.getString("appLabel"))
        assertFalse(obj.getBoolean("isSystem"))
        assertEquals(10086, obj.getInt("uid"))
        assertEquals(6, obj.length())
    }

    @Test
    fun buildPackagesInfoJson_marksSystemApps() {
        val out = WebUIPackages.buildPackagesInfoJson(listOf("android"), apps)
        assertTrue(out.getJSONObject(0).getBoolean("isSystem"))
    }

    @Test
    fun buildPackagesInfoJson_nullVersionNameBecomesEmptyString() {
        val out = WebUIPackages.buildPackagesInfoJson(listOf("com.example.system"), apps)
        assertEquals("", out.getJSONObject(0).getString("versionName"))
    }

    @Test
    fun buildPackagesInfoJson_unknownPackageIsAnErrorObject() {
        val out = WebUIPackages.buildPackagesInfoJson(listOf("com.example.missing"), apps)
        val obj = out.getJSONObject(0)
        assertEquals("com.example.missing", obj.getString("packageName"))
        assertEquals("Package not found or inaccessible", obj.getString("error"))
        assertEquals(2, obj.length())
    }

    @Test
    fun buildPackagesInfoJson_keepsTheRequestedOrder() {
        val requested = listOf("com.example.user", "com.example.missing", "android")
        val out = WebUIPackages.buildPackagesInfoJson(requested, apps)
        assertEquals(3, out.length())
        for (i in requested.indices) {
            assertEquals(requested[i], out.getJSONObject(i).getString("packageName"))
        }
    }

    @Test
    fun buildPackagesInfoJson_emptyRequestIsEmptyArray() {
        assertEquals(0, WebUIPackages.buildPackagesInfoJson(emptyList(), apps).length())
        assertEquals(0, JSONArray("[]").length())
    }

    @Test
    fun buildPackagesInfoJson_serializesLongVersionCodeWithoutNotation() {
        val out = WebUIPackages.buildPackagesInfoJson(listOf("com.example.user"), apps)
        val body = JSONObject(out.getJSONObject(0).toString())
        assertEquals(2147483648L, body.getLong("versionCode"))
        assertTrue(body.toString().contains("\"versionCode\":2147483648"))
    }
}
