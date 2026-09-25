package com.trickhook.il2cpp.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import java.util.Arrays

data class SearchSection(
    val offset: Long,
    val offsetEnd: Long,
    val address: Long,
    val addressEnd: Long
)

class SectionHelper(
    private val elf: ElfImage,
    private val version: Double,
    private val methodCount: Int,
    private val typeDefinitionsCount: Int,
    private val metadataUsagesCount: Long,
    private val imageCount: Int,
    val exec: List<SearchSection>,
    val data: List<SearchSection>,
    val bss: List<SearchSection>
) {

    private val reader = elf.reader()
    private val pointerSize = elf.pointerSize.toLong()
    private val fileLength = elf.data.size.toLong()

    var pointerInExec: Boolean = false
        private set

    fun findCodeRegistration(): Long {
        if (version < 24.2) return findCodeRegistrationOld()
        val inExec = findCodeRegistration2019(exec)
        if (inExec != 0L) {
            pointerInExec = true
            return inExec
        }
        return findCodeRegistration2019(data)
    }

    fun findMetadataRegistration(): Long = when {
        version < 19.0 -> 0L
        version >= 27.0 -> findMetadataRegistrationV21()
        else -> findMetadataRegistrationOld()
    }

    private fun findCodeRegistration2019(sections: List<SearchSection>): Long {
        for (section in sections) {
            for (at in occurrencesOf(MODULE_MARKER, section.offset, section.offsetEnd)) {
                val moduleNameAddress = at - section.offset + section.address
                for (moduleAddress in referencesTo(moduleNameAddress)) {
                    for (slotAddress in referencesTo(moduleAddress)) {
                        val found = if (version >= 27.0) {
                            tableFromSlotV27(slotAddress)
                        } else {
                            tableFromSlotOld(slotAddress)
                        }
                        if (found != 0L) return found
                    }
                }
            }
        }
        return 0L
    }

    private fun tableFromSlotV27(slotAddress: Long): Long {
        if (imageCount <= 0) return 0L
        val candidates = LongArray(imageCount) { slotAddress - it * pointerSize }
        val hits = referencesToAny(candidates)
        for (i in imageCount - 1 downTo 0) {
            val tables = hits[candidates[i]] ?: continue
            for (tableAddress in tables) {
                val countAt = elf.mapVaToOffset(tableAddress - pointerSize)
                if (countAt < 0L || countAt + pointerSize > fileLength) continue
                if (reader.pointerAt(countAt) != imageCount.toLong()) continue
                return tableAddress - pointerSize * if (version >= 29.0) 14 else 13
            }
        }
        return 0L
    }

    private fun tableFromSlotOld(slotAddress: Long): Long {
        for (i in 0 until imageCount) {
            val tableAddress = referencesTo(slotAddress - i * pointerSize).firstOrNull() ?: continue
            return tableAddress - pointerSize * 13
        }
        return 0L
    }

    private fun findCodeRegistrationOld(): Long {
        for (section in data) {
            var position = section.offset
            val end = minOf(section.offsetEnd, fileLength) - pointerSize * 2
            while (position < end) {
                if (reader.pointerAt(position) == methodCount.toLong()) {
                    val table = elf.mapVaToOffset(reader.pointerAt(position + pointerSize))
                    if (isDataOffset(table) && allPointersInside(table, methodCount.toLong(), exec)) {
                        return position - section.offset + section.address
                    }
                }
                position += pointerSize
            }
        }
        return 0L
    }

    private fun findMetadataRegistrationOld(): Long {
        for (section in data) {
            var position = section.offset
            val end = minOf(section.offsetEnd, fileLength) - pointerSize
            while (position < end) {
                if (reader.pointerAt(position) == typeDefinitionsCount.toLong() &&
                    position + pointerSize * 4 <= fileLength
                ) {
                    val table = elf.mapVaToOffset(reader.pointerAt(position + pointerSize * 3))
                    if (isDataOffset(table) && allPointersInside(table, metadataUsagesCount, bss)) {
                        return position - pointerSize * 12 - section.offset + section.address
                    }
                }
                position += pointerSize
            }
        }
        return 0L
    }

    private fun findMetadataRegistrationV21(): Long {
        val wanted = typeDefinitionsCount.toLong()
        val ranges = if (pointerInExec) exec else data
        for (section in data) {
            var position = section.offset
            val end = minOf(section.offsetEnd, fileLength) - pointerSize
            while (position < end) {
                if (reader.pointerAt(position) == wanted &&
                    position + pointerSize * 4 <= fileLength &&
                    reader.pointerAt(position + pointerSize * 2) == wanted
                ) {
                    val table = elf.mapVaToOffset(reader.pointerAt(position + pointerSize * 3))
                    if (isDataOffset(table) && allPointersInside(table, wanted, ranges)) {
                        return position - pointerSize * 10 - section.offset + section.address
                    }
                }
                position += pointerSize
            }
        }
        return 0L
    }

    private fun isDataOffset(offset: Long): Boolean =
        offset >= 0L && data.any { offset >= it.offset && offset <= it.offsetEnd }

    private fun allPointersInside(tableOffset: Long, count: Long, ranges: List<SearchSection>): Boolean {
        if (count <= 0L || ranges.isEmpty()) return false
        if (tableOffset < 0L || tableOffset + count * pointerSize > fileLength) return false
        var at = tableOffset
        repeat(count.toInt()) {
            val pointer = reader.pointerAt(at)
            if (ranges.none { pointer >= it.address && pointer <= it.addressEnd }) return false
            at += pointerSize
        }
        return true
    }

    private fun referencesTo(address: Long): List<Long> {
        val hits = ArrayList<Long>()
        for (section in data) {
            var position = section.offset
            val end = minOf(section.offsetEnd, fileLength) - pointerSize
            while (position < end) {
                if (reader.pointerAt(position) == address) {
                    hits += position - section.offset + section.address
                }
                position += pointerSize
            }
        }
        return hits
    }

    private fun referencesToAny(addresses: LongArray): Map<Long, List<Long>> {
        val sorted = addresses.copyOf().also(Arrays::sort)
        val hits = HashMap<Long, MutableList<Long>>()
        for (section in data) {
            var position = section.offset
            val end = minOf(section.offsetEnd, fileLength) - pointerSize
            while (position < end) {
                val pointer = reader.pointerAt(position)
                if (Arrays.binarySearch(sorted, pointer) >= 0) {
                    hits.getOrPut(pointer) { ArrayList() } += position - section.offset + section.address
                }
                position += pointerSize
            }
        }
        return hits
    }

    private fun occurrencesOf(pattern: ByteArray, from: Long, to: Long): List<Long> {
        val bytes = elf.data
        val last = minOf(to, fileLength).toInt() - pattern.size
        val first = pattern[0]
        val hits = ArrayList<Long>()
        var at = maxOf(from, 0L).toInt()
        while (at <= last) {
            if (bytes[at] == first && matchesAt(bytes, at, pattern)) hits += at.toLong()
            at++
        }
        return hits
    }

    private fun matchesAt(bytes: ByteArray, at: Int, pattern: ByteArray): Boolean {
        for (i in 1 until pattern.size) if (bytes[at + i] != pattern[i]) return false
        return true
    }

    companion object {

        private val MODULE_MARKER = "mscorlib.dll ".toByteArray(Charsets.US_ASCII)

        private val EXECUTABLE_FLAGS = setOf(1, 3, 5, 7)
        private val WRITABLE_FLAGS = setOf(2, 4, 6)

        fun forImage(
            elf: ElfImage,
            version: Double,
            methodCount: Int,
            typeDefinitionsCount: Int,
            metadataUsagesCount: Long,
            imageCount: Int
        ): SectionHelper {
            val exec = ArrayList<SearchSection>()
            val data = ArrayList<SearchSection>()
            for (segment in elf.segments) {
                if (segment.memSize == 0L) continue
                val section = SearchSection(
                    offset = segment.offset,
                    offsetEnd = segment.offset + segment.fileSize,
                    address = segment.vaddr,
                    addressEnd = segment.vaddr + segment.memSize
                )
                when (segment.flags) {
                    in EXECUTABLE_FLAGS -> exec += section
                    in WRITABLE_FLAGS -> data += section
                }
            }
            return SectionHelper(
                elf, version, methodCount, typeDefinitionsCount, metadataUsagesCount, imageCount,
                exec, data, data
            )
        }
    }
}
