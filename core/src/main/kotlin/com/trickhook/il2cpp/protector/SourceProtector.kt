package com.trickhook.il2cpp.protector

import com.trickhook.il2cpp.io.ByteSource

/**
 * Runs [FFProtector] over a [ByteSource] instead of a `ByteArray`.
 *
 * [FFProtector] itself is untouched: it stays the single source of truth for the
 * cipher, the key recovery and the window permutation. This adapter solves two
 * things around it.
 *
 * **Memory.** The protector needs random read/write access to the bytes from 0 up
 * to the end of the section it owns, and nothing above that, so a library of any
 * size is handled by materialising just that prefix on the heap, handing it to
 * [FFProtector] with `inPlace = true`, and writing the recovered section back into
 * the source. For a 247 MB library whose protected section ends around 13 MB that
 * is a 13 MB array instead of 247 MB, and the remaining 234 MB are never copied.
 * The bytes outside the section are identical in the prefix and in the source, so
 * writing the section back reproduces exactly what a whole-array call would have.
 *
 * **Honesty.** A library can carry more than one protected section: Call of Duty
 * Mobile has three (`.rodata`, `.text` and a custom `il2cpp` section of about
 * 152 MB). [FFProtector.findDescriptor] returns the first one, so unpacking just
 * that one and treating the rest of the file as plaintext would let a dump read
 * ciphertext and print it as if it were real data. So every descriptor in the file
 * is enumerated, and any section that does not come out with a matching CRC32 -
 * including one we did not even attempt - is reported, so the caller can mark its
 * bytes unusable instead of trusting them.
 */
object SourceProtector {

    /** `FFProtector.MAGIC`, repeated here so FFProtector needs no new API. */
    private const val MAGIC = 0x12345678L

    private const val DESCRIPTOR_MIN_SIZE = 0x34L
    private const val NAME_FIELD = 0x04L
    private const val OFFSET_FIELD = 0x14L
    private const val SIZE_FIELD = 0x1CL
    private const val CHECKSUM_FIELD = 0x20L

    private const val MAX_NAME_LENGTH = 16

    /**
     * How much of the file the protector reads starting at the descriptor: the key
     * byte at +0x186 and a base64 key blob at +0x1a8 that is at most 64 characters.
     * 0x200 is comfortably past both, which keeps the blob scan bounded by its own
     * character limit rather than by the end of the staged buffer - that is what
     * makes the staged run behave exactly like a whole-file run.
     */
    private const val DESCRIPTOR_TAIL = 0x200L

    /**
     * The most we are willing to put on the heap to unpack one section. A phone with
     * a 512 MB Dalvik cap has no room for a 150 MB transient on top of the object
     * graph, so a section that big is reported as unrecovered rather than attempted.
     */
    private const val MAX_STAGED_BYTES = 64L shl 20

    private const val SCAN_CHUNK = 1 shl 20

    private const val MAX_DESCRIPTORS = 16

    /** One protected section and what became of it. */
    class Section(
        val name: String,
        val filePosition: Long,
        val start: Long,
        val endExclusive: Long,
        val expectedCrc: Long,
        /** True only when the section's CRC32 matched the descriptor after unpacking. */
        val recovered: Boolean,
        val attempted: Boolean,
        val note: String
    ) {
        val size: Long get() = endExclusive - start

        override fun toString(): String =
            "$name 0x${start.toString(16)}..0x${endExclusive.toString(16)} ($size bytes) $note"
    }

    class Result(
        val source: ByteSource,
        val sections: List<Section>,
        val stagedBytes: Long,
        val report: String
    ) {
        val detected: Boolean get() = sections.isNotEmpty()

        /** Sections whose plaintext we do not have. Anything read from them is garbage. */
        val unrecovered: List<Section> get() = sections.filter { !it.recovered }

        val allRecovered: Boolean get() = sections.isNotEmpty() && unrecovered.isEmpty()
    }

