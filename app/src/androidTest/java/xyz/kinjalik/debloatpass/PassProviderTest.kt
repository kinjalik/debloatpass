package xyz.kinjalik.debloatpass

import android.content.ContentValues
import android.content.Context
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The read-only provider wallet apps read the pass from. */
@RunWith(AndroidJUnit4::class)
class PassProviderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var file: File
    private val bytes = TestPasses.storeCard

    @Before
    fun setUp() {
        file = File(PassProvider.dir(ctx).apply { mkdirs() }, "Coffee_Club.pkpass").apply { writeBytes(bytes) }
    }

    @After
    fun tearDown() {
        PassProvider.dir(ctx).deleteRecursively()
    }

    private val uri get() = PassProvider.uriFor(ctx, file)

    @Test
    fun servesTheFileReadOnly() {
        assertArrayEquals(bytes, ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        for (mode in listOf("w", "wt", "rw", "wa")) {
            assertThrows(mode, SecurityException::class.java) {
                ctx.contentResolver.openOutputStream(uri, mode)!!.close()
            }
        }
        assertArrayEquals(bytes, file.readBytes()) // untouched
    }

    @Test
    fun queryReturnsOnlyTheRequestedOpenableColumns() {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, "_data", "bogus"), null, null, null)!!.use { c ->
            assertEquals(listOf(OpenableColumns.SIZE), c.columnNames.toList()) // no _data path leak
            assertTrue(c.moveToFirst())
            assertEquals(bytes.size.toLong(), c.getLong(0))
        }
    }

    @Test
    fun cannotBeModified() {
        val r = ctx.contentResolver
        assertThrows(UnsupportedOperationException::class.java) { r.insert(uri, ContentValues()) }
        assertThrows(UnsupportedOperationException::class.java) { r.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { r.update(uri, ContentValues(), null, null) }
        assertTrue(file.isFile)
    }
}
