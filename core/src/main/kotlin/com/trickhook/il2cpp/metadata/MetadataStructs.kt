package com.trickhook.il2cpp.metadata

import com.trickhook.il2cpp.io.BinaryReader

data class MetadataHeader(
    val sanity: Long,
    val version: Int,
    val stringLiteralOffset: Long,
    val stringLiteralSize: Int,
    val stringLiteralDataOffset: Long,
    val stringLiteralDataSize: Int,
    val stringOffset: Long,
    val stringSize: Int,
    val eventsOffset: Long,
    val eventsSize: Int,
    val propertiesOffset: Long,
    val propertiesSize: Int,
    val methodsOffset: Long,
    val methodsSize: Int,
    val parameterDefaultValuesOffset: Long,
    val parameterDefaultValuesSize: Int,
    val fieldDefaultValuesOffset: Long,
    val fieldDefaultValuesSize: Int,
    val fieldAndParameterDefaultValueDataOffset: Long,
    val fieldAndParameterDefaultValueDataSize: Int,
    val fieldMarshaledSizesOffset: Int,
    val fieldMarshaledSizesSize: Int,
    val parametersOffset: Long,
    val parametersSize: Int,
    val fieldsOffset: Long,
    val fieldsSize: Int,
    val genericParametersOffset: Long,
    val genericParametersSize: Int,
    val genericParameterConstraintsOffset: Long,
    val genericParameterConstraintsSize: Int,
    val genericContainersOffset: Long,
    val genericContainersSize: Int,
    val nestedTypesOffset: Long,
    val nestedTypesSize: Int,
    val interfacesOffset: Long,
    val interfacesSize: Int,
    val vtableMethodsOffset: Long,
    val vtableMethodsSize: Int,
    val interfaceOffsetsOffset: Int,
    val interfaceOffsetsSize: Int,
    val typeDefinitionsOffset: Long,
    val typeDefinitionsSize: Int,
    val rgctxEntriesOffset: Long,
    val rgctxEntriesCount: Int,
    val imagesOffset: Long,
    val imagesSize: Int,
    val assembliesOffset: Long,
    val assembliesSize: Int,
    val metadataUsageListsOffset: Long,
    val metadataUsageListsCount: Int,
    val metadataUsagePairsOffset: Long,
    val metadataUsagePairsCount: Int,
    val fieldRefsOffset: Long,
    val fieldRefsSize: Int,
    val referencedAssembliesOffset: Int,
    val referencedAssembliesSize: Int,
    val attributesInfoOffset: Long,
    val attributesInfoCount: Int,
    val attributeTypesOffset: Long,
    val attributeTypesCount: Int,
    val attributeDataOffset: Long,
    val attributeDataSize: Int,
    val attributeDataRangeOffset: Long,
    val attributeDataRangeSize: Int,
    val unresolvedVirtualCallParameterTypesOffset: Int,
    val unresolvedVirtualCallParameterTypesSize: Int,
    val unresolvedVirtualCallParameterRangesOffset: Int,
    val unresolvedVirtualCallParameterRangesSize: Int,
    val windowsRuntimeTypeNamesOffset: Int,
    val windowsRuntimeTypeNamesSize: Int,
    val windowsRuntimeStringsOffset: Int,
    val windowsRuntimeStringsSize: Int,
    val exportedTypeDefinitionsOffset: Int,
    val exportedTypeDefinitionsSize: Int
)

data class Il2CppAssemblyNameDefinition(
    val nameIndex: Int,
    val cultureIndex: Int,
    val hashValueIndex: Int,
    val publicKeyIndex: Int,
    val hashAlg: Int,
    val hashLen: Int,
    val flags: Int,
    val major: Int,
    val minor: Int,
    val build: Int,
    val revision: Int,
    val publicKeyToken: ByteArray
)

data class Il2CppAssemblyDefinition(
    val imageIndex: Int,
    val token: Int,
    val customAttributeIndex: Int,
    val referencedAssemblyStart: Int,
    val referencedAssemblyCount: Int,
    val aname: Il2CppAssemblyNameDefinition
)

