package com.trickhook.il2cpp.codm

import com.trickhook.il2cpp.io.BinaryReader

/**
 * Alguns jogos publicam um `global-metadata.dat` com o header padrao da versao
 * mas com os *structs* encolhidos: campos que cabem em 16 bits viram u16, campos
 * redundantes (token) desaparecem, e nada fica alinhado.
 *
 * O dialeto implementado aqui foi derivado do **Call of Duty Mobile 1.0.57**
 * (metadata v23, 61.895.504 bytes) e esta verificado para AQUELE build:
 * `Il2CppTypeDefinition` tem 80 bytes em vez dos 104 que a versao pede,
 * `Il2CppMethodDefinition` 42 em vez de 56, `Il2CppParameterDefinition` 10 em vez
 * de 16. Nada garante que outro build use os mesmos offsets - e por isso a
 * deteccao valida o conteudo antes de ativar, em vez de confiar em nome de
 * arquivo, de pacote ou hash. O lado binario (`libunity.so`) NAO tem dialeto:
 * `Il2CppCodeRegistration`, `Il2CppMetadataRegistration` e `Il2CppType` sao o v23
 * padrao. O dialeto vive so no `global-metadata.dat`.
 *
 * A estrategia e a mesma do FFMethodLayout: nao bifurcar o parser. Cada registro
 * compacto e lido campo a campo e reempacotado no layout que a versao pede, e so
 * entao entregue ao leitor existente. Assim o resto do dumper nao sabe que o
 * dialeto existe, e quem nao e compacto - o Free Fire, por exemplo - falha a
 * sondagem e segue no caminho padrao sem nenhuma excecao especial.
 *
 * Este arquivo e o espelho de `Il2CppDumper/CODM/CompactMetadata.cs` no fork C#.
 * Os dois tem que produzir `dump.cs` e `script.json` identicos, entao as tabelas
 * de campos abaixo sao para serem comparadas linha a linha com as de la. Os nomes
 * dos campos seguem os das data classes Kotlin (`methodCount`, `typeArgc`,
 * `isMethod`, `hashAlg`, `publicKeyToken`), que e a unica diferenca de forma.
 */
