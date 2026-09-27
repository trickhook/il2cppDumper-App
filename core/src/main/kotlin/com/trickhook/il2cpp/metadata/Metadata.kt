package com.trickhook.il2cpp.metadata

import com.trickhook.il2cpp.codm.CompactMetadata
import com.trickhook.il2cpp.io.ArrayByteSource
import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.io.ByteSource
import java.util.TreeMap

class Metadata(source: ByteSource) {

    constructor(source: ByteArray) : this(ArrayByteSource(source))

    val obfuscationKey: Int = detectObfuscationKey(source)

    /**
     * The plaintext metadata. When the file is XOR obfuscated there are two ways
     * to undo it: a heap copy, which is what the historical `ByteArray` path did
     * and keeps the caller's array untouched, or an in-place pass over the source.
     * The second is the only option once the source is a 61 MB private mapping
     * that must not be duplicated on the heap; it is safe because a PRIVATE
     * mapping is copy-on-write and never reaches the file.
     */
    val raw: ByteSource = deobfuscate(source, obfuscationKey)

    private val reader = BinaryReader(raw)
    private var detectedVersion = 0.0

    /**
     * Anotacoes do parse que o chamador deve mostrar: deteccao de dialeto
     * compacto e qualquer discordancia de contagem entre tabelas. Uma tabela lida
     * com o stride errado produz um dump que parece perfeito, entao os avisos aqui
     * sao a unica coisa que separa "certo" de "plausivel".
     */
    val warnings: MutableList<String> = ArrayList()

    private var compactMetadata: CompactMetadata? = null

    /** Nome do dialeto compacto em uso, ou "" quando o layout e o padrao. */
    val compactDialect: String get() = compactMetadata?.name.orEmpty()
    private val stringCache = HashMap<Int, String>()
    private val fieldDefaultValuesByIndex: Map<Int, Il2CppFieldDefaultValue>
    private val parameterDefaultValuesByIndex: Map<Int, Il2CppParameterDefaultValue>
    private val attributeIndexByImageAndToken: Map<Int, Map<Int, Int>>
    private val imageIndexByDefinition: Map<Il2CppImageDefinition, Int>

    val version: Double get() = detectedVersion
    val header: MetadataHeader
    val imageDefs: Array<Il2CppImageDefinition>
    val assemblyDefs: Array<Il2CppAssemblyDefinition>
    val typeDefs: Array<Il2CppTypeDefinition>
    val methodDefs: Array<Il2CppMethodDefinition>
    val parameterDefs: Array<Il2CppParameterDefinition>
    val fieldDefs: Array<Il2CppFieldDefinition>
    val propertyDefs: Array<Il2CppPropertyDefinition>
    val eventDefs: Array<Il2CppEventDefinition>
    val genericContainers: Array<Il2CppGenericContainer>
    val genericParameters: Array<Il2CppGenericParameter>
    val stringLiterals: Array<Il2CppStringLiteral>
    val fieldRefs: Array<Il2CppFieldRef>
    val interfaceIndices: IntArray
    val nestedTypeIndices: IntArray
    val constraintIndices: IntArray
    val vtableMethods: IntArray
    val attributeTypeRanges: Array<Il2CppCustomAttributeTypeRange>
    val attributeTypes: IntArray
    val attributeDataRanges: Array<Il2CppCustomAttributeDataRange>
    val rgctxEntries: Array<Il2CppRGCTXDefinition>
    val metadataUsages: Map<Il2CppMetadataUsage, Map<Long, Long>>
    val metadataUsagesCount: Long
    val methodDefLayout: MethodDefLayout