data class Il2CppImageDefinition(
    val nameIndex: Int,
    val assemblyIndex: Int,
    val typeStart: Int,
    val typeCount: Int,
    val exportedTypeStart: Int,
    val exportedTypeCount: Int,
    val entryPointIndex: Int,
    val token: Int,
    val customAttributeStart: Int,
    val customAttributeCount: Int
)

data class Il2CppTypeDefinition(
    val nameIndex: Int,
    val namespaceIndex: Int,
    val customAttributeIndex: Int,
    val byvalTypeIndex: Int,
    val byrefTypeIndex: Int,
    val declaringTypeIndex: Int,
    val parentIndex: Int,
    val elementTypeIndex: Int,
    val rgctxStartIndex: Int,
    val rgctxCount: Int,
    val genericContainerIndex: Int,
    val delegateWrapperFromManagedToNativeIndex: Int,
    val marshalingFunctionsIndex: Int,
    val ccwFunctionIndex: Int,
    val guidIndex: Int,
    val flags: Int,
    val fieldStart: Int,
    val methodStart: Int,
    val eventStart: Int,
    val propertyStart: Int,
    val nestedTypesStart: Int,
    val interfacesStart: Int,
    val vtableStart: Int,
    val interfaceOffsetsStart: Int,
    val methodCount: Int,
    val propertyCount: Int,
    val fieldCount: Int,
    val eventCount: Int,
    val nestedTypeCount: Int,
    val vtableCount: Int,
    val interfacesCount: Int,
    val interfaceOffsetsCount: Int,
    val bitfield: Int,
    val token: Int
) {
    val isValueType: Boolean get() = bitfield and 0x1 == 1
    val isEnum: Boolean get() = (bitfield ushr 1) and 0x1 == 1
}

data class Il2CppMethodDefinition(
    val nameIndex: Int,
    val declaringType: Int,
    val returnType: Int,
    val returnParameterToken: Int,
    val parameterStart: Int,
    val customAttributeIndex: Int,
    val genericContainerIndex: Int,
    val methodIndex: Int,
    val invokerIndex: Int,
    val delegateWrapperIndex: Int,
    val rgctxStartIndex: Int,
    val rgctxCount: Int,
    val token: Int,
    val flags: Int,
    val iflags: Int,
    val slot: Int,
    val parameterCount: Int
)

data class Il2CppParameterDefinition(
    val nameIndex: Int,
    val token: Int,
    val customAttributeIndex: Int,
    val typeIndex: Int
)

data class Il2CppFieldDefinition(
    val nameIndex: Int,
    val typeIndex: Int,
    val customAttributeIndex: Int,
    val token: Int
)

data class Il2CppFieldDefaultValue(
    val fieldIndex: Int,
    val typeIndex: Int,
    val dataIndex: Int
)

data class Il2CppParameterDefaultValue(
    val parameterIndex: Int,
    val typeIndex: Int,
    val dataIndex: Int
)

data class Il2CppPropertyDefinition(
    val nameIndex: Int,
    val get: Int,
    val set: Int,
    val attrs: Int,
    val customAttributeIndex: Int,
    val token: Int
)

data class Il2CppEventDefinition(
    val nameIndex: Int,
    val typeIndex: Int,
    val add: Int,
    val remove: Int,
    val raise: Int,
    val customAttributeIndex: Int,
    val token: Int
)

data class Il2CppCustomAttributeTypeRange(
    val token: Int,
    val start: Int,
    val count: Int
)

data class Il2CppCustomAttributeDataRange(
    val token: Int,
    val startOffset: Long
)

data class Il2CppMetadataUsageList(
    val start: Long,
    val count: Long
)

data class Il2CppMetadataUsagePair(
    val destinationIndex: Long,
    val encodedSourceIndex: Long
)

data class Il2CppStringLiteral(
    val length: Int,
    val dataIndex: Int
)

data class Il2CppGenericContainer(
    val ownerIndex: Int,
    val typeArgc: Int,
    val isMethod: Int,
    val genericParameterStart: Int
)

data class Il2CppGenericParameter(
    val ownerIndex: Int,
    val nameIndex: Int,
    val constraintsStart: Int,
    val constraintsCount: Int,
    val num: Int,
    val flags: Int
)

