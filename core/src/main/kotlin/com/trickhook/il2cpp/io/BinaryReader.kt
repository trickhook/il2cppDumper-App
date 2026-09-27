package com.trickhook.il2cpp.io

class BinaryReader(val source: ByteSource) {

    constructor(data: ByteArray) : this(ArrayByteSource(data))

    var position: Long = 0L
    var is32Bit: Boolean = false

    val size: Long get() = source.size

    fun seek(offset: Long) { position = offset }

    fun readByte(): Byte = source.byteAt(position++)

    fun readUByte(): Int = source.uint8At(position++)

    fun readInt16(): Short {
        val p = position
        position += 2
        return source.int16At(p)
    }

    fun readUInt16(): Int = readInt16().toInt() and 0xFFFF

    fun readInt32(): Int {
        val p = position
        position += 4
        return source.int32At(p)
    }

    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    fun readInt64(): Long {
        val p = position
        position += 8
        return source.int64At(p)
    }

    fun readPointer(): Long = if (is32Bit) readUInt32() else readInt64()

    fun readBytes(count: Int): ByteArray {
        val p = position
        position += count
        return source.slice(p, count)
    }

    fun readStringToNull(offset: Long, limit: Int = 4096): String {
        var end = offset
        val max = minOf(source.size, offset + limit)
        while (end < max && source.byteAt(end).toInt() != 0) end++
        return source.stringAt(offset, (end - offset).toInt())
    }

    fun uint8At(offset: Long): Int = source.uint8At(offset)

    fun uint16At(offset: Long): Int = source.uint16At(offset)

    fun int32At(offset: Long): Int = source.int32At(offset)

    fun uint32At(offset: Long): Long = source.uint32At(offset)

    fun int64At(offset: Long): Long = source.int64At(offset)

    fun pointerAt(offset: Long): Long = if (is32Bit) uint32At(offset) else int64At(offset)
}