class CompactMetadata private constructor(
    private val dialect: CompactDialect,
    val warnings: MutableList<String>
) {

    val name: String get() = dialect.name

    /**
     * Faixas `[typeStart, typeStart + typeCount)` das imagens, na ordem. O dialeto
     * jogou fora o `token` do typeDef e o rid dele e a posicao do tipo DENTRO da
     * imagem, entao a sintese depende disto. Preenchido depois de ler `images` e
     * antes de ler `typeDefinitions`, que e a ordem em que o parser trabalha.
     */
    var imageTypeRanges: List<IntRange> = emptyList()

    private var typeRids: IntArray? = null

    fun handles(table: String): Boolean = dialect.structs.containsKey(table)

    fun strideOf(table: String): Int = dialect.structs[table]?.stride ?: 0

    /**
     * Le a tabela compacta em [table] e devolve os registros no layout padrao,
     * um bloco de cada vez para nao materializar a tabela inteira duas vezes: o
     * `methods` do CODM sozinho seriam 20 MB de origem mais 27 MB de destino.
     *
     * Devolve null quando a tabela nao e compacta, quando o layout padrao nao pode
     * ser reproduzido byte a byte, ou quando o tamanho da secao nao e um numero
     * inteiro de registros - em todos esses casos o chamador segue no caminho
     * padrao. Recusar e sempre melhor do que entregar um buffer desalinhado.
     */
    fun <T> transcode(
        reader: BinaryReader,
        table: String,
        offset: Long,
        byteSize: Int,
        standardSize: Int,
        version: Double,
        expectedCount: Long,
        read: (BinaryReader) -> T
    ): List<T>? {
        val compact = dialect.structs[table] ?: return null
        if (compact.stride <= 0 || byteSize < 0 || standardSize <= 0) return null
        if (compact.stride >= standardSize) {
            warn(
                "dialeto '${dialect.name}' declara $table com ${compact.stride} bytes, que nao e menor " +
                    "que os $standardSize padrao; lendo com o layout padrao"
            )
            return null
        }

        val count = byteSize / compact.stride
        if (byteSize - count * compact.stride > MAX_ALIGNMENT_SLACK) {
            warn(
                "a tabela $table tem $byteSize bytes, que nao e um numero inteiro de registros " +
                    "compactos de ${compact.stride} bytes; lendo com o layout padrao de $standardSize"
            )
            return null
        }
        if (count == 0) return emptyList()

        val fields = standardLayout(table, version)
        if (fields == null || fields.sumOf { it.size } != standardSize) {
            warn(
                "nao da para reproduzir o layout padrao de $table na v$version " +
                    "(${fields?.sumOf { it.size } ?: -1} != $standardSize); lendo com o layout padrao"
            )
            return null
        }

        if (expectedCount >= 0 && expectedCount != count.toLong()) {
            warn(
                "a tabela compacta $table tem $count entradas no stride ${compact.stride}, mas as tabelas " +
                    "que a indexam somam $expectedCount; o dialeto pode nao ser deste build"
            )
        }
        if (!compact.hasFieldMap) {
            warn(
                "$table tem stride compacto conhecido (${compact.stride}) mas o layout interno ainda nao " +
                    "foi levantado: as $count entradas saem zeradas. A contagem esta certa, que e o que " +
                    "mantem o resto do dump dentro da faixa."
            )
        }

        val out = ArrayList<T>(count)
        val perChunk = maxOf(1, CHUNK_RECORDS)
        val source = ByteArray(perChunk * compact.stride)
        val packed = ByteArray(perChunk * standardSize)
        var index = 0
        while (index < count) {
            val records = minOf(perChunk, count - index)
            reader.source.copyOut(offset + index.toLong() * compact.stride, source, 0, records * compact.stride)
            java.util.Arrays.fill(packed, 0, records * standardSize, 0)
            for (i in 0 until records) {
                var dst = i * standardSize
                val src = i * compact.stride
                for (field in fields) {
                    val mapped = compact.fields[field.name]
                    if (mapped != null) {
                        if (mapped.sign == CompactSign.RAW) {
                            System.arraycopy(source, src + mapped.offset, packed, dst, minOf(mapped.width, field.size))
                        } else {
                            write(packed, dst, field.size, readField(source, src, mapped))
                        }
                    } else {
                        val synthesize = compact.synthesized[field.name]
                        if (synthesize != null) write(packed, dst, field.size, synthesize(this, index + i))
                    }
                    dst += field.size
                }
            }
            val block = BinaryReader(packed).apply { is32Bit = reader.is32Bit }
            for (i in 0 until records) out.add(read(block))
            index += records
        }
        return out
    }

    private fun warn(message: String) {
        val line = "AVISO: $message"
        if (!warnings.contains(line)) warnings.add(line)
    }

    /**
     * O rid do typeDef: a posicao do tipo dentro da imagem dele, 1-based, que e
     * exatamente como o il2cpp gera o token `0x02000000 | rid`.
     */
    private fun typeRid(index: Int): Int {
        var rids = typeRids
        if (rids == null) {
            val highest = imageTypeRanges.maxOfOrNull { it.last } ?: -1
            rids = IntArray(maxOf(highest + 1, index + 1))
            for (range in imageTypeRanges) {
                if (range.first < 0) continue
                for (i in range.first..minOf(range.last, rids.size - 1)) rids[i] = i - range.first + 1
            }
            // Sem mapa de imagens o rid global e o melhor palpite: erra o token
            // de todo tipo fora da primeira imagem, mas nao trava o dump.
            for (i in rids.indices) if (rids[i] == 0) rids[i] = i + 1
            typeRids = rids
        }
        return if (index in rids.indices) rids[index] else index + 1
    }

    companion object {

        /**
         * Padding de alinhamento que aceitamos no fim de uma secao. As secoes sao
         * alinhadas em 4 e os structs sao packed, entao sobram ate 3 bytes. Por
         * isso NUNCA se deriva contagem por divisibilidade exata.
         */
        const val MAX_ALIGNMENT_SLACK = 3

        /** Quantos registros a sondagem valida antes de aceitar um stride. */
        const val PROBE_SAMPLE_SIZE = 3000

        private const val CHUNK_RECORDS = 8192

        const val TABLE_TYPE_DEFINITIONS = "typeDefinitions"
        const val TABLE_METHODS = "methods"
        const val TABLE_PARAMETERS = "parameters"
        const val TABLE_FIELDS = "fields"
        const val TABLE_PROPERTIES = "properties"
        const val TABLE_EVENTS = "events"
        const val TABLE_IMAGES = "images"
        const val TABLE_ASSEMBLIES = "assemblies"
        const val TABLE_GENERIC_CONTAINERS = "genericContainers"
        const val TABLE_GENERIC_PARAMETERS = "genericParameters"
        const val TABLE_ATTRIBUTES_INFO = "attributesInfo"
        const val TABLE_METADATA_USAGE_LISTS = "metadataUsageLists"
        const val TABLE_FIELD_REFS = "fieldRefs"

        /**
         * Decide se este metadata e de um dialeto compacto conhecido. Devolve null
         * quando nao e, e nesse caso nada no caminho padrao muda.
         *
         * A ordem das checagens importa:
         *  1. o dialeto tem que declarar a mesma versao de metadata e strides
         *     MENORES que os da versao (compacto e sempre menor);
         *  2. se o stride PADRAO fecha, e um metadata normal e paramos aqui;
         *  3. so entao o stride compacto e sondado, com o mesmo criterio;
         *  4. validacao cruzada final: `methodsSize / soma(method_count)` tem que
         *     dar exatamente o stride de metodo que o dialeto declara.
         *
         * A validacao cruzada nao e enfeite: validade de nameIndex sozinha NAO
         * identifica stride nenhum, porque num stride que e multiplo do verdadeiro
         * todo registro amostrado ainda cai em cima de um nameIndex valido. E a
         * contagem que desempata.
         */
        fun tryDetect(
            reader: BinaryReader,
            version: Double,
            typeDefinitionsOffset: Long,
            typeDefinitionsSize: Int,
            methodsSize: Int,
            stringOffset: Long,
            stringSize: Int,
            standardTypeDefSize: Int,
            standardMethodSize: Int,
            warnings: MutableList<String>
        ): CompactMetadata? {
            for (candidate in dialects) {
                if (version != candidate.metadataVersion) continue
                val compactTypeDef = candidate.structs[TABLE_TYPE_DEFINITIONS] ?: continue
                if (compactTypeDef.stride >= standardTypeDefSize ||
                    candidate.methodDefinitionStride >= standardMethodSize
                ) {
                    warnings += "AVISO: dialeto '${candidate.name}' declara strides que nao sao menores que " +
                        "os padrao da v$version; nao ativando"
                    continue
                }
                if (typeDefinitionsSize <= 0) continue
                if (typeDefinitionsOffset + typeDefinitionsSize > reader.size) continue

                val raw = reader.source.slice(typeDefinitionsOffset, typeDefinitionsSize)
                val strings = StringTable(reader, stringOffset, stringSize)

                val standardProbe = probeLayoutFromStandard(version, standardTypeDefSize)
                if (standardProbe != null && probe(raw, standardProbe, strings) != null) return null

                val compactProbe = probeLayoutFromCompact(compactTypeDef) ?: continue
                val result = probe(raw, compactProbe, strings)
                if (result == null) {
                    warnings += "AVISO: Il2CppTypeDefinition nao cabe no layout padrao de $standardTypeDefSize " +
                        "bytes da v$version nem no compacto conhecido de ${compactTypeDef.stride}; " +
                        "usando o padrao, o dump pode sair errado"
                    continue
                }
                val (typeCount, methodSum) = result
                if (methodSum <= 0L || methodsSize <= 0 || methodsSize % methodSum != 0L) {
                    warnings += "AVISO: o Il2CppTypeDefinition compacto de ${compactTypeDef.stride} bytes le " +
                        "$typeCount tipos validos, mas os method_count somam $methodSum, que nao divide a " +
                        "tabela de metodos de $methodsSize bytes; nao ativando o layout compacto"
                    continue
                }
                val methodStride = methodsSize / methodSum
                if (methodStride != candidate.methodDefinitionStride.toLong()) {
                    warnings += "AVISO: a validacao cruzada da um Il2CppMethodDefinition de $methodStride bytes, " +
                        "mas o dialeto '${candidate.name}' declara ${candidate.methodDefinitionStride}; " +
                        "nao ativando o layout compacto"
                    continue
                }
                warnings += "dialeto compacto '${candidate.name}': Il2CppTypeDefinition tem " +
                    "${compactTypeDef.stride} bytes em vez de $standardTypeDefSize e Il2CppMethodDefinition " +
                    "$methodStride em vez de $standardMethodSize; transcodificando $typeCount tipos e " +
                    "$methodSum metodos para o layout padrao da v$version"
                return CompactMetadata(candidate, warnings)
            }
            return null
        }

        private class StringTable(
            private val reader: BinaryReader,
            private val offset: Long,
            private val size: Int
        ) {
            /**
             * Indice de string plausivel: dentro da tabela e precedido de um NUL,
             * ou seja, e o comeco de uma string e nao o meio de outra.
             */
            fun looksLikeString(index: Long): Boolean {
                if (index < 0L || index >= size) return false
                if (index == 0L) return true
                val at = offset + index - 1
                if (at < 0L || at >= reader.size) return false
                return reader.source.byteAt(at).toInt() == 0
            }
        }

        private class ProbeLayout(
            val stride: Int,
            val nameIndex: CompactField,
            val namespaceIndex: CompactField,
            val methodCount: CompactField
        )

        private fun probeLayoutFromCompact(compact: CompactStruct): ProbeLayout? {
            val name = compact.fields["nameIndex"] ?: return null
            val namespace = compact.fields["namespaceIndex"] ?: return null
            val methods = compact.fields["methodCount"] ?: return null
            return ProbeLayout(compact.stride, name, namespace, methods)
        }

        /** O mesmo descritor derivado do struct padrao, para o mesmo criterio nas duas sondagens. */
        private fun probeLayoutFromStandard(version: Double, standardStride: Int): ProbeLayout? {
            val fields = standardLayout(TABLE_TYPE_DEFINITIONS, version) ?: return null
            var offset = 0
            var name: CompactField? = null
            var namespace: CompactField? = null
            var methods: CompactField? = null
            for (field in fields) {
                when (field.name) {
                    "nameIndex" -> name = CompactField(offset, field.size, CompactSign.UNSIGNED)
                    "namespaceIndex" -> namespace = CompactField(offset, field.size, CompactSign.UNSIGNED)
                    "methodCount" -> methods = CompactField(offset, field.size, CompactSign.UNSIGNED)
                }
                offset += field.size
            }
            if (offset != standardStride) return null
            return ProbeLayout(standardStride, name ?: return null, namespace ?: return null, methods ?: return null)
        }

        /** (contagem, soma dos method_count) quando o stride fecha, null quando nao. */
        private fun probe(raw: ByteArray, layout: ProbeLayout, strings: StringTable): Pair<Int, Long>? {
            if (layout.stride <= 0) return null
            val count = raw.size / layout.stride
            if (count <= 0) return null
            if (raw.size - count * layout.stride > MAX_ALIGNMENT_SLACK) return null

            // Nomes: 100% numa amostra espalhada pela tabela inteira. Um stride
            // errado quase sempre erra ja no segundo registro, mas amostrar so o
            // comeco deixa passar strides que acertam por acaso numa regiao.
            val step = maxOf(1, count / PROBE_SAMPLE_SIZE)
            var i = 0
            while (i < count) {
                val at = i * layout.stride
                if (!strings.looksLikeString(readField(raw, at, layout.nameIndex))) return null
                if (!strings.looksLikeString(readField(raw, at, layout.namespaceIndex))) return null
                i += step
            }
            if (!strings.looksLikeString(readField(raw, (count - 1) * layout.stride, layout.nameIndex))) return null

            var sum = 0L
            for (k in 0 until count) {
                val value = readField(raw, k * layout.stride, layout.methodCount)
                if (value < 0L) return null
                sum += value
            }
            return count to sum
        }

        private fun readField(raw: ByteArray, recordStart: Int, field: CompactField): Long {
            val at = recordStart + field.offset
            return when (field.width) {
                1 -> (raw[at].toLong() and 0xFF)
                2 -> {
                    val u16 = (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8)
                    when (field.sign) {
                        CompactSign.SIGNED -> u16.toShort().toLong()
                        CompactSign.NONE_SENTINEL -> if (u16 == 0xFFFF) -1L else u16.toLong()
                        else -> u16.toLong()
                    }
                }
                4 -> {
                    val i32 = (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8) or
                        ((raw[at + 2].toInt() and 0xFF) shl 16) or ((raw[at + 3].toInt() and 0xFF) shl 24)
                    when (field.sign) {
                        CompactSign.SIGNED -> i32.toLong()
                        CompactSign.NONE_SENTINEL -> if (i32 == -1) -1L else i32.toLong() and 0xFFFFFFFFL
                        else -> i32.toLong() and 0xFFFFFFFFL
                    }
                }
                else -> throw UnsupportedOperationException("largura de campo compacto ${field.width}")
            }
        }

        private fun write(buffer: ByteArray, at: Int, size: Int, value: Long) {
            for (i in 0 until size) buffer[at + i] = (value ushr (8 * i)).toByte()
        }

        // --------------------------------------------------------------
        // Layout padrao. Tem que casar EXATAMENTE, campo a campo e na mesma
        // ordem, com as funcoes read*Definition de MetadataStructs.kt: e elas que
        // vao ler o buffer que sintetizamos, e qualquer divergencia desalinha
        // tudo silenciosamente. A soma e conferida contra sizeOf* antes de usar.
        // --------------------------------------------------------------

        private fun standardLayout(table: String, v: Double): List<StandardField>? {
            val out = ArrayList<StandardField>(40)
            fun f(name: String, size: Int = 4) { out += StandardField(name, size) }
            fun f(name: String, size: Int, keep: Boolean) { if (keep) out += StandardField(name, size) }
            when (table) {
                TABLE_TYPE_DEFINITIONS -> {
                    f("nameIndex"); f("namespaceIndex")
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("byvalTypeIndex")
                    f("byrefTypeIndex", 4, v <= 24.5)
                    f("declaringTypeIndex"); f("parentIndex"); f("elementTypeIndex")
                    f("rgctxStartIndex", 4, v <= 24.1); f("rgctxCount", 4, v <= 24.1)
                    f("genericContainerIndex")
                    f("delegateWrapperFromManagedToNativeIndex", 4, v <= 22.0)
                    f("marshalingFunctionsIndex", 4, v <= 22.0)
                    f("ccwFunctionIndex", 4, v in 21.0..22.0)
                    f("guidIndex", 4, v in 21.0..22.0)
                    f("flags"); f("fieldStart"); f("methodStart"); f("eventStart"); f("propertyStart")
                    f("nestedTypesStart"); f("interfacesStart"); f("vtableStart"); f("interfaceOffsetsStart")
                    f("methodCount", 2); f("propertyCount", 2); f("fieldCount", 2); f("eventCount", 2)
                    f("nestedTypeCount", 2); f("vtableCount", 2); f("interfacesCount", 2)
                    f("interfaceOffsetsCount", 2)
                    f("bitfield")
                    f("token", 4, v >= 19.0)
                }
                TABLE_METHODS -> {
                    f("nameIndex"); f("declaringType"); f("returnType")
                    f("returnParameterToken", 4, v >= 31.0)
                    f("parameterStart")
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("genericContainerIndex")
                    f("methodIndex", 4, v <= 24.1); f("invokerIndex", 4, v <= 24.1)
                    f("delegateWrapperIndex", 4, v <= 24.1)
                    f("rgctxStartIndex", 4, v <= 24.1); f("rgctxCount", 4, v <= 24.1)
                    f("token")
                    f("flags", 2); f("iflags", 2); f("slot", 2); f("parameterCount", 2)
                }
                TABLE_PARAMETERS -> {
                    f("nameIndex"); f("token")
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("typeIndex")
                }
                TABLE_FIELDS -> {
                    f("nameIndex"); f("typeIndex")
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("token", 4, v >= 19.0)
                }
                TABLE_PROPERTIES -> {
                    f("nameIndex"); f("get"); f("set"); f("attrs")
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("token", 4, v >= 19.0)
                }
                TABLE_EVENTS -> {
                    f("nameIndex"); f("typeIndex"); f("add"); f("remove"); f("raise")
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("token", 4, v >= 19.0)
                }
                TABLE_IMAGES -> {
                    f("nameIndex"); f("assemblyIndex"); f("typeStart"); f("typeCount")
                    f("exportedTypeStart", 4, v >= 24.0); f("exportedTypeCount", 4, v >= 24.0)
                    f("entryPointIndex")
                    f("token", 4, v >= 19.0)
                    f("customAttributeStart", 4, v >= 24.1); f("customAttributeCount", 4, v >= 24.1)
                }
                TABLE_ASSEMBLIES -> {
                    f("imageIndex")
                    f("token", 4, v >= 24.1)
                    f("customAttributeIndex", 4, v <= 24.0)
                    f("referencedAssemblyStart", 4, v >= 20.0); f("referencedAssemblyCount", 4, v >= 20.0)
                    f("aname.nameIndex"); f("aname.cultureIndex")
                    f("aname.hashValueIndex", 4, v <= 24.3)
                    f("aname.publicKeyIndex"); f("aname.hashAlg"); f("aname.hashLen"); f("aname.flags")
                    f("aname.major"); f("aname.minor"); f("aname.build"); f("aname.revision")
                    f("aname.publicKeyToken", 8)
                }
                TABLE_GENERIC_CONTAINERS -> {
                    f("ownerIndex"); f("typeArgc"); f("isMethod"); f("genericParameterStart")
                }
                TABLE_GENERIC_PARAMETERS -> {
                    f("ownerIndex"); f("nameIndex")
                    f("constraintsStart", 2); f("constraintsCount", 2); f("num", 2); f("flags", 2)
                }
                TABLE_ATTRIBUTES_INFO -> {
                    f("token", 4, v >= 24.1)
                    f("start"); f("count")
                }
                TABLE_METADATA_USAGE_LISTS -> { f("start"); f("count") }
                TABLE_FIELD_REFS -> { f("typeIndex"); f("fieldIndex") }
                else -> return null
            }
            return out
        }

        // --------------------------------------------------------------
        // Dialetos
        // --------------------------------------------------------------

        private fun u32(offset: Int) = CompactField(offset, 4, CompactSign.UNSIGNED)
        private fun i32(offset: Int) = CompactField(offset, 4, CompactSign.SIGNED)
        private fun u16(offset: Int) = CompactField(offset, 2, CompactSign.UNSIGNED)
        private fun i16(offset: Int) = CompactField(offset, 2, CompactSign.SIGNED)

        /** u16 em que 0xFFFF quer dizer "nenhum": tem que virar -1, senao o dumper indexa 65535. */
        private fun n16(offset: Int) = CompactField(offset, 2, CompactSign.NONE_SENTINEL)

        private fun bytes(offset: Int, length: Int) = CompactField(offset, length, CompactSign.RAW)

        /**
         * Token de uma tabela cujo rid nao da para recuperar por imagem. Na v23 o
         * dumper so usa estes tokens para imprimir, entao um rid global 1-based e
         * honesto: unico por registro, da tabela certa, e sem fingir ser o rid
         * original do assembly.
         */
        private fun globalRidToken(table: Long): (CompactMetadata, Int) -> Long =
            { _, index -> (table shl 24) or (index + 1).toLong() }

        /**
         * Dialeto compacto de metadata v23, do Call of Duty Mobile 1.0.57.
         *
         * A regra de projeto observada, que vale para todas as tabelas: campo que
         * precisa de mais de 16 bits continua int32 e e promovido para o inicio do
         * struct; todo o resto vira u16 mantendo a ordem relativa do struct
         * original. Nada e alinhado. Nos campos `*Start` de lista a sentinela
         * "nenhum" e 0xFFFF; nos `customAttributeIndex` a sentinela e 0, porque o
         * registro 0 de `attributesInfo` e um `(start=0, count=0)` proprio para isso.
         *
         * Tabelas que NAO precisam de entrada porque sao identicas ao padrao:
         * `nestedTypes`, `interfaces`, `vtableMethods`, `genericParameterConstraints`
         * e `attributeTypes` (arrays de int32 crus); `metadataUsagePairs` (8),
         * `stringLiteral` (8), `fieldDefaultValues` (12), `parameterDefaultValues`
         * (12) e `rgctxEntries` (8), todas verificadas como stock.
         */
        private fun compactV23(): CompactDialect {
            val dialect = CompactDialect("compact v23", 23.0, 80, 42)

            dialect.add(TABLE_TYPE_DEFINITIONS, 80) {
                map("nameIndex", u32(0))
                map("namespaceIndex", u32(4))
                map("byvalTypeIndex", i32(8))
                map("byrefTypeIndex", i32(12))
                map("declaringTypeIndex", i32(16))
                map("parentIndex", i32(20))
                map("elementTypeIndex", i32(24))
                map("flags", u32(28))
                map("fieldStart", i32(32))
                map("methodStart", i32(36))
                map("vtableStart", i32(40))
                map("customAttributeIndex", i16(44))
                map("rgctxStartIndex", i16(46))
                map("rgctxCount", i16(48))
                map("genericContainerIndex", i16(50))
                map("eventStart", i16(52))
                map("propertyStart", n16(54))
                map("nestedTypesStart", n16(56))
                map("interfacesStart", n16(58))
                map("interfaceOffsetsStart", n16(60))
                map("methodCount", u16(62))
                map("propertyCount", u16(64))
                map("fieldCount", u16(66))
                map("eventCount", u16(68))
                map("nestedTypeCount", u16(70))
                map("vtableCount", u16(72))
                map("interfacesCount", u16(74))
                map("interfaceOffsetsCount", u16(76))
                map("bitfield", u16(78))
                synthesize("token") { compact, index -> 0x02000000L or compact.typeRid(index).toLong() }
            }

            dialect.add(TABLE_METHODS, 42) {
                map("nameIndex", u32(0))
                map("methodIndex", i32(4))
                map("returnType", i32(8))
                map("parameterStart", i32(12))
                map("token", u32(16))
                map("declaringType", u16(20))
                map("customAttributeIndex", u16(22))
                map("genericContainerIndex", n16(24))
                map("invokerIndex", n16(26))
                map("delegateWrapperIndex", n16(28))
                map("rgctxStartIndex", n16(30))
                map("rgctxCount", u16(32))
                map("flags", u16(34))
                map("iflags", u16(36))
                // slot e ushort no struct padrao tambem, e 0xFFFF ("sem slot") e o
                // valor que o resto do dumper espera: nao virar -1 aqui.
                map("slot", u16(38))
                map("parameterCount", u16(40))
            }

            dialect.add(TABLE_PARAMETERS, 10) {
                map("nameIndex", u32(0))
                map("customAttributeIndex", u16(4))
                map("typeIndex", i32(6))
                synthesize("token", globalRidToken(0x08))
            }

            dialect.add(TABLE_FIELDS, 10) {
                map("nameIndex", u32(0))
                map("typeIndex", i32(4))
                map("customAttributeIndex", u16(8))
                synthesize("token", globalRidToken(0x04))
            }

            dialect.add(TABLE_PROPERTIES, 12) {
                map("nameIndex", u32(0))
                map("get", i16(4))
                map("set", i16(6))
                // attrs e 0 em todos os 42.514 registros deste build (um metadata
                // v31 padrao tambem da 0 aqui), entao o rotulo e inferido.
                map("attrs", u16(8))
                map("customAttributeIndex", u16(10))
                synthesize("token", globalRidToken(0x17))
            }

            dialect.add(TABLE_EVENTS, 16) {
                map("nameIndex", u32(0))
                map("typeIndex", i32(4))
                map("add", i16(8))
                map("remove", i16(10))
                map("raise", i16(12))
                map("customAttributeIndex", u16(14))
                synthesize("token", globalRidToken(0x14))
            }

            dialect.add(TABLE_IMAGES, 20) {
                map("nameIndex", u32(0))
                map("assemblyIndex", i32(4))
                map("typeStart", i32(8))
                map("typeCount", u32(12))
                map("entryPointIndex", i32(16))
                // O token de imagem e sempre 1 no il2cpp (a unica row de Module).
                synthesize("token") { _, _ -> 1L }
            }

            dialect.add(TABLE_ASSEMBLIES, 66) {
                map("imageIndex", i32(0))
                map("customAttributeIndex", i16(4))
                map("referencedAssemblyStart", i32(6))
                map("referencedAssemblyCount", i32(10))
                // Il2CppAssemblyNameDefinition nao encolheu: os 52 bytes a partir
                // de +14 sao exatamente o layout padrao.
                map("aname.nameIndex", u32(14))
                map("aname.cultureIndex", u32(18))
                map("aname.hashValueIndex", u32(22))
                map("aname.publicKeyIndex", u32(26))
                map("aname.hashAlg", u32(30))
                map("aname.hashLen", i32(34))
                map("aname.flags", u32(38))
                map("aname.major", i32(42))
                map("aname.minor", i32(46))
                map("aname.build", i32(50))
                map("aname.revision", i32(54))
                map("aname.publicKeyToken", bytes(58, 8))
            }

            dialect.add(TABLE_GENERIC_CONTAINERS, 12) {
                // Ordem trocada em relacao ao struct padrao (ownerIndex, typeArgc,
                // isMethod, genericParameterStart): o mapa e por nome, entao a
                // troca sai de graca.
                map("ownerIndex", i32(0))
                map("genericParameterStart", i32(4))
                map("typeArgc", u16(8))
                map("isMethod", u16(10))
            }

            dialect.add(TABLE_GENERIC_PARAMETERS, 14) {
                map("nameIndex", u32(0))
                // ownerIndex e u16 aqui, nao i32: ler 4 bytes funde ele com
                // constraintsStart e a validade cai para 60%.
                map("ownerIndex", u16(4))
                map("constraintsStart", u16(6))
                map("constraintsCount", u16(8))
                map("num", u16(10))
                map("flags", u16(12))
            }

            dialect.add(TABLE_ATTRIBUTES_INFO, 4) {
                map("start", u16(0))
                map("count", u16(2))
            }

            dialect.add(TABLE_METADATA_USAGE_LISTS, 6) {
                map("start", u32(0))
                map("count", u16(4))
            }

            dialect.add(TABLE_FIELD_REFS, 6) {
                map("typeIndex", i32(0))
                map("fieldIndex", u16(4))
            }

            return dialect
        }

        val dialects: List<CompactDialect> by lazy { listOf(compactV23()) }
    }
}

