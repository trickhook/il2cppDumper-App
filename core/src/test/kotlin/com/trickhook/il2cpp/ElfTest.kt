package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EM_ARM = 40
private const val EM_AARCH64 = 183

private const val DT_INIT = 12L
private const val DT_STRTAB = 5L
private const val DT_SYMTAB = 6L
private const val DT_INIT_ARRAY = 25L

private const val ARM32_PACKED = "C:/Users/danie/Desktop/dump/libil2cpp.so"
private const val ARM32_DUMP = "C:/Users/danie/Desktop/dump/libil2cpp_memdump.so"
private const val ARM64_PACKED = "C:/Users/danie/Desktop/dump/arm64/libil2cpp.so"
private const val ARM64_DUMP = "C:/Users/danie/Desktop/dump/arm64/libil2cpp_memdump.so"

private const val ARM32_PACKED_SIZE = 175655728L
private const val ARM32_DUMP_SIZE = 176431104L
private const val ARM64_PACKED_SIZE = 184044016L
private const val ARM64_DUMP_SIZE = 186552320L

private const val ARM64_DUMP_BASE = 0x74e2814000L
private const val ARM32_DUMP_BASE = 0x70000000L

private fun load(path: String, expectedSize: Long): ByteArray {
    val file = File(path)
    assertTrue(file.isFile, "missing fixture $path")
    assertEquals(expectedSize, file.length(), "unexpected size for fixture $path")
    return file.readBytes()
}

private fun arm32Packed() = load(ARM32_PACKED, ARM32_PACKED_SIZE)

private fun arm32Dump() = load(ARM32_DUMP, ARM32_DUMP_SIZE)

private fun arm64Packed() = load(ARM64_PACKED, ARM64_PACKED_SIZE)

private fun arm64Dump() = load(ARM64_DUMP, ARM64_DUMP_SIZE)

class ElfTest {

    @Test
    fun arm32PackedHeaderMatchesTheKnownLibrary() {
        val image = ElfImage.parse(arm32Packed())

        assertFalse(image.is64)
        assertTrue(image.is32Bit)
        assertEquals(4, image.pointerSize)
        assertEquals(EM_ARM, image.machine)
        assertEquals(13, image.segments.size)
        assertEquals(23, image.sections.size)
        assertEquals(0x13e6ff0L, image.entry)
        assertFalse(image.isDumped)
    }

    @Test
    fun arm64PackedHeaderMatchesTheKnownLibrary() {
        val image = ElfImage.parse(arm64Packed())

        assertTrue(image.is64)
        assertFalse(image.is32Bit)
        assertEquals(8, image.pointerSize)
        assertEquals(EM_AARCH64, image.machine)
        assertEquals(14, image.segments.size)
        assertEquals(24, image.sections.size)
        assertEquals(0x32269d4L, image.entry)
        assertFalse(image.isDumped)
    }

    @Test
    fun protectionMarkerIsDtInitAndNotDtInitArray() {
        val arm32 = ElfImage.parse(arm32Packed())
        assertEquals(0xa819a09L, arm32.dynamicValue(DT_INIT))
        assertNull(arm32.dynamicValue(DT_INIT_ARRAY))
        assertTrue(arm32.hasDtInit())

        val arm64 = ElfImage.parse(arm64Packed())
        assertEquals(0xb1ca42cL, arm64.dynamicValue(DT_INIT))
        assertNull(arm64.dynamicValue(DT_INIT_ARRAY))
        assertTrue(arm64.hasDtInit())
    }

    @Test
    fun arm32RodataBoundsAreIdentityMapped() {
        val image = ElfImage.parse(arm32Packed())
        val rodata = assertNotNull(image.sectionNamed(".rodata"))

        assertEquals(0xc0b7a8L, rodata.addr)
        assertEquals(0xc0b7a8L, rodata.offset)
        assertEquals(0x7db844L, rodata.size)
        assertEquals(rodata.offset, image.mapVaToOffset(rodata.addr))
        assertEquals(rodata.offset + rodata.size - 1, image.mapVaToOffset(rodata.addr + rodata.size - 1))
        assertEquals(rodata.addr, image.mapOffsetToVa(rodata.offset))
        assertEquals(rodata.addr, image.rva(rodata.addr))
    }

    @Test
    fun arm64RodataBoundsAreIdentityMapped() {
        val image = ElfImage.parse(arm64Packed())
        val rodata = assertNotNull(image.sectionNamed(".rodata"))

        assertEquals(0x194a210L, rodata.addr)
        assertEquals(0x194a210L, rodata.offset)
        assertEquals(0x7f335bL, rodata.size)
        assertEquals(rodata.offset, image.mapVaToOffset(rodata.addr))
        assertEquals(rodata.offset + rodata.size - 1, image.mapVaToOffset(rodata.addr + rodata.size - 1))
        assertEquals(rodata.addr, image.mapOffsetToVa(rodata.offset))
        assertEquals(rodata.addr, image.rva(rodata.addr))
    }

