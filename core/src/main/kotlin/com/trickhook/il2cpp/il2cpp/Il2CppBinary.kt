package com.trickhook.il2cpp.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.metadata.Il2CppMethodDefinition
import com.trickhook.il2cpp.metadata.Il2CppRGCTXDefinition
import com.trickhook.il2cpp.metadata.Metadata

private const val COUNT_SANITY_LIMIT = 0x50000L

private val MISSING_GENERIC_INST = Il2CppGenericInst(0L, 0L)

private val MISSING_TYPE_DEFINITION_SIZES = Il2CppTypeDefinitionSizes(0L, 0, 0L, 0L)

class Il2CppBinary(
    val elf: ElfImage,
    val metadata: Metadata,
    requestedCodeRegistration: Long = 0L,
    requestedMetadataRegistration: Long = 0L
) {

    private val reader = elf.reader()
    private val pointerSize = elf.pointerSize

    val version: Double
    val codeRegistrationAddress: Long
    val metadataRegistrationAddress: Long
    val codeRegistration: Il2CppCodeRegistration
    val metadataRegistration: Il2CppMetadataRegistration
    val types: Array<Il2CppType>
    val typeByAddress: Map<Long, Il2CppType>
    val methodSpecs: Array<Il2CppMethodSpec>
    val genericInsts: Array<Il2CppGenericInst>
    val genericInstPointers: LongArray
    val fieldOffsets: LongArray
    val fieldOffsetsArePointers: Boolean
    val typeDefinitionsSizes: Array<Il2CppTypeDefinitionSizes>
    val methodPointers: LongArray
    val genericMethodPointers: LongArray
    val invokerPointers: LongArray
    val customAttributeGenerators: LongArray
    val reversePInvokeWrappers: LongArray
    val unresolvedVirtualCallPointers: LongArray
    val metadataUsages: LongArray
    val genericMethodTable: Array<Il2CppGenericMethodFunctionsDefinitions>
    val codeGenModules: Map<String, Il2CppCodeGenModule>
    val codeGenModuleMethodPointers: Map<String, LongArray>
    val methodDefinitionMethodSpecs: Map<Int, List<Il2CppMethodSpec>>
    val methodSpecGenericMethodPointers: Map<Il2CppMethodSpec, Long>
    val rgctxs: Map<String, Map<Long, Array<Il2CppRGCTXDefinition>>>

    init {
        var running = metadata.version
        var codeAddress = requestedCodeRegistration
        var metadataAddress = requestedMetadataRegistration

        if (codeAddress == 0L || metadataAddress == 0L) {
            val helper = SectionHelper.forImage(
                elf,
                running,
                metadata.methodDefs.size,
                metadata.typeDefs.size,
                metadata.metadataUsagesCount,
                metadata.imageDefs.size
            )
            val foundCode = helper.findCodeRegistration()
            val foundMetadata = helper.findMetadataRegistration()
            var adjusted = foundCode
            if (foundCode != 0L && running >= 24.2) {
                val probe = readCodeRegistrationAt(foundCode, running)
                if (running == 31.0) {
                    if (probe.genericMethodPointersCount > COUNT_SANITY_LIMIT) {
                        adjusted -= pointerSize * 2
                    } else {
                        running = 29.0
                    }
                }
                if (running == 29.0 && probe.genericMethodPointersCount > COUNT_SANITY_LIMIT) {
                    running = 29.1
                    adjusted -= pointerSize * 2
                }
                if (running == 27.0 && probe.reversePInvokeWrapperCount > COUNT_SANITY_LIMIT) {
                    running = 27.1
                    adjusted -= pointerSize
                }
                if (running == 24.4) {
                    adjusted -= pointerSize * 2
                    if (probe.reversePInvokeWrapperCount > COUNT_SANITY_LIMIT) {
                        running = 24.5
                        adjusted -= pointerSize
                    }
                }
                if (running == 24.2 && probe.interopDataCount == 0L) {
                    running = 24.3
                    adjusted -= pointerSize * 2
                }
            }
            if (codeAddress == 0L) codeAddress = adjusted
            if (metadataAddress == 0L) metadataAddress = foundMetadata
        }

        var code = readCodeRegistrationAt(codeAddress, running)
        if (running == 27.0 && code.invokerPointersCount > COUNT_SANITY_LIMIT) {
            running = 27.1
            code = readCodeRegistrationAt(codeAddress, running)
        }
        if (running == 27.1 && looksLikeRuntimeRgctx(code, running)) {
            running = 27.2
        }
        if (running == 24.4 && code.invokerPointersCount > COUNT_SANITY_LIMIT) {
            running = 24.5
            code = readCodeRegistrationAt(codeAddress, running)
        }
        if (running == 24.2 && code.codeGenModules == 0L) {
            running = 24.3
            code = readCodeRegistrationAt(codeAddress, running)
        }
        val meta = readMetadataRegistrationAt(metadataAddress, running)

        version = running
        codeRegistrationAddress = codeAddress
        metadataRegistrationAddress = metadataAddress
        codeRegistration = code
        metadataRegistration = meta

        genericMethodPointers = readPointerArray(code.genericMethodPointers, code.genericMethodPointersCount)
        invokerPointers = readPointerArray(code.invokerPointers, code.invokerPointersCount)
        customAttributeGenerators = if (running < 27.0) {
            readPointerArray(code.customAttributeGenerators, code.customAttributeCount)
        } else {
            LongArray(0)
        }
        metadataUsages = if (running > 16.0 && running < 27.0) {
            readPointerArray(meta.metadataUsages, metadata.metadataUsagesCount)
        } else {
            LongArray(0)
        }
        reversePInvokeWrappers = if (running >= 22.0) {
            readPointerArray(code.reversePInvokeWrappers, code.reversePInvokeWrapperCount)
        } else {
            LongArray(0)
        }
        unresolvedVirtualCallPointers = if (running >= 22.0) {
            readPointerArray(code.unresolvedVirtualCallPointers, code.unresolvedVirtualCallCount)
        } else {
            LongArray(0)
        }

        genericInstPointers = readPointerArray(meta.genericInsts, meta.genericInstsCount)
        genericInsts = Array(genericInstPointers.size) { index ->
            readAt(genericInstPointers[index], MISSING_GENERIC_INST) { readGenericInst(reader) }
        }

        fieldOffsetsArePointers = detectFieldOffsetsArePointers(meta, running)
        fieldOffsets = if (fieldOffsetsArePointers) {
            readPointerArray(meta.fieldOffsets, meta.fieldOffsetsCount)
        } else {
            readUInt32Array(meta.fieldOffsets, meta.fieldOffsetsCount)
        }

        val typePointers = readPointerArray(meta.types, meta.typesCount)
        val byAddress = HashMap<Long, Il2CppType>(typePointers.size)
        val missingType = Il2CppType(0L, 0, running)
        types = Array(typePointers.size) { index ->
            val address = typePointers[index]
            val type = readAt(address, missingType) { readIl2CppType(reader, running) }
            byAddress[address] = type
            type
        }
        typeByAddress = byAddress

        val sizePointers = readPointerArray(meta.typeDefinitionsSizes, meta.typeDefinitionsSizesCount)
        typeDefinitionsSizes = Array(sizePointers.size) { index ->
            readAt(sizePointers[index], MISSING_TYPE_DEFINITION_SIZES) { readTypeDefinitionSizes(reader) }
        }

        val modules = LinkedHashMap<String, Il2CppCodeGenModule>()
        val modulePointers = LinkedHashMap<String, LongArray>()
        val moduleRgctxs = LinkedHashMap<String, Map<Long, Array<Il2CppRGCTXDefinition>>>()
        var directMethodPointers = LongArray(0)
        if (running >= 24.2) {
            for (modulePointer in readPointerArray(code.codeGenModules, code.codeGenModulesCount)) {
                val module = readAt(modulePointer, null) { readCodeGenModule(reader, running) } ?: continue
                val name = readStringAt(module.moduleName) ?: continue
                modules[name] = module
                modulePointers[name] = readPointerArray(module.methodPointers, module.methodPointerCount)
                moduleRgctxs[name] = readModuleRgctxs(module, running)
            }
        } else {
            directMethodPointers = readPointerArray(code.methodPointers, code.methodPointersCount)
        }
        methodPointers = directMethodPointers
        codeGenModules = modules
        codeGenModuleMethodPointers = modulePointers
        rgctxs = moduleRgctxs

        genericMethodTable = readGenericMethodTable(meta, running)
        methodSpecs = readMethodSpecs(meta)

        val specsByDefinition = HashMap<Int, MutableList<Il2CppMethodSpec>>()
        val pointersBySpec = HashMap<Il2CppMethodSpec, Long>()
        for (entry in genericMethodTable) {
            if (entry.genericMethodIndex < 0 || entry.genericMethodIndex >= methodSpecs.size) continue
            if (entry.indices.methodIndex < 0 || entry.indices.methodIndex >= genericMethodPointers.size) continue
            val spec = methodSpecs[entry.genericMethodIndex]
            specsByDefinition.getOrPut(spec.methodDefinitionIndex) { ArrayList() } += spec
            pointersBySpec[spec] = genericMethodPointers[entry.indices.methodIndex]
        }
        methodDefinitionMethodSpecs = specsByDefinition
        methodSpecGenericMethodPointers = pointersBySpec
    }

    fun rva(pointer: Long): Long = elf.rva(pointer)

    fun mapVaToOffset(va: Long): Long = elf.mapVaToOffset(va)

    fun readType(address: Long): Il2CppType =
        readAt(address, Il2CppType(0L, 0, version)) { readIl2CppType(reader, version) }

    fun genericClassAt(address: Long): Il2CppGenericClass? =
        readAt(address, null) { readGenericClass(reader, version) }

    fun genericInstAt(address: Long): Il2CppGenericInst? =
        readAt(address, null) { readGenericInst(reader) }

    fun arrayTypeAt(address: Long): Il2CppArrayType? =
        readAt(address, null) { readArrayType(reader, pointerSize) }

    fun stringAt(address: Long): String? = readStringAt(address)

    fun getMethodPointer(imageName: String, methodDef: Il2CppMethodDefinition, methodIndex: Int): Long {
        if (version >= 24.2) {
            val pointers = codeGenModuleMethodPointers[imageName] ?: return 0L
            val slot = (methodDef.token and 0x00FFFFFF) - 1
            return if (slot in pointers.indices) pointers[slot] else 0L
        }
        val slot = if (methodDef.methodIndex >= 0) methodDef.methodIndex else methodIndex
        return if (slot in methodPointers.indices) methodPointers[slot] else 0L
    }

    fun getFieldOffsetFromIndex(
        typeIndex: Int,
        fieldIndexInType: Int,
        fieldIndex: Int,
        isValueType: Boolean,
        isStatic: Boolean
    ): Int {
        var offset = -1
        if (fieldOffsetsArePointers) {
            if (typeIndex !in fieldOffsets.indices) return -1
            val table = fieldOffsets[typeIndex]
            if (table > 0L) {
                val at = elf.mapVaToOffset(table)
                if (at < 0L) return -1
                val slot = at + 4L * fieldIndexInType
                if (slot < 0L || slot + 4L > reader.size) return -1
                offset = reader.int32At(slot)
            }
        } else {
            if (fieldIndex !in fieldOffsets.indices) return -1
            offset = fieldOffsets[fieldIndex].toInt()
        }
        if (offset > 0 && isValueType && !isStatic) offset -= if (elf.is32Bit) 8 else 16
        return offset
    }

    private fun seek(address: Long): Boolean {
        if (address == 0L) return false
        val offset = elf.mapVaToOffset(address)
        if (offset < 0L || offset >= reader.size) return false
        reader.seek(offset)
        return true
    }

    private fun <T> readAt(address: Long, missing: T, read: () -> T): T =
        if (seek(address)) runCatching(read).getOrDefault(missing) else missing

    private fun readStringAt(address: Long): String? {
        val offset = elf.mapVaToOffset(address)
        return if (offset < 0L || offset >= reader.size) null else reader.readStringToNull(offset)
    }

    private fun readCodeRegistrationAt(address: Long, forVersion: Double) =
        readAt(address, Il2CppCodeRegistration()) { readCodeRegistration(reader, forVersion) }

    private fun readMetadataRegistrationAt(address: Long, forVersion: Double) =
        readAt(address, Il2CppMetadataRegistration()) { readMetadataRegistration(reader, forVersion) }

    private fun fitsInImage(count: Long, stride: Int) =
        count > 0L && count <= Int.MAX_VALUE && count * stride <= reader.size

    private fun readPointerArray(address: Long, count: Long): LongArray {
        if (!fitsInImage(count, pointerSize)) return LongArray(0)
        val values = LongArray(count.toInt())
        val offset = elf.mapVaToOffset(address)
        if (address == 0L || offset < 0L || offset + count * pointerSize > reader.size) return values
        var at = offset
        for (index in values.indices) {
            values[index] = reader.pointerAt(at)
            at += pointerSize
        }
        return values
    }

    private fun readUInt32Array(address: Long, count: Long): LongArray {
        if (!fitsInImage(count, 4)) return LongArray(0)
        val values = LongArray(count.toInt())
        val offset = elf.mapVaToOffset(address)
        if (address == 0L || offset < 0L || offset + count * 4L > reader.size) return values
        var at = offset
        for (index in values.indices) {
            values[index] = reader.uint32At(at)
            at += 4L
        }
        return values
    }

    private fun detectFieldOffsetsArePointers(
        meta: Il2CppMetadataRegistration,
        forVersion: Double
    ): Boolean {
        if (forVersion != 21.0) return forVersion > 21.0
        val probe = readUInt32Array(meta.fieldOffsets, 6L)
        return probe.size == 6 &&
            probe[0] == 0L && probe[1] == 0L && probe[2] == 0L &&
            probe[3] == 0L && probe[4] == 0L && probe[5] > 0L
    }

    private fun looksLikeRuntimeRgctx(code: Il2CppCodeRegistration, forVersion: Double): Boolean {
        for (modulePointer in readPointerArray(code.codeGenModules, code.codeGenModulesCount)) {
            val module = readAt(modulePointer, null) { readCodeGenModule(reader, forVersion) } ?: continue
            if (module.rgctxsCount <= 0L) continue
            val entries = readRgctxArray(module.rgctxs, module.rgctxsCount, forVersion)
            return entries.isNotEmpty() && entries.all { it.dataDummy.toLong() > COUNT_SANITY_LIMIT }
        }
        return false
    }

    private fun readModuleRgctxs(
        module: Il2CppCodeGenModule,
        forVersion: Double
    ): Map<Long, Array<Il2CppRGCTXDefinition>> {
        if (module.rgctxsCount <= 0L) return emptyMap()
        val entries = readRgctxArray(module.rgctxs, module.rgctxsCount, forVersion)
        val ranges = readTokenRangePairs(module.rgctxRanges, module.rgctxRangesCount)
        val byToken = LinkedHashMap<Long, Array<Il2CppRGCTXDefinition>>()
        for (range in ranges) {
            val start = range.range.start
            val length = range.range.length
            if (start < 0 || length < 0 || start.toLong() + length > entries.size) continue
            byToken[range.token] = entries.copyOfRange(start, start + length)
        }
        return byToken
    }

    private fun readRgctxArray(
        address: Long,
        count: Long,
        forVersion: Double
    ): Array<Il2CppRGCTXDefinition> {
        val stride = sizeOfBinaryRgctxDefinition(forVersion, pointerSize)
        if (!canRead(address, count, stride)) return emptyArray()
        seek(address)
        return Array(count.toInt()) { readBinaryRgctxDefinition(reader, forVersion) }
    }

    private fun readTokenRangePairs(address: Long, count: Long): Array<Il2CppTokenRangePair> {
        if (!canRead(address, count, SIZE_OF_TOKEN_RANGE_PAIR)) return emptyArray()
        seek(address)
        return Array(count.toInt()) { readTokenRangePair(reader) }
    }

    private fun readMethodSpecs(meta: Il2CppMetadataRegistration): Array<Il2CppMethodSpec> {
        val count = meta.methodSpecsCount
        if (!canRead(meta.methodSpecs, count, SIZE_OF_METHOD_SPEC)) return emptyArray()
        seek(meta.methodSpecs)
        return Array(count.toInt()) { readMethodSpec(reader) }
    }

    private fun readGenericMethodTable(
        meta: Il2CppMetadataRegistration,
        forVersion: Double
    ): Array<Il2CppGenericMethodFunctionsDefinitions> {
        val count = meta.genericMethodTableCount
        val stride = sizeOfGenericMethodFunctionsDefinitions(forVersion)
        if (!canRead(meta.genericMethodTable, count, stride)) return emptyArray()
        seek(meta.genericMethodTable)
        return Array(count.toInt()) { readGenericMethodFunctionsDefinitions(reader, forVersion) }
    }

    private fun canRead(address: Long, count: Long, stride: Int): Boolean {
        if (address == 0L || stride <= 0 || !fitsInImage(count, stride)) return false
        val offset = elf.mapVaToOffset(address)
        return offset >= 0L && offset + count * stride <= reader.size
    }

    companion object {

        fun load(elf: ElfImage, metadata: Metadata) = Il2CppBinary(elf, metadata)

        fun load(
            elf: ElfImage,
            metadata: Metadata,
            codeRegistration: Long,
            metadataRegistration: Long
        ) = Il2CppBinary(elf, metadata, codeRegistration, metadataRegistration)
    }
}
