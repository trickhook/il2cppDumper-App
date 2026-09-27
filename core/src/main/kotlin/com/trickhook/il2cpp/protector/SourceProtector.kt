package com.trickhook.il2cpp.protector

import com.trickhook.il2cpp.io.ByteSource

/**
 * Runs [FFProtector] over a [ByteSource] instead of a `ByteArray`.
 *
 * [FFProtector] itself is untouched: it stays the single source of truth for the
 * cipher, the key recovery and the window permutation. This adapter only solves
 * the memory problem. The protector needs random read/write access to the bytes
 * from 0 up to the end of the section it owns, and nothing above that, so a
 * library of any size is handled by materialising just that prefix on the heap,
 * handing it to [FFProtector] with `inPlace = true`, and writing the recovered
 * section back into the source.
 *
 * For a 247 MB library whose protected section ends around 13 MB that is a 13 MB
 * heap array instead of 247 MB, and the remaining 234 MB are never copied.
 *
 * The bytes outside the section are identical in the prefix and in the source, so
 * writing the section back reproduces exactly what the whole-array call would
 * have produced.
 */
object SourceProtector {

    /** `FFProtector.MAGIC`, repeated here so FFProtector needs no new API. */
    private const val MAGIC = 0x12345678L

    private const val DESCRIPTOR_MIN_SIZE = 0x34L
    private const val OFFSET_FIELD = 0x14L
    private const val SIZE_FIELD = 0x1CL

    /**
     * How much of the file past the descriptor the protector reads: the key byte
     * at +0x186 and a base64 key blob at +0x1a8 that is at most 64 characters.
     * 0x200 is comfortably past both, which keeps the blob scan bounded by its
     * own character limit rather than by the end of the staged prefix - that is
     * what makes the staged run behave exactly like a whole-file run.
     */
    private const val DESCRIPTOR_TAIL = 0x200L

    private const val SCAN_CHUNK = 1 shl 20

    private const val MAX_CANDIDATES = 64

    class Result(
        val source: ByteSource,
        val detected: Boolean,
        val unpacked: Boolean,
        val checksumVerified: Boolean,
        /** Start of the section the protector owns, or -1 when nothing was found. */
        val protectedStart: Long,
        /** Exclusive end of that section, or -1. */
        val protectedEnd: Long,
        val bytesUnrecovered: Long,
        val report: String
    ) {
        /**
         * True when the protected section still holds bytes we could not turn back
         * into plaintext. Anything the dump reads from this range is garbage and
         * must not be emitted as if it were real.
         */
        val protectedRangeIsUnreliable: Boolean get() = detected && !checksumVerified

        val stagedBytes: Long get() = if (detected) protectedEnd else 0L
    }

    fun unpack(source: ByteSource, enabled: Boolean = true): Result {
        var tried = 0
        for (position in magicPositions(source)) {
            if (tried >= MAX_CANDIDATES) break
            val offset = source.uint32At(position + OFFSET_FIELD)
            val size = source.uint32At(position + SIZE_FIELD)
            // The same plausibility gate FFProtector.findDescriptor applies. Doing
            // it here avoids staging megabytes for a magic that is just noise.
            if (offset == 0L || size == 0L || offset + size > source.size) continue
            tried++

            val prefix = minOf(source.size, maxOf(offset + size, position + DESCRIPTOR_TAIL))
            if (prefix > Int.MAX_VALUE.toLong()) continue
            val staged = source.slice(0L, prefix.toInt())

            val result = FFProtector.tryUnpack(staged, enabled = enabled, inPlace = true)
            val descriptor = result.descriptor
            if (!result.detected || descriptor == null) continue
            // Normally this is the candidate we sized for. If FFProtector settled on
            // a different one, only accept it when the prefix still covered it.
            if (descriptor.offset + descriptor.size > prefix) continue

            if (result.unpacked) {
                source.copyIn(
                    descriptor.offset,
                    staged,
                    descriptor.offset.toInt(),
                    descriptor.size.toInt()
                )
            }
            return Result(
                source = source,
                detected = true,
                unpacked = result.unpacked,
                checksumVerified = result.checksumVerified,
                protectedStart = descriptor.offset,
                protectedEnd = descriptor.offset + descriptor.size,
                bytesUnrecovered = result.bytesUnrecovered,
                report = FFProtector.describe(result)
            )
        }
        return Result(
            source = source,
            detected = false,
            unpacked = false,
            checksumVerified = false,
            protectedStart = -1L,
            protectedEnd = -1L,
            bytesUnrecovered = 0L,
            report = ""
        )
    }

    /**
     * File offsets where the protector magic appears, in the same 4-byte-aligned
     * scan order FFProtector uses. Lazy and chunked so a library whose descriptor
     * sits in the first megabyte is not scanned to the end.
     */
    private fun magicPositions(source: ByteSource): Sequence<Long> = sequence {
        val limit = source.size - DESCRIPTOR_MIN_SIZE
        if (limit < 0L) return@sequence
        // Candidate positions are 4-byte aligned and SCAN_CHUNK is a multiple of 4,
        // so no magic can straddle a chunk boundary and the chunks need no overlap.
        val chunk = ByteArray(SCAN_CHUNK)
        val b0 = (MAGIC and 0xFF).toByte()
        val b1 = ((MAGIC ushr 8) and 0xFF).toByte()
        val b2 = ((MAGIC ushr 16) and 0xFF).toByte()
        val b3 = ((MAGIC ushr 24) and 0xFF).toByte()
        var base = 0L
        while (base <= limit) {
            val want = minOf(SCAN_CHUNK.toLong(), source.size - base).toInt()
            source.copyOut(base, chunk, 0, want)
            var i = 0
            while (i + 4 <= want && base + i <= limit) {
                if (chunk[i] == b0 && chunk[i + 1] == b1 && chunk[i + 2] == b2 && chunk[i + 3] == b3) {
                    yield(base + i)
                }
                i += 4
            }
            base += SCAN_CHUNK
        }
    }

    init {
        // Keeps the local MAGIC honest if FFProtector's ever changes.
        check(MAGIC == FFProtector.MAGIC) { "protector magic drifted" }
    }
}