data class Il2CppFieldRef(
    val typeIndex: Int,
    val fieldIndex: Int
)

data class Il2CppRGCTXDefinition(
    val typePre29: Int,
    val typePost29: Long,
    val dataDummy: Int,
    val data: Long
) {
    val rgctxDataType: Int get() = if (typePost29 == 0L) typePre29 else typePost29.toInt()
}

enum class Il2CppMetadataUsage {
    INVALID,
    TYPE_INFO,
    IL2CPP_TYPE,
    METHOD_DEF,
    FIELD_INFO,
    STRING_LITERAL,
    METHOD_REF
}

data class MethodDefLayout(
    val count: Int,
    val stride: Int,
    val expectedSize: Int,
    val padOffset: Int,
    val padSize: Int,
    val confidence: Double
) {
    val isStandard: Boolean get() = padSize == 0
}

internal fun readMetadataHeader(reader: BinaryReader, version: Double): MetadataHeader {
    reader.seek(0)
    val sanity = reader.readUInt32()
    val headerVersion = reader.readInt32()
    val stringLiteralOffset = reader.readUInt32()
    val stringLiteralSize = reader.readInt32()
    val stringLiteralDataOffset = reader.readUInt32()
    val stringLiteralDataSize = reader.readInt32()
    val stringOffset = reader.readUInt32()
    val stringSize = reader.readInt32()
    val eventsOffset = reader.readUInt32()
    val eventsSize = reader.readInt32()
    val propertiesOffset = reader.readUInt32()
    val propertiesSize = reader.readInt32()
    val methodsOffset = reader.readUInt32()
    val methodsSize = reader.readInt32()
    val parameterDefaultValuesOffset = reader.readUInt32()
    val parameterDefaultValuesSize = reader.readInt32()
    val fieldDefaultValuesOffset = reader.readUInt32()
    val fieldDefaultValuesSize = reader.readInt32()
    val fieldAndParameterDefaultValueDataOffset = reader.readUInt32()
    val fieldAndParameterDefaultValueDataSize = reader.readInt32()
    val fieldMarshaledSizesOffset = reader.readInt32()
    val fieldMarshaledSizesSize = reader.readInt32()
    val parametersOffset = reader.readUInt32()
    val parametersSize = reader.readInt32()
    val fieldsOffset = reader.readUInt32()
    val fieldsSize = reader.readInt32()
    val genericParametersOffset = reader.readUInt32()
    val genericParametersSize = reader.readInt32()
    val genericParameterConstraintsOffset = reader.readUInt32()
    val genericParameterConstraintsSize = reader.readInt32()
    val genericContainersOffset = reader.readUInt32()
    val genericContainersSize = reader.readInt32()
    val nestedTypesOffset = reader.readUInt32()
    val nestedTypesSize = reader.readInt32()
    val interfacesOffset = reader.readUInt32()
    val interfacesSize = reader.readInt32()
    val vtableMethodsOffset = reader.readUInt32()
    val vtableMethodsSize = reader.readInt32()
    val interfaceOffsetsOffset = reader.readInt32()
    val interfaceOffsetsSize = reader.readInt32()
    val typeDefinitionsOffset = reader.readUInt32()
    val typeDefinitionsSize = reader.readInt32()
    val rgctxEntriesOffset = if (version <= 24.1) reader.readUInt32() else 0L
    val rgctxEntriesCount = if (version <= 24.1) reader.readInt32() else 0
    val imagesOffset = reader.readUInt32()
    val imagesSize = reader.readInt32()
    val assembliesOffset = reader.readUInt32()
    val assembliesSize = reader.readInt32()
    val hasMetadataUsage = version >= 19.0 && version <= 24.5
    val metadataUsageListsOffset = if (hasMetadataUsage) reader.readUInt32() else 0L
    val metadataUsageListsCount = if (hasMetadataUsage) reader.readInt32() else 0
    val metadataUsagePairsOffset = if (hasMetadataUsage) reader.readUInt32() else 0L
    val metadataUsagePairsCount = if (hasMetadataUsage) reader.readInt32() else 0
    val fieldRefsOffset = if (version >= 19.0) reader.readUInt32() else 0L
    val fieldRefsSize = if (version >= 19.0) reader.readInt32() else 0
    val referencedAssembliesOffset = if (version >= 20.0) reader.readInt32() else 0
    val referencedAssembliesSize = if (version >= 20.0) reader.readInt32() else 0
    val hasAttributesInfo = version >= 21.0 && version <= 27.2
    val attributesInfoOffset = if (hasAttributesInfo) reader.readUInt32() else 0L
    val attributesInfoCount = if (hasAttributesInfo) reader.readInt32() else 0
    val attributeTypesOffset = if (hasAttributesInfo) reader.readUInt32() else 0L
    val attributeTypesCount = if (hasAttributesInfo) reader.readInt32() else 0
    val attributeDataOffset = if (version >= 29.0) reader.readUInt32() else 0L
    val attributeDataSize = if (version >= 29.0) reader.readInt32() else 0
    val attributeDataRangeOffset = if (version >= 29.0) reader.readUInt32() else 0L
    val attributeDataRangeSize = if (version >= 29.0) reader.readInt32() else 0
    val unresolvedVirtualCallParameterTypesOffset = if (version >= 22.0) reader.readInt32() else 0
    val unresolvedVirtualCallParameterTypesSize = if (version >= 22.0) reader.readInt32() else 0
    val unresolvedVirtualCallParameterRangesOffset = if (version >= 22.0) reader.readInt32() else 0
    val unresolvedVirtualCallParameterRangesSize = if (version >= 22.0) reader.readInt32() else 0
    val windowsRuntimeTypeNamesOffset = if (version >= 23.0) reader.readInt32() else 0
    val windowsRuntimeTypeNamesSize = if (version >= 23.0) reader.readInt32() else 0
    val windowsRuntimeStringsOffset = if (version >= 27.0) reader.readInt32() else 0
    val windowsRuntimeStringsSize = if (version >= 27.0) reader.readInt32() else 0
    val exportedTypeDefinitionsOffset = if (version >= 24.0) reader.readInt32() else 0
    val exportedTypeDefinitionsSize = if (version >= 24.0) reader.readInt32() else 0
    return MetadataHeader(
        sanity = sanity,
        version = headerVersion,
        stringLiteralOffset = stringLiteralOffset,
        stringLiteralSize = stringLiteralSize,
        stringLiteralDataOffset = stringLiteralDataOffset,
        stringLiteralDataSize = stringLiteralDataSize,
        stringOffset = stringOffset,
        stringSize = stringSize,
        eventsOffset = eventsOffset,
        eventsSize = eventsSize,
        propertiesOffset = propertiesOffset,
        propertiesSize = propertiesSize,
        methodsOffset = methodsOffset,
        methodsSize = methodsSize,
        parameterDefaultValuesOffset = parameterDefaultValuesOffset,
        parameterDefaultValuesSize = parameterDefaultValuesSize,
        fieldDefaultValuesOffset = fieldDefaultValuesOffset,
        fieldDefaultValuesSize = fieldDefaultValuesSize,
        fieldAndParameterDefaultValueDataOffset = fieldAndParameterDefaultValueDataOffset,
        fieldAndParameterDefaultValueDataSize = fieldAndParameterDefaultValueDataSize,
        fieldMarshaledSizesOffset = fieldMarshaledSizesOffset,
        fieldMarshaledSizesSize = fieldMarshaledSizesSize,
        parametersOffset = parametersOffset,
        parametersSize = parametersSize,
        fieldsOffset = fieldsOffset,
        fieldsSize = fieldsSize,
        genericParametersOffset = genericParametersOffset,
        genericParametersSize = genericParametersSize,
        genericParameterConstraintsOffset = genericParameterConstraintsOffset,
        genericParameterConstraintsSize = genericParameterConstraintsSize,
        genericContainersOffset = genericContainersOffset,
        genericContainersSize = genericContainersSize,
        nestedTypesOffset = nestedTypesOffset,
        nestedTypesSize = nestedTypesSize,
        interfacesOffset = interfacesOffset,
        interfacesSize = interfacesSize,
        vtableMethodsOffset = vtableMethodsOffset,
        vtableMethodsSize = vtableMethodsSize,
        interfaceOffsetsOffset = interfaceOffsetsOffset,
        interfaceOffsetsSize = interfaceOffsetsSize,
        typeDefinitionsOffset = typeDefinitionsOffset,
        typeDefinitionsSize = typeDefinitionsSize,
        rgctxEntriesOffset = rgctxEntriesOffset,
        rgctxEntriesCount = rgctxEntriesCount,
        imagesOffset = imagesOffset,
        imagesSize = imagesSize,
        assembliesOffset = assembliesOffset,
        assembliesSize = assembliesSize,
        metadataUsageListsOffset = metadataUsageListsOffset,
        metadataUsageListsCount = metadataUsageListsCount,
        metadataUsagePairsOffset = metadataUsagePairsOffset,
        metadataUsagePairsCount = metadataUsagePairsCount,
        fieldRefsOffset = fieldRefsOffset,
        fieldRefsSize = fieldRefsSize,
        referencedAssembliesOffset = referencedAssembliesOffset,
        referencedAssembliesSize = referencedAssembliesSize,
        attributesInfoOffset = attributesInfoOffset,
        attributesInfoCount = attributesInfoCount,
        attributeTypesOffset = attributeTypesOffset,
        attributeTypesCount = attributeTypesCount,
        attributeDataOffset = attributeDataOffset,
        attributeDataSize = attributeDataSize,
        attributeDataRangeOffset = attributeDataRangeOffset,
        attributeDataRangeSize = attributeDataRangeSize,
        unresolvedVirtualCallParameterTypesOffset = unresolvedVirtualCallParameterTypesOffset,
        unresolvedVirtualCallParameterTypesSize = unresolvedVirtualCallParameterTypesSize,
        unresolvedVirtualCallParameterRangesOffset = unresolvedVirtualCallParameterRangesOffset,
        unresolvedVirtualCallParameterRangesSize = unresolvedVirtualCallParameterRangesSize,
        windowsRuntimeTypeNamesOffset = windowsRuntimeTypeNamesOffset,
        windowsRuntimeTypeNamesSize = windowsRuntimeTypeNamesSize,
        windowsRuntimeStringsOffset = windowsRuntimeStringsOffset,
        windowsRuntimeStringsSize = windowsRuntimeStringsSize,
        exportedTypeDefinitionsOffset = exportedTypeDefinitionsOffset,
        exportedTypeDefinitionsSize = exportedTypeDefinitionsSize
    )
}

