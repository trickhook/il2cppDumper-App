package com.trickhook.il2cpp.dotnet

import java.io.ByteArrayOutputStream

/**
 * Buffer de bytes com as primitivas que o formato de metadata do .NET usa.
 *
 * O inteiro comprimido (ECMA-335 II.23.2) e big-endian e o proprio tamanho
 * esta nos bits altos do primeiro byte, entao os tres casos tem que ser
 * escritos exatamente nesta ordem.
 */
class ByteBuffer(initial: Int = 64) {

    private var data = ByteArray(initial)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var capacity = maxOf(data.size * 2, 64)
        while (capacity < size + extra) capacity *= 2
        data = data.copyOf(capacity)
    }

    fun u8(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun u16(value: Int) {
        ensure(2)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
    }

    fun u32(value: Int) {
        ensure(4)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
        data[size++] = (value ushr 16).toByte()
        data[size++] = (value ushr 24).toByte()
    }

    fun u32(value: Long) = u32(value.toInt())

    fun u64(value: Long) {
        u32(value.toInt())
        u32((value ushr 32).toInt())
    }

    fun bytes(source: ByteArray, from: Int = 0, count: Int = source.size - from) {
        ensure(count)
        System.arraycopy(source, from, data, size, count)
        size += count
    }

    fun zeros(count: Int) {
        ensure(count)
        size += count
    }

    /** Alinha o buffer, preenchendo com zero. */
    fun align(boundary: Int) {
        val over = size % boundary
        if (over != 0) zeros(boundary - over)
    }

    fun compressed(value: Int) {
        when {
            value < 0x80 -> u8(value)
            value < 0x4000 -> {
                u8(0x80 or (value ushr 8))
                u8(value and 0xFF)
            }
            else -> {
                u8(0xC0 or (value ushr 24))
                u8((value ushr 16) and 0xFF)
                u8((value ushr 8) and 0xFF)
                u8(value and 0xFF)
            }
        }
    }

    /** Inteiro comprimido com sinal, usado nos limites de array multidimensional. */
    fun compressedSigned(value: Int) {
        val rotated = if (value >= 0) value shl 1 else ((-value) shl 1) or 1
        compressed(rotated)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    fun writeTo(out: ByteArrayOutputStream) = out.write(data, 0, size)
}

/**
 * Heap de strings (#Strings): UTF-8 terminado em zero, uma entrada vazia
 * obrigatoria no offset 0. Deduplicado, porque nomes de tipo e de metodo se
 * repetem muito e o heap de um assembly grande seria varias vezes maior sem
 * isso.
 */
class StringHeap {

    private val buffer = ByteBuffer(1 shl 16)
    private val offsets = HashMap<String, Int>()

    init {
        buffer.u8(0)
        offsets[""] = 0
    }

    val size: Int get() = buffer.size

    fun add(text: String): Int {
        if (text.isEmpty()) return 0
        offsets[text]?.let { return it }
        val at = buffer.size
        buffer.bytes(text.toByteArray(Charsets.UTF_8))
        buffer.u8(0)
        offsets[text] = at
        return at
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/**
 * Heap de blobs (#Blob): cada entrada e o tamanho comprimido seguido dos
 * bytes. Deduplicado pelo conteudo, o que importa bastante aqui porque
 * assinaturas identicas aparecem aos milhares.
 */
class BlobHeap {

    private val buffer = ByteBuffer(1 shl 16)
    private val offsets = HashMap<Int, MutableList<Pair<ByteArray, Int>>>()

    init {
        buffer.u8(0)
    }

    val size: Int get() = buffer.size

    fun add(value: ByteArray): Int {
        if (value.isEmpty()) return 0
        val bucket = offsets.getOrPut(value.contentHashCode()) { mutableListOf() }
        for ((existing, at) in bucket) {
            if (existing.contentEquals(value)) return at
        }
        val at = buffer.size
        buffer.compressed(value.size)
        buffer.bytes(value)
        bucket += value to at
        return at
    }

    fun add(value: ByteBuffer): Int = add(value.toByteArray())

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/**
 * Heap de GUIDs (#GUID): entradas de 16 bytes, indexadas a partir de 1.
 */
class GuidHeap {

    private val buffer = ByteBuffer(32)
    private var count = 0

    val size: Int get() = buffer.size

    fun add(value: ByteArray): Int {
        require(value.size == 16) { "GUID tem que ter 16 bytes, veio ${value.size}" }
        buffer.bytes(value)
        return ++count
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/**
 * Heap de strings de usuario (#US): tamanho comprimido, UTF-16LE e um byte
 * final que diz se algum caractere sai do intervalo ASCII imprimivel.
 *
 * O DummyDll nao tem literais de string em corpo de metodo, entao na pratica
 * este heap fica so com a entrada vazia - mas ele precisa existir com o byte
 * zero inicial para os leitores nao tropecarem.
 */
class UserStringHeap {

    private val buffer = ByteBuffer(16)
    private val offsets = HashMap<String, Int>()

    init {
        buffer.u8(0)
    }

    val size: Int get() = buffer.size
    val isEmpty: Boolean get() = buffer.size <= 1

    fun add(text: String): Int {
        offsets[text]?.let { return it }
        val at = buffer.size
        val encoded = text.toByteArray(Charsets.UTF_16LE)
        buffer.compressed(encoded.size + 1)
        buffer.bytes(encoded)
        var special = 0
        for (ch in text) {
            val code = ch.code
            if (code >= 0x80 || (code in 0x01..0x08) || (code in 0x0E..0x1F) ||
                code == 0x27 || code == 0x2D || code == 0x7F
            ) {
                special = 1
                break
            }
        }
        buffer.u8(special)
        offsets[text] = at
        return at
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
