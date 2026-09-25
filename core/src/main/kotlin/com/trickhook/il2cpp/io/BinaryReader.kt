package com.trickhook.il2cpp.io

class BinaryReader(val data: ByteArray) {

    var position: Long = 0L
    var is32Bit: Boolean = false

    val size: Long get() = data.size.toLong()

    fun seek(offset: Long) { position = offset }

    fun readByte(): Byte = data[position++.toInt()]

    fun readUByte(): Int = data[position++.toInt()].toInt() and 0xFF

    fun readInt16(): Short {
        val p = position.toInt()
        position += 2
        return ((data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    fun readUInt16(): Int = readInt16().toInt() and 0xFFFF

    fun readInt32(): Int {
        val p = position.toInt()
        position += 4
        return (data[p].toInt() and 0xFF) or
            ((data[p + 1].toInt() and 0xFF) shl 8) or
            ((data[p + 2].toInt() and 0xFF) shl 16) or
            ((data[p + 3].toInt() and 0xFF) shl 24)
    }

    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    fun readInt64(): Long {
        val lo = readUInt32()
        val hi = readUInt32()
        return lo or (hi shl 32)
    }

    fun readPointer(): Long = if (is32Bit) readUInt32() else readInt64()

    fun readBytes(count: Int): ByteArray {
        val p = position.toInt()
        position += count
        return data.copyOfRange(p, p + count)
    }

    fun readStringToNull(offset: Long, limit: Int = 4096): String {
        var end = offset.toInt()
        val max = minOf(data.size, end + limit)
        while (end < max && data[end].toInt() != 0) end++
        return String(data, offset.toInt(), end - offset.toInt(), Charsets.UTF_8)
    }

    fun int32At(offset: Long): Int {
        val p = offset.toInt()
        return (data[p].toInt() and 0xFF) or
            ((data[p + 1].toInt() and 0xFF) shl 8) or
            ((data[p + 2].toInt() and 0xFF) shl 16) or
            ((data[p + 3].toInt() and 0xFF) shl 24)
    }

    fun uint32At(offset: Long): Long = int32At(offset).toLong() and 0xFFFFFFFFL

    fun int64At(offset: Long): Long = uint32At(offset) or (uint32At(offset + 4) shl 32)

    fun pointerAt(offset: Long): Long = if (is32Bit) uint32At(offset) else int64At(offset)
}
