package com.trickhook.il2cpp.protector

import java.security.GeneralSecurityException
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ProtectorDescriptor(
    val filePosition: Long,
    val name: String,
    val offset: Long,
    val virtualAddress: Long,
    val size: Long,
    val checksum: Long,
    val firstLoadFileSize: Long,
    val kind: Long,
    val algo: Long
) {
    override fun toString(): String =
        "$name offset=0x${offset.toString(16)} vaddr=0x${virtualAddress.toString(16)} size=0x${size.toString(16)}"
}

@Suppress("ArrayInDataClass")
data class UnpackResult(
    val detected: Boolean,
    val unpacked: Boolean,
    val descriptor: ProtectorDescriptor?,
    val key: Int,
    val keySource: String,
    val aesSeed: Long,
    val window0Method: String,
    val permutation: IntArray,
    val permutationSource: String,
    val windowsTotal: Int,
    val windowsRecovered: Int,
    val windowsSkipped: Int,
    val checksumVerified: Boolean,
    val expectedCrc: Long,
    val actualCrc: Long,
    val bytesUnrecovered: Long,
    val data: ByteArray
)

private data class Window(val index: Int, val start: Long)

object FFProtector {

    const val MAGIC = 0x12345678L

    private const val OBFUSCATION_CONSTANT = 0x4F

    private const val GROUP_SIZE = 8
    private const val FIRST_GROUP_INDEX = 2
    private const val WINDOW_SIZE = 0x4000
    private const val SLOT_STRIDE = 0x10000
    private const val FIRST_WINDOW_PHASE = 0x2000

    private const val SMALL_SECTION_LIMIT = 0x100000L

    private const val KEY_BYTE_OFFSET = 0x186
    private const val ALGO_OFFSET = 0x188
    private const val KEY_BLOB_OFFSET = 0x1a8

    private const val ASCII_SPACE = 0x20
    private const val ASCII_TILDE = 0x7e

    private const val MAX_SECTION_NAME_LENGTH = 16
    private const val MIN_KEY_BLOB_CHARS = 28
    private const val MAX_KEY_BLOB_CHARS = 64
    private const val MIN_KEY_MATERIAL_SIZE = 16
    private const val AES_KEY_SIZE = 16
    private const val BUILD_YEAR_PREFIX = 0x20

    private const val FALLBACK_XOR_KEY = 0x87

    private const val WINDOW0_CHUNK_SIZE = 0x800

    private val WINDOW0_IV = byteArrayOf(
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
        0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11
    )

    private val IDENTITY = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    private val KNOWN_PERMUTATIONS = listOf(
        intArrayOf(4, 0, 6, 2, 1, 3, 5, 7),
        intArrayOf(3, 4, 0, 2, 6, 1, 5, 7),
        IDENTITY
    )

    fun findDescriptor(data: ByteArray): ProtectorDescriptor? {
        var i = 0L
        while (i + 0x34 <= data.size) {
            if (readUInt32(data, i) == MAGIC) {
                val name = readName(data, i + 4)
                if (name != null) {
                    val offset = readUInt32(data, i + 0x14)
                    val size = readUInt32(data, i + 0x1c)
                    if (size != 0L && offset != 0L && offset + size <= data.size) {
                        return ProtectorDescriptor(
                            filePosition = i,
                            name = name,
                            offset = offset,
                            virtualAddress = readUInt32(data, i + 0x18),
                            size = size,
                            checksum = readUInt32(data, i + 0x20),
                            firstLoadFileSize = readUInt32(data, i + 0x28),
                            kind = readUInt32(data, i + 0x2c),
                            algo = if (i + ALGO_OFFSET + 4 <= data.size) readUInt32(data, i + ALGO_OFFSET) else 0L
                        )
                    }
                }
            }
            i += 4
        }
        return null
    }