    init {
        reader.seek(0)
        val sanity = reader.readUInt32()
        require(sanity == SANITY) { "not a valid global-metadata.dat" }
        val fileVersion = reader.readInt32()
        require(fileVersion in MIN_VERSION..MAX_VERSION) { "unsupported metadata version $fileVersion" }
        detectedVersion = fileVersion.toDouble()

        var parsedHeader = readMetadataHeader(reader, detectedVersion)

        // Antes de ler qualquer tabela: as tabelas de alguns jogos vem encolhidas,
        // inclusive images, e a deteccao precisa acontecer antes de images ser
        // lida. Nao ativa nada por nome de arquivo nem de pacote: sonda o stride
        // padrao primeiro e so tenta o compacto se o padrao nao fechar.
        compactMetadata = CompactMetadata.tryDetect(
            reader = reader,
            version = detectedVersion,
            typeDefinitionsOffset = parsedHeader.typeDefinitionsOffset,
            typeDefinitionsSize = parsedHeader.typeDefinitionsSize,
            methodsSize = parsedHeader.methodsSize,
            stringOffset = parsedHeader.stringOffset,
            stringSize = parsedHeader.stringSize,
            standardTypeDefSize = sizeOfTypeDefinition(detectedVersion),
            standardMethodSize = sizeOfMethodDefinition(detectedVersion),
            warnings = warnings
        )

        if (fileVersion == 24) {
            if (parsedHeader.stringLiteralOffset == V242_STRING_LITERAL_OFFSET) {
                detectedVersion = 24.2
                parsedHeader = readMetadataHeader(reader, detectedVersion)
            } else if (readImageDefs(parsedHeader).any { it.token != 1 }) {
                detectedVersion = 24.1
            }
        }
        val images = readImageDefs(parsedHeader)
        if (detectedVersion == 24.2 && parsedHeader.assembliesSize / V242_ASSEMBLY_SIZE < images.size) {
            detectedVersion = 24.4
        }
        val v241Plus = detectedVersion == 24.1 &&
            parsedHeader.assembliesSize / V241_ASSEMBLY_SIZE == images.size
        if (v241Plus) detectedVersion = 24.4
        val assemblies = readTable(
            CompactMetadata.TABLE_ASSEMBLIES,
            parsedHeader.assembliesOffset,
            parsedHeader.assembliesSize,
            sizeOfAssemblyDefinition(detectedVersion),
            detectedVersion,
            images.size.toLong()
        ) { readAssemblyDefinition(it, detectedVersion) }
        if (v241Plus) detectedVersion = 24.1

        val v = detectedVersion
        // O token do typeDef nao existe no dialeto compacto e e sintetizado a
        // partir da particao de tipos das imagens, entao isto tem que estar no
        // lugar antes de typeDefinitions ser lida.
        compactMetadata?.imageTypeRanges = images.map { it.typeStart until it.typeStart + it.typeCount }
        val types = readTable(
            CompactMetadata.TABLE_TYPE_DEFINITIONS,
            parsedHeader.typeDefinitionsOffset,
            parsedHeader.typeDefinitionsSize,
            sizeOfTypeDefinition(v),
            v,
            -1L
        ) { readTypeDefinition(it, v) }
        val methods = readMethods(parsedHeader, types, v)
        val parameters = readTable(
            CompactMetadata.TABLE_PARAMETERS,
            parsedHeader.parametersOffset,
            parsedHeader.parametersSize,
            sizeOfParameterDefinition(v),
            v,
            methods.first.sumOf { it.parameterCount.toLong() }
        ) { readParameterDefinition(it, v) }
        val fields = readTable(
            CompactMetadata.TABLE_FIELDS,
            parsedHeader.fieldsOffset,
            parsedHeader.fieldsSize,
            sizeOfFieldDefinition(v),
            v,
            types.sumOf { it.fieldCount.toLong() }
        ) { readFieldDefinition(it, v) }
        val fieldDefaults = readStructArray(
            parsedHeader.fieldDefaultValuesOffset,
            parsedHeader.fieldDefaultValuesSize,
            SIZE_OF_FIELD_DEFAULT_VALUE
        ) { readFieldDefaultValue(it) }
        val parameterDefaults = readStructArray(
            parsedHeader.parameterDefaultValuesOffset,
            parsedHeader.parameterDefaultValuesSize,
            SIZE_OF_PARAMETER_DEFAULT_VALUE
        ) { readParameterDefaultValue(it) }
        val properties = readTable(
            CompactMetadata.TABLE_PROPERTIES,
            parsedHeader.propertiesOffset,
            parsedHeader.propertiesSize,
            sizeOfPropertyDefinition(v),
            v,
            types.sumOf { it.propertyCount.toLong() }
        ) { readPropertyDefinition(it, v) }
        val interfaces = readIntArray(parsedHeader.interfacesOffset, parsedHeader.interfacesSize)
        val nestedTypes = readIntArray(parsedHeader.nestedTypesOffset, parsedHeader.nestedTypesSize)
        val events = readTable(
            CompactMetadata.TABLE_EVENTS,
            parsedHeader.eventsOffset,
            parsedHeader.eventsSize,
            sizeOfEventDefinition(v),
            v,
            types.sumOf { it.eventCount.toLong() }
        ) { readEventDefinition(it, v) }
        val containers = readTable(
            CompactMetadata.TABLE_GENERIC_CONTAINERS,
            parsedHeader.genericContainersOffset,
            parsedHeader.genericContainersSize,
            SIZE_OF_GENERIC_CONTAINER,
            v,
            -1L
        ) { readGenericContainer(it) }
        val parametersOfGenerics = readTable(
            CompactMetadata.TABLE_GENERIC_PARAMETERS,
            parsedHeader.genericParametersOffset,
            parsedHeader.genericParametersSize,
            SIZE_OF_GENERIC_PARAMETER,
            v,
            containers.sumOf { it.typeArgc.toLong() }
        ) { readGenericParameter(it) }
        val constraints = readIntArray(
            parsedHeader.genericParameterConstraintsOffset,
            parsedHeader.genericParameterConstraintsSize
        )
        val vtables = readIntArray(parsedHeader.vtableMethodsOffset, parsedHeader.vtableMethodsSize)
        val literals = readStructArray(
            parsedHeader.stringLiteralOffset,
            parsedHeader.stringLiteralSize,
            SIZE_OF_STRING_LITERAL
        ) { readStringLiteral(it) }

        var refs = emptyArray<Il2CppFieldRef>()
        var usages: Map<Il2CppMetadataUsage, Map<Long, Long>> = emptyMap()
        var usagesTotal = 0L
        if (v > 16.0) {
            refs = readTable(
                CompactMetadata.TABLE_FIELD_REFS,
                parsedHeader.fieldRefsOffset,
                parsedHeader.fieldRefsSize,
                SIZE_OF_FIELD_REF,
                v,
                -1L
            ) { readFieldRef(it) }
            if (v < 27.0) {
                val usageLists = readTable(
                    CompactMetadata.TABLE_METADATA_USAGE_LISTS,
                    parsedHeader.metadataUsageListsOffset,
                    parsedHeader.metadataUsageListsCount,
                    SIZE_OF_METADATA_USAGE_LIST,
                    v,
                    -1L
                ) { readMetadataUsageList(it) }
                val usagePairs = readStructArray(
                    parsedHeader.metadataUsagePairsOffset,
                    parsedHeader.metadataUsagePairsCount,
                    SIZE_OF_METADATA_USAGE_PAIR
                ) { readMetadataUsagePair(it) }
                val processed = buildMetadataUsages(usageLists, usagePairs)
                usages = processed.first
                usagesTotal = processed.second
            }
        }

        var typeRanges = emptyArray<Il2CppCustomAttributeTypeRange>()
        var typeIndices = IntArray(0)
        if (v > 20.0 && v < 29.0) {
            typeRanges = readTable(
                CompactMetadata.TABLE_ATTRIBUTES_INFO,
                parsedHeader.attributesInfoOffset,
                parsedHeader.attributesInfoCount,
                sizeOfCustomAttributeTypeRange(v),
                v,
                -1L
            ) { readCustomAttributeTypeRange(it, v) }
            typeIndices = readIntArray(parsedHeader.attributeTypesOffset, parsedHeader.attributeTypesCount)
        }
        var dataRanges = emptyArray<Il2CppCustomAttributeDataRange>()
        if (v >= 29.0) {
            dataRanges = readStructArray(
                parsedHeader.attributeDataRangeOffset,
                parsedHeader.attributeDataRangeSize,
                SIZE_OF_CUSTOM_ATTRIBUTE_DATA_RANGE
            ) { readCustomAttributeDataRange(it) }
        }
        var rgctx = emptyArray<Il2CppRGCTXDefinition>()
        if (v <= 24.1) {
            rgctx = readStructArray(
                parsedHeader.rgctxEntriesOffset,
                parsedHeader.rgctxEntriesCount,
                sizeOfRgctxDefinition(v)
            ) { readRgctxDefinition(it, v) }
        }

        header = parsedHeader
        imageDefs = images
        assemblyDefs = assemblies
        typeDefs = types
        methodDefs = methods.first
        methodDefLayout = methods.second
        parameterDefs = parameters
        fieldDefs = fields
        propertyDefs = properties
        eventDefs = events
        genericContainers = containers
        genericParameters = parametersOfGenerics
        stringLiterals = literals
        fieldRefs = refs
        interfaceIndices = interfaces
        nestedTypeIndices = nestedTypes
        constraintIndices = constraints
        vtableMethods = vtables
        attributeTypeRanges = typeRanges
        attributeTypes = typeIndices
        attributeDataRanges = dataRanges
        rgctxEntries = rgctx
        metadataUsages = usages
        metadataUsagesCount = usagesTotal
        fieldDefaultValuesByIndex = fieldDefaults.associateBy { it.fieldIndex }
        parameterDefaultValuesByIndex = parameterDefaults.associateBy { it.parameterIndex }
        imageIndexByDefinition = images.withIndex().associate { (index, image) -> image to index }
        attributeIndexByImageAndToken = buildAttributeIndex(images, typeRanges, dataRanges)
    }

