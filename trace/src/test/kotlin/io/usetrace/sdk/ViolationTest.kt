package io.usetrace.sdk

import android.os.Build
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// DELIBERATE VIOLATION, reverted in the next commit: fails at 21 and at 35, so both levels have to be running.
@RunWith(RobolectricTestRunner::class)
class ViolationTest {
    @Test
    fun failsAtMinSdkAndAtCompileSdk() {
        assertTrue("ran at SDK ${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT in 22..34)
    }
}
