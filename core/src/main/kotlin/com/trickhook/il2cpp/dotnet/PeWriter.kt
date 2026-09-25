package com.trickhook.il2cpp.dotnet

/**
 * Monta o arquivo PE de um assembly gerenciado somente-IL.
 *
 * Layout copiado do que o Mono.Cecil produz, medido nos DLLs de referencia:
 * PE32 (nao PE32+, mesmo para jogos arm64 - o formato gerenciado nao segue a
 * arquitetura do jogo), duas secoes, metadata "v4.0.30319", stream #~ na
 * versao 2.0.
 *
 * A secao .reloc existe so por causa do stub de 6 bytes que os carregadores
 * antigos do Windows usavam para entrar no CLR. Nenhum runtime atual precisa
 * dele, mas ferramentas conferem a consistencia do cabecalho, entao sai igual.
 */
class PeWriter(
    private val metadata: ByteArray,
    private val code: ByteArray = ByteArray(0),
    private val entryPointToken: Int = 0
) {

    companion object {
        private const val FILE_ALIGNMENT = 0x200
        private const val SECTION_ALIGNMENT = 0x2000
        private const val IMAGE_BASE = 0x00400000

        /** Onde o conteudo do .text comeca depois do cabecalho. */
        private const val TEXT_RVA = 0x2000

        private const val CLI_HEADER_SIZE = 72

        /** COMIMAGE_FLAGS_ILONLY. */
        private const val CLI_FLAGS = 0x00000001

        private val ROM_STUB = byteArrayOf(0xFF.toByte(), 0x25)

        /**
         * RVA onde os corpos de metodo comecam. O cabecalho CLI tem tamanho
         * fixo e vem primeiro no .text, entao isto e constante - e precisa
         * ser, porque a coluna RVA da tabela MethodDef e preenchida antes de
         * este escritor rodar.
         */
        const val CODE_RVA = TEXT_RVA + CLI_HEADER_SIZE
    }

    private fun align(value: Int, boundary: Int): Int {
        val over = value % boundary
        return if (over == 0) value else value + (boundary - over)
    }

    fun build(): ByteArray {
        // A tabela de import tem uma entrada so, _CorDllMain do mscoree.
        val importStub = buildImports()

        val textContent = ByteBuffer(metadata.size + 0x400)
        // O cabecalho CLI vem primeiro no .text, como o Cecil grava.
        val cliRva = TEXT_RVA + textContent.size
        textContent.u32(CLI_HEADER_SIZE)
        textContent.u16(2)
        textContent.u16(5)
        val metadataDirAt = textContent.size
        textContent.u32(0)
        textContent.u32(metadata.size)
        textContent.u32(CLI_FLAGS)
        textContent.u32(entryPointToken)
        // Resources, StrongNameSignature, CodeManagerTable, VTableFixups,
        // ExportAddressTableJumps, ManagedNativeHeader: todos vazios.
        textContent.zeros(48)

        check(TEXT_RVA + textContent.size == CODE_RVA) {
            "cabecalho CLI saiu com ${textContent.size} bytes; CODE_RVA nao bate"
        }
        textContent.bytes(code)
        textContent.align(4)

        val metadataRva = TEXT_RVA + textContent.size
        textContent.bytes(metadata)
        textContent.align(4)

        val importsRva = TEXT_RVA + textContent.size
        val imports = importStub.build(importsRva)
        textContent.bytes(imports.bytes)

        val raw = textContent.toByteArray()
        // Preenche o ponteiro de metadata agora que o RVA e conhecido.
        writeU32(raw, metadataDirAt, metadataRva)

        val textSize = raw.size
        val relocRva = align(TEXT_RVA + textSize, SECTION_ALIGNMENT)
        val reloc = buildReloc(imports.stubFixupRva)

        val headerSize = align(0x80 + 4 + 20 + 224 + 2 * 40, FILE_ALIGNMENT)
        val textFileOffset = headerSize
        val textFileSize = align(textSize, FILE_ALIGNMENT)
        val relocFileOffset = textFileOffset + textFileSize
        val relocFileSize = align(reloc.size, FILE_ALIGNMENT)
        val imageSize = align(relocRva + reloc.size, SECTION_ALIGNMENT)

        val out = ByteBuffer(relocFileOffset + relocFileSize)
        writeDosHeader(out)
        writeCoffAndOptional(
            out,
            textRva = TEXT_RVA, textSize = textSize, textFileOffset = textFileOffset,
            relocRva = relocRva, relocSize = reloc.size, relocFileOffset = relocFileOffset,
            imageSize = imageSize, headerSize = headerSize,
            cliRva = cliRva, importsRva = importsRva,
            importDirSize = imports.directorySize, iatRva = imports.iatRva
        )
        out.zeros(textFileOffset - out.size)
        out.bytes(raw)
        out.zeros(textFileOffset + textFileSize - out.size)
        out.bytes(reloc)
        out.zeros(relocFileOffset + relocFileSize - out.size)
        return out.toByteArray()
    }

    private fun writeU32(data: ByteArray, at: Int, value: Int) {
        data[at] = value.toByte()
        data[at + 1] = (value ushr 8).toByte()
        data[at + 2] = (value ushr 16).toByte()
        data[at + 3] = (value ushr 24).toByte()
    }

    private fun writeDosHeader(out: ByteBuffer) {
        val stub = byteArrayOf(
            0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00, 0x00, 0x00,
            0x04, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
            0xB8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00,
            0x0E, 0x1F, 0xBA.toByte(), 0x0E, 0x00, 0xB4.toByte(), 0x09, 0xCD.toByte(),
            0x21, 0xB8.toByte(), 0x01, 0x4C, 0xCD.toByte(), 0x21, 0x54, 0x68,
            0x69, 0x73, 0x20, 0x70, 0x72, 0x6F, 0x67, 0x72,
            0x61, 0x6D, 0x20, 0x63, 0x61, 0x6E, 0x6E, 0x6F,
            0x74, 0x20, 0x62, 0x65, 0x20, 0x72, 0x75, 0x6E,
            0x20, 0x69, 0x6E, 0x20, 0x44, 0x4F, 0x53, 0x20,
            0x6D, 0x6F, 0x64, 0x65, 0x2E, 0x0D, 0x0D, 0x0A,
            0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        out.bytes(stub)
    }

    private fun writeCoffAndOptional(
        out: ByteBuffer,
        textRva: Int, textSize: Int, textFileOffset: Int,
        relocRva: Int, relocSize: Int, relocFileOffset: Int,
        imageSize: Int, headerSize: Int,
        cliRva: Int, importsRva: Int, importDirSize: Int, iatRva: Int
    ) {
        out.u32(0x00004550)
        out.u16(0x014C)
        out.u16(2)
        out.u32(0)
        out.u32(0)
        out.u32(0)
        out.u16(224)
        // EXECUTABLE_IMAGE | 32BIT_MACHINE | DLL
        out.u16(0x2102)

        val optionalStart = out.size
        out.u16(0x010B)
        out.u8(8)
        out.u8(0)
        out.u32(textSize)
        out.u32(relocSize)
        out.u32(0)
        out.u32(0)
        out.u32(textRva)
        out.u32(textRva)
        out.u32(IMAGE_BASE)
        out.u32(SECTION_ALIGNMENT)
        out.u32(FILE_ALIGNMENT)
        out.u16(4); out.u16(0)
        out.u16(0); out.u16(0)
        out.u16(4); out.u16(0)
        out.u32(0)
        out.u32(imageSize)
        out.u32(headerSize)
        out.u32(0)
        out.u16(3)
        // DYNAMIC_BASE | NO_SEH | NX_COMPAT
        out.u16(0x8540.toShort().toInt() and 0xFFFF)
        out.u32(0x00100000); out.u32(0x00001000)
        out.u32(0x00100000); out.u32(0x00001000)
        out.u32(0)
        out.u32(16)

        // Os 16 data directories, na ordem da norma. A posicao importa: o IAT
        // e o 12 e o cabecalho CLI e o 14, e errar o indice desloca a tabela
        // de secoes inteira.
        val directories = arrayOf(
            0 to 0,                          // 0  Export
            importsRva to importDirSize,     // 1  Import
            0 to 0,                          // 2  Resource
            0 to 0,                          // 3  Exception
            0 to 0,                          // 4  Certificate
            relocRva to relocSize,           // 5  BaseRelocation
            0 to 0,                          // 6  Debug
            0 to 0,                          // 7  Architecture
            0 to 0,                          // 8  GlobalPtr
            0 to 0,                          // 9  TLS
            0 to 0,                          // 10 LoadConfig
            0 to 0,                          // 11 BoundImport
            iatRva to 8,                     // 12 IAT
            0 to 0,                          // 13 DelayImport
            cliRva to CLI_HEADER_SIZE,       // 14 CLI header
            0 to 0                           // 15 reservado
        )
        check(directories.size == 16)
        for ((rva, size) in directories) {
            out.u32(rva)
            out.u32(size)
        }

        // SizeOfOptionalHeader diz 224; se o que foi gravado nao bater, a
        // tabela de secoes fica no lugar errado e o arquivo vira lixo que
        // ainda assim "parece" um PE. Melhor estourar aqui.
        check(out.size - optionalStart == 224) {
            "optional header saiu com ${out.size - optionalStart} bytes, esperado 224"
        }

        section(out, ".text", textRva, textSize, textFileOffset, 0x60000020)
        section(out, ".reloc", relocRva, relocSize, relocFileOffset, 0x42000040)
    }

    private fun section(
        out: ByteBuffer, name: String, rva: Int, virtualSize: Int,
        fileOffset: Int, characteristics: Int
    ) {
        val raw = ByteArray(8)
        val bytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, raw, 0, bytes.size)
        out.bytes(raw)
        out.u32(virtualSize)
        out.u32(rva)
        out.u32(align(virtualSize, FILE_ALIGNMENT))
        out.u32(fileOffset)
        out.u32(0); out.u32(0)
        out.u16(0); out.u16(0)
        out.u32(characteristics)
    }

    private class Imports(val bytes: ByteArray, val iatRva: Int, val directorySize: Int, val stubFixupRva: Int)

    private class ImportBuilder {
        fun build(rva: Int): Imports {
            val b = ByteBuffer(128)
            // Import directory: uma entrada + terminador.
            val dirSize = 40
            val iltRva = rva + dirSize
            val nameRva = iltRva + 8
            val hintRva = nameRva + 12
            val iatRva = hintRva + 16

            b.u32(iltRva); b.u32(0); b.u32(0); b.u32(nameRva); b.u32(iatRva)
            b.u32(0); b.u32(0); b.u32(0); b.u32(0); b.u32(0)

            b.u32(hintRva); b.u32(0)
            b.bytes("mscoree.dll ".toByteArray(Charsets.US_ASCII))
            b.u16(0)
            b.bytes("_CorDllMain ".toByteArray(Charsets.US_ASCII))
            b.align(4)
            while (rva + b.size < iatRva) b.u8(0)
            b.u32(hintRva); b.u32(0)

            val stubRva = rva + b.size
            b.bytes(ROM_STUB)
            b.u32(IMAGE_BASE + iatRva)
            b.align(4)

            return Imports(b.toByteArray(), iatRva, dirSize, stubRva + 2)
        }
    }

    private fun buildImports() = ImportBuilder()

    private fun buildReloc(fixupRva: Int): ByteArray {
        val page = fixupRva and 0x1000.inv()
        val b = ByteBuffer(16)
        b.u32(page)
        b.u32(12)
        // IMAGE_REL_BASED_HIGHLOW sobre o endereco absoluto do stub.
        b.u16((3 shl 12) or (fixupRva - page))
        b.u16(0)
        return b.toByteArray()
    }
}