    fun tryUnpack(data: ByteArray, enabled: Boolean = true, inPlace: Boolean = false): UnpackResult {
        val descriptor = findDescriptor(data)
            ?: return emptyResult(data, detected = false, descriptor = null)

        if (!enabled) return emptyResult(data, detected = true, descriptor = descriptor)

        val start = descriptor.offset
        val end = start + descriptor.size
        val windows = enumerateWindows(start, end)
        if (windows.isEmpty()) return emptyResult(data, detected = true, descriptor = descriptor)

        val dominant = mostFrequentByte(data, windows, end)
        if (dominant == 0) {
            return emptyResult(data, detected = true, descriptor = descriptor, windowsTotal = windows.size)
        }

        val blob = readKeyBlob(data, descriptor)
        val fromByte = keyFromDescriptorByte(data, descriptor)
        val fromBlob = if (blob != null) blob[13].toInt() and 0xFF else 0

        val keySource = when {
            fromByte != 0 && fromByte == fromBlob -> "descriptor"
            fromByte != 0 && fromByte == dominant -> "descriptor + histogram"
            fromBlob != 0 && fromBlob == dominant -> "key blob + histogram"
            bestByTextScore(data, windows, end) == dominant -> "histogram"
            else -> null
        } ?: return emptyResult(data, detected = true, descriptor = descriptor, windowsTotal = windows.size)

        val selected = when (keySource) {
            "descriptor", "descriptor + histogram" -> fromByte
            "key blob + histogram" -> fromBlob
            else -> dominant
        }
        val key = if (selected == 0) FALLBACK_XOR_KEY else selected

        val out = if (inPlace) data else data.copyOf()
        val n = windows.size

        var window0Method = "not attempted"
        var aesSeed = 0L
        var recovered = 0
        var skipped = 0
        var unrecovered = 0L
        var sigma = IDENTITY
        var sigmaSource = "identity"

        fun windowLength(index: Int): Int {
            val dst = windows[index].start
            return if (index == n - 1) (end - dst).toInt()
            else minOf(WINDOW_SIZE.toLong(), end - dst).toInt()
        }

        fun copyWindow(dst: Long, from: ByteArray, fromOffset: Int, len: Int) {
            val to = dst.toInt()
            for (i in 0 until len) out[to + i] = (from[fromOffset + i].toInt() xor key).toByte()
        }

        if (descriptor.size < SMALL_SECTION_LIMIT) {
            var i = windows[0].start
            while (i < end) {
                out[i.toInt()] = (data[i.toInt()].toInt() xor key).toByte()
                i++
            }
            recovered = n
            window0Method = "n/a (small section, plain XOR)"
            sigmaSource = "n/a (small section)"
        } else {
            val w0 = windows[0].start
            val w0Len = minOf(WINDOW_SIZE.toLong(), end - w0).toInt()
            val seed = if (blob != null) {
                ((blob[12].toLong() and 0xFF) shl 24) or
                    ((blob[13].toLong() and 0xFF) shl 16) or
                    ((blob[14].toLong() and 0xFF) shl 8) or
                    (blob[15].toLong() and 0xFF)
            } else 0L

            if (seed != 0L && decryptWindow0(data, out, w0, w0Len, seed)) {
                aesSeed = seed
                window0Method = "AES-128-CBC"
                recovered++
            } else {
                window0Method = "failed"
                skipped++
                unrecovered += w0Len
            }

            val lastGroupStart = FIRST_GROUP_INDEX + GROUP_SIZE * ((n - FIRST_GROUP_INDEX) / GROUP_SIZE)

            for (index in 1 until n) {
                if (index >= FIRST_GROUP_INDEX && index < lastGroupStart) continue
                val dst = windows[index].start
                val len = windowLength(index)
                if (len <= 0) {
                    skipped++
                } else {
                    copyWindow(dst, data, dst.toInt(), len)
                    recovered++
                }
            }

            val solved = solvePermutation(data, out, windows, start, end, lastGroupStart, key, descriptor.checksum)
            if (solved != null) {
                sigma = solved.first
                sigmaSource = solved.second
            } else if (descriptor.algo != 2L) {
                sigma = KNOWN_PERMUTATIONS[0]
                sigmaSource = "fallback table"
            } else {
                sigmaSource = "identity (algo 2)"
            }

            val scratch = ByteArray(GROUP_SIZE * WINDOW_SIZE)
            var index = FIRST_GROUP_INDEX
            while (index < lastGroupStart) {
                val groupEnd = minOf(index + GROUP_SIZE, lastGroupStart)
                val members = groupEnd - index

                for (p in 0 until members) {
                    val source = index + sigma[p]
                    if (source >= n) continue
                    val len = windowLength(index + p)
                    val at = windows[source].start
                    if (len > 0 && at + len <= end) {
                        System.arraycopy(data, at.toInt(), scratch, p * WINDOW_SIZE, len)
                    }
                }

                for (p in 0 until members) {
                    val member = index + p
                    val source = index + sigma[p]
                    val len = windowLength(member)
                    val at = if (source < n) windows[source].start else -1L
                    if (len <= 0 || at < start || at + len > end) {
                        skipped++
                        unrecovered += maxOf(len, 0)
                    } else {
                        copyWindow(windows[member].start, scratch, p * WINDOW_SIZE, len)
                        recovered++
                    }
                }
                index = groupEnd
            }
        }

        val expectedCrc = descriptor.checksum
        val actualCrc = crc32(out, start, end - start)

        return UnpackResult(
            detected = true,
            unpacked = true,
            descriptor = descriptor,
            key = key,
            keySource = keySource,
            aesSeed = aesSeed,
            window0Method = window0Method,
            permutation = sigma,
            permutationSource = sigmaSource,
            windowsTotal = n,
            windowsRecovered = recovered,
            windowsSkipped = skipped,
            checksumVerified = actualCrc == expectedCrc,
            expectedCrc = expectedCrc,
            actualCrc = actualCrc,
            bytesUnrecovered = unrecovered,
            data = out
        )
    }

