package com.trickhook.il2cpp.il2cpp

import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_ASSEMBLY
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_FAMILY
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_FAM_AND_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_FAM_OR_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_FIELD_ACCESS_MASK
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_INIT_ONLY
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_LITERAL
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_PRIVATE
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_PUBLIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_STATIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_ABSTRACT
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_FAMILY
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_FAM_AND_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_FAM_OR_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_FINAL
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_MEMBER_ACCESS_MASK
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_NEW_SLOT
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_PINVOKE_IMPL
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_PRIVATE
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_PUBLIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_REUSE_SLOT
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_STATIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_VIRTUAL
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_VTABLE_LAYOUT_MASK
import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.metadata.Il2CppFieldDefinition
import com.trickhook.il2cpp.metadata.Il2CppGenericContainer
import com.trickhook.il2cpp.metadata.Il2CppGenericParameter
import com.trickhook.il2cpp.metadata.Il2CppImageDefinition
import com.trickhook.il2cpp.metadata.Il2CppMethodDefinition
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition
import com.trickhook.il2cpp.metadata.Metadata

data class EncodedTypeEnum(val typeEnum: Il2CppTypeEnum, val enumType: Il2CppType?)

class Il2CppExecutor(val metadata: Metadata, val binary: Il2CppBinary) {

    private val version: Double get() = binary.version

    private val methodModifiers = HashMap<Il2CppMethodDefinition, String>()

    fun getTypeName(type: Il2CppType, addNamespace: Boolean, isNested: Boolean): String =
        when (type.type) {
            Il2CppTypeEnum.IL2CPP_TYPE_ARRAY -> {
                val arrayType = binary.arrayTypeAt(type.arrayType)
                    ?: throw IllegalStateException("array type at 0x${type.arrayType.toString(16)} is not mapped")
                getTypeName(typeAt(arrayType.etype), addNamespace, false) +
                    "[" + ",".repeat(arrayType.rank - 1) + "]"
            }
            Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY ->
                getTypeName(typeAt(type.elementType), addNamespace, false) + "[]"
            Il2CppTypeEnum.IL2CPP_TYPE_PTR ->
                getTypeName(typeAt(type.elementType), addNamespace, false) + "*"
            Il2CppTypeEnum.IL2CPP_TYPE_VAR, Il2CppTypeEnum.IL2CPP_TYPE_MVAR ->
                metadata.getString(getGenericParameterFromIl2CppType(type).nameIndex)
            Il2CppTypeEnum.IL2CPP_TYPE_CLASS,
            Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE,
            Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> declaredTypeName(type, addNamespace, isNested)
            else -> intrinsicName(type)
        }

    fun getTypeDefName(typeDef: Il2CppTypeDefinition, addNamespace: Boolean, genericParameter: Boolean): String {
        val prefix = when {
            typeDef.declaringTypeIndex != -1 ->
                getTypeName(binary.types[typeDef.declaringTypeIndex], addNamespace, true) + "."
            addNamespace -> metadata.getString(typeDef.namespaceIndex).let { if (it.isEmpty()) "" else "$it." }
            else -> ""
        }
        val rawName = metadata.getString(typeDef.nameIndex)
        if (typeDef.genericContainerIndex < 0) return prefix + rawName
        val trimmed = rawName.substringBefore('`')
        if (!genericParameter) return prefix + trimmed
        return prefix + trimmed + genericContainerSuffix(metadata.genericContainers[typeDef.genericContainerIndex])
    }

    fun getMethodSpecName(spec: Il2CppMethodSpec, addNamespace: Boolean = false): Pair<String, String> {
        val methodDef = metadata.methodDefs[spec.methodDefinitionIndex]
        val typeDef = metadata.typeDefs[methodDef.declaringType]
        val typeName = StringBuilder(getTypeDefName(typeDef, addNamespace, false))
        if (spec.classIndexIndex != -1) {
            typeName.append(genericInstSuffix(binary.genericInsts[spec.classIndexIndex]))
        }
        val methodName = StringBuilder(metadata.getString(methodDef.nameIndex))
        if (spec.methodIndexIndex != -1) {
            methodName.append(genericInstSuffix(binary.genericInsts[spec.methodIndexIndex]))
        }
        return typeName.toString() to methodName.toString()
    }

    fun getGenericContainerParams(container: Il2CppGenericContainer): List<String> =
        getGenericContainerParamNames(container)

    fun getGenericContainerParamNames(container: Il2CppGenericContainer): List<String> =
        (0 until container.typeArgc).map {
            metadata.getString(metadata.genericParameters[container.genericParameterStart + it].nameIndex)
        }