    fun getString(index: Int): String =
        stringCache.getOrPut(index) { reader.readStringToNull(header.stringOffset + index) }

    fun getStringLiteral(index: Int): String {
        val literal = stringLiterals[index]
        // Long arithmetic on purpose: dataIndex is an Int but the sum with the
        // section offset is a file offset and must not wrap on a large metadata.
        val start = header.stringLiteralDataOffset + literal.dataIndex.toLong()
        return raw.stringAt(start, literal.length)
    }

    fun getFieldDefaultValue(index: Int): Il2CppFieldDefaultValue? = fieldDefaultValuesByIndex[index]

    fun getParameterDefaultValue(index: Int): Il2CppParameterDefaultValue? =
        parameterDefaultValuesByIndex[index]

    fun getDefaultValueData(index: Int): Long = header.fieldAndParameterDefaultValueDataOffset + index

    fun getCustomAttributeIndex(imageIndex: Int, customAttributeIndex: Int, token: Int): Int {
        if (detectedVersion <= 24.0) return customAttributeIndex
        return attributeIndexByImageAndToken[imageIndex]?.get(token) ?: -1
    }

    fun getCustomAttributeIndex(
        imageDef: Il2CppImageDefinition,
        customAttributeIndex: Int,
        token: Int
    ): Int {
        val imageIndex = imageIndexByDefinition[imageDef] ?: return -1
        return getCustomAttributeIndex(imageIndex, customAttributeIndex, token)
    }

