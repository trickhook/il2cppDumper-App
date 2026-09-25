package com.trickhook.il2cpp.il2cpp

import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.metadata.Il2CppRGCTXDefinition

enum class Il2CppTypeEnum(val code: Int) {
    IL2CPP_TYPE_END(0x00),
    IL2CPP_TYPE_VOID(0x01),
    IL2CPP_TYPE_BOOLEAN(0x02),
    IL2CPP_TYPE_CHAR(0x03),
    IL2CPP_TYPE_I1(0x04),
    IL2CPP_TYPE_U1(0x05),
    IL2CPP_TYPE_I2(0x06),
    IL2CPP_TYPE_U2(0x07),
    IL2CPP_TYPE_I4(0x08),
    IL2CPP_TYPE_U4(0x09),
    IL2CPP_TYPE_I8(0x0a),
    IL2CPP_TYPE_U8(0x0b),
    IL2CPP_TYPE_R4(0x0c),
    IL2CPP_TYPE_R8(0x0d),
    IL2CPP_TYPE_STRING(0x0e),
    IL2CPP_TYPE_PTR(0x0f),
    IL2CPP_TYPE_BYREF(0x10),
    IL2CPP_TYPE_VALUETYPE(0x11),
    IL2CPP_TYPE_CLASS(0x12),
    IL2CPP_TYPE_VAR(0x13),
    IL2CPP_TYPE_ARRAY(0x14),
    IL2CPP_TYPE_GENERICINST(0x15),
    IL2CPP_TYPE_TYPEDBYREF(0x16),
    IL2CPP_TYPE_I(0x18),
    IL2CPP_TYPE_U(0x19),
    IL2CPP_TYPE_FNPTR(0x1b),
    IL2CPP_TYPE_OBJECT(0x1c),
    IL2CPP_TYPE_SZARRAY(0x1d),
    IL2CPP_TYPE_MVAR(0x1e),
    IL2CPP_TYPE_CMOD_REQD(0x1f),
    IL2CPP_TYPE_CMOD_OPT(0x20),
    IL2CPP_TYPE_INTERNAL(0x21),
    IL2CPP_TYPE_MODIFIER(0x40),
    IL2CPP_TYPE_SENTINEL(0x41),
    IL2CPP_TYPE_PINNED(0x45),
    IL2CPP_TYPE_ENUM(0x55),
    IL2CPP_TYPE_IL2CPP_TYPE_INDEX(0xff);

    companion object {
        private val byCode = entries.associateBy(Il2CppTypeEnum::code)

        fun from(code: Int): Il2CppTypeEnum? = byCode[code]
    }
}

data class Il2CppCodeRegistration(
    val methodPointersCount: Long = 0L,
    val methodPointers: Long = 0L,
    val delegateWrappersFromNativeToManagedCount: Long = 0L,
    val delegateWrappersFromNativeToManaged: Long = 0L,
    val reversePInvokeWrapperCount: Long = 0L,
    val reversePInvokeWrappers: Long = 0L,
    val delegateWrappersFromManagedToNativeCount: Long = 0L,
    val delegateWrappersFromManagedToNative: Long = 0L,
    val marshalingFunctionsCount: Long = 0L,
    val marshalingFunctions: Long = 0L,
    val ccwMarshalingFunctionsCount: Long = 0L,
    val ccwMarshalingFunctions: Long = 0L,
    val genericMethodPointersCount: Long = 0L,
    val genericMethodPointers: Long = 0L,
    val genericAdjustorThunks: Long = 0L,
    val invokerPointersCount: Long = 0L,
    val invokerPointers: Long = 0L,
    val customAttributeCount: Long = 0L,
    val customAttributeGenerators: Long = 0L,
    val guidCount: Long = 0L,
    val guids: Long = 0L,
    val unresolvedVirtualCallCount: Long = 0L,
    val unresolvedVirtualCallPointers: Long = 0L,
    val unresolvedInstanceCallPointers: Long = 0L,
    val unresolvedStaticCallPointers: Long = 0L,
    val interopDataCount: Long = 0L,
    val interopData: Long = 0L,
    val windowsRuntimeFactoryCount: Long = 0L,
    val windowsRuntimeFactoryTable: Long = 0L,
    val codeGenModulesCount: Long = 0L,
    val codeGenModules: Long = 0L
)

