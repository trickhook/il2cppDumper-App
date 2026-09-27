package com.trickhook.il2cpp.io

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Random access, little endian, over a blob that may be much larger than the
 * Dalvik heap allows. Every offset is a `Long` so callers never have to think
 * about 32 bit truncation.
 *
 * Two implementations:
 *  - [ArrayByteSource] wraps a `ByteArray`. This is the historical path and the
 *    one the tests use; it is on the heap.
 *  - [MappedByteSource] wraps a `MappedByteBuffer` obtained with
 *    [FileChannel.MapMode.PRIVATE], which is writable, off heap, and does not
 *    count against the Dalvik heap limit. A 247 MB library costs nothing on the
 *    heap this way.
 *
 * A single `MappedByteBuffer` can only address [MappedByteSource.MAX_MAPPING]
 * bytes, so [MappedByteSource.of] refuses anything bigger instead of silently
 * truncating.
 */
interface ByteSource : Closeable {

    val size: Long

    fun byteAt(offset: Long): Byte

    fun int16At(offset: Long): Short

    fun int32At(offset: Long): Int

    fun int64At(offset: Long): Long

    fun putByte(offset: Long, value: Byte)

    fun putInt32(offset: Long, value: Int)

    fun copyOut(offset: Long, dest: ByteArray, destOffset: Int, length: Int)

    fun copyIn(offset: Long, src: ByteArray, srcOffset: Int, length: Int)

    fun uint8At(offset: Long): Int = byteAt(offset).toInt() and 0xFF

    fun uint16At(offset: Long): Int = int16At(offset).toInt() and 0xFFFF

    fun uint32At(offset: Long): Long = int32At(offset).toLong() and 0xFFFFFFFFL

    fun putInt64(offset: Long, value: Long) {
        putInt32(offset, (value and 0xFFFFFFFFL).toInt())
        putInt32(offset + 4, (value ushr 32).toInt())
    }

    fun slice(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        copyOut(offset, out, 0, length)
        return out
    }

    fun stringAt(offset: Long, length: Int): String =
        if (length <= 0) "" else String(slice(offset, length), Charsets.UTF_8)

    /**
     * The backing array, when there is one. Callers must treat `null` as normal
     * and never require a heap array: that is the whole point of this interface.
     */
    val array: ByteArray? get() = null

    /** Human readable description for the log. */
    val describe: String

    override fun close() {}
}

class ArrayByteSource(private val data: ByteArray) : ByteSource {

    override val array: ByteArray get() = data

    override val size: Long get() = data.size.toLong()

    override val describe: String get() = "heap array, ${data.size} bytes"

    override fun byteAt(offset: Long): Byte = data[offset.toInt()]

    override fun int16At(offset: Long): Short {
        val p = offset.toInt()
        return ((data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    override fun int32At(offset: Long): Int {
        val p = offset.toInt()
        return (data[p].toInt() and 0xFF) or
            ((data[p + 1].toInt() and 0xFF) shl 8) or
            ((data[p + 2].toInt() and 0xFF) shl 16) or
            ((data[p + 3].toInt() and 0xFF) shl 24)
    }

    override fun int64At(offset: Long): Long =
        (int32At(offset).toLong() and 0xFFFFFFFFL) or (int32At(offset + 4).toLong() shl 32)

    override fun putByte(offset: Long, value: Byte) {
        data[offset.toInt()] = value
    }

    override fun putInt32(offset: Long, value: Int) {
        val p = offset.toInt()
        data[p] = (value and 0xFF).toByte()
        data[p + 1] = ((value ushr 8) and 0xFF).toByte()
        data[p + 2] = ((value ushr 16) and 0xFF).toByte()
        data[p + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    override fun copyOut(offset: Long, dest: ByteArray, destOffset: Int, length: Int) {
        System.arraycopy(data, offset.toInt(), dest, destOffset, length)
    }

    override fun copyIn(offset: Long, src: ByteArray, srcOffset: Int, length: Int) {
        System.arraycopy(src, srcOffset, data, offset.toInt(), length)
    }

    override fun slice(offset: Long, length: Int): ByteArray {
        val from = offset.toInt()
        return data.copyOfRange(from, from + length)
    }

    override fun stringAt(offset: Long, length: Int): String =
        if (length <= 0) "" else String(data, offset.toInt(), length, Charsets.UTF_8)
}

class MappedByteSource private constructor(
    private val buffer: MappedByteBuffer,
    override val size: Long,
    private val handle: RandomAccessFile?,
    private val mode: String,
    private val name: String
) : ByteSource {

    override val describe: String get() = "$name mapped $mode, $size bytes"

    override fun byteAt(offset: Long): Byte = buffer.get(offset.toInt())

    override fun int16At(offset: Long): Short = buffer.getShort(offset.toInt())

    override fun int32At(offset: Long): Int = buffer.getInt(offset.toInt())

    override fun int64At(offset: Long): Long = buffer.getLong(offset.toInt())

    override fun putByte(offset: Long, value: Byte) {
        buffer.put(offset.toInt(), value)
    }

    override fun putInt32(offset: Long, value: Int) {
        buffer.putInt(offset.toInt(), value)
    }

    override fun putInt64(offset: Long, value: Long) {
        buffer.putLong(offset.toInt(), value)
    }

    // The bulk moves are the only places that need the buffer's cursor. They are
    // guarded because position() is shared state and the reader is free to be
    // used from more than one thread.
    override fun copyOut(offset: Long, dest: ByteArray, destOffset: Int, length: Int) {
        synchronized(buffer) {
            buffer.position(offset.toInt())
            buffer.get(dest, destOffset, length)
        }
    }

    override fun copyIn(offset: Long, src: ByteArray, srcOffset: Int, length: Int) {
        synchronized(buffer) {
            buffer.position(offset.toInt())
            buffer.put(src, srcOffset, length)
        }
    }

    override fun close() {
        runCatching { handle?.close() }
    }

    companion object {

        /** A single mapping cannot address more than this. */
        const val MAX_MAPPING: Long = Int.MAX_VALUE.toLong()

        /**
         * Maps [file] copy-on-write. Writes land in private, off heap pages and
         * are never flushed back to the file, so the caller may freely decrypt
         * or relocate in place. Needs the file to be writable by us, which is
         * why callers stage a copy they own.
         */
        fun privateMap(file: File): MappedByteSource = map(file, "rw", FileChannel.MapMode.PRIVATE)

        /** Maps [file] read only. Cheapest way to probe a file we do not own. */
        fun readOnly(file: File): MappedByteSource = map(file, "r", FileChannel.MapMode.READ_ONLY)

        private fun map(file: File, openMode: String, mapMode: FileChannel.MapMode): MappedByteSource {
            val length = file.length()
            require(length > 0L) { "${file.name} is empty" }
            require(length <= MAX_MAPPING) {
                "${file.name} is $length bytes; a single mapping tops out at $MAX_MAPPING"
            }
            val handle = RandomAccessFile(file, openMode)
            return try {
                val mapped = handle.channel.map(mapMode, 0L, length)
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                // The mapping outlives the channel, so the channel can go now and
                // only the RandomAccessFile handle is kept for close().
                MappedByteSource(mapped, length, handle, mapModeName(mapMode), file.name)
            } catch (failure: Throwable) {
                runCatching { handle.close() }
                throw failure
            }
        }

        private fun mapModeName(mode: FileChannel.MapMode): String = when (mode) {
            FileChannel.MapMode.PRIVATE -> "PRIVATE"
            FileChannel.MapMode.READ_ONLY -> "READ_ONLY"
            else -> "READ_WRITE"
        }
    }
}
