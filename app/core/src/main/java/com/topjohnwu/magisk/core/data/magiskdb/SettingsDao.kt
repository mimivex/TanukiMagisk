package com.topjohnwu.magisk.core.data.magiskdb

class SettingsDao : MagiskDB() {

    suspend fun delete(key: String) {
        val query = "DELETE FROM ${Table.SETTINGS} WHERE key=\"$key\""
        exec(query)
    }

    suspend fun put(key: String, value: Int) {
        val kv = mapOf("key" to key, "value" to value)
        val query = "REPLACE INTO ${Table.SETTINGS} ${kv.toQuery()}"
        exec(query)
    }

    // Null means the command failed; the default only covers a
    // legitimately missing key.
    suspend fun fetch(key: String, default: Int = -1): Int? {
        val query = "SELECT value FROM ${Table.SETTINGS} WHERE key=\"$key\" LIMIT 1"
        val result = execResult(query) { it["value"]?.toInt() }
        return if (result.ok) result.rows.firstOrNull() ?: default else null
    }
}