data class Il2CppMetadataRegistration(
    val genericClassesCount: Long = 0L,
    val genericClasses: Long = 0L,
    val genericInstsCount: Long = 0L,
    val genericInsts: Long = 0L,
    val genericMethodTableCount: Long = 0L,
    val genericMethodTable: Long = 0L,
    val typesCount: Long = 0L,
    val types: Long = 0L,
    val methodSpecsCount: Long = 0L,
    val methodSpecs: Long = 0L,
    val methodReferencesCount: Long = 0L,
    val methodReferences: Long = 0L,
    val fieldOffsetsCount: Long = 0L,
    val fieldOffsets: Long = 0L,
    val typeDefinitionsSizesCount: Long = 0L,
    val typeDefinitionsSizes: Long = 0L,
    val metadataUsagesCount: Long = 0L,
    val metadataUsages: Long = 0L
)

data class Il2CppType(val data: Long, val bits: Int, val version: Double) {

    private val wideBitfield = version >= 27.2

    val attrs: Int = bits and 0xFFFF
    val typeCode: Int = (bits ushr 16) and 0xFF
    val numMods: Int = if (wideBitfield) (bits ushr 24) and 0x1F else (bits ushr 24) and 0x3F
    val byref: Boolean = ((bits ushr if (wideBitfield) 29 else 30) and 1) == 1
    val pinned: Boolean = ((bits ushr if (wideBitfield) 30 else 31) and 1) == 1
    val valueType: Boolean = wideBitfield && (bits ushr 31) == 1
    val type: Il2CppTypeEnum? = Il2CppTypeEnum.from(typeCode)

    val klassIndex: Int get() = data.toInt()
    val typeHandle: Long get() = data
    val elementType: Long get() = data
    val arrayType: Long get() = data
    val genericParameterIndex: Int get() = data.toInt()
    val genericParameterHandle: Long get() = data
    val genericClass: Long get() = data
}

data class Il2CppGenericContext(val classInst: Long, val methodInst: Long)

data class Il2CppGenericClass(
    val typeDefinitionIndex: Long,
    val type: Long,
    val context: Il2CppGenericContext,
    val cachedClass: Long
)

data class Il2CppGenericInst(val typeArgc: Long, val typeArgv: Long)

data class Il2CppArrayType(
    val etype: Long,
    val rank: Int,
    val numSizes: Int,
    val numLoBounds: Int,
    val sizes: Long,
    val loBounds: Long
)

data class Il2CppGenericMethodIndices(
    val methodIndex: Int,
    val invokerIndex: Int,
    val adjustorThunk: Int
)

data class Il2CppGenericMethodFunctionsDefinitions(
    val genericMethodIndex: Int,
    val indices: Il2CppGenericMethodIndices
)

data class Il2CppMethodSpec(
    val methodDefinitionIndex: Int,
    val classIndexIndex: Int,
    val methodIndexIndex: Int
)

data class Il2CppCodeGenModule(
    val moduleName: Long,
    val methodPointerCount: Long,
    val methodPointers: Long,
    val adjustorThunkCount: Long,
    val adjustorThunks: Long,
    val invokerIndices: Long,
    val reversePInvokeWrapperCount: Long,
    val reversePInvokeWrapperIndices: Long,
    val rgctxRangesCount: Long,
    val rgctxRanges: Long,
    val rgctxsCount: Long,
    val rgctxs: Long,
    val debuggerMetadata: Long,
    val customAttributeCacheGenerator: Long,
    val moduleInitializer: Long,
    val staticConstructorTypeIndices: Long,
    val metadataRegistration: Long,
    val codeRegistration: Long
)

data class Il2CppRange(val start: Int, val length: Int)

data class Il2CppTokenRangePair(val token: Long, val range: Il2CppRange)

data class Il2CppTypeDefinitionSizes(
    val instanceSize: Long,
    val nativeSize: Int,
    val staticFieldsSize: Long,
    val threadStaticFieldsSize: Long
)

private fun hasAdjustorThunks(version: Double) = version == 24.5 || version >= 27.1