    fun encodedIndexType(encoded: Long): Int = ((encoded and 0xE0000000L) ushr 29).toInt()

    fun encodedIndexType(encoded: Int): Int = (encoded ushr 29) and 0x7

    fun decodeMethodIndex(encoded: Long): Long =
        if (detectedVersion >= 27.0) (encoded and 0x1FFFFFFEL) ushr 1 else encoded and 0x1FFFFFFFL

    fun decodeMethodIndex(encoded: Int): Int =
        if (detectedVersion >= 27.0) (encoded and 0x1FFFFFFE) ushr 1 else encoded and 0x1FFFFFFF

    private fun readImageDefs(source: MetadataHeader): Array<Il2CppImageDefinition> =
        readTable(
            CompactMetadata.TABLE_IMAGES,
            source.imagesOffset,
            source.imagesSize,
            sizeOfImageDefinition(detectedVersion),
            detectedVersion,
            -1L
        ) { readImageDefinition(it, detectedVersion) }

    /**
     * Uma tabela de structs, transcodificada primeiro quando o dialeto compacto
     * cobre ela. [expectedCount] e quantas entradas as tabelas ja lidas dizem que
     * esta deveria ter, ou -1 quando nao da para saber: e essa checagem que
     * distingue um stride de verdade de um multiplo dele, porque validade de nome
     * passa nos dois.
     */
    private inline fun <reified T> readTable(
        table: String,
        offset: Long,
        byteSize: Int,
        standardSize: Int,
        version: Double,
        expectedCount: Long,
        noinline read: (BinaryReader) -> T
    ): Array<T> {
        val compact = compactMetadata
        if (compact != null && compact.handles(table)) {
            val transcoded = compact.transcode(
                reader, table, offset, byteSize, standardSize, version, expectedCount, read
            )
            if (transcoded != null) return transcoded.toTypedArray()
        }
        return readStructArray(offset, byteSize, standardSize, read)
    }