    @Test
    fun segmentBoundsAreExclusiveAtTheLoadSeam() {
        val image = ElfImage.parse(arm32Packed())
        val first = image.segments[1]
        val second = image.segments[2]

        assertEquals(0L, first.offset)
        assertEquals(0L, first.vaddr)
        assertEquals(0xa1b5c20L, first.fileSize)
        assertEquals(0xa1b5c20L, second.offset)
        assertEquals(0xa1b9c20L, second.vaddr)

        assertEquals(0xa1b9c20L, image.mapOffsetToVa(second.offset))
        assertEquals(first.vaddr + first.fileSize - 1, image.mapOffsetToVa(first.offset + first.fileSize - 1))
        assertEquals(-1L, image.mapVaToOffset(first.vaddr + first.memSize))
        assertEquals(second.offset, image.mapVaToOffset(second.vaddr))
    }

    @Test
    fun unmappedAddressesReportMinusOne() {
        val image = ElfImage.parse(arm64Packed())

        assertEquals(-1L, image.mapVaToOffset(0x7fffffffffffL))
        assertEquals(-1L, image.mapOffsetToVa(0x7fffffffffffL))
    }

    @Test
    fun arm64DumpMapsRuntimeAddressesThroughImageBase() {
        val image = ElfImage.parseDump(arm64Dump(), ARM64_DUMP_BASE)

        assertTrue(image.is64)
        assertTrue(image.isDumped)
        assertEquals(ARM64_DUMP_BASE, image.imageBase)
        assertEquals(14, image.segments.size)
        assertEquals(0x194a210L, image.mapVaToOffset(ARM64_DUMP_BASE + 0x194a210L))
        assertEquals(0x194a210L, image.rva(ARM64_DUMP_BASE + 0x194a210L))
        assertEquals(0x32269d4L, image.mapVaToOffset(ARM64_DUMP_BASE + 0x32269d4L))

        val text = image.segments[2]
        assertEquals(text.memSize, text.fileSize)
        assertEquals(0x32269d4L, text.offset)
        assertEquals(ARM64_DUMP_BASE + 0x32269d4L, text.vaddr)
    }

    @Test
    fun arm32DumpMapsRuntimeAddressesThroughImageBase() {
        val image = ElfImage.parseDump(arm32Dump(), ARM32_DUMP_BASE)

        assertTrue(image.is32Bit)
        assertTrue(image.isDumped)
        assertEquals(13, image.segments.size)
        assertEquals(0xc0b7a8L, image.mapVaToOffset(ARM32_DUMP_BASE + 0xc0b7a8L))
        assertEquals(0xc0b7a8L, image.rva(ARM32_DUMP_BASE + 0xc0b7a8L))

        val loaded = image.segments[1]
        assertEquals(loaded.memSize, loaded.fileSize)
        assertEquals(0L, loaded.offset)
        assertEquals(ARM32_DUMP_BASE, loaded.vaddr)
    }

    @Test
    fun dumpedDynamicEntriesAreShiftedByImageBase() {
        val image = ElfImage.parseDump(arm64Dump(), ARM64_DUMP_BASE)

        val strtab = assertNotNull(image.dynamicValue(DT_STRTAB))
        val symtab = assertNotNull(image.dynamicValue(DT_SYMTAB))
        assertEquals(ARM64_DUMP_BASE + 0x1754338L, strtab)
        assertEquals(ARM64_DUMP_BASE + 0xb1d8470L, symtab)
        assertEquals(0x1754338L, image.mapVaToOffset(strtab))
        assertEquals(0xb1d8470L, image.mapVaToOffset(symtab))
        assertEquals(ARM64_DUMP_BASE + 0xb1ca42cL, image.dynamicValue(DT_INIT))
        assertTrue(image.hasDtInit())
    }

    @Test
    fun arm32DumpRebasesThirtyTwoBitDynamicValues() {
        val image = ElfImage.parseDump(arm32Dump(), ARM32_DUMP_BASE)

        val strtab = assertNotNull(image.dynamicValue(DT_STRTAB))
        val symtab = assertNotNull(image.dynamicValue(DT_SYMTAB))
        assertEquals(0x7a824948L, strtab)
        assertEquals(0x707d1998L, symtab)
        assertEquals(0xa824948L, image.mapVaToOffset(strtab))
        assertEquals(0x7d1998L, image.mapVaToOffset(symtab))
    }