    fun describe(result: UnpackResult): String {
        if (!result.detected) return ""
        val lines = mutableListOf("Detected packed ELF (stub_decrypt_elf): ${result.descriptor}")
        if (!result.unpacked) {
            lines += "  Section content already looks unpacked, leaving it alone."
            return lines.joinToString("\n")
        }
        val seedPart = if (result.aesSeed != 0L) " seed 0x${hex8(result.aesSeed)}" else ""
        lines += "  Unpacked with key 0x${hex2(result.key)} (from ${result.keySource}), " +
            "window 0 via ${result.window0Method}$seedPart: " +
            "${result.windowsRecovered}/${result.windowsTotal} windows recovered"
        lines += "  Window permutation ${result.permutation.joinToString(",")} (${result.permutationSource})"
        if (result.checksumVerified) {
            lines += "  CRC32 matches the descriptor (0x${hex8(result.expectedCrc).uppercase()}) - section is byte-exact."
        } else {
            lines += "  CRC32 0x${hex8(result.actualCrc).uppercase()} != descriptor " +
                "0x${hex8(result.expectedCrc).uppercase()}; ${result.bytesUnrecovered} byte(s) could not be recovered."
            lines += "  Dump the library from memory instead."
        }
        return lines.joinToString("\n")
    }

    private fun solvePermutation(
        data: ByteArray,
        out: ByteArray,
        windows: List<Window>,
        start: Long,
        end: Long,
        lastGroupStart: Int,
        key: Int,
        target: Long
    ): Pair<IntArray, String>? {
        val count = lastGroupStart - FIRST_GROUP_INDEX
        if (count <= 0) return null
        if (windows[lastGroupStart - 1].start + WINDOW_SIZE > end) return null

        val total = (end - start).toInt()
        val zeroCrc = Crc32Affine.of(ByteArray(WINDOW_SIZE), 0, WINDOW_SIZE)
        val keyCrc = Crc32Affine.of(ByteArray(WINDOW_SIZE) { key.toByte() }, 0, WINDOW_SIZE)

        var base = Crc32Affine.of(out, start.toInt(), total)
        val rests = LongArray(count)
        for (k in 0 until count) {
            val at = windows[FIRST_GROUP_INDEX + k].start
            val rest = total.toLong() - (at - start) - WINDOW_SIZE
            rests[k] = rest
            val present = Crc32Affine.of(out, at.toInt(), WINDOW_SIZE) xor zeroCrc
            base = base xor Crc32Affine.combine(present, 0L, rest)
        }

        val aggregate = Array(GROUP_SIZE) { LongArray(GROUP_SIZE) }
        for (k in 0 until count) {
            val groupBase = FIRST_GROUP_INDEX + GROUP_SIZE * (k / GROUP_SIZE)
            val p = k % GROUP_SIZE
            for (j in 0 until GROUP_SIZE) {
                val at = windows[groupBase + j].start
                val term = Crc32Affine.of(data, at.toInt(), WINDOW_SIZE) xor keyCrc
                aggregate[p][j] = aggregate[p][j] xor Crc32Affine.combine(term, 0L, rests[k])
            }
        }

        fun evaluate(sigma: IntArray): Long {
            var acc = base
            for (p in 0 until GROUP_SIZE) acc = acc xor aggregate[p][sigma[p]]
            return acc
        }

        for (known in KNOWN_PERMUTATIONS) {
            if (evaluate(known) == target) {
                return known to if (known === IDENTITY) "identity, CRC32 verified" else "known table, CRC32 verified"
            }
        }

        val sigma = IntArray(GROUP_SIZE)
        val used = BooleanArray(GROUP_SIZE)

        fun search(p: Int, acc: Long): Boolean {
            if (p == GROUP_SIZE) return acc == target
            for (j in 0 until GROUP_SIZE) {
                if (used[j]) continue
                used[j] = true
                sigma[p] = j
                if (search(p + 1, acc xor aggregate[p][j])) return true
                used[j] = false
            }
            return false
        }

        return if (search(0, base)) sigma to "solved from CRC32" else null
    }