enum class CompactSign {
    UNSIGNED,
    SIGNED,

    /** Valor todo-1 significa "nenhum" e vira -1 no campo padrao. */
    NONE_SENTINEL,

    /** Bytes copiados como estao (arrays fixos). */
    RAW
}

class CompactField(val offset: Int, val width: Int, val sign: CompactSign)

class StandardField(val name: String, val size: Int)

class CompactStruct(val stride: Int) {
    /** Nome do campo no struct padrao -> onde ler no registro compacto. */
    val fields = HashMap<String, CompactField>()

    /** Campos que o dialeto jogou fora e o dumper ainda usa. */
    val synthesized = HashMap<String, (CompactMetadata, Int) -> Long>()

    val hasFieldMap: Boolean get() = fields.isNotEmpty() || synthesized.isNotEmpty()

    fun map(standardField: String, source: CompactField) { fields[standardField] = source }

    fun synthesize(standardField: String, value: (CompactMetadata, Int) -> Long) {
        synthesized[standardField] = value
    }
}

class CompactDialect(
    val name: String,
    val metadataVersion: Double,
    val typeDefinitionStride: Int,
    val methodDefinitionStride: Int
) {
    val structs = HashMap<String, CompactStruct>()

    fun add(table: String, stride: Int, build: CompactStruct.() -> Unit) {
        structs[table] = CompactStruct(stride).apply(build)
    }
}