    fun getGenericInstParams(genericInst: Il2CppGenericInst): List<String> {
        val reader = readerAt(genericInst.typeArgv)
        val pointers = LongArray(genericInst.typeArgc.toInt()) { reader.readPointer() }
        return pointers.map { getTypeName(typeAt(it), false, false) }
    }

    /**
     * Os argumentos de uma instancia generica como TIPOS, e nao como nomes.
     * O DummyDll precisa remonta-los numa assinatura, onde nome nao serve.
     */
    fun getGenericInstTypes(genericInst: Il2CppGenericInst): List<Il2CppType> {
        val reader = readerAt(genericInst.typeArgv)
        val pointers = LongArray(genericInst.typeArgc.toInt()) { reader.readPointer() }
        return pointers.map { typeAt(it) }
    }

    fun getTypeDefaultValue(type: Il2CppType, dataIndex: Int): String {
        val pointer = metadata.getDefaultValueData(dataIndex)
        val reader = BinaryReader(metadata.raw)
        reader.seek(pointer)
        val kind = type.type ?: return unmappedDefaultValue(pointer)
        val blob = readConstantValueFromBlob(kind, reader) ?: return unmappedDefaultValue(pointer)
        return renderBlobLiteral(blob) { getTypeName(it, false, false) }
    }

    fun getCustomAttributeIndex(image: Il2CppImageDefinition, customAttributeIndex: Int, token: Long): Int =
        metadata.getCustomAttributeIndex(image, customAttributeIndex, token.toInt())

    fun getCustomAttributeData(
        image: Il2CppImageDefinition,
        customAttributeIndex: Int,
        token: Long
    ): List<CustomAttributeEntry> {
        if (version < 29.0) throw UnsupportedOperationException(
            "custom attribute blobs need metadata version 29 or newer, this image is version $version"
        )
        val attributeIndex = getCustomAttributeIndex(image, customAttributeIndex, token)
        if (attributeIndex < 0) return emptyList()
        val base = metadata.header.attributeDataOffset
        val start = base + metadata.attributeDataRanges[attributeIndex].startOffset
        val end = base + metadata.attributeDataRanges[attributeIndex + 1].startOffset
        val reader = CustomAttributeDataReader(this, metadata.raw.copyOfRange(start.toInt(), end.toInt()))
        return (0 until reader.count).map { reader.readEntry() }
    }

    fun getModifiers(method: Il2CppMethodDefinition): String = methodModifiers.getOrPut(method) {
        val builder = StringBuilder()
        when (method.flags and METHOD_ATTRIBUTE_MEMBER_ACCESS_MASK) {
            METHOD_ATTRIBUTE_PRIVATE -> builder.append("private ")
            METHOD_ATTRIBUTE_PUBLIC -> builder.append("public ")
            METHOD_ATTRIBUTE_FAMILY -> builder.append("protected ")
            METHOD_ATTRIBUTE_ASSEM, METHOD_ATTRIBUTE_FAM_AND_ASSEM -> builder.append("internal ")
            METHOD_ATTRIBUTE_FAM_OR_ASSEM -> builder.append("protected internal ")
        }
        if (method.flags and METHOD_ATTRIBUTE_STATIC != 0) builder.append("static ")
        val reusesSlot = method.flags and METHOD_ATTRIBUTE_VTABLE_LAYOUT_MASK == METHOD_ATTRIBUTE_REUSE_SLOT
        if (method.flags and METHOD_ATTRIBUTE_ABSTRACT != 0) {
            builder.append("abstract ")
            if (reusesSlot) builder.append("override ")
        } else if (method.flags and METHOD_ATTRIBUTE_FINAL != 0) {
            if (reusesSlot) builder.append("sealed override ")
        } else if (method.flags and METHOD_ATTRIBUTE_VIRTUAL != 0) {
            builder.append(if (reusesSlot) "override " else "virtual ")
        }
        if (method.flags and METHOD_ATTRIBUTE_PINVOKE_IMPL != 0) builder.append("extern ")
        builder.toString()
    }

    fun getFieldModifiers(field: Il2CppFieldDefinition, attrs: Int): String {
        val builder = StringBuilder()
        when (attrs and FIELD_ATTRIBUTE_FIELD_ACCESS_MASK) {
            FIELD_ATTRIBUTE_PRIVATE -> builder.append("private ")
            FIELD_ATTRIBUTE_PUBLIC -> builder.append("public ")
            FIELD_ATTRIBUTE_FAMILY -> builder.append("protected ")
            FIELD_ATTRIBUTE_ASSEMBLY, FIELD_ATTRIBUTE_FAM_AND_ASSEM -> builder.append("internal ")
            FIELD_ATTRIBUTE_FAM_OR_ASSEM -> builder.append("protected internal ")
        }
        if (attrs and FIELD_ATTRIBUTE_LITERAL != 0) {
            builder.append("const ")
        } else {
            if (attrs and FIELD_ATTRIBUTE_STATIC != 0) builder.append("static ")
            if (attrs and FIELD_ATTRIBUTE_INIT_ONLY != 0) builder.append("readonly ")
        }
        return builder.toString()
    }

