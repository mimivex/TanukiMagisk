package com.topjohnwu.magisk.core.model.su

import com.topjohnwu.magisk.core.data.magiskdb.MagiskDB

// Timed su grants are kept: SuRequestHandler fills remain from the
// timeout choices in both manager UIs, so until is a raw SQL expression.
// remain > 0 is a duration that toMap() adds to the write time and 0 is a
// permanent grant (upstream semantics); the default is 0 so a policy created
// by the manager is never written as already expired.
class SuPolicy(
    var uid: Int,
    var policy: Int = DENY,
    var remain: Long = 0L,
    var logging: Boolean = true,
    var notification: Boolean = true,
) {
    companion object {
        const val QUERY = 0
        const val DENY = 1
        const val ALLOW = 2
        const val RESTRICT = 3
    }

    fun toMap(): MutableMap<String, Any> {
        val until = if (remain <= 0) {
            remain
        } else {
            MagiskDB.Literal("(strftime(\"%s\", \"now\") + $remain)")
        }
        return mutableMapOf(
            "uid" to uid,
            "policy" to policy,
            "until" to until,
            "logging" to logging,
            "notification" to notification
        )
    }
}
