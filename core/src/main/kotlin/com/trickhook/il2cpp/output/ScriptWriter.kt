package com.trickhook.il2cpp.output

import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_STATIC
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.il2cpp.Il2CppMethodSpec
import com.trickhook.il2cpp.il2cpp.Il2CppType
import com.trickhook.il2cpp.il2cpp.Il2CppTypeEnum
import com.trickhook.il2cpp.metadata.Il2CppMetadataUsage
import com.trickhook.il2cpp.metadata.Il2CppMethodDefinition
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition
import java.io.Writer

class ScriptWriter(private val executor: Il2CppExecutor) {

    private val metadata = executor.metadata
    private val binary = executor.binary
    private val elf = binary.elf
    private val version = binary.version
    private val pointerSize = elf.pointerSize.toLong()
    private val bytes = elf.reader()

    private val names: NameTables by lazy { buildNameTables() }

    private val dataRegions: List<Region> by lazy {
        elf.segments
            .filter { it.memSize != 0L && it.flags in WRITABLE_FLAGS }
            .map { Region(it.offset, it.offset + it.fileSize) }
    }

    fun writeScript(out: Writer) {
        val json = JsonWriter(out)
        json.beginObject()

        json.name("ScriptMethod").beginArray()
        writeMethods(json)
        json.endArray()

        json.name("ScriptString").beginArray()
        scanUsages { usage, index, address ->
            if (usage == USAGE_STRING_LITERAL && index < metadata.stringLiterals.size) {
                json.beginObject()
                json.property("Address", binary.rva(address))
                json.property("Value", metadata.getStringLiteral(index))
                json.endObject()
            }
        }
        json.endArray()

        json.name("ScriptMetadata").beginArray()
        scanUsages { usage, index, address ->
            when {
                usage == USAGE_IL2CPP_TYPE && index < binary.types.size ->
                    writeTypeUsage(json, binary.types[index], address)
                usage == USAGE_FIELD_INFO && index < metadata.fieldRefs.size ->
                    writeFieldUsage(json, index, address)
            }
        }
        json.endArray()

        json.name("ScriptTypeInfo").beginArray()
        scanUsages { usage, index, address ->
            if (usage == USAGE_TYPE_INFO && index < binary.types.size) {
                writeTypeInfoUsage(json, binary.types[index], address)
            }
        }
        json.endArray()

        json.name("ScriptMetadataMethod").beginArray()
        scanUsages { usage, index, address ->
            when {
                usage == USAGE_METHOD_DEF && index < metadata.methodDefs.size ->
                    writeMethodDefUsage(json, index, address)
                usage == USAGE_METHOD_REF && index < binary.methodSpecs.size ->
                    writeMethodRefUsage(json, binary.methodSpecs[index], address)
            }
        }
        json.endArray()

        json.name("Addresses").beginArray()
        for (pointer in orderedPointers()) json.value(binary.rva(pointer))
        json.endArray()

        json.endObject()
        out.flush()
    }

    fun writeStringLiterals(out: Writer) {
        val json = JsonWriter(out)
        json.beginArray()
        scanUsages { usage, index, address ->
            if (usage == USAGE_STRING_LITERAL && index < metadata.stringLiterals.size) {
                json.beginObject()
                json.property("value", metadata.getStringLiteral(index))
                json.property("address", "0x" + java.lang.Long.toHexString(binary.rva(address)).uppercase())
                json.endObject()
            }
        }
        json.endArray()
        out.flush()
    }

    private fun writeMethods(json: JsonWriter) {
        for (imageDef in metadata.imageDefs) {
            val imageName = metadata.getString(imageDef.nameIndex)
            for (typeIndex in imageDef.typeStart until imageDef.typeStart + imageDef.typeCount) {
                if (typeIndex !in metadata.typeDefs.indices) continue
                val typeDef = metadata.typeDefs[typeIndex]
                val typeName = executor.getTypeDefName(typeDef, true, true)
                for (methodIndex in typeDef.methodStart until typeDef.methodStart + typeDef.methodCount) {
                    if (methodIndex !in metadata.methodDefs.indices) continue
                    val methodDef = metadata.methodDefs[methodIndex]
                    val pointer = binary.getMethodPointer(imageName, methodDef, methodIndex)
                    if (pointer > 0L) {
                        writePlainMethod(json, typeDef, typeName, methodDef, pointer)
                    }
                    val specs = binary.methodDefinitionMethodSpecs[methodIndex] ?: continue
                    for (spec in specs) {
                        val genericPointer = binary.methodSpecGenericMethodPointers[spec] ?: 0L
                        if (genericPointer > 0L) {
                            writeGenericMethod(json, typeIndex, typeDef, typeName, methodDef, spec, genericPointer)
                        }
                    }
                }
            }
        }
    }

