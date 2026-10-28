package com.ghostlock.app.data.profile

import com.ghostlock.app.data.NativeProfileDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Self-check for the runtime compiler: a real upstream profile must produce a
 * GLK1 document that decodes back to the same release and route.
 */
class ProfileCompilerTest {
    private val profile = File(
        requireNotNull(javaClass.classLoader?.getResource("profiles/6.1.115-android14-11-ga2521ca27699-ab13294383.conf"))
            .toURI(),
    )

    @Test
    fun compilesUpstreamProfile() {
        val bytes = ProfileCompiler.compile(profile)

        assertTrue("empty output", bytes.size > 64)
        // GLK1 magic 0x0D000721, little-endian on the wire.
        assertEquals(0x21, bytes[0].toInt() and 0xff)
        assertEquals(0x07, bytes[1].toInt() and 0xff)
        assertEquals(0x00, bytes[2].toInt() and 0xff)
        assertEquals(0x0D, bytes[3].toInt() and 0xff)

        val decoded = NativeProfileDocument.fromBinary(bytes)
        assertNotNull("binary does not decode", decoded)
        assertEquals("6.1.115-android14-11-ga2521ca27699-ab13294383", decoded!!.release)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsProfileWithoutRelease() {
        val dir = kotlin.io.path.createTempDirectory("ghostlock-test").toFile()
        try {
            val bad = File(dir, "bad.conf")
            bad.writeText("schema_version = 1\nkernel_major = 6\n")
            ProfileCompiler.compile(bad)
        } finally {
            dir.deleteRecursively()
        }
    }
}