internal fun sizeOfAssemblyNameDefinition(version: Double): Int =
    if (version <= 24.3) 52 else 48

internal fun readAssemblyNameDefinition(reader: BinaryReader, version: Double): Il2CppAssemblyNameDefinition {
    val nameIndex = reader.readInt32()
    val cultureIndex = reader.readInt32()
    val hashValueIndex = if (version <= 24.3) reader.readInt32() else 0
    val publicKeyIndex = reader.readInt32()
    val hashAlg = reader.readInt32()
    val hashLen = reader.readInt32()
    val flags = reader.readInt32()
    val major = reader.readInt32()
    val minor = reader.readInt32()
    val build = reader.readInt32()
    val revision = reader.readInt32()
    val publicKeyToken = reader.readBytes(8)
    return Il2CppAssemblyNameDefinition(
        nameIndex, cultureIndex, hashValueIndex, publicKeyIndex, hashAlg, hashLen,
        flags, major, minor, build, revision, publicKeyToken
    )
}

internal fun sizeOfAssemblyDefinition(version: Double): Int {
    var size = 4
    if (version >= 24.1) size += 4
    if (version <= 24.0) size += 4
    if (version >= 20.0) size += 8
    return size + sizeOfAssemblyNameDefinition(version)
}

internal fun readAssemblyDefinition(reader: BinaryReader, version: Double): Il2CppAssemblyDefinition {
    val imageIndex = reader.readInt32()
    val token = if (version >= 24.1) reader.readInt32() else 0
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val referencedAssemblyStart = if (version >= 20.0) reader.readInt32() else 0
    val referencedAssemblyCount = if (version >= 20.0) reader.readInt32() else 0
    val aname = readAssemblyNameDefinition(reader, version)
    return Il2CppAssemblyDefinition(
        imageIndex, token, customAttributeIndex, referencedAssemblyStart, referencedAssemblyCount, aname
    )
}

