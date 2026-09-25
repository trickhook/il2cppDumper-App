package com.trickhook.il2cpp.elf

import com.trickhook.il2cpp.io.BinaryReader

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

private const val EM_ARM = 40
private const val EM_AARCH64 = 183

private const val R_ARM_ABS32 = 2L
private const val R_AARCH64_ABS64 = 257L
private const val R_AARCH64_RELATIVE = 1027L

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

private fun ByteArray.uint16At(offset: Long): Int {
    val p = offset.toInt()
    return (this[p].toInt() and 0xFF) or ((this[p + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.putUInt32(offset: Long, value: Long) {
    val p = offset.toInt()
    this[p] = (value and 0xFF).toByte()
    this[p + 1] = ((value ushr 8) and 0xFF).toByte()
    this[p + 2] = ((value ushr 16) and 0xFF).toByte()
    this[p + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun ByteArray.putUInt64(offset: Long, value: Long) {
    putUInt32(offset, value and 0xFFFFFFFFL)
    putUInt32(offset + 4, value ushr 32)
}

private fun ByteArray.putWord(offset: Long, value: Long, wide: Boolean) {
    if (wide) putUInt64(offset, value) else putUInt32(offset, value and 0xFFFFFFFFL)
}

private fun truncateToWidth(value: Long, wide: Boolean): Long =
    if (wide) value else value and 0xFFFFFFFFL

class ElfImage private constructor(
    val data: ByteArray,
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

    val is32Bit: Boolean get() = !is64

    val pointerSize: Int get() = if (is64) 8 else 4

    fun mapVaToOffset(va: Long): Long = segments.offsetOf(va)

    fun mapOffsetToVa(offset: Long): Long = segments.addressOf(offset)

    fun rva(pointer: Long): Long = if (isDumped) pointer - imageBase else pointer

    fun reader(): BinaryReader = BinaryReader(data).apply { is32Bit = !is64 }

    fun hasDtInit(): Boolean = dynamic.any { it.tag == DT_INIT }

    fun sectionNamed(name: String): Section? = sections.firstOrNull { it.name == name }

    fun dynamicValue(tag: Long): Long? = dynamic.firstOrNull { it.tag == tag }?.value

    fun symbolNamed(name: String): ElfSymbol? = symbols.firstOrNull { it.name == name }

    fun applyRelocations() {
        if (isDumped) return
        runCatching { if (is64) applyRela() else applyRel() }
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
            data.putUInt32(target, symbols[symbolIndex].value and 0xFFFFFFFFL)
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
            data.putUInt64(target, value)
        }
    }

    companion object {

        fun parse(data: ByteArray): ElfImage = build(data, 0L, false)

        fun parseDump(data: ByteArray, imageBase: Long): ElfImage = build(data, imageBase, true)

        private fun build(data: ByteArray, imageBase: Long, dumped: Boolean): ElfImage {
            require(data.size > 64) { "buffer too small to hold an ELF header" }
            require(
                data[0].toInt() == 0x7F && data[1].toInt() == 'E'.code &&
                    data[2].toInt() == 'L'.code && data[3].toInt() == 'F'.code
            ) { "not an ELF image" }

            val is64 = data[EI_CLASS].toInt() == ELFCLASS64
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
            data: ByteArray,
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
            data: ByteArray,
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
                        info = reader.data[(at + 4).toInt()].toInt() and 0xFF,
                        shndx = reader.data.uint16At(at + 6)
                    )
                } else {
                    ElfSymbol(
                        name = name,
                        value = reader.uint32At(at + 4),
                        size = reader.uint32At(at + 8),
                        info = reader.data[(at + 12).toInt()].toInt() and 0xFF,
                        shndx = reader.data.uint16At(at + 14)
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