    /**
     * Os metodos. Dois dialetos concorrem aqui: o do Free Fire, cujo registro e
     * MAIOR que o padrao por causa de um campo extra (resolvido por
     * [readMethodDefs] procurando onde o padding cai), e o compacto, cujo registro
     * e MENOR. Sao problemas opostos, por isso caminhos separados.
     */
    private fun readMethods(
        source: MetadataHeader,
        types: Array<Il2CppTypeDefinition>,
        version: Double
    ): Pair<Array<Il2CppMethodDefinition>, MethodDefLayout> {
        val compact = compactMetadata
        if (compact == null || !compact.handles(CompactMetadata.TABLE_METHODS)) {
            return readMethodDefs(source, types)
        }
        val standard = sizeOfMethodDefinition(version)
        val defs = readTable(
            CompactMetadata.TABLE_METHODS,
            source.methodsOffset,
            source.methodsSize,
            standard,
            version,
            types.sumOf { it.methodCount.toLong() }
        ) { readMethodDefinition(it, version) }
        val stride = compact.strideOf(CompactMetadata.TABLE_METHODS)
        return defs to MethodDefLayout(defs.size, stride, standard, 0, 0, 1.0)
    }

    private inline fun <reified T> readStructArray(
        offset: Long,
        byteSize: Int,
        size: Int,
        read: (BinaryReader) -> T
    ): Array<T> {
        if (size <= 0 || byteSize <= 0) return emptyArray()
        val count = byteSize / size
        if (count <= 0) return emptyArray()
        reader.seek(offset)
        val items = ArrayList<T>(count)
        for (i in 0 until count) items.add(read(reader))
        return items.toTypedArray()
    }

    private fun readIntArray(offset: Long, byteSize: Int): IntArray {
        if (byteSize <= 0) return IntArray(0)
        val count = byteSize / 4
        reader.seek(offset)
        val values = IntArray(count)
        for (i in 0 until count) values[i] = reader.readInt32()
        return values
    }

