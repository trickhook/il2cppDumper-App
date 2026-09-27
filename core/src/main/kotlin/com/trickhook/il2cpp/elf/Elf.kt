package com.trickhook.il2cpp.elf

import com.trickhook.il2cpp.io.ArrayByteSource
import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.io.ByteSource

data class Segment(
    val type: Int,
    val offset: Long,
    val vaddr: Long,
    val fileSize: Long,
    val memSize: Long,
    val flags: Int
)

data class Section(
    val name: String,
    val type: Int,
    val flags: Long,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val link: Int,
    val entSize: Long
)

data class DynamicEntry(val tag: Long, val value: Long)

data class ElfSymbol(
    val name: String,
    val value: Long,
    val size: Long,
    val info: Int,
    val shndx: Int
)

private const val EI_CLASS = 4
private const val ELFCLASS64 = 2

private const val PT_LOAD = 1
private const val PT_DYNAMIC = 2

private const val SHT_DYNSYM = 11

private const val DT_PLTGOT = 3L
private const val DT_HASH = 4L
private const val DT_STRTAB = 5L
private const val DT_SYMTAB = 6L
private const val DT_RELA = 7L
private const val DT_RELASZ = 8L
private const val DT_SYMENT = 11L
private const val DT_INIT = 12L
private const val DT_FINI = 13L
private const val DT_REL = 17L
private const val DT_RELSZ = 18L
private const val DT_JMPREL = 23L
private const val DT_INIT_ARRAY = 25L
private const val DT_FINI_ARRAY = 26L
private const val DT_GNU_HASH = 0x6ffffef5L
private const val DT_ANDROID_REL = 0x6000000FL
private const val DT_ANDROID_RELSZ = 0x60000010L
private const val DT_ANDROID_RELA = 0x60000011L
private const val DT_ANDROID_RELASZ = 0x60000012L

private const val EM_ARM = 40
private const val EM_AARCH64 = 183

private const val R_ARM_ABS32 = 2L
private const val R_AARCH64_ABS64 = 257L
private const val R_AARCH64_RELATIVE = 1027L

// Android packed relocations ("APS2"). Group flags, from bionic's linker.
private const val APS2_MAGIC = 0x32535041 // "APS2" little endian
private const val RELOCATION_GROUPED_BY_INFO = 1L
private const val RELOCATION_GROUPED_BY_OFFSET_DELTA = 2L
private const val RELOCATION_GROUPED_BY_ADDEND = 4L
private const val RELOCATION_GROUP_HAS_ADDEND = 8L

private const val PHDR32_SIZE = 32
private const val PHDR64_SIZE = 56
private const val SHDR32_SIZE = 40
private const val SHDR64_SIZE = 64
private const val SYM32_SIZE = 16L
private const val SYM64_SIZE = 24L
private const val DYN32_SIZE = 8L
private const val DYN64_SIZE = 16L
private const val REL32_SIZE = 8L
private const val RELA64_SIZE = 24L

private val RebasedTags = setOf(
    DT_PLTGOT, DT_HASH, DT_STRTAB, DT_SYMTAB, DT_RELA,
    DT_INIT, DT_FINI, DT_REL, DT_JMPREL, DT_INIT_ARRAY, DT_FINI_ARRAY
)

private fun List<Segment>.offsetOf(va: Long): Long {
    for (segment in this) {
        if (segment.type != PT_LOAD) continue
        if (va >= segment.vaddr && va < segment.vaddr + segment.memSize) {
            return va - segment.vaddr + segment.offset
        }
    }
    return -1L
}

private fun List<Segment>.addressOf(offset: Long): Long {
    for (segment in this) {
        if (segment.type != PT_LOAD) continue
        if (offset >= segment.offset && offset < segment.offset + segment.fileSize) {
            return offset - segment.offset + segment.vaddr
        }
    }
    return -1L
}

private fun ByteSource.putUInt32(offset: Long, value: Long) {
    putInt32(offset, (value and 0xFFFFFFFFL).toInt())
}

private fun ByteSource.putUInt64(offset: Long, value: Long) {
    putUInt32(offset, value and 0xFFFFFFFFL)
    putUInt32(offset + 4, value ushr 32)
}

private fun ByteSource.putWord(offset: Long, value: Long, wide: Boolean) {
    if (wide) putUInt64(offset, value) else putUInt32(offset, value and 0xFFFFFFFFL)
}

private const val R_ARM_RELATIVE = 23L