    private fun emptyResult(
        data: ByteArray,
        detected: Boolean,
        descriptor: ProtectorDescriptor?,
        windowsTotal: Int = 0
    ) = UnpackResult(
        detected = detected,
        unpacked = false,
        descriptor = descriptor,
        key = 0,
        keySource = "",
        aesSeed = 0L,
        window0Method = "not attempted",
        permutation = IDENTITY,
        permutationSource = "",
        windowsTotal = windowsTotal,
        windowsRecovered = 0,
        windowsSkipped = 0,
        checksumVerified = false,
        expectedCrc = 0L,
        actualCrc = 0L,
        bytesUnrecovered = 0L,
        data = data
    )

    private fun readUInt32(data: ByteArray, at: Long): Long {
        val p = at.toInt()
        return (data[p].toLong() and 0xFF) or
            ((data[p + 1].toLong() and 0xFF) shl 8) or
            ((data[p + 2].toLong() and 0xFF) shl 16) or
            ((data[p + 3].toLong() and 0xFF) shl 24)
    }

    private fun readName(data: ByteArray, at: Long): String? {
        val sb = StringBuilder()
        for (k in 0 until MAX_SECTION_NAME_LENGTH) {
            val index = at + k
            if (index >= data.size) return null
            val b = data[index.toInt()].toInt() and 0xFF
            if (b == 0) break
            if (b < ASCII_SPACE || b > ASCII_TILDE) return null
            sb.append(b.toChar())
        }
        if (sb.length < 2 || sb[0] != '.') return null
        return sb.toString()
    }

