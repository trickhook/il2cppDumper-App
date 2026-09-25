package com.trickhook.il2cpp

import com.trickhook.il2cpp.protector.FFProtector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProtectorTest {

    private data class Expectation(
        val label: String,
        val packedPath: String,
        val memdumpPath: String,
        val filePosition: Long,
        val offset: Long,
        val virtualAddress: Long,
        val size: Long,
        val checksum: Long,
        val key: Int,
        val aesSeed: Long,
        val windows: Int
    )

    private val arm32 = Expectation(
        label = "arm32",
        packedPath = "C:/Users/danie/Desktop/dump/libil2cpp.so",
        memdumpPath = "C:/Users/danie/Desktop/dump/libil2cpp_memdump.so",
        filePosition = 0xa766c10L,
        offset = 0xc0b7a8L,
        virtualAddress = 0xc0b7a8L,
        size = 0x7db844L,
        checksum = 0xa8de6309L,
        key = 0x6A,
        aesSeed = 0xc56a888fL,
        windows = 126
    )

    private val arm64 = Expectation(
        label = "arm64",
        packedPath = "C:/Users/danie/Desktop/dump/arm64/libil2cpp.so",
        memdumpPath = "C:/Users/danie/Desktop/dump/arm64/libil2cpp_memdump.so",
        filePosition = 0xaf73710L,
        offset = 0x194a210L,
        virtualAddress = 0x194a210L,
        size = 0x7f335bL,
        checksum = 0x6876e3c7L,
        key = 0xE0,
        aesSeed = 0xcbe04605L,
        windows = 128
    )

    @Test
    fun `unpacks the arm32 library byte for byte`() = verify(arm32)

    @Test
    fun `unpacks the arm64 library byte for byte`() = verify(arm64)

    @Test
    fun `leaves the arm32 memory dump untouched`() = verifyMemoryDumpIsLeftAlone(arm32)

    @Test
    fun `leaves the arm64 memory dump untouched`() = verifyMemoryDumpIsLeftAlone(arm64)

    @Test
    fun `reports nothing on a buffer without a descriptor`() {
        val result = FFProtector.tryUnpack(ByteArray(0x10000))
        assertTrue(!result.detected)
        assertTrue(!result.unpacked)
        assertEquals("", FFProtector.describe(result))
    }

    @Test
    fun `detects without touching the data when disabled`() {
        val packed = File(arm32.packedPath)
        if (skipUnlessPresent(packed)) return
        val data = packed.readBytes()
        val result = FFProtector.tryUnpack(data, enabled = false)
        assertTrue(result.detected)
        assertTrue(!result.unpacked)
        assertTrue(result.data === data)
    }

    private fun skipUnlessPresent(vararg files: File): Boolean {
        val missing = files.filterNot { it.isFile }
        if (missing.isEmpty()) return false
        println("SKIPPED, test data absent: ${missing.joinToString { it.path }}")
        return true
    }

    private fun verifyMemoryDumpIsLeftAlone(expected: Expectation) {
        val memdumpFile = File(expected.memdumpPath)
        if (skipUnlessPresent(memdumpFile)) return

        val memdump = memdumpFile.readBytes()
        val result = FFProtector.tryUnpack(memdump)

        assertTrue(result.detected, "${expected.label}: descriptor still present in the dump")
        assertTrue(!result.unpacked, "${expected.label}: an unpacked section must be left alone")
        assertEquals(expected.windows, result.windowsTotal, "${expected.label}: windowsTotal")
        assertTrue(result.data === memdump, "${expected.label}: data must not be copied or rewritten")
        println("${expected.label} memdump: ${FFProtector.describe(result)}")
    }

    private fun verify(expected: Expectation) {
        val packedFile = File(expected.packedPath)
        val memdumpFile = File(expected.memdumpPath)
        if (skipUnlessPresent(packedFile, memdumpFile)) return

        val packed = packedFile.readBytes()

        val descriptor = assertNotNull(FFProtector.findDescriptor(packed), "${expected.label}: no descriptor")
        assertEquals(expected.filePosition, descriptor.filePosition, "${expected.label}: filePosition")
        assertEquals(".rodata", descriptor.name, "${expected.label}: name")
        assertEquals(expected.offset, descriptor.offset, "${expected.label}: offset")
        assertEquals(expected.virtualAddress, descriptor.virtualAddress, "${expected.label}: virtualAddress")
        assertEquals(expected.size, descriptor.size, "${expected.label}: size")
        assertEquals(expected.checksum, descriptor.checksum, "${expected.label}: checksum")

        val result = FFProtector.tryUnpack(packed)
        println("${expected.label}\n${FFProtector.describe(result)}")

        assertTrue(result.detected, "${expected.label}: detected")
        assertTrue(result.unpacked, "${expected.label}: unpacked")
        assertEquals(expected.key, result.key, "${expected.label}: key")
        assertEquals("descriptor", result.keySource, "${expected.label}: keySource")
        assertEquals(expected.aesSeed, result.aesSeed, "${expected.label}: aesSeed")
        assertEquals("AES-128-CBC", result.window0Method, "${expected.label}: window0Method")
        assertEquals(expected.windows, result.windowsTotal, "${expected.label}: windowsTotal")
        assertEquals(expected.windows, result.windowsRecovered, "${expected.label}: windowsRecovered")
        assertEquals(0, result.windowsSkipped, "${expected.label}: windowsSkipped")
        assertEquals(0L, result.bytesUnrecovered, "${expected.label}: bytesUnrecovered")
        assertEquals(expected.checksum, result.expectedCrc, "${expected.label}: expectedCrc")
        assertEquals(expected.checksum, result.actualCrc, "${expected.label}: actualCrc")
        assertTrue(result.checksumVerified, "${expected.label}: checksumVerified")

        val unpacked = result.data
        val memdump = memdumpFile.readBytes()
        val base = expected.offset.toInt()
        val length = expected.size.toInt()
        assertTrue(base + length <= memdump.size, "${expected.label}: memdump too short")

        var equal = 0
        var firstMismatch = -1L
        for (i in 0 until length) {
            if (unpacked[base + i] == memdump[base + i]) {
                equal++
            } else if (firstMismatch < 0) {
                firstMismatch = (base + i).toLong()
            }
        }
        println("${expected.label}: $equal/$length bytes equal to the memory dump, first mismatch $firstMismatch")
        assertEquals(length, equal, "${expected.label}: byte-for-byte equality with the memory dump")
    }
}
