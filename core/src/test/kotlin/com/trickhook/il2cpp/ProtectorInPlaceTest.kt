package com.trickhook.il2cpp

import com.trickhook.il2cpp.protector.FFProtector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtectorInPlaceTest {

    private val arm32 = File("C:/Users/danie/Desktop/dump/libil2cpp.so")
    private val arm32Dump = File("C:/Users/danie/Desktop/dump/libil2cpp_memdump.so")
    private val arm64 = File("C:/Users/danie/Desktop/dump/arm64/libil2cpp.so")
    private val arm64Dump = File("C:/Users/danie/Desktop/dump/arm64/libil2cpp_memdump.so")

    private fun check(library: File, memoryDump: File, label: String) {
        val copied = FFProtector.tryUnpack(library.readBytes(), inPlace = false)

        val buffer = library.readBytes()
        val identity = System.identityHashCode(buffer)
        val inPlace = FFProtector.tryUnpack(buffer, inPlace = true)

        assertTrue(inPlace.checksumVerified, "$label crc")
        assertEquals(identity, System.identityHashCode(inPlace.data), "$label reused the buffer")
        assertEquals(copied.windowsRecovered, inPlace.windowsRecovered, "$label windows")
        assertEquals(copied.actualCrc, inPlace.actualCrc, "$label crc value")
        assertContentEquals(copied.data, inPlace.data, "$label whole image")

        val descriptor = inPlace.descriptor!!
        val from = descriptor.offset.toInt()
        val size = descriptor.size.toInt()
        val expected = memoryDump.readBytes().copyOfRange(from, from + size)
        val actual = inPlace.data.copyOfRange(from, from + size)
        var equal = 0
        for (i in expected.indices) if (expected[i] == actual[i]) equal++
        println("$label in-place: $equal/$size bytes equal to the memory dump")
        assertEquals(size, equal, "$label section bytes")
    }

    @Test
    fun `unpacks arm32 in place without allocating a second image`() = check(arm32, arm32Dump, "arm32")

    @Test
    fun `unpacks arm64 in place without allocating a second image`() = check(arm64, arm64Dump, "arm64")
}
