package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ArArchiveTest {

    @Test
    fun readsMembersAndSkipsPadding() {
        val first = "odd".toByteArray()
        val second = SyntheticArchives.compressXz(SyntheticArchives.tar(emptyList()))
        val archive = SyntheticArchives.rawAr(
            listOf(
                "debian-binary/" to "2.0\n".toByteArray(),
                "odd-member/" to first,
                "data.tar.xz/" to second
            )
        )
        ArArchiveReader(ByteArrayInputStream(archive)).use { reader ->
            val magic = reader.nextEntry()!!
            assertEquals("debian-binary", magic.name)
            assertTrue(magic.isDebianBinary)
            assertEquals("2.0\n", reader.entryData().readBytes().toString(Charsets.UTF_8))

            val odd = reader.nextEntry()!!
            assertEquals("odd-member", odd.name)
            assertEquals(3L, odd.size)
            assertEquals("odd", reader.entryData().readBytes().toString(Charsets.UTF_8))

            val data = reader.nextEntry()!!
            assertEquals("data.tar.xz", data.name)
            assertTrue(data.isDataArchive)
            assertTrue(reader.entryData().readBytes().contentEquals(second))

            assertEquals(null, reader.nextEntry())
        }
    }

    @Test
    fun skipsUnreadMemberDataOnNextEntry() {
        val archive = SyntheticArchives.rawAr(
            listOf(
                "debian-binary/" to "2.0\n".toByteArray(),
                "control.tar.xz/" to ByteArray(11) { 0x42 },
                "data.tar.xz/" to "payload".toByteArray()
            )
        )
        ArArchiveReader(ByteArrayInputStream(archive)).use { reader ->
            assertEquals("debian-binary", reader.nextEntry()!!.name)
            assertEquals("control.tar.xz", reader.nextEntry()!!.name)
            // Deliberately do not read the control payload.
            val data = reader.nextEntry()!!
            assertEquals("data.tar.xz", data.name)
            assertEquals("payload", reader.entryData().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun rejectsNonArFiles() {
        val error = assertThrows(ArchiveFormatException::class.java) {
            ArArchiveReader(ByteArrayInputStream("PK\u0003\u0004not-an-ar".toByteArray())).use {
                it.nextEntry()
            }
        }
        assertTrue(error.message!!.contains("Debian package"))
    }

    @Test
    fun rejectsTruncatedHeader() {
        val bytes = "!<arch>\n".toByteArray() + ByteArray(10)
        assertThrows(ArchiveFormatException::class.java) {
            ArArchiveReader(ByteArrayInputStream(bytes)).use { it.nextEntry() }
        }
    }
}
