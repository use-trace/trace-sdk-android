package io.usetrace.sdk

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Robolectric gives a real SharedPreferences, so the file the backup rules exclude is the file these tests write.
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
    fun `the install id file is excluded from backup and from device transfer`() {
        val legacy = readRes("trace_backup_rules.xml")
        assertTrue(
            "full-backup-content does not exclude the install id file",
            legacy.contains("""<exclude domain="sharedpref" path="io.usetrace.sdk.installid.xml" />"""),
        )

        // Android 12 and later ignore the file above. A device transfer is the resold phone case, so an exclusion
        // that covers only cloud-backup leaves the worst failure open.
        val modern = readRes("trace_data_extraction_rules.xml")
        val cloudBackup = section(modern, "cloud-backup")
        val deviceTransfer = section(modern, "device-transfer")
        assertTrue(
            "cloud-backup does not exclude the install id file",
            cloudBackup.contains("""<exclude domain="sharedpref" path="io.usetrace.sdk.installid.xml" />"""),
        )
        assertTrue(
            "device-transfer does not exclude the install id file",
            deviceTransfer.contains("""<exclude domain="sharedpref" path="io.usetrace.sdk.installid.xml" />"""),
        )
    }

    /** The unit tests run with the module directory as the working directory, the repository root in some IDEs. */
    private fun readRes(name: String): String {
        val candidates = listOf(File("src/main/res/xml/$name"), File("trace/src/main/res/xml/$name"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("$name is missing, looked in ${candidates.joinToString()}", file)
        return file!!.readText()
    }

    private fun section(xml: String, tag: String): String {
        val start = xml.indexOf("<$tag>")
        val end = xml.indexOf("</$tag>")
        assertTrue("$tag section is missing", start >= 0 && end > start)
        return xml.substring(start, end)
    }
}