    fun unpack(source: ByteSource, enabled: Boolean = true): Result {
        val candidates = descriptors(source)
        if (candidates.isEmpty()) return Result(source, emptyList(), 0L, "")

        val sections = ArrayList<Section>(candidates.size)
        val lines = ArrayList<String>()
        var staged = 0L
        var unpackedOne = false

        for (candidate in candidates) {
            // FFProtector.findDescriptor always returns the first valid descriptor in
            // the buffer it is given, so only the first one can be driven through it.
            // The others are recorded, not guessed at: reporting a section as
            // unrecovered is correct, quietly reading its ciphertext is not.
            if (unpackedOne || !enabled) {
                sections += candidate.asUnattempted(
                    if (enabled) {
                        "secao protegida adicional; este desempacotador so processa a primeira"
                    } else {
                        "desempacotamento desativado"
                    }
                )
                continue
            }
            // The buffer only has to reach the end of the section: everything below
            // it that the protector touches lies inside the section, and everything
            // above it is never read.
            val length = candidate.endExclusive
            if (length > Int.MAX_VALUE.toLong() || length > MAX_STAGED_BYTES) {
                sections += candidate.asUnattempted(
                    "secao grande demais para desempacotar no telefone " +
                        "(precisaria de $length bytes no heap)"
                )
                continue
            }
            // Both packers seen so far keep their stub, and therefore the descriptor,
            // near the END of the file: 175 MB into Free Fire's 184 MB library and
            // 232 MB into Call of Duty's 248 MB one. Staging up to the descriptor
            // would mean copying almost the whole file. Instead the descriptor's own
            // 0x200 bytes are moved to the front of the staged buffer, which is both
            // the earliest position FFProtector can find and outside the section it
            // decrypts. That turns a 232 MB copy into a 13 MB one.
            if (candidate.start < DESCRIPTOR_TAIL) {
                sections += candidate.asUnattempted(
                    "secao comeca em 0x${candidate.start.toString(16)}, sem espaco para reposicionar o descritor"
                )
                continue
            }
            val buffer = ByteArray(length.toInt())
            source.copyOut(0L, buffer, 0, length.toInt())
            val descriptorAt = if (candidate.filePosition + DESCRIPTOR_TAIL <= length) {
                candidate.filePosition
            } else {
                source.copyOut(candidate.filePosition, buffer, 0, DESCRIPTOR_TAIL.toInt())
                0L
            }

            val result = FFProtector.tryUnpack(buffer, enabled = true, inPlace = true)
            val descriptor = result.descriptor
            if (!result.detected || descriptor == null ||
                descriptor.filePosition != descriptorAt ||
                descriptor.offset != candidate.start ||
                descriptor.offset + descriptor.size != candidate.endExclusive
            ) {
                sections += candidate.asUnattempted("FFProtector nao validou este descritor")
                continue
            }
            if (result.unpacked) {
                source.copyIn(
                    descriptor.offset,
                    buffer,
                    descriptor.offset.toInt(),
                    descriptor.size.toInt()
                )
            }
            staged = length
            unpackedOne = true
            lines += FFProtector.describe(result)
            sections += Section(
                name = descriptor.name,
                filePosition = descriptor.filePosition,
                start = descriptor.offset,
                endExclusive = descriptor.offset + descriptor.size,
                expectedCrc = descriptor.checksum,
                recovered = result.checksumVerified,
                attempted = true,
                note = if (result.checksumVerified) "CRC32 confere" else "CRC32 nao fecha"
            )
        }

        if (sections.size > 1) {
            lines += "  ${sections.size} secoes protegidas neste arquivo:"
            for (section in sections) lines += "    $section"
        }
        return Result(source, sections, staged, lines.joinToString("\n"))
    }

    private class Candidate(
        val name: String,
        val filePosition: Long,
        val start: Long,
        val endExclusive: Long,
        val checksum: Long
    ) {
        fun asUnattempted(note: String) = Section(
            name = name,
            filePosition = filePosition,
            start = start,
            endExclusive = endExclusive,
            expectedCrc = checksum,
            recovered = false,
            attempted = false,
            note = note
        )
    }

    /**
     * Every protector descriptor in the file, in scan order. The plausibility gate is
     * the same one [FFProtector.findDescriptor] applies, so this never invents a
     * section that FFProtector would have rejected.
     */
    private fun descriptors(source: ByteSource): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seen = HashSet<Long>()
        for (position in magicPositions(source)) {
            if (out.size >= MAX_DESCRIPTORS) break
            val name = sectionName(source, position + NAME_FIELD) ?: continue
            val offset = source.uint32At(position + OFFSET_FIELD)
            val size = source.uint32At(position + SIZE_FIELD)
            if (offset == 0L || size == 0L || offset + size > source.size) continue
            if (!seen.add(offset)) continue
            out += Candidate(name, position, offset, offset + size, source.uint32At(position + CHECKSUM_FIELD))
        }
        return out
    }

    /** The descriptor's section name: up to 16 printable ASCII bytes starting with a dot. */
    private fun sectionName(source: ByteSource, at: Long): String? {
        val builder = StringBuilder()
        for (k in 0 until MAX_NAME_LENGTH) {
            val index = at + k
            if (index >= source.size) return null
            val b = source.uint8At(index)
            if (b == 0) break
            if (b < 0x20 || b > 0x7E) return null
            builder.append(b.toChar())
        }
        if (builder.length < 2 || builder[0] != '.') return null
        return builder.toString()
    }

    /**
     * File offsets where the protector magic appears, in the same 4-byte-aligned
     * scan order FFProtector uses. Lazy and chunked so it costs a megabyte of heap
     * no matter how big the library is.
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