    fun getTypeDefinitionFromIl2CppType(type: Il2CppType): Il2CppTypeDefinition {
        if (version >= 27.0 && binary.elf.isDumped) throw UnsupportedOperationException(
            "type handles of a dumped image need the metadata image base, which this metadata reader does not track"
        )
        return metadata.typeDefs[type.klassIndex]
    }

    fun getGenericParameterFromIl2CppType(type: Il2CppType): Il2CppGenericParameter {
        if (version >= 27.0 && binary.elf.isDumped) throw UnsupportedOperationException(
            "generic parameter handles of a dumped image need the metadata image base, which this metadata reader does not track"
        )
        return metadata.genericParameters[type.genericParameterIndex]
    }

    fun readEncodedTypeEnum(reader: BinaryReader): EncodedTypeEnum {
        val code = reader.readUByte()
        val declared = Il2CppTypeEnum.from(code)
            ?: throw IllegalStateException("unknown il2cpp type code 0x${code.toString(16)}")
        if (declared != Il2CppTypeEnum.IL2CPP_TYPE_ENUM) return EncodedTypeEnum(declared, null)
        val enumType = binary.types[reader.readBlobCompressedInt32()]
        val typeDef = getTypeDefinitionFromIl2CppType(enumType)
        val underlying = binary.types[typeDef.elementTypeIndex].type
            ?: throw IllegalStateException("enum ${metadata.getString(typeDef.nameIndex)} has no underlying type")
        return EncodedTypeEnum(underlying, enumType)
    }

    fun readConstantValueFromBlob(type: Il2CppTypeEnum, reader: BinaryReader): BlobValue? = when (type) {
        Il2CppTypeEnum.IL2CPP_TYPE_BOOLEAN -> BlobValue(type, reader.readUByte() != 0)
        Il2CppTypeEnum.IL2CPP_TYPE_U1 -> BlobValue(type, reader.readUByte())
        Il2CppTypeEnum.IL2CPP_TYPE_I1 -> BlobValue(type, reader.readByte())
        Il2CppTypeEnum.IL2CPP_TYPE_CHAR -> BlobValue(type, reader.readUInt16().toChar())
        Il2CppTypeEnum.IL2CPP_TYPE_U2 -> BlobValue(type, reader.readUInt16())
        Il2CppTypeEnum.IL2CPP_TYPE_I2 -> BlobValue(type, reader.readInt16())
        Il2CppTypeEnum.IL2CPP_TYPE_U4 ->
            BlobValue(type, if (version >= 29.0) reader.readBlobCompressedUInt32() else reader.readUInt32())
        Il2CppTypeEnum.IL2CPP_TYPE_I4 ->
            BlobValue(type, if (version >= 29.0) reader.readBlobCompressedInt32() else reader.readInt32())
        Il2CppTypeEnum.IL2CPP_TYPE_U8 -> BlobValue(type, reader.readInt64().toULong())
        Il2CppTypeEnum.IL2CPP_TYPE_I8 -> BlobValue(type, reader.readInt64())
        Il2CppTypeEnum.IL2CPP_TYPE_R4 -> BlobValue(type, Float.fromBits(reader.readInt32()))
        Il2CppTypeEnum.IL2CPP_TYPE_R8 -> BlobValue(type, Double.fromBits(reader.readInt64()))
        Il2CppTypeEnum.IL2CPP_TYPE_STRING -> BlobValue(type, readBlobString(reader))
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY -> BlobValue(type, readBlobArray(reader))
        Il2CppTypeEnum.IL2CPP_TYPE_IL2CPP_TYPE_INDEX ->
            reader.readBlobCompressedInt32().let { BlobValue(type, if (it == -1) null else binary.types[it]) }
        else -> null
    }

    private fun readBlobString(reader: BinaryReader): String? {
        if (version < 29.0) return String(reader.readBytes(reader.readInt32()), Charsets.UTF_8)
        val length = reader.readBlobCompressedInt32()
        return if (length == -1) null else String(reader.readBytes(length), Charsets.UTF_8)
    }

    private fun readBlobArray(reader: BinaryReader): Array<BlobValue>? {
        val length = reader.readBlobCompressedInt32()
        if (length == -1) return null
        val declared = readEncodedTypeEnum(reader)
        val perElementTypes = reader.readUByte() == 1
        return Array(length) {
            val encoded = if (perElementTypes) readEncodedTypeEnum(reader) else declared
            val element = readConstantValueFromBlob(encoded.typeEnum, reader)
                ?: throw IllegalStateException("unsupported array blob element ${encoded.typeEnum}")
            element.copy(typeEnum = encoded.typeEnum, enumType = encoded.enumType)
        }
    }