    private fun writePlainMethod(
        json: JsonWriter,
        typeDef: Il2CppTypeDefinition,
        typeName: String,
        methodDef: Il2CppMethodDefinition,
        pointer: Long
    ) {
        val fullName = typeName + METHOD_JOIN + metadata.getString(methodDef.nameIndex)
        val signature = StringBuilder()
        val typeSignature = StringBuilder()
        appendReturn(signature, typeSignature, methodDef, fullName, null)
        var written = false
        if (methodDef.flags and METHOD_ATTRIBUTE_STATIC == 0) {
            val thisType = binary.types[typeDef.byvalTypeIndex]
            signature.append(parseType(thisType, null)).append(" __this")
            typeSignature.append(signatureCharOf(thisType.type))
            written = true
        } else if (version <= 24.0) {
            signature.append("Il2CppObject* __this")
            typeSignature.append('i')
            written = true
        }
        appendParameters(signature, typeSignature, methodDef, null, "MethodInfo", written)
        writeMethodEntry(json, binary.rva(pointer), fullName, signature.toString(), typeSignature.toString())
    }

    private fun writeGenericMethod(
        json: JsonWriter,
        typeIndex: Int,
        typeDef: Il2CppTypeDefinition,
        typeName: String,
        methodDef: Il2CppMethodDefinition,
        spec: Il2CppMethodSpec,
        pointer: Long
    ) {
        val address = binary.rva(pointer)
        val methodInfoName = "MethodInfo_" + java.lang.Long.toHexString(address).uppercase()
        val (specTypeName, specMethodName) = executor.getMethodSpecName(spec, true)
        val fullName = specTypeName + METHOD_JOIN + specMethodName
        val context = contextOf(spec)
        val signature = StringBuilder()
        val typeSignature = StringBuilder()
        appendReturn(signature, typeSignature, methodDef, fullName, context)
        var written = false
        if (methodDef.flags and METHOD_ATTRIBUTE_STATIC == 0) {
            val thisType = genericThisType(typeIndex, typeDef, typeName, specTypeName, spec)
            signature.append(parseType(thisType, null)).append(" __this")
            typeSignature.append(signatureCharOf(thisType.type))
            written = true
        } else if (version <= 24.0) {
            signature.append("Il2CppObject* __this")
            typeSignature.append('i')
            written = true
        }
        appendParameters(signature, typeSignature, methodDef, context, methodInfoName, written)
        writeMethodEntry(json, address, fullName, signature.toString(), typeSignature.toString())
    }

    private fun genericThisType(
        typeIndex: Int,
        typeDef: Il2CppTypeDefinition,
        typeName: String,
        specTypeName: String,
        spec: Il2CppMethodSpec
    ): Il2CppType {
        if (spec.classIndexIndex == -1) return binary.types[typeDef.byvalTypeIndex]
        val structName = names.structNames[typeIndex]
            .replace(names.fixedNames[typeIndex], fixName(specTypeName))
        return names.genericTypeByStructName[structName] ?: binary.types[typeDef.byvalTypeIndex]
    }

    private fun appendReturn(
        signature: StringBuilder,
        typeSignature: StringBuilder,
        methodDef: Il2CppMethodDefinition,
        fullName: String,
        context: GenericContext?
    ) {
        val returnType = binary.types[methodDef.returnType]
        signature.append(parseType(returnType, context))
        if (returnType.byref) signature.append('*')
        typeSignature.append(
            if (returnType.byref) 'i' else signatureCharOf(returnType.type)
        )
        signature.append(' ').append(fixName(fullName)).append(" (")
    }

