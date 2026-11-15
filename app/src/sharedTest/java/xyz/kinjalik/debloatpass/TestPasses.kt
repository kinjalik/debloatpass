package xyz.kinjalik.debloatpass

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds in-memory zips that look like (or deliberately unlike) a .pkpass. */
object TestPasses {
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    fun pkpass(passJson: String): ByteArray = zip(
        "pass.json" to passJson.toByteArray(),
        "manifest.json" to "{}".toByteArray(),
    )

    val storeCard = pkpass(
        """{"formatVersion":1,"organizationName":"Coffee Club","description":"Loyalty card","storeCard":{}}""",
    )
}
