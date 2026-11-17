package com.topjohnwu.magisk.core.data.magiskdb

import android.os.Process.FIRST_APPLICATION_UID
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.model.su.SuPolicy
import java.util.concurrent.TimeUnit

private const val SELECT_QUERY = "SELECT *"

// Last-resort parse of an unevaluated toMap() expression
private val UNTIL_EXPRESSION_SECONDS = Regex("""\+\s*(\d+)\s*\)""")

class PolicyDao : MagiskDB() {

    suspend fun deleteOutdated() {
        val query = "DELETE FROM ${Table.POLICY} WHERE " +
            "(until > 0 AND until < strftime(\"%s\", \"now\")) OR until < 0"
        exec(query)
    }

    suspend fun delete(uid: Int) {
        val query = "DELETE FROM ${Table.POLICY} WHERE uid=${normalizeUid(uid)}"
        exec(query)
    }

    suspend fun fetch(uid: Int): SuPolicy? {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} WHERE uid=${normalizeUid(uid)} LIMIT 1"
        return exec(query, ::toPolicy).firstOrNull()
    }

    suspend fun update(policy: SuPolicy) {
        policy.uid = normalizeUid(policy.uid)
        val map = policy.toMap()
        if (!Const.Version.atLeast_25_0()) {
            // Put in package_name for old database
            map["package_name"] = AppContext.packageManager.getNameForUid(policy.uid)!!
        }
        val query = "REPLACE INTO ${Table.POLICY} ${map.toQuery()}"
        exec(query)
    }

    // Null means the command failed (no root channel).
    suspend fun fetchAll(): List<SuPolicy>? {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} WHERE uid/100000=${Const.USER_ID} " +
                "OR uid < $FIRST_APPLICATION_UID"
        val result = execResult(query, ::toPolicy)
        return if (result.ok) result.rows.filterNotNull() else null
    }

    private fun toPolicy(map: Map<String, String>): SuPolicy? {
        val uid = map["uid"]?.toInt() ?: return null
        val policy = SuPolicy(uid)
        map["policy"]?.toInt()?.let { policy.policy = it }
        // Restore the timed grant on read. SQLite evaluates toMap()'s
        // strftime expression when the row is written, so until is an
        // absolute deadline; convert it back to the seconds-from-now that
        // SuPolicy writes, or an edit from the manager would rewrite the row
        // as expired and the native side would prompt for root again.
        map["until"]?.let { until ->
            val deadline = until.toLongOrNull()
            policy.remain = when {
                deadline == null -> UNTIL_EXPRESSION_SECONDS.find(until)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: policy.remain
                deadline > 0 -> (deadline -
                        TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
                    .coerceAtLeast(1L)
                else -> deadline
            }
        }
        map["logging"]?.toInt()?.let { policy.logging = it != 0 }
        map["notification"]?.toInt()?.let { policy.notification = it != 0 }
        return policy
    }

    companion object {
        // If appId < FIRST_APPLICATION_UID, use appId as UID
        fun normalizeUid(uid: Int): Int {
            val appId = uid % 100000
            return if (appId < FIRST_APPLICATION_UID) appId else uid
        }

        // Revert normalizeUid
        fun denormalizeUid(uid: Int): Int {
            return if (uid < FIRST_APPLICATION_UID) (uid + Const.USER_ID * 100000) else uid
        }
    }
}