internal fun sizeOfImageDefinition(version: Double): Int {
    var size = 16
    if (version >= 24.0) size += 8
    size += 4
    if (version >= 19.0) size += 4
    if (version >= 24.1) size += 8
    return size
}

internal fun readImageDefinition(reader: BinaryReader, version: Double): Il2CppImageDefinition {
    val nameIndex = reader.readInt32()
    val assemblyIndex = reader.readInt32()
    val typeStart = reader.readInt32()
    val typeCount = reader.readInt32()
    val exportedTypeStart = if (version >= 24.0) reader.readInt32() else 0
    val exportedTypeCount = if (version >= 24.0) reader.readInt32() else 0
    val entryPointIndex = reader.readInt32()
    val token = if (version >= 19.0) reader.readInt32() else 0
    val customAttributeStart = if (version >= 24.1) reader.readInt32() else 0
    val customAttributeCount = if (version >= 24.1) reader.readInt32() else 0
    return Il2CppImageDefinition(
        nameIndex, assemblyIndex, typeStart, typeCount, exportedTypeStart, exportedTypeCount,
        entryPointIndex, token, customAttributeStart, customAttributeCount
    )
}

internal fun sizeOfTypeDefinition(version: Double): Int {
    var size = 8
    if (version <= 24.0) size += 4
    size += 4
    if (version <= 24.5) size += 4
    size += 12
    if (version <= 24.1) size += 8
    size += 4
    if (version <= 22.0) size += 8
    if (version >= 21.0 && version <= 22.0) size += 8
    size += 4
    size += 32
    size += 16
    size += 4
    if (version >= 19.0) size += 4
    return size
}