private const val MAX_PACKED_RELOCATIONS = 40_000_000L

/**
 * sleb128 reader over the packed relocation blob. Android's encoder emits signed
 * LEB128 for every field, including the counts.
 */
private class Sleb128(private val data: ByteArray, private var at: Int) {
    fun next(): Long {
        var result = 0L
        var shift = 0
        var byte: Int
        do {
            byte = data[at++].toInt()
            // Guard the shift explicitly: Kotlin's shl only uses the low 6 bits of
            // the count, so a 10-byte sleb128 would wrap around and fold bits back
            // into the low word. bionic accumulates in a size_t and lets the high
            // bits fall off the end, which is what this reproduces.
            if (shift < 64) result = result or ((byte.toLong() and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        if (shift < 64 && byte and 0x40 != 0) result = result or (-1L shl shift)
        return result
    }
}

private fun truncateToWidth(value: Long, wide: Boolean): Long =
    if (wide) value else value and 0xFFFFFFFFL

class ElfImage private constructor(
    val source: ByteSource,
    val is64: Boolean,
    val machine: Int,
    val entry: Long,
    val segments: List<Segment>,
    val sections: List<Section>,
    val dynamic: List<DynamicEntry>,
    val symbols: List<ElfSymbol>,
    baseAddress: Long,
    dumped: Boolean
) {
    var imageBase: Long = baseAddress
    var isDumped: Boolean = dumped

    /**
     * File ranges inside [source] that are known not to be plaintext. Set by
     * whoever unpacked the image; consumers must consult it before trusting data
     * they read, so a packed section can never be emitted as if it were real.
     */
    var unreliable: UnreliableRanges = UnreliableRanges.NONE

    val is32Bit: Boolean get() = !is64

    val pointerSize: Int get() = if (is64) 8 else 4

    fun mapVaToOffset(va: Long): Long = segments.offsetOf(va)

    fun mapOffsetToVa(offset: Long): Long = segments.addressOf(offset)

    fun rva(pointer: Long): Long = if (isDumped) pointer - imageBase else pointer

    fun reader(): BinaryReader = BinaryReader(source).apply { is32Bit = !is64 }

    fun hasDtInit(): Boolean = dynamic.any { it.tag == DT_INIT }

    fun sectionNamed(name: String): Section? = sections.firstOrNull { it.name == name }

    fun dynamicValue(tag: Long): Long? = dynamic.firstOrNull { it.tag == tag }?.value

    fun symbolNamed(name: String): ElfSymbol? = symbols.firstOrNull { it.name == name }

    /**
     * Relocations actually applied by the last [applyRelocations] call, for the log.
     * `format` is "" when the image needed none.
     */
    var relocationFormat: String = ""
        private set
    var relocationsApplied: Int = 0
        private set
    var relocationsUnsupported: Int = 0
        private set

    fun applyRelocations() {
        if (isDumped) return
        relocationFormat = ""
        relocationsApplied = 0
        relocationsUnsupported = 0
        // A library may carry either the classic tables or Android's packed ones.
        // Newer NDK builds ship only the packed form, and an image whose packed
        // relocations are ignored comes out with every pointer in .data.rel.ro at
        // zero, which looks exactly like "registrations not found".
        runCatching { if (is64) applyRela() else applyRel() }
        runCatching { applyAndroidPacked() }
    }

    /**
     * Applies DT_ANDROID_RELA / DT_ANDROID_REL, the "APS2" packed encoding: a
     * stream of sleb128 groups where the offset, the info and the addend may each
     * be shared across a whole group. Decoded exactly as bionic's
     * `packed_reloc_iterator` does, because any drift here silently writes the
     * wrong pointers rather than failing.
     */
    private fun applyAndroidPacked() {
        val wide = is64
        val tableVa = dynamicValue(if (wide) DT_ANDROID_RELA else DT_ANDROID_REL) ?: return
        val tableSize = dynamicValue(if (wide) DT_ANDROID_RELASZ else DT_ANDROID_RELSZ) ?: return
        if (tableSize <= 8L || tableSize > Int.MAX_VALUE.toLong()) return
        val tableOffset = mapVaToOffset(tableVa)
        if (tableOffset < 0L || tableOffset + tableSize > source.size) return
        if (source.int32At(tableOffset) != APS2_MAGIC) return

        val packed = source.slice(tableOffset, tableSize.toInt())
        val stream = Sleb128(packed, 4)
        val count = stream.next()
        if (count <= 0L || count > MAX_PACKED_RELOCATIONS) return

        var offset = stream.next()
        var info = 0L
        var addend = 0L
        var groupSize = 0L
        var groupFlags = 0L
        var groupOffsetDelta = 0L
        var inGroup = 0L
        var applied = 0
        var unsupported = 0

        var index = 0L
        while (index < count) {
            if (inGroup == groupSize) {
                groupSize = stream.next()
                groupFlags = stream.next()
                if (groupSize <= 0L) break
                if (groupFlags and RELOCATION_GROUPED_BY_OFFSET_DELTA != 0L) {
                    groupOffsetDelta = stream.next()
                }
                if (groupFlags and RELOCATION_GROUPED_BY_INFO != 0L) info = stream.next()
                if (groupFlags and RELOCATION_GROUP_HAS_ADDEND != 0L &&
                    groupFlags and RELOCATION_GROUPED_BY_ADDEND != 0L
                ) {
                    addend += stream.next()
                } else if (groupFlags and RELOCATION_GROUP_HAS_ADDEND == 0L) {
                    addend = 0L
                }
                inGroup = 0L
            }

            offset += if (groupFlags and RELOCATION_GROUPED_BY_OFFSET_DELTA != 0L) {
                groupOffsetDelta
            } else {
                stream.next()
            }
            if (groupFlags and RELOCATION_GROUPED_BY_INFO == 0L) info = stream.next()
            if (groupFlags and RELOCATION_GROUP_HAS_ADDEND != 0L &&
                groupFlags and RELOCATION_GROUPED_BY_ADDEND == 0L
            ) {
                addend += stream.next()
            }
            index++
            inGroup++

            if (applyOne(offset, info, addend, wide)) applied++ else unsupported++
        }

        if (applied > 0 || unsupported > 0) {
            relocationFormat = if (wide) "DT_ANDROID_RELA (APS2)" else "DT_ANDROID_REL (APS2)"
            relocationsApplied = applied
            relocationsUnsupported = unsupported
        }
    }

    /** Writes one decoded relocation. Returns false when the type is not handled. */
    private fun applyOne(virtualAddress: Long, info: Long, addend: Long, wide: Boolean): Boolean {
        val type = info and if (wide) 0xFFFFFFFFL else 0xFFL
        val symbolIndex = if (wide) (info ushr 32).toInt() else (info ushr 8).toInt()
        val target = mapVaToOffset(virtualAddress)
        val width = if (wide) 8L else 4L
        if (target < 0L || target + width > source.size) return false
        if (wide) {
            val value = when (type) {
                R_AARCH64_RELATIVE -> addend
                R_AARCH64_ABS64 ->
                    if (symbolIndex in symbols.indices) symbols[symbolIndex].value + addend else null
                else -> null
            } ?: return false
            source.putUInt64(target, value)
        } else {
            val value = when (type) {
                R_ARM_ABS32 ->
                    if (symbolIndex in symbols.indices) {
                        (symbols[symbolIndex].value + addend) and 0xFFFFFFFFL
                    } else {
                        null
                    }
                // R_ARM_RELATIVE with a zero load base leaves the word as linked.
                R_ARM_RELATIVE -> return true
                else -> null
            } ?: return false
            source.putUInt32(target, value)
        }
        return true
    }

    private fun applyRel() {
        if (machine != EM_ARM) return
        val tableVa = dynamicValue(DT_REL) ?: return
        val tableSize = dynamicValue(DT_RELSZ) ?: return
        val tableOffset = mapVaToOffset(tableVa)
        if (tableOffset < 0) return
        val reader = reader()
        for (i in 0 until tableSize / REL32_SIZE) {
            val at = tableOffset + i * REL32_SIZE
            if (at + REL32_SIZE > reader.size) break
            val info = reader.uint32At(at + 4)
            if (info and 0xFF != R_ARM_ABS32) continue
            val symbolIndex = (info ushr 8).toInt()
            if (symbolIndex !in symbols.indices) continue
            val target = mapVaToOffset(reader.uint32At(at))
            if (target < 0 || target + 4 > reader.size) continue
            source.putUInt32(target, symbols[symbolIndex].value and 0xFFFFFFFFL)
        }
    }

    private fun applyRela() {
        if (machine != EM_AARCH64) return
        val tableVa = dynamicValue(DT_RELA) ?: return
        val tableSize = dynamicValue(DT_RELASZ) ?: return
        val tableOffset = mapVaToOffset(tableVa)
        if (tableOffset < 0) return
        val reader = reader()
        for (i in 0 until tableSize / RELA64_SIZE) {
            val at = tableOffset + i * RELA64_SIZE
            if (at + RELA64_SIZE > reader.size) break
            val info = reader.int64At(at + 8)
            val addend = reader.int64At(at + 16)
            val symbolIndex = (info ushr 32).toInt()
            val value = when (info and 0xFFFFFFFFL) {
                R_AARCH64_RELATIVE -> addend
                R_AARCH64_ABS64 ->
                    if (symbolIndex in symbols.indices) symbols[symbolIndex].value + addend else null
                else -> null
            } ?: continue
            val target = mapVaToOffset(reader.int64At(at))
            if (target < 0 || target + 8 > reader.size) continue
            source.putUInt64(target, value)
        }
    }

    companion object {

        fun parse(data: ByteArray): ElfImage = build(ArrayByteSource(data), 0L, false)

        fun parseDump(data: ByteArray, imageBase: Long): ElfImage =
            build(ArrayByteSource(data), imageBase, true)

        fun parse(source: ByteSource): ElfImage = build(source, 0L, false)

        fun parseDump(source: ByteSource, imageBase: Long): ElfImage = build(source, imageBase, true)

        /** True when [source] starts with an ELF magic of the expected class. */
        fun looksLikeElf(source: ByteSource): Boolean =
            source.size > 64 &&
                source.uint8At(0) == 0x7F && source.uint8At(1) == 'E'.code &&
                source.uint8At(2) == 'L'.code && source.uint8At(3) == 'F'.code

        private fun build(data: ByteSource, imageBase: Long, dumped: Boolean): ElfImage {
            require(data.size > 64) { "buffer too small to hold an ELF header" }
            require(looksLikeElf(data)) { "not an ELF image" }

            val is64 = data.uint8At(EI_CLASS.toLong()) == ELFCLASS64
            val reader = BinaryReader(data).apply { is32Bit = !is64 }

            reader.seek(18)
            val machine = reader.readUInt16()

            reader.seek(24)
            val entry = reader.readPointer()
            val programHeaderOffset = reader.readPointer()
            val sectionHeaderOffset = reader.readPointer()
            reader.readUInt32()
            reader.readUInt16()
            val programEntrySize = reader.readUInt16()
                .let { if (it > 0) it else if (is64) PHDR64_SIZE else PHDR32_SIZE }
            val programCount = reader.readUInt16()
            val sectionEntrySize = reader.readUInt16()
                .let { if (it > 0) it else if (is64) SHDR64_SIZE else SHDR32_SIZE }
            val sectionCount = reader.readUInt16()
            val sectionNameIndex = reader.readUInt16()

            val loadedSegments =
                readSegments(reader, programHeaderOffset, programEntrySize, programCount, is64)
            val segments = if (dumped) {
                rebaseSegments(data, loadedSegments, programHeaderOffset, programEntrySize, imageBase, is64)
            } else {
                loadedSegments
            }

            val ptDynamic = segments.firstOrNull { it.type == PT_DYNAMIC }
            val loadedDynamic = readDynamic(reader, ptDynamic, is64)
            val dynamic = if (dumped) {
                rebaseDynamic(data, loadedDynamic, ptDynamic, imageBase, is64)
            } else {
                loadedDynamic
            }

            val sections = readSections(
                reader, sectionHeaderOffset, sectionEntrySize, sectionCount, sectionNameIndex, is64
            )
            val symbols = readSymbols(reader, is64, segments, sections, dynamic)

            return ElfImage(
                data, is64, machine, entry, segments, sections, dynamic, symbols, imageBase, dumped
            )
        }

        private fun readSegments(
            reader: BinaryReader,
            tableOffset: Long,
            entrySize: Int,
            count: Int,
            is64: Boolean
        ): List<Segment> {
            val result = ArrayList<Segment>(count)
            for (i in 0 until count) {
                val at = tableOffset + i.toLong() * entrySize
                if (at < 0 || at + entrySize > reader.size) break
                result += if (is64) {
                    Segment(
                        type = reader.int32At(at),
                        offset = reader.int64At(at + 8),
                        vaddr = reader.int64At(at + 16),
                        fileSize = reader.int64At(at + 32),
                        memSize = reader.int64At(at + 40),
                        flags = reader.int32At(at + 4)
                    )
                } else {
                    Segment(
                        type = reader.int32At(at),
                        offset = reader.uint32At(at + 4),
                        vaddr = reader.uint32At(at + 8),
                        fileSize = reader.uint32At(at + 16),
                        memSize = reader.uint32At(at + 20),
                        flags = reader.int32At(at + 24)
                    )
                }
            }
            return result
        }

        private fun rebaseSegments(
            data: ByteSource,
            segments: List<Segment>,
            tableOffset: Long,
            entrySize: Int,
            imageBase: Long,
            is64: Boolean
        ): List<Segment> {
            val wordSize = if (is64) 8 else 4
            return segments.mapIndexed { index, segment ->
                val rebased = segment.copy(
                    offset = segment.vaddr,
                    vaddr = truncateToWidth(segment.vaddr + imageBase, is64),
                    fileSize = segment.memSize
                )
                val at = tableOffset + index.toLong() * entrySize + wordSize
                if (at >= 0 && at + wordSize * 4 <= data.size) {
                    data.putWord(at, rebased.offset, is64)
                    data.putWord(at + wordSize, rebased.vaddr, is64)
                    data.putWord(at + wordSize * 3, rebased.fileSize, is64)
                }
                rebased
            }
        }

        private fun readDynamic(
            reader: BinaryReader,
            ptDynamic: Segment?,
            is64: Boolean
        ): List<DynamicEntry> {
            if (ptDynamic == null) return emptyList()
            val entrySize = if (is64) DYN64_SIZE else DYN32_SIZE
            val count = ptDynamic.fileSize / entrySize
            val result = ArrayList<DynamicEntry>(count.coerceIn(0L, 4096L).toInt())
            for (i in 0 until count) {
                val at = ptDynamic.offset + i * entrySize
                if (at < 0 || at + entrySize > reader.size) break
                result += if (is64) {
                    DynamicEntry(reader.int64At(at), reader.int64At(at + 8))
                } else {
                    DynamicEntry(reader.int32At(at).toLong(), reader.uint32At(at + 4))
                }
            }
            return result
        }

        private fun rebaseDynamic(
            data: ByteSource,
            dynamic: List<DynamicEntry>,
            ptDynamic: Segment?,
            imageBase: Long,
            is64: Boolean
        ): List<DynamicEntry> {
            if (ptDynamic == null) return dynamic
            val entrySize = if (is64) DYN64_SIZE else DYN32_SIZE
            val wordSize = if (is64) 8L else 4L
            return dynamic.mapIndexed { index, entry ->
                if (entry.tag !in RebasedTags) return@mapIndexed entry
                val rebased = entry.copy(value = truncateToWidth(entry.value + imageBase, is64))
                val at = ptDynamic.offset + index * entrySize + wordSize
                if (at >= 0 && at + wordSize <= data.size) data.putWord(at, rebased.value, is64)
                rebased
            }
        }

        private fun readSections(
            reader: BinaryReader,
            tableOffset: Long,
            entrySize: Int,
            count: Int,
            nameIndex: Int,
            is64: Boolean
        ): List<Section> = runCatching {
            if (tableOffset <= 0L || count <= 0 || nameIndex >= count) return emptyList()
            if (tableOffset + count.toLong() * entrySize > reader.size) return emptyList()
            val namesAt = tableOffset + nameIndex.toLong() * entrySize
            val stringTable =
                if (is64) reader.int64At(namesAt + 24) else reader.uint32At(namesAt + 16)
            if (stringTable <= 0L || stringTable >= reader.size) return emptyList()
            val result = ArrayList<Section>(count)
            for (i in 0 until count) {
                val at = tableOffset + i.toLong() * entrySize
                val nameAt = stringTable + reader.uint32At(at)
                val name = if (nameAt in 0 until reader.size) reader.readStringToNull(nameAt) else ""
                result += if (is64) {
                    Section(
                        name = name,
                        type = reader.int32At(at + 4),
                        flags = reader.int64At(at + 8),
                        addr = reader.int64At(at + 16),
                        offset = reader.int64At(at + 24),
                        size = reader.int64At(at + 32),
                        link = reader.int32At(at + 40),
                        entSize = reader.int64At(at + 56)
                    )
                } else {
                    Section(
                        name = name,
                        type = reader.int32At(at + 4),
                        flags = reader.uint32At(at + 8),
                        addr = reader.uint32At(at + 12),
                        offset = reader.uint32At(at + 16),
                        size = reader.uint32At(at + 20),
                        link = reader.int32At(at + 24),
                        entSize = reader.uint32At(at + 36)
                    )
                }
            }
            if (result.none { it.name == ".text" }) emptyList() else result
        }.getOrDefault(emptyList())

        private fun readSymbols(
            reader: BinaryReader,
            is64: Boolean,
            segments: List<Segment>,
            sections: List<Section>,
            dynamic: List<DynamicEntry>
        ): List<ElfSymbol> = runCatching {
            val defaultEntrySize = if (is64) SYM64_SIZE else SYM32_SIZE
            var tableOffset = -1L
            var stringOffset = -1L
            var entrySize = defaultEntrySize
            var count = 0L

            val dynsym = sections.firstOrNull { it.type == SHT_DYNSYM }
            if (dynsym != null && dynsym.entSize > 0L && dynsym.link in sections.indices) {
                tableOffset = dynsym.offset
                stringOffset = sections[dynsym.link].offset
                entrySize = dynsym.entSize
                count = dynsym.size / dynsym.entSize
            }

            if (tableOffset < 0L || stringOffset < 0L || count <= 0L) {
                val symbolTable = dynamic.firstOrNull { it.tag == DT_SYMTAB } ?: return emptyList()
                val stringTable = dynamic.firstOrNull { it.tag == DT_STRTAB } ?: return emptyList()
                tableOffset = segments.offsetOf(symbolTable.value)
                stringOffset = segments.offsetOf(stringTable.value)
                entrySize = dynamic.firstOrNull { it.tag == DT_SYMENT }
                    ?.value?.takeIf { it > 0L } ?: defaultEntrySize
                count = symbolCount(reader, segments, dynamic, is64)
            }

            if (tableOffset < 0L || stringOffset < 0L || count <= 0L) return emptyList()
            val total = minOf(count, (reader.size - tableOffset) / entrySize)
            if (total <= 0L) return emptyList()

            val result = ArrayList<ElfSymbol>(total.toInt())
            for (i in 0 until total) {
                val at = tableOffset + i * entrySize
                val nameAt = stringOffset + reader.uint32At(at)
                val name = if (nameAt in 0 until reader.size) reader.readStringToNull(nameAt) else ""
                result += if (is64) {
                    ElfSymbol(
                        name = name,
                        value = reader.int64At(at + 8),
                        size = reader.int64At(at + 16),
                        info = reader.uint8At(at + 4),
                        shndx = reader.uint16At(at + 6)
                    )
                } else {
                    ElfSymbol(
                        name = name,
                        value = reader.uint32At(at + 4),
                        size = reader.uint32At(at + 8),
                        info = reader.uint8At(at + 12),
                        shndx = reader.uint16At(at + 14)
                    )
                }
            }
            result
        }.getOrDefault(emptyList())

        private fun symbolCount(
            reader: BinaryReader,
            segments: List<Segment>,
            dynamic: List<DynamicEntry>,
            is64: Boolean
        ): Long {
            val hash = dynamic.firstOrNull { it.tag == DT_HASH }
            if (hash != null) {
                val at = segments.offsetOf(hash.value)
                if (at >= 0L && at + 8 <= reader.size) return reader.uint32At(at + 4)
            }
            val gnuHash = dynamic.firstOrNull { it.tag == DT_GNU_HASH } ?: return 0L
            val at = segments.offsetOf(gnuHash.value)
            if (at < 0L || at + 16 > reader.size) return 0L
            val buckets = reader.uint32At(at)
            val firstSymbol = reader.uint32At(at + 4)
            val bloomSize = reader.uint32At(at + 8)
            val bucketsAt = at + 16 + (if (is64) 8L else 4L) * bloomSize
            if (bucketsAt < 0L || bucketsAt + buckets * 4 > reader.size) return 0L
            var last = 0L
            for (i in 0 until buckets) {
                val bucket = reader.uint32At(bucketsAt + i * 4)
                if (bucket > last) last = bucket
            }
            if (last < firstSymbol) return firstSymbol
            var cursor = bucketsAt + buckets * 4 + (last - firstSymbol) * 4
            while (cursor >= 0L && cursor + 4 <= reader.size) {
                val chain = reader.uint32At(cursor)
                cursor += 4
                last++
                if (chain and 1L != 0L) break
            }
            return last
        }
    }
}
