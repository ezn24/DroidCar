package io.github.ezn24.droidcar.iap2

/**
 * Sends now-playing artwork over the iAP2 file-transfer link session (one instance per link).
 *
 * The 0x5001 update names the transfer in MediaItemAttributes 26 (ArtworkFileTransferIdentifier). Then, on the
 * file-transfer session, each datagram is `[transfer id][type][payload]`:
 *   phone Setup     `[id][0x04][file size u64 BE][file type u16 BE = 0x0002 NowPlayingArtworkData]` (session v2)
 *   car   Start     `[id][0x01]`  -> phone sends the bytes:
 *   phone FirstAndOnlyData `0xC0`, or FirstData `0x80`, Data `0x00` ..., LastData `0x40`
 *   car   Success `[id][0x05]` / Failure `0x06` / Cancel `0x02` (Pause `0x03`: wait for the next Start)
 * Datagram types and the Setup layout come from public iAP2 Wireshark dissectors and an accessory implementation
 * (rGnng/iap2mediactrl, which answers Setup with Start and the last data datagram with Success).
 *
 * Only the newest artwork matters: starting a new transfer drops an unfinished older one.
 */
internal class ArtworkTransfer(
    /** Sends one datagram on the file-transfer session; false = not possible on this link (no session, link down). */
    private val send: (ByteArray) -> Boolean,
    /** Largest datagram the link accepts. */
    private val maxDatagram: () -> Int,
    private val log: (String) -> Unit,
) {
    private class Pending(
        val id: Int,
        val data: ByteArray,
    )

    @Volatile
    private var pending: Pending? = null

    /** Announces transfer [id] (already named in the 0x5001 just sent) and waits for the head unit's Start. */
    @Synchronized
    fun begin(
        id: Int,
        data: ByteArray,
    ): Boolean {
        pending = Pending(id, data)
        val setup = ByteArray(12)
        setup[0] = id.toByte()
        setup[1] = SETUP.toByte()
        val size = data.size.toLong()
        for (i in 0 until 8) setup[2 + i] = (size ushr (56 - 8 * i)).toByte()
        setup[10] = (FILE_TYPE_ARTWORK ushr 8).toByte()
        setup[11] = FILE_TYPE_ARTWORK.toByte()
        val ok = send(setup)
        log("MEDIA-CAR: artwork #$id ${data.size}B setup ${if (ok) "sent" else "not possible on this link"}")
        if (!ok) pending = null
        return ok
    }

    /** A datagram from the head unit on the file-transfer session. */
    @Synchronized
    fun onDatagram(bytes: ByteArray) {
        if (bytes.size < 2) return
        val id = bytes[0].toInt() and 0xFF
        val type = bytes[1].toInt() and 0xFF
        val current = pending
        if (current == null || current.id != id) {
            log("MEDIA-CAR: artwork datagram type=0x%02x for #%d ignored (not the current transfer)".format(type, id))
            return
        }
        when (type) {
            START -> sendData(current)
            SUCCESS -> {
                pending = null
                log("MEDIA-CAR: artwork #$id delivered")
            }
            FAILURE, CANCEL -> {
                pending = null
                log("MEDIA-CAR: artwork #$id refused by the head unit (type=0x%02x)".format(type))
            }
            PAUSE -> log("MEDIA-CAR: artwork #$id paused by the head unit")
            else -> log("MEDIA-CAR: artwork #$id unexpected datagram type=0x%02x".format(type))
        }
    }

    private fun sendData(transfer: Pending) {
        val chunk = (maxDatagram() - 2).coerceIn(1, MAX_CHUNK)
        val total = transfer.data.size
        var offset = 0
        var pieces = 0
        do {
            val end = minOf(offset + chunk, total)
            val first = offset == 0
            val last = end == total
            val type =
                when {
                    first && last -> FIRST_AND_ONLY_DATA
                    first -> FIRST_DATA
                    last -> LAST_DATA
                    else -> DATA
                }
            val datagram = ByteArray(2 + end - offset)
            datagram[0] = transfer.id.toByte()
            datagram[1] = type.toByte()
            transfer.data.copyInto(datagram, 2, offset, end)
            if (!send(datagram)) {
                log("MEDIA-CAR: artwork #${transfer.id} link closed after $pieces pieces")
                pending = null
                return
            }
            pieces++
            offset = end
        } while (offset < total)
        log("MEDIA-CAR: artwork #${transfer.id} sent ${total}B in $pieces pieces, waiting for Success")
    }

    companion object {
        const val START = 0x01
        const val CANCEL = 0x02
        const val PAUSE = 0x03
        const val SETUP = 0x04
        const val SUCCESS = 0x05
        const val FAILURE = 0x06
        const val DATA = 0x00
        const val LAST_DATA = 0x40
        const val FIRST_DATA = 0x80
        const val FIRST_AND_ONLY_DATA = 0xC0
        const val FILE_TYPE_ARTWORK = 0x0002

        /** Keeps single link packets small so artwork does not hold up control messages behind it for long. */
        const val MAX_CHUNK = 4_096
    }
}