internal fun readCodeRegistration(reader: BinaryReader, version: Double) = Il2CppCodeRegistration(
    methodPointersCount = if (version <= 24.1) reader.readPointer() else 0L,
    methodPointers = if (version <= 24.1) reader.readPointer() else 0L,
    delegateWrappersFromNativeToManagedCount = if (version <= 21.0) reader.readPointer() else 0L,
    delegateWrappersFromNativeToManaged = if (version <= 21.0) reader.readPointer() else 0L,
    reversePInvokeWrapperCount = if (version >= 22.0) reader.readPointer() else 0L,
    reversePInvokeWrappers = if (version >= 22.0) reader.readPointer() else 0L,
    delegateWrappersFromManagedToNativeCount = if (version <= 22.0) reader.readPointer() else 0L,
    delegateWrappersFromManagedToNative = if (version <= 22.0) reader.readPointer() else 0L,
    marshalingFunctionsCount = if (version <= 22.0) reader.readPointer() else 0L,
    marshalingFunctions = if (version <= 22.0) reader.readPointer() else 0L,
    ccwMarshalingFunctionsCount = if (version in 21.0..22.0) reader.readPointer() else 0L,
    ccwMarshalingFunctions = if (version in 21.0..22.0) reader.readPointer() else 0L,
    genericMethodPointersCount = reader.readPointer(),
    genericMethodPointers = reader.readPointer(),
    genericAdjustorThunks = if (hasAdjustorThunks(version)) reader.readPointer() else 0L,
    invokerPointersCount = reader.readPointer(),
    invokerPointers = reader.readPointer(),
    customAttributeCount = if (version <= 24.5) reader.readPointer() else 0L,
    customAttributeGenerators = if (version <= 24.5) reader.readPointer() else 0L,
    guidCount = if (version in 21.0..22.0) reader.readPointer() else 0L,
    guids = if (version in 21.0..22.0) reader.readPointer() else 0L,
    unresolvedVirtualCallCount = if (version >= 22.0) reader.readPointer() else 0L,
    unresolvedVirtualCallPointers = if (version >= 22.0) reader.readPointer() else 0L,
    unresolvedInstanceCallPointers = if (version >= 29.1) reader.readPointer() else 0L,
    unresolvedStaticCallPointers = if (version >= 29.1) reader.readPointer() else 0L,
    interopDataCount = if (version >= 23.0) reader.readPointer() else 0L,
    interopData = if (version >= 23.0) reader.readPointer() else 0L,
    windowsRuntimeFactoryCount = if (version >= 24.3) reader.readPointer() else 0L,
    windowsRuntimeFactoryTable = if (version >= 24.3) reader.readPointer() else 0L,
    codeGenModulesCount = if (version >= 24.2) reader.readPointer() else 0L,
    codeGenModules = if (version >= 24.2) reader.readPointer() else 0L
)

internal fun readMetadataRegistration(reader: BinaryReader, version: Double) = Il2CppMetadataRegistration(
    genericClassesCount = reader.readPointer(),
    genericClasses = reader.readPointer(),
    genericInstsCount = reader.readPointer(),
    genericInsts = reader.readPointer(),
    genericMethodTableCount = reader.readPointer(),
    genericMethodTable = reader.readPointer(),
    typesCount = reader.readPointer(),
    types = reader.readPointer(),
    methodSpecsCount = reader.readPointer(),
    methodSpecs = reader.readPointer(),
    methodReferencesCount = if (version <= 16.0) reader.readPointer() else 0L,
    methodReferences = if (version <= 16.0) reader.readPointer() else 0L,
    fieldOffsetsCount = reader.readPointer(),
    fieldOffsets = reader.readPointer(),
    typeDefinitionsSizesCount = reader.readPointer(),
    typeDefinitionsSizes = reader.readPointer(),
    metadataUsagesCount = if (version >= 19.0) reader.readPointer() else 0L,
    metadataUsages = if (version >= 19.0) reader.readPointer() else 0L
)

internal fun readIl2CppType(reader: BinaryReader, version: Double): Il2CppType {
    val data = reader.readPointer()
    val bits = reader.readInt32()
    return Il2CppType(data, bits, version)
}

internal fun readGenericContext(reader: BinaryReader) =
    Il2CppGenericContext(reader.readPointer(), reader.readPointer())

internal fun readGenericClass(reader: BinaryReader, version: Double) = Il2CppGenericClass(
    typeDefinitionIndex = if (version <= 24.5) reader.readPointer() else 0L,
    type = if (version >= 27.0) reader.readPointer() else 0L,
    context = readGenericContext(reader),
    cachedClass = reader.readPointer()
)