internal fun readTypeDefinition(reader: BinaryReader, version: Double): Il2CppTypeDefinition {
    val nameIndex = reader.readInt32()
    val namespaceIndex = reader.readInt32()
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val byvalTypeIndex = reader.readInt32()
    val byrefTypeIndex = if (version <= 24.5) reader.readInt32() else 0
    val declaringTypeIndex = reader.readInt32()
    val parentIndex = reader.readInt32()
    val elementTypeIndex = reader.readInt32()
    val rgctxStartIndex = if (version <= 24.1) reader.readInt32() else 0
    val rgctxCount = if (version <= 24.1) reader.readInt32() else 0
    val genericContainerIndex = reader.readInt32()
    val delegateWrapperFromManagedToNativeIndex = if (version <= 22.0) reader.readInt32() else 0
    val marshalingFunctionsIndex = if (version <= 22.0) reader.readInt32() else 0
    val hasComInterop = version >= 21.0 && version <= 22.0
    val ccwFunctionIndex = if (hasComInterop) reader.readInt32() else 0
    val guidIndex = if (hasComInterop) reader.readInt32() else 0
    val flags = reader.readInt32()
    val fieldStart = reader.readInt32()
    val methodStart = reader.readInt32()
    val eventStart = reader.readInt32()
    val propertyStart = reader.readInt32()
    val nestedTypesStart = reader.readInt32()
    val interfacesStart = reader.readInt32()
    val vtableStart = reader.readInt32()
    val interfaceOffsetsStart = reader.readInt32()
    val methodCount = reader.readUInt16()
    val propertyCount = reader.readUInt16()
    val fieldCount = reader.readUInt16()
    val eventCount = reader.readUInt16()
    val nestedTypeCount = reader.readUInt16()
    val vtableCount = reader.readUInt16()
    val interfacesCount = reader.readUInt16()
    val interfaceOffsetsCount = reader.readUInt16()
    val bitfield = reader.readInt32()
    val token = if (version >= 19.0) reader.readInt32() else 0
    return Il2CppTypeDefinition(
        nameIndex, namespaceIndex, customAttributeIndex, byvalTypeIndex, byrefTypeIndex,
        declaringTypeIndex, parentIndex, elementTypeIndex, rgctxStartIndex, rgctxCount,
        genericContainerIndex, delegateWrapperFromManagedToNativeIndex, marshalingFunctionsIndex,
        ccwFunctionIndex, guidIndex, flags, fieldStart, methodStart, eventStart, propertyStart,
        nestedTypesStart, interfacesStart, vtableStart, interfaceOffsetsStart, methodCount,
        propertyCount, fieldCount, eventCount, nestedTypeCount, vtableCount, interfacesCount,
        interfaceOffsetsCount, bitfield, token
    )
}