    private fun buildAttributeIndex(
        images: Array<Il2CppImageDefinition>,
        typeRanges: Array<Il2CppCustomAttributeTypeRange>,
        dataRanges: Array<Il2CppCustomAttributeDataRange>
    ): Map<Int, Map<Int, Int>> {
        if (detectedVersion <= 24.0) return emptyMap()
        val byImage = HashMap<Int, Map<Int, Int>>(images.size)
        images.forEachIndexed { imageIndex, image ->
            val byToken = HashMap<Int, Int>(image.customAttributeCount.coerceAtLeast(0))
            val end = image.customAttributeStart + image.customAttributeCount
            for (i in image.customAttributeStart until end) {
                if (i < 0) continue
                val token = when {
                    detectedVersion >= 29.0 && i < dataRanges.size -> dataRanges[i].token
                    detectedVersion < 29.0 && i < typeRanges.size -> typeRanges[i].token
                    else -> null
                } ?: continue
                byToken[token] = i
            }
            byImage[imageIndex] = byToken
        }
        return byImage
    }

    private fun buildMetadataUsages(
        lists: Array<Il2CppMetadataUsageList>,
        pairs: Array<Il2CppMetadataUsagePair>
    ): Pair<Map<Il2CppMetadataUsage, Map<Long, Long>>, Long> {
        val usages = LinkedHashMap<Il2CppMetadataUsage, TreeMap<Long, Long>>()
        for (i in 1..6) usages[Il2CppMetadataUsage.entries[i]] = TreeMap()
        for (list in lists) {
            for (i in 0 until list.count.toInt()) {
                val offset = (list.start + i).toInt()
                if (offset < 0 || offset >= pairs.size) continue
                val pair = pairs[offset]
                val kind = encodedIndexType(pair.encodedSourceIndex)
                if (kind !in Il2CppMetadataUsage.entries.indices) continue
                val bucket = usages[Il2CppMetadataUsage.entries[kind]] ?: continue
                bucket[pair.destinationIndex] = decodeMethodIndex(pair.encodedSourceIndex)
            }
        }
        val highest = usages.values.maxOfOrNull { it.keys.lastOrNull() ?: 0L } ?: 0L
        return usages to highest + 1
    }

    private fun readMethodDefs(
        source: MetadataHeader,
        types: Array<Il2CppTypeDefinition>
    ): Pair<Array<Il2CppMethodDefinition>, MethodDefLayout> {
        val expected = sizeOfMethodDefinition(detectedVersion)
        val realCount = methodCountFromTypeDefs(types)
        if (realCount <= 0 || source.methodsSize <= 0 || source.methodsSize % realCount != 0) {
            return readStandardMethodDefs(source, expected, 1.0)
        }
        val stride = source.methodsSize / realCount
        if (stride == expected) {
            reader.seek(source.methodsOffset)
            val defs = ArrayList<Il2CppMethodDefinition>(realCount)
            for (i in 0 until realCount) defs.add(readMethodDefinition(reader, detectedVersion))
            return defs.toTypedArray() to
                MethodDefLayout(realCount, stride, expected, 0, 0, 1.0)
        }
        val pad = stride - expected
        if (pad <= 0 || pad > MAX_METHOD_PADDING) {
            return readStandardMethodDefs(source, expected, 0.0)
        }
        reader.seek(source.methodsOffset)
        val rawMethods = reader.readBytes(realCount * stride)
        val owner = buildMethodOwnerTable(types, realCount)
        val parametersCount = source.parametersSize / sizeOfParameterDefinition(detectedVersion)
        val containersCount = source.genericContainersSize / SIZE_OF_GENERIC_CONTAINER
        val blocks = methodSampleBlocks(realCount)
        var bestOffset = -1
        var bestScore = 0.0
        var candidate = 0
        while (candidate <= expected) {
            var sum = 0.0
            for ((start, length) in blocks) {
                val slice = rawMethods.copyOfRange(start * stride, (start + length) * stride)
                val repacked = repackMethods(slice, length, stride, expected, candidate, pad)
                val parsed = parseMethodDefs(repacked, length)
                sum += scoreMethodDefs(
                    parsed, start, owner, source.stringSize, parametersCount, containersCount
                )
            }
            val score = if (blocks.isEmpty()) 0.0 else sum / blocks.size
            if (score > bestScore) {
                bestScore = score
                bestOffset = candidate
            }
            candidate += 4
        }
        if (bestOffset < 0 || bestScore < MIN_LAYOUT_CONFIDENCE) {
            return readStandardMethodDefs(source, expected, bestScore)
        }
        val repacked = repackMethods(rawMethods, realCount, stride, expected, bestOffset, pad)
        return parseMethodDefs(repacked, realCount) to
            MethodDefLayout(realCount, stride, expected, bestOffset, pad, bestScore)
    }