    private fun appendParameters(
        signature: StringBuilder,
        typeSignature: StringBuilder,
        methodDef: Il2CppMethodDefinition,
        context: GenericContext?,
        methodInfoName: String,
        hasPrevious: Boolean
    ) {
        var written = hasPrevious
        for (offset in 0 until methodDef.parameterCount) {
            val index = methodDef.parameterStart + offset
            if (index !in metadata.parameterDefs.indices) continue
            if (written) signature.append(", ")
            written = true
            val parameterDef = metadata.parameterDefs[index]
            val parameterType = binary.types[parameterDef.typeIndex]
            signature.append(parseType(parameterType, context))
            if (parameterType.byref) signature.append('*')
            typeSignature.append(if (parameterType.byref) 'i' else signatureCharOf(parameterType.type))
            signature.append(' ').append(fixName(metadata.getString(parameterDef.nameIndex)))
        }
        if (written) signature.append(", ")
        typeSignature.append('i')
        signature.append("const ").append(methodInfoName).append("* method);")
    }

    private fun writeMethodEntry(
        json: JsonWriter,
        address: Long,
        name: String,
        signature: String,
        typeSignature: String
    ) {
        json.beginObject()
        json.property("Address", address)
        json.property("Name", name)
        json.property("Signature", signature)
        json.property("TypeSignature", typeSignature)
        json.endObject()
    }

    private fun writeTypeInfoUsage(json: JsonWriter, type: Il2CppType, address: Long) {
        val structName = il2CppStructName(type, null)
        json.beginObject()
        json.property("Address", binary.rva(address))
        json.property("Name", executor.getTypeName(type, true, false) + "_TypeInfo")
        json.property(
            "Signature",
            if (structName.endsWith("_array")) "Il2CppClass*" else fixName(structName) + "_c*"
        )
        json.endObject()
    }

    private fun writeTypeUsage(json: JsonWriter, type: Il2CppType, address: Long) {
        json.beginObject()
        json.property("Address", binary.rva(address))
        json.property("Name", executor.getTypeName(type, true, false) + "_var")
        json.property("Signature", "Il2CppType*")
        json.endObject()
    }

    private fun writeFieldUsage(json: JsonWriter, index: Int, address: Long) {
        val fieldRef = metadata.fieldRefs[index]
        val type = binary.types[fieldRef.typeIndex]
        val typeDef = metadata.typeDefs[declaringTypeIndexOf(type)]
        val fieldDef = metadata.fieldDefs[typeDef.fieldStart + fieldRef.fieldIndex]
        json.beginObject()
        json.property("Address", binary.rva(address))
        json.property(
            "Name",
            FIELD_PREFIX + executor.getTypeName(type, true, false) + "." + metadata.getString(fieldDef.nameIndex)
        )
        json.name("Signature").nullValue()
        json.endObject()
    }

    private fun writeMethodDefUsage(json: JsonWriter, index: Int, address: Long) {
        val methodDef = metadata.methodDefs[index]
        val typeDef = metadata.typeDefs[methodDef.declaringType]
        val typeName = executor.getTypeDefName(typeDef, true, true)
        val imageName = names.imageNames[methodDef.declaringType]
        val pointer = binary.getMethodPointer(imageName, methodDef, index)
        json.beginObject()
        json.property("Address", binary.rva(address))
        json.property(
            "Name",
            METHOD_PREFIX + typeName + "." + metadata.getString(methodDef.nameIndex) + "()"
        )
        json.property("MethodAddress", if (pointer > 0L) binary.rva(pointer) else 0L)
        json.endObject()
    }

    private fun writeMethodRefUsage(json: JsonWriter, spec: Il2CppMethodSpec, address: Long) {
        val (specTypeName, specMethodName) = executor.getMethodSpecName(spec, true)
        val pointer = binary.methodSpecGenericMethodPointers[spec] ?: 0L
        json.beginObject()
        json.property("Address", binary.rva(address))
        json.property("Name", METHOD_PREFIX + specTypeName + "." + specMethodName + "()")
        json.property("MethodAddress", if (pointer > 0L) binary.rva(pointer) else 0L)
        json.endObject()
    }

