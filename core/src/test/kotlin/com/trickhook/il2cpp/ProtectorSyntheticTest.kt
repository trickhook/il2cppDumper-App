package com.trickhook.il2cpp

import com.trickhook.il2cpp.protector.FFProtector
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProtectorSyntheticTest {

    private val magic = 0x12345678
    private val obfuscation = 0x4F
    private val windowSize = 0x4000
    private val slotStride = 0x10000
    private val firstWindowPhase = 0x2000
    private val chunkSize = 0x800
    private val smallSectionLimit = 0x100000
    private val slotDelta = intArrayOf(-1, 0, 4, -1, 4, -1, -3, -2)
    private val window0Iv = ByteArray(16) { (it + 2).toByte() }

    private val descriptorPosition = 0x1000
    private val descriptorLength = 0x200
    private val sectionOffset = 0x2a7a8
    private val imageSize = 0x180000

    private class Image(val packed: ByteArray, val plain: ByteArray, val key: Int, val crc: Long)

    @Test
    fun `round trips a large permuted section`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 1)
        val result = FFProtector.tryUnpack(image.packed)

        assertTrue(result.unpacked)
        assertEquals("descriptor", result.keySource)
        assertEquals(image.key, result.key)
        assertEquals(0xC56A888FL, result.aesSeed)
        assertEquals("AES-128-CBC", result.window0Method)
        assertEquals(21, result.windowsTotal)
        assertEquals(21, result.windowsRecovered)
        assertEquals(0, result.windowsSkipped)
        assertEquals(0L, result.bytesUnrecovered)
        assertTrue(result.checksumVerified)
        assertEquals(image.crc, result.actualCrc)
        assertByteIdentical(image.plain, result.data)
    }

    @Test
    fun `round trips a large section that is not permuted`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 2)
        val result = FFProtector.tryUnpack(image.packed)

        assertTrue(result.checksumVerified)
        assertEquals("AES-128-CBC", result.window0Method)
        assertEquals(21, result.windowsRecovered)
        assertByteIdentical(image.plain, result.data)
    }

    @Test
    fun `round trips a small section with plain xor and no aes`() {
        val image = build(sectionSize = 0x83844, seed = 0xC56A888FL, algo = 1)
        val result = FFProtector.tryUnpack(image.packed)

        assertTrue(result.checksumVerified)
        assertEquals("n/a (small section, plain XOR)", result.window0Method)
        assertEquals(0L, result.aesSeed)
        assertEquals(9, result.windowsTotal)
        assertEquals(9, result.windowsRecovered)
        assertByteIdentical(image.plain, result.data)
    }

    @Test
    fun `round trips a last window longer than one window`() {
        val image = build(sectionSize = 0x10FFFF, seed = 0x123C4567L, algo = 1)
        val result = FFProtector.tryUnpack(image.packed)

        assertEquals(17, result.windowsTotal)
        assertEquals(0x3C, result.key)
        assertTrue(result.checksumVerified)
        assertByteIdentical(image.plain, result.data)
    }

    @Test
    fun `leaves window zero packed when the key blob is missing`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 1, includeBlob = false)
        val result = FFProtector.tryUnpack(image.packed)

        assertTrue(result.unpacked)
        assertEquals("descriptor + histogram", result.keySource)
        assertEquals("failed", result.window0Method)
        assertEquals(0L, result.aesSeed)
        assertEquals(20, result.windowsRecovered)
        assertEquals(1, result.windowsSkipped)
        assertEquals(windowSize.toLong(), result.bytesUnrecovered)
        assertFalse(result.checksumVerified)
        assertTrue(FFProtector.describe(result).contains("could not be recovered"))
    }

    @Test
    fun `takes the key from the blob when the descriptor byte is blank`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 1, descriptorKeyByte = 0)
        val result = FFProtector.tryUnpack(image.packed)

        assertEquals("key blob + histogram", result.keySource)
        assertEquals(image.key, result.key)
        assertTrue(result.checksumVerified)
        assertByteIdentical(image.plain, result.data)
    }

    @Test
    fun `accepts a key blob padded with spaces`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 1)
        val at = descriptorPosition + 0x1a8
        val end = generateSequence(at) { it + 1 }.first { image.packed[it].toInt() == 0 }
        image.packed[end] = ' '.code.toByte()
        image.packed[end + 1] = ' '.code.toByte()

        val result = FFProtector.tryUnpack(image.packed)

        assertEquals("descriptor", result.keySource)
        assertEquals(0xC56A888FL, result.aesSeed)
        assertTrue(result.checksumVerified)
    }

    @Test
    fun `leaves an already unpacked section alone`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 1, pack = false)
        val result = FFProtector.tryUnpack(image.packed)

        assertTrue(result.detected)
        assertFalse(result.unpacked)
        assertEquals(21, result.windowsTotal)
        assertTrue(result.data === image.packed)
        assertTrue(FFProtector.describe(result).contains("already looks unpacked"))
    }

    @Test
    fun `detects but does nothing when the section holds no windows`() {
        val image = build(
            sectionSize = 0x100,
            seed = 0xC56A888FL,
            algo = 1,
            pack = false,
            offset = 0x10000,
            total = 0x20000
        )
        val result = FFProtector.tryUnpack(image.packed)

        val descriptor = assertNotNull(result.descriptor)
        assertEquals(0x100L, descriptor.size)
        assertTrue(result.detected)
        assertFalse(result.unpacked)
        assertEquals(0, result.windowsTotal)
        assertTrue(result.data === image.packed)
    }

    @Test
    fun `reads every descriptor field at its own offset`() {
        val image = build(sectionSize = 0x143844, seed = 0xC56A888FL, algo = 2)
        val descriptor = assertNotNull(FFProtector.findDescriptor(image.packed))

        assertEquals(descriptorPosition.toLong(), descriptor.filePosition)
        assertEquals(".rodata", descriptor.name)
        assertEquals(sectionOffset.toLong(), descriptor.offset)
        assertEquals(sectionOffset.toLong(), descriptor.virtualAddress)
        assertEquals(0x143844L, descriptor.size)
        assertEquals(image.crc, descriptor.checksum)
        assertEquals(0x1000L, descriptor.firstLoadFileSize)
        assertEquals(1L, descriptor.kind)
        assertEquals(2L, descriptor.algo)
    }

    private fun assertByteIdentical(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "length")
        val mismatch = java.util.Arrays.mismatch(expected, actual)
        assertEquals(-1, mismatch, "first mismatch at 0x" + mismatch.toString(16))
    }

    private fun build(
        sectionSize: Int,
        seed: Long,
        algo: Int,
        includeBlob: Boolean = true,
        descriptorKeyByte: Int? = null,
        pack: Boolean = true,
        offset: Int = sectionOffset,
        total: Int = imageSize
    ): Image {
        val key = ((seed shr 16) and 0xFF).toInt()
        val plain = ByteArray(total)
        sectionContent(sectionSize, sectionSize + algo).copyInto(plain, offset)

        val crc = CRC32().apply { update(plain, offset, sectionSize) }.value
        val packed = plain.copyOf()
        if (pack) packSection(plain, packed, offset, sectionSize, key, seed, algo)

        writeDescriptor(
            image = packed,
            offset = offset,
            size = sectionSize,
            crc = crc,
            keyByte = descriptorKeyByte ?: key,
            algo = algo,
            blob = if (includeBlob) keyBlobText(seed) else ""
        )
        writeDescriptor(
            image = plain,
            offset = offset,
            size = sectionSize,
            crc = crc,
            keyByte = descriptorKeyByte ?: key,
            algo = algo,
            blob = if (includeBlob) keyBlobText(seed) else ""
        )
        return Image(packed, plain, key, crc)
    }

    private fun sectionContent(size: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val buffer = ByteArray(size)
        val words = listOf(
            "UnityEngine.Object", "System.Collections.Generic", "Il2CppClass",
            "PlayerController", "Assembly-CSharp.dll", "get_transform"
        )
        var position = 0
        while (position < size - 0x400) {
            position += random.nextInt(8, 96)
            val word = words.random(random).toByteArray(Charsets.US_ASCII)
            word.copyInto(buffer, position)
            position += word.size
        }
        return buffer
    }

    private fun windowsOf(start: Int, end: Int): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        var w = (start and 0xfff.inv()) + firstWindowPhase
        var index = 0
        while (w < end) {
            list += index to w
            w += slotStride
            index++
        }
        return list
    }

    private fun packSection(
        plain: ByteArray,
        packed: ByteArray,
        offset: Int,
        size: Int,
        key: Int,
        seed: Long,
        algo: Int
    ) {
        val end = offset + size
        val windows = windowsOf(offset, end)
        if (windows.isEmpty()) return

        val first = windows[0].second
        if (size < smallSectionLimit) {
            for (i in first until end) packed[i] = (plain[i].toInt() xor key).toByte()
            return
        }

        encryptWindow0(plain, packed, first, minOf(windowSize, end - first), seed)

        val permute = algo != 2
        val count = windows.size
        val lastGroupStart = 2 + 8 * ((count - 2) / 8)
        for ((index, destination) in windows) {
            if (index == 0) continue
            val delta = if (!permute || index >= lastGroupStart) 0 else slotDelta[index % 8] * slotStride
            val source = destination + delta
            val length = if (index == count - 1) end - destination else minOf(windowSize, end - destination)
            for (i in 0 until length) packed[source + i] = (plain[destination + i].toInt() xor key).toByte()
        }
    }

    private fun encryptWindow0(plain: ByteArray, packed: ByteArray, offset: Int, length: Int, seed: Long) {
        val material = String.format("%08x%08x", seed, seed).toByteArray(Charsets.US_ASCII)
        val spec = SecretKeySpec(material, "AES")
        val iv = IvParameterSpec(window0Iv)
        var off = 0
        while (off + chunkSize <= length) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, spec, iv)
            cipher.doFinal(plain, offset + off, chunkSize, packed, offset + off)
            off += chunkSize
        }
    }

    private fun keyBlobText(seed: Long): String {
        val raw = ByteArray(20)
        byteArrayOf(0x20, 0x24, 0x08, 0x29).copyInto(raw, 0)
        writeBigEndian(raw, 4, 1L)
        writeBigEndian(raw, 8, 1L)
        writeBigEndian(raw, 12, seed)
        writeBigEndian(raw, 16, seed - 1)
        for (i in raw.indices) raw[i] = (raw[i].toInt() xor obfuscation).toByte()
        return Base64.getEncoder().encodeToString(raw)
    }

    private fun writeDescriptor(
        image: ByteArray,
        offset: Int,
        size: Int,
        crc: Long,
        keyByte: Int,
        algo: Int,
        blob: String
    ) {
        java.util.Arrays.fill(image, descriptorPosition, descriptorPosition + descriptorLength, 0.toByte())
        writeLittleEndian(image, descriptorPosition, magic.toLong())
        ".rodata".toByteArray(Charsets.US_ASCII).copyInto(image, descriptorPosition + 4)
        writeLittleEndian(image, descriptorPosition + 0x14, offset.toLong())
        writeLittleEndian(image, descriptorPosition + 0x18, offset.toLong())
        writeLittleEndian(image, descriptorPosition + 0x1c, size.toLong())
        writeLittleEndian(image, descriptorPosition + 0x20, crc)
        writeLittleEndian(image, descriptorPosition + 0x28, 0x1000L)
        writeLittleEndian(image, descriptorPosition + 0x2c, 1L)
        image[descriptorPosition + 0x186] = (keyByte xor obfuscation).toByte()
        writeLittleEndian(image, descriptorPosition + 0x188, algo.toLong())
        blob.toByteArray(Charsets.US_ASCII).copyInto(image, descriptorPosition + 0x1a8)
    }

    private fun writeLittleEndian(image: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) image[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun writeBigEndian(raw: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) raw[at + i] = ((value shr (8 * (3 - i))) and 0xFF).toByte()
    }
}
