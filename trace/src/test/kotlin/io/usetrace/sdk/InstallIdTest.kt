package io.usetrace.sdk

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The install id is the SDK's whole identity. These tests hold it to three things: it is stable, it is shaped the
 * way the server expects, and reading it does not create it.
 *
 * Robolectric gives a real SharedPreferences, so these tests write the file the SDK writes on a device.
 */
@RunWith(RobolectricTestRunner::class)
class InstallIdTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `get returns the same id on a second call`() {
        val first = InstallId.get(context)
        assertEquals(first, InstallId.get(context))
    }

    @Test
    fun `get returns an app scoped anonymous key`() {
        val id = InstallId.get(context)
        assertTrue("unexpected shape", Regex("^auk_app_[0-9a-f]{32}$").matches(id))
    }

    @Test
    fun `peek does not create an id`() {
        assertNull(InstallId.peek(context))
        val created = InstallId.get(context)
        assertEquals(created, InstallId.peek(context))
    }

    @Test
    fun `two contexts for the same package share the id`() {
        val id = InstallId.get(context)
        val other = context.createPackageContext(context.packageName, 0)
        assertEquals(id, InstallId.get(other))
    }
}