internal fun sizeOfMethodDefinition(version: Double): Int {
    var size = 12
    if (version >= 31.0) size += 4
    size += 4
    if (version <= 24.0) size += 4
    size += 4
    if (version <= 24.1) size += 20
    size += 4
    size += 8
    return size
}

internal fun readMethodDefinition(reader: BinaryReader, version: Double): Il2CppMethodDefinition {
    val nameIndex = reader.readInt32()
    val declaringType = reader.readInt32()
    val returnType = reader.readInt32()
    val returnParameterToken = if (version >= 31.0) reader.readInt32() else 0
    val parameterStart = reader.readInt32()
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val genericContainerIndex = reader.readInt32()
    val legacy = version <= 24.1
    val methodIndex = if (legacy) reader.readInt32() else 0
    val invokerIndex = if (legacy) reader.readInt32() else 0
    val delegateWrapperIndex = if (legacy) reader.readInt32() else 0
    val rgctxStartIndex = if (legacy) reader.readInt32() else 0
    val rgctxCount = if (legacy) reader.readInt32() else 0
    val token = reader.readInt32()
    val flags = reader.readUInt16()
    val iflags = reader.readUInt16()
    val slot = reader.readUInt16()
    val parameterCount = reader.readUInt16()
    return Il2CppMethodDefinition(
        nameIndex, declaringType, returnType, returnParameterToken, parameterStart,
        customAttributeIndex, genericContainerIndex, methodIndex, invokerIndex,
        delegateWrapperIndex, rgctxStartIndex, rgctxCount, token, flags, iflags, slot, parameterCount
    )
}

internal fun sizeOfParameterDefinition(version: Double): Int =
    if (version <= 24.0) 16 else 12

internal fun readParameterDefinition(reader: BinaryReader, version: Double): Il2CppParameterDefinition {
    val nameIndex = reader.readInt32()
    val token = reader.readInt32()
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val typeIndex = reader.readInt32()
    return Il2CppParameterDefinition(nameIndex, token, customAttributeIndex, typeIndex)
}

internal fun sizeOfFieldDefinition(version: Double): Int {
    var size = 8
    if (version <= 24.0) size += 4
    if (version >= 19.0) size += 4
    return size
}

internal fun readFieldDefinition(reader: BinaryReader, version: Double): Il2CppFieldDefinition {
    val nameIndex = reader.readInt32()
    val typeIndex = reader.readInt32()
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val token = if (version >= 19.0) reader.readInt32() else 0
    return Il2CppFieldDefinition(nameIndex, typeIndex, customAttributeIndex, token)
}

internal const val SIZE_OF_FIELD_DEFAULT_VALUE = 12

internal fun readFieldDefaultValue(reader: BinaryReader): Il2CppFieldDefaultValue =
    Il2CppFieldDefaultValue(reader.readInt32(), reader.readInt32(), reader.readInt32())

internal const val SIZE_OF_PARAMETER_DEFAULT_VALUE = 12

internal fun readParameterDefaultValue(reader: BinaryReader): Il2CppParameterDefaultValue =
    Il2CppParameterDefaultValue(reader.readInt32(), reader.readInt32(), reader.readInt32())

internal fun sizeOfPropertyDefinition(version: Double): Int {
    var size = 16
    if (version <= 24.0) size += 4
    if (version >= 19.0) size += 4
    return size
}

internal fun readPropertyDefinition(reader: BinaryReader, version: Double): Il2CppPropertyDefinition {
    val nameIndex = reader.readInt32()
    val get = reader.readInt32()
    val set = reader.readInt32()
    val attrs = reader.readInt32()
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val token = if (version >= 19.0) reader.readInt32() else 0
    return Il2CppPropertyDefinition(nameIndex, get, set, attrs, customAttributeIndex, token)
}

