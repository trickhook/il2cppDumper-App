package com.trickhook.il2cpp.dotnet

/**
 * Monta a regiao de metadata: a raiz BSJB, os cabecalhos de stream e os heaps.
 *
 * A ordem das streams e a mesma que o Mono.Cecil grava - #~, #Strings, #US,
 * #GUID, #Blob - e streams vazias sao omitidas, que e o motivo de os DLLs de
 * referencia menores nao terem #US.
 */
object MetadataRoot {

    private const val SIGNATURE = 0x424A5342
    private const val VERSION = "v4.0.30319"

    fun build(
        tables: TableSet,
        strings: StringHeap,
        userStrings: UserStringHeap,
        guids: GuidHeap,
        blobs: BlobHeap
    ): ByteArray {
        // O tamanho do heap decide a largura do indice nas tabelas, entao tem
        // que ser resolvido antes de gravar a stream #~.
        var heapSizes = 0
        if (strings.size >= 0x10000) heapSizes = heapSizes or 0x01
        if (guids.size >= 0x10000) heapSizes = heapSizes or 0x02
        if (blobs.size >= 0x10000) heapSizes = heapSizes or 0x04

        val tableBytes = tables.write(heapSizes)
        val stringBytes = strings.toByteArray()
        val userStringBytes = userStrings.toByteArray()
        val guidBytes = guids.toByteArray()
        val blobBytes = blobs.toByteArray()

        val streams = mutableListOf<Pair<String, ByteArray>>()
        streams += "#~" to tableBytes
        streams += "#Strings" to pad4(stringBytes)
        if (!userStrings.isEmpty) streams += "#US" to pad4(userStringBytes)
        if (guidBytes.isNotEmpty()) streams += "#GUID" to guidBytes
        streams += "#Blob" to pad4(blobBytes)

        val versionBytes = versionField()
        var headerSize = 16 + versionBytes.size + 4
        for ((name, _) in streams) headerSize += 8 + align4(name.length + 1)

        val out = ByteBuffer(headerSize + tableBytes.size + stringBytes.size + blobBytes.size + 64)
        out.u32(SIGNATURE)
        out.u16(1)
        out.u16(1)
        out.u32(0)
        out.u32(versionBytes.size)
        out.bytes(versionBytes)
        out.u16(0)
        out.u16(streams.size)

        var offset = headerSize
        for ((name, body) in streams) {
            out.u32(offset)
            out.u32(body.size)
            val raw = name.toByteArray(Charsets.US_ASCII)
            out.bytes(raw)
            out.u8(0)
            var written = raw.size + 1
            while (written % 4 != 0) {
                out.u8(0)
                written++
            }
            offset += body.size
        }
        check(out.size == headerSize) { "cabecalho de metadata saiu com ${out.size}, esperado $headerSize" }
        for ((_, body) in streams) out.bytes(body)
        return out.toByteArray()
    }

    private fun align4(value: Int) = (value + 3) and 3.inv()

    private fun pad4(value: ByteArray): ByteArray {
        val padded = align4(value.size)
        return if (padded == value.size) value else value.copyOf(padded)
    }

    private fun versionField(): ByteArray {
        val raw = VERSION.toByteArray(Charsets.US_ASCII)
        return raw.copyOf(align4(raw.size + 1))
    }
}