    private fun orderedPointers(): LongArray {
        val collected = ArrayList<LongArray>()
        if (version >= 24.2) {
            binary.codeGenModuleMethodPointers.values.forEach { collected += it }
        } else {
            collected += binary.methodPointers
        }
        collected += binary.genericMethodPointers
        collected += binary.invokerPointers
        if (version < 29.0) collected += binary.customAttributeGenerators
        if (version >= 22.0) {
            collected += binary.reversePInvokeWrappers
            collected += binary.unresolvedVirtualCallPointers
        }
        val total = collected.sumOf { it.size }
        val merged = LongArray(total)
        var at = 0
        for (chunk in collected) {
            chunk.copyInto(merged, at)
            at += chunk.size
        }
        sortUnsigned(merged)
        var unique = 0
        for (index in merged.indices) {
            val value = merged[index]
            if (value == 0L) continue
            if (unique > 0 && merged[unique - 1] == value) continue
            merged[unique++] = value
        }
        return merged.copyOf(unique)
    }

    private fun sortUnsigned(values: LongArray) {
        for (index in values.indices) values[index] = values[index] xor Long.MIN_VALUE
        values.sort()
        for (index in values.indices) values[index] = values[index] xor Long.MIN_VALUE
    }

    private inline fun scanUsages(action: (usage: Int, index: Int, address: Long) -> Unit) {
        if (version < 27.0) {
            if (version > 16.0) replayRecordedUsages(action)
            return
        }
        val length = elf.data.size.toLong()
        for (region in dataRegions) {
            var position = region.offset
            val end = minOf(region.offsetEnd, length) - pointerSize
            while (position < end) {
                val value = bytes.pointerAt(position)
                if (value in 0L until UINT_MAX) {
                    val usage = metadata.encodedIndexType(value)
                    if (usage in USAGE_TYPE_INFO..USAGE_METHOD_REF) {
                        val decoded = metadata.decodeMethodIndex(value)
                        if (value == ((usage.toLong() shl 29) or (decoded shl 1)) + 1L) {
                            val address = mapOffsetToAddress(position)
                            if (address > 0L) action(usage, decoded.toInt(), address)
                        }
                    }
                }
                position += pointerSize
            }
        }
    }

    private inline fun replayRecordedUsages(action: (usage: Int, index: Int, address: Long) -> Unit) {
        for (kind in Il2CppMetadataUsage.entries) {
            val usage = kind.ordinal
            if (usage !in USAGE_TYPE_INFO..USAGE_METHOD_REF) continue
            val recorded = metadata.metadataUsages[kind] ?: continue
            for (slot in recorded.keys.sorted()) {
                val index = recorded.getValue(slot).toInt()
                val position = slot.toInt()
                if (position in binary.metadataUsages.indices) {
                    action(usage, index, binary.metadataUsages[position])
                }
            }
        }
    }

    private fun mapOffsetToAddress(offset: Long): Long {
        for (segment in elf.segments) {
            if (offset >= segment.offset && offset <= segment.offset + segment.fileSize) {
                return offset - segment.offset + segment.vaddr
            }
        }
        return 0L
    }