internal fun sizeOfEventDefinition(version: Double): Int {
    var size = 20
    if (version <= 24.0) size += 4
    if (version >= 19.0) size += 4
    return size
}

internal fun readEventDefinition(reader: BinaryReader, version: Double): Il2CppEventDefinition {
    val nameIndex = reader.readInt32()
    val typeIndex = reader.readInt32()
    val add = reader.readInt32()
    val remove = reader.readInt32()
    val raise = reader.readInt32()
    val customAttributeIndex = if (version <= 24.0) reader.readInt32() else 0
    val token = if (version >= 19.0) reader.readInt32() else 0
    return Il2CppEventDefinition(nameIndex, typeIndex, add, remove, raise, customAttributeIndex, token)
}

internal fun sizeOfCustomAttributeTypeRange(version: Double): Int =
    if (version >= 24.1) 12 else 8

internal fun readCustomAttributeTypeRange(reader: BinaryReader, version: Double): Il2CppCustomAttributeTypeRange {
    val token = if (version >= 24.1) reader.readInt32() else 0
    val start = reader.readInt32()
    val count = reader.readInt32()
    return Il2CppCustomAttributeTypeRange(token, start, count)
}

internal const val SIZE_OF_CUSTOM_ATTRIBUTE_DATA_RANGE = 8

internal fun readCustomAttributeDataRange(reader: BinaryReader): Il2CppCustomAttributeDataRange =
    Il2CppCustomAttributeDataRange(reader.readInt32(), reader.readUInt32())

internal const val SIZE_OF_METADATA_USAGE_LIST = 8

internal fun readMetadataUsageList(reader: BinaryReader): Il2CppMetadataUsageList =
    Il2CppMetadataUsageList(reader.readUInt32(), reader.readUInt32())

internal const val SIZE_OF_METADATA_USAGE_PAIR = 8

internal fun readMetadataUsagePair(reader: BinaryReader): Il2CppMetadataUsagePair =
    Il2CppMetadataUsagePair(reader.readUInt32(), reader.readUInt32())

internal const val SIZE_OF_STRING_LITERAL = 8

internal fun readStringLiteral(reader: BinaryReader): Il2CppStringLiteral =
    Il2CppStringLiteral(reader.readInt32(), reader.readInt32())

internal const val SIZE_OF_GENERIC_CONTAINER = 16

internal fun readGenericContainer(reader: BinaryReader): Il2CppGenericContainer =
    Il2CppGenericContainer(
        reader.readInt32(), reader.readInt32(), reader.readInt32(), reader.readInt32()
    )

internal const val SIZE_OF_GENERIC_PARAMETER = 16

internal fun readGenericParameter(reader: BinaryReader): Il2CppGenericParameter {
    val ownerIndex = reader.readInt32()
    val nameIndex = reader.readInt32()
    val constraintsStart = reader.readInt16().toInt()
    val constraintsCount = reader.readInt16().toInt()
    val num = reader.readUInt16()
    val flags = reader.readUInt16()
    return Il2CppGenericParameter(ownerIndex, nameIndex, constraintsStart, constraintsCount, num, flags)
}

internal const val SIZE_OF_FIELD_REF = 8

internal fun readFieldRef(reader: BinaryReader): Il2CppFieldRef =
    Il2CppFieldRef(reader.readInt32(), reader.readInt32())

internal fun sizeOfRgctxDefinition(version: Double): Int {
    var size = 0
    if (version <= 27.1) size += 4
    if (version >= 29.0) size += 8
    if (version <= 27.1) size += 4
    if (version >= 27.2) size += 8
    return size
}

internal fun readRgctxDefinition(reader: BinaryReader, version: Double): Il2CppRGCTXDefinition {
    val typePre29 = if (version <= 27.1) reader.readInt32() else 0
    val typePost29 = if (version >= 29.0) reader.readInt64() else 0L
    val dataDummy = if (version <= 27.1) reader.readInt32() else 0
    val data = if (version >= 27.2) reader.readInt64() else 0L
    return Il2CppRGCTXDefinition(typePre29, typePost29, dataDummy, data)
}