    private fun decryptWindow0(src: ByteArray, dst: ByteArray, offset: Long, length: Int, k: Long): Boolean {
        if (length < WINDOW0_CHUNK_SIZE || offset + length > src.size) return false
        val material = String.format("%08x%08x", k, k).toByteArray(Charsets.US_ASCII)
        if (material.size != AES_KEY_SIZE) return false

        val spec = SecretKeySpec(material, "AES")
        val iv = IvParameterSpec(WINDOW0_IV)
        val base = offset.toInt()
        return try {
            var off = 0
            while (off + WINDOW0_CHUNK_SIZE <= length) {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, spec, iv)
                cipher.doFinal(src, base + off, WINDOW0_CHUNK_SIZE, dst, base + off)
                off += WINDOW0_CHUNK_SIZE
            }
            true
        } catch (unusable: GeneralSecurityException) {
            false
        }
    }

    private fun readKeyBlob(data: ByteArray, descriptor: ProtectorDescriptor): ByteArray? {
        val at = descriptor.filePosition + KEY_BLOB_OFFSET
        if (at < 0 || at + MIN_KEY_BLOB_CHARS > data.size) return null

        val base64Text = StringBuilder()
        var i = at
        while (i < data.size && i < at + MAX_KEY_BLOB_CHARS) {
            val b = data[i.toInt()].toInt() and 0xFF
            if (b == 0) break
            if (b < ASCII_SPACE || b > ASCII_TILDE) return null
            if (b != ASCII_SPACE) base64Text.append(b.toChar())
            i++
        }

        val raw = try {
            Base64.getDecoder().decode(base64Text.toString())
        } catch (malformed: IllegalArgumentException) {
            return null
        }
        if (raw.size < MIN_KEY_MATERIAL_SIZE) return null
        for (j in raw.indices) raw[j] = (raw[j].toInt() xor OBFUSCATION_CONSTANT).toByte()
        return if ((raw[0].toInt() and 0xFF) != BUILD_YEAR_PREFIX) null else raw
    }

    private fun keyFromDescriptorByte(data: ByteArray, descriptor: ProtectorDescriptor): Int {
        val at = descriptor.filePosition + KEY_BYTE_OFFSET
        if (at < 0 || at >= data.size) return 0
        return (data[at.toInt()].toInt() and 0xFF) xor OBFUSCATION_CONSTANT
    }

    private fun enumerateWindows(sectionStart: Long, sectionEnd: Long): List<Window> {
        val list = mutableListOf<Window>()
        var w = (sectionStart and 0xfffL.inv()) + FIRST_WINDOW_PHASE
        var i = 0
        while (w < sectionEnd) {
            list += Window(i, w)
            w += SLOT_STRIDE
            i++
        }
        return list
    }

    private fun mostFrequentByte(data: ByteArray, windows: List<Window>, end: Long): Int {
        val histogram = LongArray(256)
        for ((_, at) in windows) {
            val len = minOf(WINDOW_SIZE.toLong(), end - at).toInt()
            val base = at.toInt()
            for (i in 0 until len) histogram[data[base + i].toInt() and 0xFF]++
        }
        var best = 0
        for (i in 1 until 256) if (histogram[i] > histogram[best]) best = i
        return best
    }

    private fun bestByTextScore(data: ByteArray, windows: List<Window>, end: Long): Int {
        val sample = 512
        val sources = windows.mapNotNull { (_, at) ->
            if (at + sample > end) null else at.toInt()
        }
        var best = 0
        var bestScore = -1L
        for (k in 0 until 256) {
            var score = 0L
            for (base in sources) {
                for (i in 0 until sample) {
                    val v = (data[base + i].toInt() xor k) and 0xFF
                    if (v == 0) score += 4 else if (v in 0x20..0x7e) score++
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        return best
    }

    private fun crc32(data: ByteArray, offset: Long, length: Long): Long {
        val crc = CRC32()
        crc.update(data, offset.toInt(), length.toInt())
        return crc.value
    }

    private fun hex2(value: Int) = String.format("%02X", value)

    private fun hex8(value: Long) = String.format("%08x", value)
}