    private fun readStandardMethodDefs(
        source: MetadataHeader,
        expected: Int,
        confidence: Double
    ): Pair<Array<Il2CppMethodDefinition>, MethodDefLayout> {
        val count = if (expected > 0) source.methodsSize / expected else 0
        val defs = readStructArray(source.methodsOffset, source.methodsSize, expected) {
            readMethodDefinition(it, detectedVersion)
        }
        return defs to MethodDefLayout(count, expected, expected, 0, 0, confidence)
    }

    private fun parseMethodDefs(buffer: ByteArray, count: Int): Array<Il2CppMethodDefinition> {
        val blockReader = BinaryReader(buffer)
        val defs = ArrayList<Il2CppMethodDefinition>(count)
        for (i in 0 until count) defs.add(readMethodDefinition(blockReader, detectedVersion))
        return defs.toTypedArray()
    }

    private fun methodCountFromTypeDefs(types: Array<Il2CppTypeDefinition>): Int {
        var count = 0
        for (type in types) {
            if (type.methodStart < 0) continue
            val end = type.methodStart + type.methodCount
            if (end > count) count = end
        }
        return count
    }

    private fun buildMethodOwnerTable(types: Array<Il2CppTypeDefinition>, methodCount: Int): IntArray {
        val owner = IntArray(methodCount) { -1 }
        for (typeIndex in types.indices) {
            val type = types[typeIndex]
            if (type.methodStart < 0) continue
            val end = minOf(type.methodStart + type.methodCount, methodCount)
            for (method in type.methodStart until end) owner[method] = typeIndex
        }
        return owner
    }

    private fun methodSampleBlocks(count: Int): List<Pair<Int, Int>> {
        if (count <= METHOD_SAMPLE_BLOCKS * METHOD_SAMPLE_BLOCK_SIZE) return listOf(0 to count)
        val step = count / METHOD_SAMPLE_BLOCKS
        val blocks = ArrayList<Pair<Int, Int>>(METHOD_SAMPLE_BLOCKS)
        for (block in 0 until METHOD_SAMPLE_BLOCKS) {
            val start = block * step
            val length = minOf(METHOD_SAMPLE_BLOCK_SIZE, count - start)
            if (length > 0) blocks.add(start to length)
        }
        return blocks
    }

    private fun repackMethods(
        source: ByteArray,
        count: Int,
        stride: Int,
        expectedSize: Int,
        padOffset: Int,
        padSize: Int
    ): ByteArray {
        val packed = ByteArray(count * expectedSize)
        val tail = expectedSize - padOffset
        for (i in 0 until count) {
            val from = i * stride
            val to = i * expectedSize
            if (padOffset > 0) System.arraycopy(source, from, packed, to, padOffset)
            if (tail > 0) System.arraycopy(source, from + padOffset + padSize, packed, to + padOffset, tail)
        }
        return packed
    }

