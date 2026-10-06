package io.usetrace.sdk

import android.app.usage.UsageStatsManager
import android.content.Context

// DELIBERATE VIOLATION, reverted in the next commit: a public symbol missing from trace/api/trace.api, a log call
// outside TraceLog, and an API 23 call in a library whose minSdk is 21.
public fun violation(context: Context) {
    println(context.getSystemService(UsageStatsManager::class.java))
}
