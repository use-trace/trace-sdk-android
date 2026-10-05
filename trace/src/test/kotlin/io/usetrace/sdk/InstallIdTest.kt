package io.usetrace.sdk

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The install id is the SDK's whole identity. These tests hold it to three things: it is stable, it is shaped the
 * way the server expects, and reading it does not create it.
 *
 * Robolectric gives a real file system, so the file these tests write is the file the SDK writes on a device.
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

    @Test
    fun `the id is stored where android never backs it up`() {
        val id = InstallId.get(context)

        // getNoBackupFilesDir is the only storage a host app cannot opt back into a backup. Android excludes it
        // from Auto Backup and from a transfer whatever the app's own backup rules say, and a missing section in
        // those rules enables that mode for everything except the no-backup and cache directories.
        val stored = File(context.noBackupFilesDir, "install_id")
        assertTrue("nothing was written to the no-backup directory", stored.isFile)
        assertEquals(id, stored.readText())
        assertFalse("the id is in files, which Auto Backup includes", File(context.filesDir, "install_id").exists())
    }
}
