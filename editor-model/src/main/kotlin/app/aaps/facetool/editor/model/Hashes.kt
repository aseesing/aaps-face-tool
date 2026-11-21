package app.aaps.facetool.editor.model

import java.security.MessageDigest

/** SHA-256 hashes as small hex text. */
object Hashes {

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** One hash over named parts. The order of [parts] counts. */
    fun sha256(parts: List<Pair<String, ByteArray>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in parts) {
            digest.update(name.toByteArray())
            digest.update(0)
            digest.update(bytes.size.toString().toByteArray())
            digest.update(0)
            digest.update(bytes)
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}
