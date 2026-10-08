package io.usetrace.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the module builds its own BuildConfig and that the version the transport will put in the User-Agent is
 * the version the library is published as. It does not test SDK behaviour: there is none yet.
 */
class BuildConfigTest {

    @Test
    fun `the sdk version is the published version`() {
        assertEquals("0.2.0", BuildConfig.SDK_VERSION)
    }
}
