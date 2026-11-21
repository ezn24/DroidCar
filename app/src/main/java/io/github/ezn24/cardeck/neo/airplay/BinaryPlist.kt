package io.github.ezn24.cardeck.neo.airplay

import java.io.ByteArrayOutputStream

/**
 * Minimal Apple binary plist (bplist00) parser: covers the types used by the /info capability list
 * (null/bool/int/real/data/ASCII/UTF-16 string/UID/array/dictionary).
 * Return mapping: Map<String,Any?> / List<Any?> / String / Long / Double / Boolean / ByteArray / null.
 */
object BinaryPlist {
    // ---- Encoding: Map<String,Any?>/List/String/Long/Int/Boolean/Double/ByteArray ----

    fun encode(value: Any?): ByteArray {
        val objects = ArrayList<ByteArray>()
        val totalObjects = countObjects(value)
        val rootRef = encodeObject(value, objects, totalObjects)
        var offset = 8
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = offset
            offset += bytes.size
        }
        val offsetIntSize = intBytes(offset)
        val refSize = intBytes(totalObjects)
        val tableSize = objects.size * offsetIntSize
        val tableOffset = offset

        val out = ByteArrayOutputStream(offset + tableSize + 32)
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        objects.forEach(out::write)
        offsets.forEach { writeUint(out, it.toLong(), offsetIntSize) }
        // trailer: unused(6) offsetIntSize(1) refSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        out.write(ByteArray(6))
        out.write(offsetIntSize)
        out.write(refSize)
        writeUint(out, objects.size.toLong(), 8)
        writeUint(out, rootRef.toLong(), 8)
        writeUint(out, tableOffset.toLong(), 8)
        // The root object must sit at byte 8. This encoder encodes child objects recursively and appends the root container last,
        // so the root is at the end by default. Old head units such as CarPlay 210.81 **do not read the trailer topObject
        // and read offset 8 directly** (catplay upstream 9ad3a7e "fix recent encoder regression with
        // CarPlay 210.81 that hardcodes expected offset of the root object").
        // Apple's encoder also puts the root first; we rearrange to the same layout to match a real iPhone.
        return makeRootFirst(out.toByteArray())
    }

    /**
     * Moves the whole root object to offset 8 and fixes the offset table (same algorithm as catplay `make_root_first`).
     * Input must be a valid bplist just produced by this class (root is the last object); other layouts are returned unchanged.
     */
    private fun makeRootFirst(data: ByteArray): ByteArray {
        if (data.size < 41 || !data.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            return data
        }
        val trailer = data.size - 32
        val width = data[trailer + 6].toInt() and 0xFF
        if (width != 1 && width != 2 && width != 4 && width != 8) return data
        val count = readUint(data, trailer + 8, 8).toInt()
        val root = readUint(data, trailer + 16, 8).toInt()
        val table = readUint(data, trailer + 24, 8).toInt()
        if (root >= count ||
            table < 9 ||
            table.toLong() + count.toLong() * width > trailer
        ) {
            return data
        }

        fun entry(index: Int): Int = table + index * width
        val rootStart = readUint(data, entry(root), width).toInt()
        if (rootStart !in 8 until table) return data
        if (rootStart == 8) return data
        // Bytes belonging to the root object (after the root, up to the offset table); all other objects precede it.
        var rootEnd = table
        for (index in 0 until count) {
            val offset = readUint(data, entry(index), width).toInt()
            if (offset !in 8 until table) return data
            if (offset > rootStart) rootEnd = minOf(rootEnd, offset)
        }
        val rootLen = rootEnd - rootStart
        if (rootLen <= 0) return data
        val out = data.copyOf()
        // Rotating data[8..rootEnd) right by rootLen is equivalent to moving the root block to the front and shifting the other objects back.
        val tailLen = rootEnd - 8 - rootLen
        System.arraycopy(data, rootStart, out, 8, rootLen)
        System.arraycopy(data, 8, out, 8 + rootLen, tailLen)
        for (index in 0 until count) {
            val at = entry(index)
            val offset = readUint(data, at, width).toInt()
            val adjusted =
                when {
                    index == root -> 8
                    offset < rootStart -> offset + rootLen
                    else -> offset
                }
            writeUint(out, adjusted.toLong(), width, at)
        }
        return out
    }

    private fun writeUint(
        target: ByteArray,
        value: Long,
        size: Int,
        offset: Int,
    ) {
        for (index in 0 until size) {
            target[offset + index] = (value ushr (8 * (size - 1 - index))).toByte()
        }
    }

    /** Pre-pass: the reference width must be chosen from the final total object count (including the containers themselves). */
    private fun countObjects(value: Any?): Int =
        when (value) {
            is List<*> -> 1 + value.sumOf { countObjects(it) }
            is Map<*, *> -> 1 + value.keys.sumOf { countObjects(it) } + value.values.sumOf { countObjects(it) }
            else -> 1
        }

    private fun encodeObject(
        value: Any?,
        objects: ArrayList<ByteArray>,
        total: Int,
    ): Int {
        val bytes =
            when (value) {
                null -> byteArrayOf(0x00)
                is Boolean -> byteArrayOf(if (value) 0x09 else 0x08)
                is Int -> encodeInt(value.toLong())
                is Long -> encodeInt(value)
                is Double -> byteArrayOf(0x23) + value.toRawBits().toBytes(8)
                is ByteArray -> withLength(0x40, value)
                is String -> encodeString(value)
                is List<*> -> {
                    val refs = value.map { encodeObject(it, objects, total) }
                    withLength(0xA0, refsToBytes(refs, total), refs.size)
                }

                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = value as Map<String, Any?>
                    val keyRefs = map.keys.map { encodeObject(it, objects, total) }
                    val valueRefs = map.values.map { encodeObject(it, objects, total) }
                    withLength(0xD0, refsToBytes(keyRefs + valueRefs, total), map.size)
                }

                else -> throw IllegalArgumentException("unsupported plist type ${value.javaClass.simpleName}")
            }
        objects += bytes
        return objects.size - 1
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.toByteArray(Charsets.US_ASCII)
        return if (value.all { it.code < 0x80 } && ascii.decodeToString() == value) {
            withLength(0x50, ascii)
        } else {
            withLength(0x60, value.toByteArray(Charsets.UTF_16BE), value.length)
        }
    }

    /**
     * Integers choose their width from the **signed** range (bplist spec: integers are signed, readers sign-extend).
     *
     * It used to choose by unsigned thresholds: 35449 (0x8A79) was written as 2 bytes, which Apple reads as int16, i.e.
     * -30087. Once an integer port (timingPort/eventPort/dataPort) lands in 32768..65535 it
     * becomes negative, and the receiver may reject it or bind the wrong port. Unit test `SessionSetupBodyTest`
     * found this.
     */
    private fun encodeInt(value: Long): ByteArray {
        val size =
            when {
                value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE -> 1
                value >= Short.MIN_VALUE && value <= Short.MAX_VALUE -> 2
                value >= Int.MIN_VALUE && value <= Int.MAX_VALUE -> 4
                else -> 8
            }
        return byteArrayOf((0x10 or (log2(size))).toByte()) + value.toBytes(size)
    }

    private fun withLength(
        marker: Int,
        payload: ByteArray,
        count: Int = payload.size,
    ): ByteArray {
        if (count < 15) return byteArrayOf((marker or count).toByte()) + payload
        val lengthBytes = encodeInt(count.toLong())
        return byteArrayOf((marker or 0x0F).toByte()) + lengthBytes + payload
    }

    private fun refsToBytes(
        refs: List<Int>,
        objectCount: Int,
    ): ByteArray {
        val width = intBytes(objectCount)
        val out = ByteArray(refs.size * width)
        refs.forEachIndexed { index, ref ->
            val value = ref.toLong()
            for (byte in 0 until width) {
                out[index * width + byte] = (value ushr (8 * (width - 1 - byte))).toByte()
            }
        }
        return out
    }

    private fun Long.toBytes(size: Int): ByteArray = writeBytes(this, size)

    private fun writeBytes(
        value: Long,
        size: Int,
    ): ByteArray =
        ByteArray(size) { index ->
            (value ushr (8 * (size - 1 - index))).toByte()
        }

    private fun writeUint(
        out: ByteArrayOutputStream,
        value: Long,
        size: Int,
    ) {
        out.write(writeBytes(value, size))
    }

    private fun intBytes(value: Int): Int =
        when {
            value < 0x100 -> 1
            value < 0x10000 -> 2
            value < 0x100000000 -> 4
            else -> 8
        }

    private fun log2(size: Int): Int =
        when (size) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }

    // ---- Parsing ----

    /** Parse result + recovery note (note is non-null when trimming happened, null for a normal parse). */
    class ParseResult(
        val value: Any?,
        val note: String?,
    )

    /**
     * Tolerant parsing: parse by the standard layout first; on failure, retry trimming 1~8 trailing bytes in turn.
     *
     * RTSP messages from head units sometimes carry trailing bytes beyond `Content-Length` (CRLF / padding),
     * and the bplist trailer is located **32 bytes from the end**, so one extra byte shifts everything,
     * showing up as "the body is a valid bplist00 yet parsing fails". Trim-and-retry rescues such messages,
     * and on failure the exception thrown is the original one with full detail (the trailer summary is already in its message).
     */
    fun parseRecovered(bytes: ByteArray): ParseResult {
        val first = runCatching { parse(bytes) }
        first.getOrNull()?.let { return ParseResult(it, null) }
        if (first.isSuccess) return ParseResult(null, null)
        val failure = first.exceptionOrNull() ?: IllegalArgumentException("parse failed")
        if (bytes.size >= 48) {
            for (trim in 1..8) {
                if (bytes.size - trim < 40) break
                val attempt = runCatching { parse(bytes.copyOfRange(0, bytes.size - trim)) }
                if (attempt.isSuccess) {
                    return ParseResult(attempt.getOrThrow(), "$trim extra trailing bytes (parsed after trimming)")
                }
            }
        }
        throw failure as? IllegalArgumentException
            ?: IllegalArgumentException("${failure.javaClass.simpleName}: ${failure.message}")
    }

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size < 40) throw IllegalArgumentException("plist too short (${bytes.size} bytes)")
        val magic = "bplist00".toByteArray(Charsets.US_ASCII)
        if (!bytes.copyOfRange(0, 8).contentEquals(magic)) {
            throw IllegalArgumentException(
                "not bplist00 (first 8 bytes " + bytes.take(8).joinToString(" ") { "%02x".format(it) } + ")",
            )
        }

        // 32-byte trailer at the end: unused(6) offsetIntSize(1) objectRefSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        val offsetIntSize = u8(bytes[bytes.size - 26])
        val objectRefSize = u8(bytes[bytes.size - 25])
        val numObjects = readUint(bytes, bytes.size - 24, 8)
        val topObject = readUint(bytes, bytes.size - 16, 8)
        val offsetTableOffset = readUint(bytes, bytes.size - 8, 8)
        val trailer =
            "trailer{offsetIntSize=$offsetIntSize objectRefSize=$objectRefSize numObjects=$numObjects " +
                "topObject=$topObject offsetTableOffset=$offsetTableOffset body=${bytes.size}B}"
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
            throw IllegalArgumentException("invalid trailer fields $trailer")
        }
        if (numObjects <= 0 || numObjects > MAX_OBJECTS) {
            throw IllegalArgumentException("invalid object count $trailer")
        }
        if (topObject >= numObjects) throw IllegalArgumentException("root object index out of range $trailer")
        if (offsetTableOffset < 9 ||
            offsetTableOffset + numObjects * offsetIntSize > bytes.size - 32
        ) {
            throw IllegalArgumentException("offset table out of range $trailer")
        }

        val count = numObjects.toInt()
        val tableOffset = offsetTableOffset.toInt()
        val offsets =
            IntArray(count) { index ->
                readUint(bytes, tableOffset + index * offsetIntSize, offsetIntSize).toInt()
            }
        return try {
            parseObject(bytes, offsets[topObject.toInt()], objectRefSize, offsets)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: IndexOutOfBoundsException) {
            throw IllegalArgumentException("read out of range ($trailer, ${error.message})")
        } catch (error: NegativeArraySizeException) {
            throw IllegalArgumentException("negative length field ($trailer)")
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("length field too large ($trailer)")
        }
    }

    private fun parseObject(
        bytes: ByteArray,
        offset: Int,
        refSize: Int,
        offsets: IntArray,
    ): Any? {
        val marker = u8(bytes[offset])
        val type = marker ushr 4
        val info = marker and 0xF
        return when (type) {
            0x0 ->
                when (info) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }

            // Integers are always read as **unsigned**: the receiver (head unit) writes bitmaps/microseconds/sample rates into
            // 2-byte ints, and values above 32767 read as signed become negative (measured: 44100 -> -21436,
            // 35000us -> -30536, 0x8854 -> -30636). Once a bitmap field is sign-extended its upper 16 bits
            // all become 1, so any format bit above bit15 "looks declared" and negotiation picks a
            // format the head unit does not support at all.
            0x1 -> readUint(bytes, offset + 1, 1 shl info)

            0x2 ->
                when (info) {
                    0x2 -> Float.fromBits(readUint(bytes, offset + 1, 4).toInt()).toDouble()
                    0x3 -> Double.fromBits(readUint(bytes, offset + 1, 8))
                    else -> null
                }

            0x3 -> // date: CFAbsoluteTime (seconds since 2001-01-01), returned as a Double as-is
                Double.fromBits(readUint(bytes, offset + 1, 8))

            0x4 -> { // data
                val (count, start) = elementCount(bytes, offset, info)
                bytes.copyOfRange(start, start + count)
            }

            0x5 -> { // ASCII string
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.US_ASCII)
            }

            0x6 -> { // UTF-16BE string
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count * 2, Charsets.UTF_16BE)
            }

            0x7 -> { // UTF-8 string (bplist type 0x7; some encoders write it)
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.UTF_8)
            }

            // UID: the length is determined by info (info+1 bytes), **not** refSize; reading by refSize would
            // consume extra following bytes when refSize>1 and skew every object pointer in the plist.
            0x8 -> readUint(bytes, offset + 1, info + 1)

            0xB -> { // UUID (fixed 16 bytes)
                val size = info + 1
                bytes.copyOfRange(offset + 1, minOf(offset + 1 + size, bytes.size))
            }

            0xA, 0xC -> { // array / set
                val (count, start) = elementCount(bytes, offset, info)
                List(count) { index ->
                    parseObject(
                        bytes,
                        offsets[readUint(bytes, start + index * refSize, refSize).toInt()],
                        refSize,
                        offsets,
                    )
                }
            }

            0xD -> { // dict
                val (count, start) = elementCount(bytes, offset, info)
                val map = LinkedHashMap<String, Any?>(count)
                for (index in 0 until count) {
                    val keyRef = readUint(bytes, start + index * refSize, refSize).toInt()
                    val valueRef = readUint(bytes, start + (count + index) * refSize, refSize).toInt()
                    val key = parseObject(bytes, offsets[keyRef], refSize, offsets)
                    map[key?.toString() ?: "#$index"] = parseObject(bytes, offsets[valueRef], refSize, offsets)
                }
                map
            }

            else -> null
        }
    }

    /** Collection/string/data length: when info<0xF the length is info itself; otherwise it is stored in the following int object. */
    private fun elementCount(
        bytes: ByteArray,
        offset: Int,
        info: Int,
    ): Pair<Int, Int> {
        if (info != 0xF) return info to offset + 1
        if (offset + 1 >= bytes.size) {
            throw IllegalArgumentException("length byte missing (offset=$offset body=${bytes.size}B)")
        }
        val intMarker = u8(bytes[offset + 1])
        if (intMarker ushr 4 != 0x1) {
            throw IllegalArgumentException(
                "expected a length int object, got 0x${intMarker.toString(16)} (offset=$offset " +
                    "context=" +
                    bytes
                        .copyOfRange(offset, minOf(offset + 8, bytes.size))
                        .joinToString(" ") { "%02x".format(it) } + ")",
            )
        }
        val intSize = 1 shl (intMarker and 0xF)
        // The extended length must be read as **unsigned**: when DiPlay sends a 182B data it writes 0xB6, which read as signed is
        // -74, and copyOfRange(51, -23) throws IAE directly, so the whole iAPSendMessage command is dropped as
        // "unrecognized" (located 2026-10-20; same family of bug as integer sign extension).
        val count = readUint(bytes, offset + 2, intSize)
        if (count < 0 || count > MAX_OBJECTS) {
            throw IllegalArgumentException("invalid length field (count=$count offset=$offset)")
        }
        return count.toInt() to offset + 2 + intSize
    }

    private const val MAX_OBJECTS = 1 shl 20

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        if (size < 0 || size > 8) throw IllegalArgumentException("invalid read width (size=$size offset=$offset)")
        if (offset < 0 || offset + size > bytes.size) {
            throw IllegalArgumentException(
                "read out of range (offset=$offset size=$size body=${bytes.size}B) - " +
                    "happens when the trailer disagrees with the actual message length",
            )
        }
        var value = 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
}