internal fun readGenericInst(reader: BinaryReader) =
    Il2CppGenericInst(reader.readPointer(), reader.readPointer())

internal fun readArrayType(reader: BinaryReader, pointerSize: Int): Il2CppArrayType {
    val etype = reader.readPointer()
    val rank = reader.readUByte()
    val numSizes = reader.readUByte()
    val numLoBounds = reader.readUByte()
    reader.position += pointerSize - 3
    return Il2CppArrayType(etype, rank, numSizes, numLoBounds, reader.readPointer(), reader.readPointer())
}

internal fun sizeOfGenericMethodFunctionsDefinitions(version: Double) =
    if (hasAdjustorThunks(version)) 16 else 12

internal fun readGenericMethodFunctionsDefinitions(
    reader: BinaryReader,
    version: Double
): Il2CppGenericMethodFunctionsDefinitions {
    val genericMethodIndex = reader.readInt32()
    val methodIndex = reader.readInt32()
    val invokerIndex = reader.readInt32()
    val adjustorThunk = if (hasAdjustorThunks(version)) reader.readInt32() else 0
    return Il2CppGenericMethodFunctionsDefinitions(
        genericMethodIndex,
        Il2CppGenericMethodIndices(methodIndex, invokerIndex, adjustorThunk)
    )
}

internal const val SIZE_OF_METHOD_SPEC = 12

internal fun readMethodSpec(reader: BinaryReader) =
    Il2CppMethodSpec(reader.readInt32(), reader.readInt32(), reader.readInt32())

internal fun readCodeGenModule(reader: BinaryReader, version: Double) = Il2CppCodeGenModule(
    moduleName = reader.readPointer(),
    methodPointerCount = reader.readPointer(),
    methodPointers = reader.readPointer(),
    adjustorThunkCount = if (hasAdjustorThunks(version)) reader.readPointer() else 0L,
    adjustorThunks = if (hasAdjustorThunks(version)) reader.readPointer() else 0L,
    invokerIndices = reader.readPointer(),
    reversePInvokeWrapperCount = reader.readPointer(),
    reversePInvokeWrapperIndices = reader.readPointer(),
    rgctxRangesCount = reader.readPointer(),
    rgctxRanges = reader.readPointer(),
    rgctxsCount = reader.readPointer(),
    rgctxs = reader.readPointer(),
    debuggerMetadata = reader.readPointer(),
    customAttributeCacheGenerator = if (version in 27.0..27.2) reader.readPointer() else 0L,
    moduleInitializer = if (version >= 27.0) reader.readPointer() else 0L,
    staticConstructorTypeIndices = if (version >= 27.0) reader.readPointer() else 0L,
    metadataRegistration = if (version >= 27.0) reader.readPointer() else 0L,
    codeRegistration = if (version >= 27.0) reader.readPointer() else 0L
)

internal const val SIZE_OF_TOKEN_RANGE_PAIR = 12

internal fun readTokenRangePair(reader: BinaryReader) =
    Il2CppTokenRangePair(reader.readUInt32(), Il2CppRange(reader.readInt32(), reader.readInt32()))

internal fun readTypeDefinitionSizes(reader: BinaryReader) = Il2CppTypeDefinitionSizes(
    instanceSize = reader.readUInt32(),
    nativeSize = reader.readInt32(),
    staticFieldsSize = reader.readUInt32(),
    threadStaticFieldsSize = reader.readUInt32()
)

internal fun sizeOfBinaryRgctxDefinition(version: Double, pointerSize: Int): Int {
    var size = 0
    if (version <= 27.1) size += 4
    if (version >= 29.0) size += pointerSize
    if (version <= 27.1) size += 4
    if (version >= 27.2) size += pointerSize
    return size
}

internal fun readBinaryRgctxDefinition(reader: BinaryReader, version: Double): Il2CppRGCTXDefinition {
    val typePre29 = if (version <= 27.1) reader.readInt32() else 0
    val typePost29 = if (version >= 29.0) reader.readPointer() else 0L
    val dataDummy = if (version <= 27.1) reader.readInt32() else 0
    val data = if (version >= 27.2) reader.readPointer() else 0L
    return Il2CppRGCTXDefinition(typePre29, typePost29, dataDummy, data)
}