    private fun scoreMethodDefs(
        parsed: Array<Il2CppMethodDefinition>,
        firstIndex: Int,
        owner: IntArray,
        stringSize: Int,
        parametersCount: Int,
        genericContainersCount: Int
    ): Double {
        if (parsed.isEmpty()) return 0.0
        var accepted = 0
        var total = 0
        for (i in parsed.indices) {
            val methodIndex = firstIndex + i
            if (methodIndex >= owner.size) break
            total++
            val method = parsed[i]
            if (method.nameIndex < 0 || method.nameIndex >= stringSize) continue
            if (owner[methodIndex] >= 0 && method.declaringType != owner[methodIndex]) continue
            if ((method.token ushr 24) != METHOD_TOKEN_TAG) continue
            if (method.parameterStart < -1 || method.parameterStart > parametersCount) continue
            if (method.genericContainerIndex < -1 ||
                method.genericContainerIndex >= genericContainersCount
            ) continue
            val returnToken = method.returnParameterToken
            if (returnToken != 0 && returnToken != -1 && (returnToken ushr 24) != PARAM_TOKEN_TAG) continue
            if (method.parameterCount > MAX_PARAMETER_COUNT) continue
            accepted++
        }
        return if (total == 0) 0.0 else accepted.toDouble() / total
    }

    private companion object {
        const val SANITY = 0xFAB11BAFL
        private val SANITY_BYTES = byteArrayOf(0xAF.toByte(), 0x1B, 0xB1.toByte(), 0xFA.toByte())

        fun deobfuscate(source: ByteSource, key: Int): ByteSource {
            if (key == 0) return source
            val array = source.array
            if (array != null) {
                return ArrayByteSource(
                    ByteArray(array.size) { (array[it].toInt() xor key).toByte() }
                )
            }
            var at = 0L
            val end = source.size
            val chunk = ByteArray(1 shl 16)
            while (at < end) {
                val length = minOf(chunk.size.toLong(), end - at).toInt()
                source.copyOut(at, chunk, 0, length)
                for (i in 0 until length) chunk[i] = (chunk[i].toInt() xor key).toByte()
                source.copyIn(at, chunk, 0, length)
                at += length
            }
            return source
        }

        fun detectObfuscationKey(source: ByteSource): Int {
            if (source.size < 0x110) return 0
            val key = (source.uint8At(0) xor SANITY_BYTES[0].toInt()) and 0xFF
            if (key == 0) return 0
            for (i in SANITY_BYTES.indices) {
                if ((source.uint8At(i.toLong()) xor SANITY_BYTES[i].toInt()) and 0xFF != key) return 0
            }
            val version = readLittleInt(source, 4, key)
            if (version !in MIN_VERSION..MAX_VERSION) return 0
            var highest = 0L
            var index = 8
            while (index + 8 <= 0x110) {
                val offset = readLittleInt(source, index, key)
                val size = readLittleInt(source, index + 4, key)
                if (offset < 0 || size < 0) return 0
                if (offset > 0) highest = maxOf(highest, offset.toLong() + size)
                index += 8
            }
            return if (highest in 1..source.size) key else 0
        }

        private fun readLittleInt(source: ByteSource, at: Int, key: Int): Int =
            ((source.uint8At(at.toLong()) xor key) and 0xFF) or
                (((source.uint8At(at + 1L) xor key) and 0xFF) shl 8) or
                (((source.uint8At(at + 2L) xor key) and 0xFF) shl 16) or
                (((source.uint8At(at + 3L) xor key) and 0xFF) shl 24)

        const val MIN_VERSION = 16
        const val MAX_VERSION = 31
        const val V242_STRING_LITERAL_OFFSET = 264L
        const val V242_ASSEMBLY_SIZE = 68
        const val V241_ASSEMBLY_SIZE = 64
        const val MAX_METHOD_PADDING = 64
        const val METHOD_SAMPLE_BLOCKS = 8
        const val METHOD_SAMPLE_BLOCK_SIZE = 256
        const val MIN_LAYOUT_CONFIDENCE = 0.95
        const val METHOD_TOKEN_TAG = 0x06
        const val PARAM_TOKEN_TAG = 0x08
        const val MAX_PARAMETER_COUNT = 0x400
    }
}
