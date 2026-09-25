package com.trickhook.il2cpp.protector

import java.util.zip.CRC32

object Crc32Affine {

    fun of(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }

    fun ofZeros(length: Long): Long = combine(0L, 0L, length)

    fun combine(first: Long, second: Long, secondLength: Long): Long {
        if (secondLength <= 0L) return first
        val odd = LongArray(32)
        val even = LongArray(32)

        odd[0] = 0xEDB88320L
        var row = 1L
        for (i in 1 until 32) {
            odd[i] = row
            row = row shl 1
        }

        square(even, odd)
        square(odd, even)

        var crc = first
        var len = secondLength
        do {
            square(even, odd)
            if (len and 1L != 0L) crc = times(even, crc)
            len = len ushr 1
            if (len == 0L) break
            square(odd, even)
            if (len and 1L != 0L) crc = times(odd, crc)
            len = len ushr 1
        } while (len != 0L)

        return crc xor second
    }

    private fun times(matrix: LongArray, vector: Long): Long {
        var sum = 0L
        var value = vector
        var index = 0
        while (value != 0L) {
            if (value and 1L != 0L) sum = sum xor matrix[index]
            value = value ushr 1
            index++
        }
        return sum
    }

    private fun square(target: LongArray, source: LongArray) {
        for (i in 0 until 32) target[i] = times(source, source[i])
    }
}