    private fun declaredTypeName(type: Il2CppType, addNamespace: Boolean, isNested: Boolean): String {
        val genericClass = if (type.type == Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST) {
            binary.genericClassAt(type.genericClass)
                ?: throw IllegalStateException("generic class at 0x${type.genericClass.toString(16)} is not mapped")
        } else {
            null
        }
        val typeDef = if (genericClass != null) genericClassTypeDefinition(genericClass)
        else getTypeDefinitionFromIl2CppType(type)

        val builder = StringBuilder()
        if (typeDef.declaringTypeIndex != -1) {
            builder.append(getTypeName(binary.types[typeDef.declaringTypeIndex], addNamespace, true))
            builder.append('.')
        } else if (addNamespace) {
            val namespace = metadata.getString(typeDef.namespaceIndex)
            if (namespace.isNotEmpty()) builder.append(namespace).append('.')
        }
        builder.append(metadata.getString(typeDef.nameIndex).substringBefore('`'))

        if (isNested) return builder.toString()

        if (genericClass != null) {
            val classInst = binary.genericInstAt(genericClass.context.classInst)
                ?: throw IllegalStateException(
                    "generic inst at 0x${genericClass.context.classInst.toString(16)} is not mapped"
                )
            builder.append(genericInstSuffix(classInst))
        } else if (typeDef.genericContainerIndex >= 0) {
            builder.append(genericContainerSuffix(metadata.genericContainers[typeDef.genericContainerIndex]))
        }
        return builder.toString()
    }

    private fun genericClassTypeDefinition(genericClass: Il2CppGenericClass): Il2CppTypeDefinition =
        if (version >= 27.0) getTypeDefinitionFromIl2CppType(typeAt(genericClass.type))
        else metadata.typeDefs[genericClass.typeDefinitionIndex.toInt()]

    private fun genericContainerSuffix(container: Il2CppGenericContainer): String =
        getGenericContainerParamNames(container).joinToString(", ", "<", ">")

    private fun genericInstSuffix(genericInst: Il2CppGenericInst): String =
        getGenericInstParams(genericInst).joinToString(", ", "<", ">")

    private fun intrinsicName(type: Il2CppType): String {
        val kind = type.type ?: return "0x" + type.typeCode.toString(16)
        return intrinsicNames[kind] ?: kind.name
    }

    private fun unmappedDefaultValue(pointer: Long): String =
        "/*Metadata offset 0x" + pointer.toString(16).uppercase() + "*/"

    private fun readerAt(address: Long): BinaryReader {
        val offset = binary.mapVaToOffset(address)
        if (offset < 0) throw IllegalStateException("virtual address 0x${address.toString(16)} is not mapped")
        return binary.elf.reader().apply { seek(offset) }
    }

    private fun typeAt(address: Long): Il2CppType =
        binary.typeByAddress[address] ?: binary.readType(address)

    private companion object {
        val intrinsicNames = mapOf(
            Il2CppTypeEnum.IL2CPP_TYPE_VOID to "void",
            Il2CppTypeEnum.IL2CPP_TYPE_BOOLEAN to "bool",
            Il2CppTypeEnum.IL2CPP_TYPE_CHAR to "char",
            Il2CppTypeEnum.IL2CPP_TYPE_I1 to "sbyte",
            Il2CppTypeEnum.IL2CPP_TYPE_U1 to "byte",
            Il2CppTypeEnum.IL2CPP_TYPE_I2 to "short",
            Il2CppTypeEnum.IL2CPP_TYPE_U2 to "ushort",
            Il2CppTypeEnum.IL2CPP_TYPE_I4 to "int",
            Il2CppTypeEnum.IL2CPP_TYPE_U4 to "uint",
            Il2CppTypeEnum.IL2CPP_TYPE_I8 to "long",
            Il2CppTypeEnum.IL2CPP_TYPE_U8 to "ulong",
            Il2CppTypeEnum.IL2CPP_TYPE_R4 to "float",
            Il2CppTypeEnum.IL2CPP_TYPE_R8 to "double",
            Il2CppTypeEnum.IL2CPP_TYPE_STRING to "string",
            Il2CppTypeEnum.IL2CPP_TYPE_TYPEDBYREF to "TypedReference",
            Il2CppTypeEnum.IL2CPP_TYPE_I to "IntPtr",
            Il2CppTypeEnum.IL2CPP_TYPE_U to "UIntPtr",
            Il2CppTypeEnum.IL2CPP_TYPE_OBJECT to "object"
        )
    }
}