    private fun parseType(type: Il2CppType, context: GenericContext?): String = when (type.type) {
        Il2CppTypeEnum.IL2CPP_TYPE_VOID -> "void"
        Il2CppTypeEnum.IL2CPP_TYPE_BOOLEAN -> "bool"
        Il2CppTypeEnum.IL2CPP_TYPE_CHAR -> "uint16_t"
        Il2CppTypeEnum.IL2CPP_TYPE_I1 -> "int8_t"
        Il2CppTypeEnum.IL2CPP_TYPE_U1 -> "uint8_t"
        Il2CppTypeEnum.IL2CPP_TYPE_I2 -> "int16_t"
        Il2CppTypeEnum.IL2CPP_TYPE_U2 -> "uint16_t"
        Il2CppTypeEnum.IL2CPP_TYPE_I4 -> "int32_t"
        Il2CppTypeEnum.IL2CPP_TYPE_U4 -> "uint32_t"
        Il2CppTypeEnum.IL2CPP_TYPE_I8 -> "int64_t"
        Il2CppTypeEnum.IL2CPP_TYPE_U8 -> "uint64_t"
        Il2CppTypeEnum.IL2CPP_TYPE_R4 -> "float"
        Il2CppTypeEnum.IL2CPP_TYPE_R8 -> "double"
        Il2CppTypeEnum.IL2CPP_TYPE_STRING -> "System_String_o*"
        Il2CppTypeEnum.IL2CPP_TYPE_PTR -> parseType(typeAt(type.elementType), null) + "*"
        Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE -> {
            val typeDef = metadata.typeDefs[type.klassIndex]
            if (typeDef.isEnum) parseType(binary.types[typeDef.elementTypeIndex], null)
            else names.structNames[type.klassIndex] + "_o"
        }
        Il2CppTypeEnum.IL2CPP_TYPE_CLASS -> names.structNames[type.klassIndex] + "_o*"
        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> {
            val argument = genericArgument(type, context?.classArguments)
            if (argument == null) "Il2CppObject*" else parseType(argument, null)
        }
        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY ->
            il2CppStructName(arrayElementType(type), context) + "_array*"
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
            val structName = genericStructName(type)
            val typeDef = metadata.typeDefs[genericClassTypeIndex(type)]
            when {
                !typeDef.isValueType -> structName + "_o*"
                typeDef.isEnum -> parseType(binary.types[typeDef.elementTypeIndex], null)
                else -> structName + "_o"
            }
        }
        Il2CppTypeEnum.IL2CPP_TYPE_TYPEDBYREF -> "Il2CppObject*"
        Il2CppTypeEnum.IL2CPP_TYPE_I -> "intptr_t"
        Il2CppTypeEnum.IL2CPP_TYPE_U -> "uintptr_t"
        Il2CppTypeEnum.IL2CPP_TYPE_OBJECT -> "Il2CppObject*"
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY ->
            il2CppStructName(typeAt(type.elementType), context) + "_array*"
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> {
            val arguments =
                if (context?.methodArguments == null) context?.classArguments else context.methodArguments
            val argument = genericArgument(type, arguments)
            if (argument == null) "Il2CppObject*" else parseType(argument, null)
        }
        else -> throw UnsupportedOperationException("cannot render il2cpp type ${type.type} as a C type")
    }

    private fun il2CppStructName(type: Il2CppType, context: GenericContext?): String = when (type.type) {
        Il2CppTypeEnum.IL2CPP_TYPE_PTR -> il2CppStructName(typeAt(type.elementType), null)
        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY ->
            il2CppStructName(arrayElementType(type), context) + "_array"
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY ->
            il2CppStructName(typeAt(type.elementType), context) + "_array"
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> genericStructName(type)
        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> {
            val argument = genericArgument(type, context?.classArguments)
            if (argument == null) "System_Object" else il2CppStructName(argument, null)
        }
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> {
            val argument = genericArgument(type, context?.methodArguments)
            if (argument == null) "System_Object" else il2CppStructName(argument, null)
        }
        in DEFINITION_BACKED_TYPES -> names.structNames[type.klassIndex]
        else -> throw UnsupportedOperationException("no il2cpp struct name for ${type.type}")
    }

    private fun arrayElementType(type: Il2CppType): Il2CppType {
        val arrayType = binary.arrayTypeAt(type.arrayType)
            ?: throw IllegalStateException("array type at 0x${type.arrayType.toString(16)} is not mapped")
        return typeAt(arrayType.etype)
    }

    private fun genericArgument(type: Il2CppType, arguments: LongArray?): Il2CppType? {
        if (arguments == null) return null
        val number = metadata.genericParameters[type.genericParameterIndex].num
        if (number !in arguments.indices) return null
        return typeAt(arguments[number])
    }

    private fun genericStructName(type: Il2CppType): String =
        names.genericStructNames[type.genericClass] ?: fixName(executor.getTypeName(type, true, false))

    private fun genericClassTypeIndex(type: Il2CppType): Int =
        genericClassTypeIndexOf(type.genericClass)
            ?: throw IllegalStateException("generic class at 0x${type.genericClass.toString(16)} has no type definition")

    private fun genericClassTypeIndexOf(pointer: Long): Int? {
        val genericClass = binary.genericClassAt(pointer) ?: return null
        val index = if (version >= 27.0) {
            typeAt(genericClass.type).klassIndex
        } else {
            genericClass.typeDefinitionIndex.toInt()
        }
        return if (index in metadata.typeDefs.indices) index else null
    }

    private fun declaringTypeIndexOf(type: Il2CppType): Int =
        if (type.type == Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST) genericClassTypeIndex(type) else type.klassIndex

    private fun typeAt(address: Long): Il2CppType =
        binary.typeByAddress[address] ?: binary.readType(address)

    private fun contextOf(spec: Il2CppMethodSpec) = GenericContext(
        if (spec.classIndexIndex != -1) instArguments(spec.classIndexIndex) else null,
        if (spec.methodIndexIndex != -1) instArguments(spec.methodIndexIndex) else null
    )

    private fun instArguments(index: Int): LongArray? {
        val inst = binary.genericInsts.getOrNull(index) ?: return null
        val count = inst.typeArgc.toInt()
        if (count <= 0) return LongArray(0)
        val offset = binary.mapVaToOffset(inst.typeArgv)
        if (offset < 0L || offset + count * pointerSize > bytes.size) return null
        return LongArray(count) { bytes.pointerAt(offset + it * pointerSize) }
    }

    private fun buildNameTables(): NameTables {
        val count = metadata.typeDefs.size
        val fixedNames = Array(count) { "" }
        val structNames = Array(count) { "" }
        val imageNames = Array(count) { "" }
        val taken = HashSet<String>(count * 2)
        for (imageDef in metadata.imageDefs) {
            val imageName = metadata.getString(imageDef.nameIndex)
            for (typeIndex in imageDef.typeStart until imageDef.typeStart + imageDef.typeCount) {
                if (typeIndex !in metadata.typeDefs.indices) continue
                imageNames[typeIndex] = imageName
                val fixed = fixName(executor.getTypeDefName(metadata.typeDefs[typeIndex], true, true))
                fixedNames[typeIndex] = fixed
                structNames[typeIndex] = uniqueName(fixed, taken)
            }
        }
        val genericStructNames = HashMap<Long, String>()
        val genericTypeByStructName = HashMap<String, Il2CppType>()
        for (type in binary.types) {
            if (type.type != Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST) continue
            val pointer = type.genericClass
            if (genericStructNames.containsKey(pointer)) continue
            val typeIndex = genericClassTypeIndexOf(pointer) ?: continue
            val structName = structNames[typeIndex]
                .replace(fixedNames[typeIndex], fixName(executor.getTypeName(type, true, false)))
            genericStructNames[pointer] = structName
            genericTypeByStructName[structName] = type
        }
        return NameTables(fixedNames, structNames, imageNames, genericStructNames, genericTypeByStructName)
    }

    private fun uniqueName(name: String, taken: MutableSet<String>): String {
        if (taken.add(name)) return name
        var suffix = 1
        while (true) {
            val candidate = name + "_" + suffix
            if (taken.add(candidate)) return candidate
            suffix++
        }
    }

    private fun fixName(name: String): String {
        val base = when (name) {
            in KEYWORDS -> "_" + name
            in SPECIAL_KEYWORDS -> "_" + name + "_"
            else -> name
        }
        if (base.isNotEmpty() && base[0] in '0'..'9') return "_" + base
        val builder = StringBuilder(base.length)
        for (character in base) {
            builder.append(
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_') {
                    character
                } else {
                    '_'
                }
            )
        }
        return builder.toString()
    }

    private fun signatureCharOf(type: Il2CppTypeEnum?): Char = when (type) {
        Il2CppTypeEnum.IL2CPP_TYPE_VOID -> 'v'
        Il2CppTypeEnum.IL2CPP_TYPE_I8, Il2CppTypeEnum.IL2CPP_TYPE_U8 -> 'j'
        Il2CppTypeEnum.IL2CPP_TYPE_R4 -> 'f'
        Il2CppTypeEnum.IL2CPP_TYPE_R8 -> 'd'
        Il2CppTypeEnum.IL2CPP_TYPE_BOOLEAN,
        Il2CppTypeEnum.IL2CPP_TYPE_CHAR,
        Il2CppTypeEnum.IL2CPP_TYPE_I1,
        Il2CppTypeEnum.IL2CPP_TYPE_U1,
        Il2CppTypeEnum.IL2CPP_TYPE_I2,
        Il2CppTypeEnum.IL2CPP_TYPE_U2,
        Il2CppTypeEnum.IL2CPP_TYPE_I4,
        Il2CppTypeEnum.IL2CPP_TYPE_U4,
        Il2CppTypeEnum.IL2CPP_TYPE_STRING,
        Il2CppTypeEnum.IL2CPP_TYPE_PTR,
        Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE,
        Il2CppTypeEnum.IL2CPP_TYPE_CLASS,
        Il2CppTypeEnum.IL2CPP_TYPE_VAR,
        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY,
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST,
        Il2CppTypeEnum.IL2CPP_TYPE_TYPEDBYREF,
        Il2CppTypeEnum.IL2CPP_TYPE_I,
        Il2CppTypeEnum.IL2CPP_TYPE_U,
        Il2CppTypeEnum.IL2CPP_TYPE_OBJECT,
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY,
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> 'i'
        else -> throw UnsupportedOperationException("no method type signature letter for $type")
    }

    private class GenericContext(val classArguments: LongArray?, val methodArguments: LongArray?)

    private class NameTables(
        val fixedNames: Array<String>,
        val structNames: Array<String>,
        val imageNames: Array<String>,
        val genericStructNames: Map<Long, String>,
        val genericTypeByStructName: Map<String, Il2CppType>
    )

    private data class Region(val offset: Long, val offsetEnd: Long)

    private companion object {

        const val UINT_MAX = 0xFFFFFFFFL
        const val USAGE_TYPE_INFO = 1
        const val USAGE_IL2CPP_TYPE = 2
        const val USAGE_METHOD_DEF = 3
        const val USAGE_FIELD_INFO = 4
        const val USAGE_STRING_LITERAL = 5
        const val USAGE_METHOD_REF = 6
        const val METHOD_JOIN = "\$\$"
        const val METHOD_PREFIX = "Method\$"
        const val FIELD_PREFIX = "Field\$"

        val WRITABLE_FLAGS = setOf(2, 4, 6)

        val DEFINITION_BACKED_TYPES = setOf(
            Il2CppTypeEnum.IL2CPP_TYPE_VOID,
            Il2CppTypeEnum.IL2CPP_TYPE_BOOLEAN,
            Il2CppTypeEnum.IL2CPP_TYPE_CHAR,
            Il2CppTypeEnum.IL2CPP_TYPE_I1,
            Il2CppTypeEnum.IL2CPP_TYPE_U1,
            Il2CppTypeEnum.IL2CPP_TYPE_I2,
            Il2CppTypeEnum.IL2CPP_TYPE_U2,
            Il2CppTypeEnum.IL2CPP_TYPE_I4,
            Il2CppTypeEnum.IL2CPP_TYPE_U4,
            Il2CppTypeEnum.IL2CPP_TYPE_I8,
            Il2CppTypeEnum.IL2CPP_TYPE_U8,
            Il2CppTypeEnum.IL2CPP_TYPE_R4,
            Il2CppTypeEnum.IL2CPP_TYPE_R8,
            Il2CppTypeEnum.IL2CPP_TYPE_STRING,
            Il2CppTypeEnum.IL2CPP_TYPE_TYPEDBYREF,
            Il2CppTypeEnum.IL2CPP_TYPE_I,
            Il2CppTypeEnum.IL2CPP_TYPE_U,
            Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE,
            Il2CppTypeEnum.IL2CPP_TYPE_CLASS,
            Il2CppTypeEnum.IL2CPP_TYPE_OBJECT
        )

        val KEYWORDS = setOf(
            "klass", "monitor", "register", "_cs", "auto", "friend", "template", "flat", "default",
            "_ds", "interrupt", "unsigned", "signed", "asm", "if", "case", "break", "continue", "do",
            "new", "_", "short", "union", "class", "namespace"
        )

        val SPECIAL_KEYWORDS = setOf("inline", "near", "far")
    }
}