    @Test
    fun symbolsCoverTheWholeDynsymSectionOnPackedImages() {
        val arm32 = ElfImage.parse(arm32Packed())
        val arm32Dynsym = assertNotNull(arm32.sectionNamed(".dynsym"))
        assertEquals(0xabf0L, arm32Dynsym.size)
        assertEquals(0x10L, arm32Dynsym.entSize)
        assertEquals(2751, arm32.symbols.size)
        assertEquals("strncmp", arm32.symbols.last().name)
        assertNotNull(arm32.symbolNamed("__cxa_atexit"))
        assertNotNull(arm32.symbolNamed("g_aco_array"))
        assertEquals(
            arm32.mapVaToOffset(assertNotNull(arm32.dynamicValue(DT_STRTAB))),
            assertNotNull(arm32.sectionNamed(".dynstr")).offset
        )

        val arm64 = ElfImage.parse(arm64Packed())
        val arm64Dynsym = assertNotNull(arm64.sectionNamed(".dynsym"))
        assertEquals(0xfeb8L, arm64Dynsym.size)
        assertEquals(0x18L, arm64Dynsym.entSize)
        assertEquals(2717, arm64.symbols.size)
        assertEquals("strncmp", arm64.symbols.last().name)
        assertNotNull(arm64.symbolNamed("__cxa_atexit"))
        assertNotNull(arm64.symbolNamed("g_aco_array"))
        assertEquals(
            arm64.mapVaToOffset(assertNotNull(arm64.dynamicValue(DT_STRTAB))),
            assertNotNull(arm64.sectionNamed(".dynstr")).offset
        )
    }

    @Test
    fun symbolsFallBackToTheHashTableOnDumps() {
        val arm64 = ElfImage.parseDump(arm64Dump(), ARM64_DUMP_BASE)
        assertTrue(arm64.sections.isEmpty())
        assertEquals(2704, arm64.symbols.size)
        assertNotNull(arm64.symbolNamed("__cxa_atexit"))

        val arm32 = ElfImage.parseDump(arm32Dump(), ARM32_DUMP_BASE)
        assertTrue(arm32.sections.isEmpty())
        assertEquals(2737, arm32.symbols.size)
        assertNotNull(arm32.symbolNamed("__cxa_atexit"))
    }

    @Test
    fun arm64RelativeRelocationsAreWrittenBack() {
        val data = arm64Packed()
        val image = ElfImage.parse(data)
        val reader = image.reader()

        assertEquals(0L, reader.int64At(0xa4140f0L))
        assertEquals(0L, reader.int64At(0xa414100L))
        image.applyRelocations()
        assertEquals(0xa41c0f0L, reader.int64At(0xa4140f0L))
        assertEquals(0x1953b40L, reader.int64At(0xa414100L))
    }

    @Test
    fun arm32Abs32RelocationsResolveToUndefinedImports() {
        val data = arm32Packed()
        val image = ElfImage.parse(data)
        val reader = image.reader()
        val targets = mapOf(
            0xa769ec8L to 40,
            0xa769ed0L to 39,
            0xa769ed8L to 96,
            0xa769edcL to 143
        )

        assertEquals("free", image.symbols[39].name)
        assertEquals("malloc", image.symbols[40].name)
        assertEquals("calloc", image.symbols[96].name)
        assertEquals("realloc", image.symbols[143].name)

        for ((virtualAddress, index) in targets) {
            assertEquals(0L, image.symbols[index].value)
            assertEquals(virtualAddress - 0x8000L, image.mapVaToOffset(virtualAddress))
        }
        image.applyRelocations()
        for ((virtualAddress, index) in targets) {
            assertEquals(image.symbols[index].value, reader.uint32At(virtualAddress - 0x8000L))
        }
    }

    @Test
    fun arm32KeepsNonAbs32RelocationsUntouched() {
        val data = arm32Packed()
        val image = ElfImage.parse(data)
        val reader = image.reader()
        val before = data.copyOfRange(0xa1b5c20, 0xa1b5c20 + 0x1000)

        assertEquals(0xa1b9c20L, reader.uint32At(0xa1b5c20L))
        image.applyRelocations()

        assertEquals(0xa1b9c20L, reader.uint32At(0xa1b5c20L))
        assertContentEquals(before, data.copyOfRange(0xa1b5c20, 0xa1b5c20 + 0x1000))
    }

    @Test
    fun relocationsAreSkippedOnDumps() {
        val image = ElfImage.parseDump(arm64Dump(), ARM64_DUMP_BASE)
        val reader = image.reader()
        val before = reader.int64At(0xa4140f0L)

        image.applyRelocations()

        assertEquals(before, reader.int64At(0xa4140f0L))
    }
}
