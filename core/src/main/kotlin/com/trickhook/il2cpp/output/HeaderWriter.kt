package com.trickhook.il2cpp.output

import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_LITERAL
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_STATIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_STATIC
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.il2cpp.Il2CppGenericClass
import com.trickhook.il2cpp.il2cpp.Il2CppGenericInst
import com.trickhook.il2cpp.il2cpp.Il2CppType
import com.trickhook.il2cpp.il2cpp.Il2CppTypeEnum
import com.trickhook.il2cpp.metadata.Il2CppMetadataUsage
import com.trickhook.il2cpp.metadata.Il2CppMethodDefinition
import com.trickhook.il2cpp.metadata.Il2CppRGCTXDefinition
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition
import java.io.BufferedWriter
import java.io.Writer
import java.util.TreeMap

class HeaderWriter(private val executor: Il2CppExecutor) {

    private val metadata = executor.metadata
    private val binary = executor.binary
    private val version = binary.version
    private val elf = binary.elf
    private val reader = elf.reader()
    private val pointerSize = elf.pointerSize

    private val typeDefs = metadata.typeDefs
    private val imageNameOfType = arrayOfNulls<String>(typeDefs.size)
    private val structNames = arrayOfNulls<String>(typeDefs.size)

    private val structNameHashSet = HashSet<String>(1 shl 17)
    private val structInfoList = ArrayList<StructInfo>(1 shl 16)
    private val structInfoByName = HashMap<String, StructInfo>(1 shl 17)
    private val genericClassStructNames = HashMap<Long, String>(1 shl 15)
    private val genericClassTypeDefIndexes = HashMap<Long, Int>(1 shl 15)
    private val nameGenericClasses = HashMap<String, Il2CppType>(1 shl 15)
    private val genericClassList = ArrayList<Long>(1 shl 14)
    private val arrayEntries = ArrayList<ArrayEntry>(1 shl 13)
    private val methodInfoEntries = ArrayList<MethodInfoEntry>(1 shl 17)
    private val methodInfoCache = HashSet<Long>(1 shl 17)
    private val pool = HashMap<String, String>(1 shl 17)

    private var used = false

    fun write(out: Writer) {
        check(!used) { "HeaderWriter is single use, construct a new one for each header file" }
        used = true
        val sink = if (out is BufferedWriter) out else BufferedWriter(out, 1 shl 16)
        val versionHeader = HeaderConstants.forVersion(version)
            ?: throw UnsupportedOperationException("il2cpp version $version cannot generate a header file")
        collect()
        sink.write(HeaderConstants.GENERIC_HEADER)
        sink.write(versionHeader)
        for (info in structInfoList) {
            writeStruct(info, sink)
        }
        for (entry in arrayEntries) {
            writeArrayClass(entry, sink)
        }
        for (entry in methodInfoEntries) {
            writeMethodInfo(entry, sink)
        }
        sink.flush()
    }

    private fun collect() {
        for (imageDef in metadata.imageDefs) {
            val imageName = intern(metadata.getString(imageDef.nameIndex))
            val typeEnd = imageDef.typeStart + imageDef.typeCount
            for (typeIndex in imageDef.typeStart until typeEnd) {
                imageNameOfType[typeIndex] = imageName
                structNames[typeIndex] = uniqueName(fixName(executor.getTypeDefName(typeDefs[typeIndex], true, true)))
            }
        }
        for (type in binary.types) {
            if (type.type != Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST) continue
            val pointer = type.genericClass
            if (genericClassStructNames.containsKey(pointer)) continue
            val genericClass = binary.genericClassAt(pointer) ?: continue
            val typeDefIndex = genericClassTypeDefIndex(genericClass) ?: continue
            val typeBaseName = structNames[typeDefIndex] ?: continue
            val typeToReplaceName = fixName(executor.getTypeDefName(typeDefs[typeDefIndex], true, true))
            val typeReplaceName = fixName(executor.getTypeName(type, true, false))
            val typeStructName = typeBaseName.replace(typeToReplaceName, typeReplaceName)
            nameGenericClasses[typeStructName] = type
            genericClassStructNames[pointer] = typeStructName
            genericClassTypeDefIndexes[pointer] = typeDefIndex
        }
        for (imageDef in metadata.imageDefs) {
            val imageName = metadata.getString(imageDef.nameIndex)
            val typeEnd = imageDef.typeStart + imageDef.typeCount
            for (typeIndex in imageDef.typeStart until typeEnd) {
                addStruct(typeIndex)
                walkMethods(imageName, typeIndex)
            }
        }
        scanMetadataUsages()
        var index = 0
        while (index < genericClassList.size) {
            addGenericClassStruct(genericClassList[index])
            index++
        }
        for (info in structInfoList) {
            structInfoByName[info.typeName + "_o"] = info
        }
        structNameHashSet.clear()
        genericClassStructNames.clear()
        genericClassTypeDefIndexes.clear()
        nameGenericClasses.clear()
        genericClassList.clear()
        methodInfoCache.clear()
        pool.clear()
    }

    private fun walkMethods(imageName: String, typeIndex: Int) {
        val typeDef = typeDefs[typeIndex]
        val methodEnd = typeDef.methodStart + typeDef.methodCount
        for (methodIndex in typeDef.methodStart until methodEnd) {
            val methodDef = metadata.methodDefs[methodIndex]
            if (binary.getMethodPointer(imageName, methodDef, methodIndex) > 0L) {
                parseType(binary.types[methodDef.returnType], null)
                if (methodDef.flags and METHOD_ATTRIBUTE_STATIC == 0) {
                    parseType(binary.types[typeDef.byvalTypeIndex], null)
                }
                for (parameter in 0 until methodDef.parameterCount) {
                    val parameterDef = metadata.parameterDefs[methodDef.parameterStart + parameter]
                    parseType(binary.types[parameterDef.typeIndex], null)
                }
            }
            val specs = binary.methodDefinitionMethodSpecs[methodIndex] ?: continue
            for (spec in specs) {
                val genericMethodPointer = binary.methodSpecGenericMethodPointers[spec] ?: 0L
                if (genericMethodPointer <= 0L) continue
                if (methodInfoCache.add(genericMethodPointer)) {
                    methodInfoEntries.add(
                        MethodInfoEntry(binary.rva(genericMethodPointer), typeIndex, methodIndex, imageName)
                    )
                }
                val context = GenericContext(
                    if (spec.classIndexIndex != -1) binary.genericInsts[spec.classIndexIndex] else null,
                    if (spec.methodIndexIndex != -1) binary.genericInsts[spec.methodIndexIndex] else null
                )
                parseType(binary.types[methodDef.returnType], context)
                if (methodDef.flags and METHOD_ATTRIBUTE_STATIC == 0) {
                    var resolved = false
                    if (spec.classIndexIndex != -1) {
                        val typeToReplaceName = fixName(executor.getTypeDefName(typeDef, true, true))
                        val typeReplaceName = fixName(executor.getMethodSpecName(spec, true).first)
                        val typeStructName = structNames[typeIndex]!!.replace(typeToReplaceName, typeReplaceName)
                        val instanceType = nameGenericClasses[typeStructName]
                        if (instanceType != null) {
                            parseType(instanceType, null)
                            resolved = true
                        }
                    }
                    if (!resolved) parseType(binary.types[typeDef.byvalTypeIndex], null)
                }
                for (parameter in 0 until methodDef.parameterCount) {
                    val parameterDef = metadata.parameterDefs[methodDef.parameterStart + parameter]
                    parseType(binary.types[parameterDef.typeIndex], context)
                }
            }
        }
    }

    private fun scanMetadataUsages() {
        if (version >= 27.0) {
            val limit = reader.size
            for (segment in elf.segments) {
                if (segment.memSize == 0L) continue
                if (segment.flags != 2 && segment.flags != 4 && segment.flags != 6) continue
                var at = segment.offset
                val end = minOf(segment.offset + segment.fileSize, limit) - pointerSize
                while (at < end) {
                    val value = reader.pointerAt(at)
                    val address = at
                    at += pointerSize
                    if (value < 0L || value >= 0xFFFFFFFFL) continue
                    val usage = metadata.encodedIndexType(value)
                    if (usage != Il2CppMetadataUsage.TYPE_INFO.ordinal) continue
                    val decodedIndex = metadata.decodeMethodIndex(value)
                    if (value != ((usage.toLong() shl 29) or (decodedIndex shl 1)) + 1L) continue
                    if (decodedIndex >= binary.types.size) continue
                    if (elf.mapOffsetToVa(address) <= 0L) continue
                    getIl2CppStructName(binary.types[decodedIndex.toInt()], null)
                }
            }
        } else if (version > 16.0) {
            val usages = metadata.metadataUsages[Il2CppMetadataUsage.TYPE_INFO] ?: return
            for (value in usages.values) {
                if (value < binary.types.size) getIl2CppStructName(binary.types[value.toInt()], null)
            }
        }
    }

    private fun addStruct(typeIndex: Int) {
        val typeDef = typeDefs[typeIndex]
        val parent = parentStructName(typeDef)
        val fields = ArrayList<StructField>()
        val staticFields = ArrayList<StructField>()
        addFields(typeDef, fields, staticFields, null)
        val vtable = buildVTable(typeDef)
        val rgctxs = buildRgctx(rgctxDefinitions(imageNameOfType[typeIndex]!!, typeDef.token, typeDef.rgctxStartIndex, typeDef.rgctxCount))
        structInfoList.add(
            StructInfo(
                structNames[typeIndex]!!,
                typeDef.isValueType,
                parent,
                compact(fields),
                compact(staticFields),
                vtable,
                rgctxs
            )
        )
    }

    private fun addGenericClassStruct(pointer: Long) {
        val genericClass = binary.genericClassAt(pointer) ?: return
        val typeDefIndex = genericClassTypeDefIndex(genericClass) ?: return
        val typeDef = typeDefs[typeDefIndex]
        val parent = parentStructName(typeDef)
        val fields = ArrayList<StructField>()
        val staticFields = ArrayList<StructField>()
        val context = GenericContext(
            binary.genericInstAt(genericClass.context.classInst),
            binary.genericInstAt(genericClass.context.methodInst)
        )
        addFields(typeDef, fields, staticFields, context)
        val vtable = buildVTable(typeDef)
        structInfoList.add(
            StructInfo(
                genericClassStructNames[pointer] ?: return,
                typeDef.isValueType,
                parent,
                compact(fields),
                compact(staticFields),
                vtable,
                emptyList()
            )
        )
    }

    private fun compact(fields: ArrayList<StructField>): List<StructField> {
        if (fields.isEmpty()) return emptyList()
        fields.trimToSize()
        return fields
    }

    private fun parentStructName(typeDef: Il2CppTypeDefinition): String? {
        if (typeDef.isValueType || typeDef.isEnum) return null
        if (typeDef.parentIndex < 0) return null
        val parent = binary.types[typeDef.parentIndex]
        if (parent.type == Il2CppTypeEnum.IL2CPP_TYPE_OBJECT) return null
        return intern(getIl2CppStructName(parent, null))
    }

    private fun addFields(
        typeDef: Il2CppTypeDefinition,
        fields: MutableList<StructField>,
        staticFields: MutableList<StructField>,
        context: GenericContext?
    ) {
        if (typeDef.fieldCount <= 0) return
        val fieldEnd = typeDef.fieldStart + typeDef.fieldCount
        val cache = HashSet<String>(typeDef.fieldCount * 2)
        for (index in typeDef.fieldStart until fieldEnd) {
            val fieldDef = metadata.fieldDefs[index]
            val fieldType = binary.types[fieldDef.typeIndex]
            if (fieldType.attrs and FIELD_ATTRIBUTE_LITERAL != 0) continue
            val fieldTypeName = intern(parseType(fieldType, context))
            var fieldName = fixName(metadata.getString(fieldDef.nameIndex))
            if (!cache.add(fieldName)) fieldName = "_${index - typeDef.fieldStart}_$fieldName"
            val field = StructField(
                fieldTypeName,
                intern(fieldName),
                isValueType(fieldType, context),
                isCustomType(fieldType, context)
            )
            if (fieldType.attrs and FIELD_ATTRIBUTE_STATIC != 0) staticFields.add(field) else fields.add(field)
        }
    }

    private fun buildVTable(typeDef: Il2CppTypeDefinition): Array<String?> {
        val bySlot = TreeMap<Int, Il2CppMethodDefinition>()
        for (index in 0 until typeDef.vtableCount) {
            val encoded = metadata.vtableMethods[typeDef.vtableStart + index]
            val usage = metadata.encodedIndexType(encoded)
            val decoded = metadata.decodeMethodIndex(encoded)
            val methodDef = if (usage == Il2CppMetadataUsage.METHOD_REF.ordinal) {
                metadata.methodDefs[binary.methodSpecs[decoded].methodDefinitionIndex]
            } else {
                metadata.methodDefs[decoded]
            }
            if (methodDef.slot != 0xFFFF) bySlot[methodDef.slot] = methodDef
        }
        if (bySlot.isEmpty()) return EMPTY_VTABLE
        val slots = arrayOfNulls<String>(bySlot.lastKey() + 1)
        for ((slot, methodDef) in bySlot) {
            slots[slot] = intern(fixName(metadata.getString(methodDef.nameIndex)))
        }
        return slots
    }

    private fun rgctxDefinitions(
        imageName: String,
        token: Int,
        startIndex: Int,
        count: Int
    ): Array<Il2CppRGCTXDefinition>? {
        if (version >= 24.2) return binary.rgctxs[imageName]?.get(token.toLong() and 0xFFFFFFFFL)
        if (count <= 0) return null
        return Array(count) { metadata.rgctxEntries[startIndex + it] }
    }

    private fun buildRgctx(definitions: Array<Il2CppRGCTXDefinition>?): List<StructRgctx> {
        if (definitions == null || definitions.isEmpty()) return emptyList()
        return definitions.map { definition ->
            val kind = definition.rgctxDataType
            val dataIndex = if (version >= 27.2) {
                val offset = binary.mapVaToOffset(definition.data)
                if (offset < 0L) -1 else reader.int32At(offset)
            } else {
                definition.dataDummy
            }
            val name = when {
                dataIndex < 0 -> ""
                kind == RGCTX_DATA_TYPE || kind == RGCTX_DATA_CLASS ->
                    fixName(executor.getTypeName(binary.types[dataIndex], true, false))
                kind == RGCTX_DATA_METHOD -> {
                    val spec = executor.getMethodSpecName(binary.methodSpecs[dataIndex], true)
                    fixName(spec.first + "." + spec.second)
                }
                else -> ""
            }
            StructRgctx(kind, name)
        }
    }

    private fun writeStruct(info: StructInfo, out: Writer) {
        if (info.emitted) return
        info.emitted = true
        val deps = ArrayList<StructInfo>()
        val body = StringBuilder(256)
        val parent = info.parent
        if (parent != null) {
            structInfoByName[parent + "_o"]?.let { deps.add(it) }
            body.append("struct ").append(info.typeName).append("_Fields : ").append(parent).append("_Fields {\n")
        } else {
            body.append("struct ").append(info.typeName).append("_Fields {\n")
        }
        for (field in info.fields) {
            if (field.isValueType) structInfoByName[field.typeName]?.let { deps.add(it) }
            appendField(body, field)
        }
        body.append("};\n")

        if (info.rgctxs.isNotEmpty()) {
            body.append("struct ").append(info.typeName).append("_RGCTXs {\n")
            for (index in info.rgctxs.indices) {
                val rgctx = info.rgctxs[index]
                when (rgctx.kind) {
                    RGCTX_DATA_TYPE -> body.append("\tIl2CppType* _").append(index).append('_').append(rgctx.name).append(";\n")
                    RGCTX_DATA_CLASS -> body.append("\tIl2CppClass* _").append(index).append('_').append(rgctx.name).append(";\n")
                    RGCTX_DATA_METHOD -> body.append("\tMethodInfo* _").append(index).append('_').append(rgctx.name).append(";\n")
                }
            }
            body.append("};\n")
        }

        if (info.vtable.isNotEmpty()) {
            body.append("struct ").append(info.typeName).append("_VTable {\n")
            for (index in info.vtable.indices) {
                body.append("\tVirtualInvokeData _").append(index).append('_')
                body.append(info.vtable[index] ?: "unknown")
                body.append(";\n")
            }
            body.append("};\n")
        }

        body.append("struct ").append(info.typeName).append("_c {\n")
        body.append("\tIl2CppClass_1 _1;\n")
        if (info.staticFields.isNotEmpty()) {
            body.append("\tstruct ").append(info.typeName).append("_StaticFields* static_fields;\n")
        } else {
            body.append("\tvoid* static_fields;\n")
        }
        if (info.rgctxs.isNotEmpty()) {
            body.append('\t').append(info.typeName).append("_RGCTXs* rgctx_data;\n")
        } else {
            body.append("\tIl2CppRGCTXData* rgctx_data;\n")
        }
        body.append("\tIl2CppClass_2 _2;\n")
        if (info.vtable.isNotEmpty()) {
            body.append('\t').append(info.typeName).append("_VTable vtable;\n")
        } else {
            body.append("\tVirtualInvokeData vtable[32];\n")
        }
        body.append("};\n")

        body.append("struct ").append(info.typeName).append("_o {\n")
        if (!info.isValueType) {
            body.append('\t').append(info.typeName).append("_c *klass;\n")
            body.append("\tvoid *monitor;\n")
        }
        body.append('\t').append(info.typeName).append("_Fields fields;\n")
        body.append("};\n")

        if (info.staticFields.isNotEmpty()) {
            body.append("struct ").append(info.typeName).append("_StaticFields {\n")
            for (field in info.staticFields) {
                if (field.isValueType) structInfoByName[field.typeName]?.let { deps.add(it) }
                appendField(body, field)
            }
            body.append("};\n")
        }

        for (dep in deps) writeStruct(dep, out)
        out.write(body.toString())
    }

    private fun appendField(body: StringBuilder, field: StructField) {
        if (field.isCustomType) {
            body.append("\tstruct ").append(field.typeName).append(' ').append(field.name).append(";\n")
        } else {
            body.append('\t').append(field.typeName).append(' ').append(field.name).append(";\n")
        }
    }

    private fun writeArrayClass(entry: ArrayEntry, out: Writer) {
        out.write("struct ")
        out.write(entry.structName)
        out.write("_array {\n")
        out.write("\tIl2CppObject obj;\n")
        out.write("\tIl2CppArrayBounds *bounds;\n")
        out.write("\til2cpp_array_size_t max_length;\n")
        out.write("\t")
        out.write(entry.itemTypeName)
        out.write(" m_Items[65535];\n")
        out.write("};\n")
    }

    private fun writeMethodInfo(entry: MethodInfoEntry, out: Writer) {
        val name = "MethodInfo_" + java.lang.Long.toHexString(entry.address).uppercase()
        val structTypeName = structNames[entry.typeIndex]!!
        val methodDef = metadata.methodDefs[entry.methodIndex]
        val rgctxs = buildRgctx(
            rgctxDefinitions(entry.imageName, methodDef.token, methodDef.rgctxStartIndex, methodDef.rgctxCount)
        )
        val body = StringBuilder(512)
        if (rgctxs.isNotEmpty()) {
            body.append("struct ").append(name).append("_RGCTXs {\n")
            for (index in rgctxs.indices) {
                val rgctx = rgctxs[index]
                when (rgctx.kind) {
                    RGCTX_DATA_TYPE -> body.append("\tIl2CppType* _").append(index).append('_').append(rgctx.name).append(";\n")
                    RGCTX_DATA_CLASS -> body.append("\tIl2CppClass* _").append(index).append('_').append(rgctx.name).append(";\n")
                    RGCTX_DATA_METHOD -> body.append("\tMethodInfo* _").append(index).append('_').append(rgctx.name).append(";\n")
                }
            }
            body.append("};\n")
        }
        body.append("struct ").append(name).append(" {\n")
        body.append("\tIl2CppMethodPointer methodPointer;\n")
        if (version >= 29.0) {
            body.append("\tIl2CppMethodPointer virtualMethodPointer;\n")
            body.append("\tInvokerMethod invoker_method;\n")
        } else {
            body.append("\tvoid* invoker_method;\n")
        }
        body.append("\tconst char* name;\n")
        if (version <= 24.0) {
            body.append('\t').append(structTypeName).append("_c *declaring_type;\n")
        } else {
            body.append('\t').append(structTypeName).append("_c *klass;\n")
        }
        body.append("\tconst Il2CppType *return_type;\n")
        if (version >= 29.0) {
            body.append("\tconst Il2CppType** parameters;\n")
        } else {
            body.append("\tconst void* parameters;\n")
        }
        if (rgctxs.isNotEmpty()) {
            body.append("\tconst ").append(name).append("_RGCTXs* rgctx_data;\n")
        } else {
            body.append("\tconst Il2CppRGCTXData* rgctx_data;\n")
        }
        body.append("\tunion\n")
        body.append("\t{\n")
        body.append("\t\tconst void* genericMethod;\n")
        if (version >= 27.0) {
            body.append("\t\tconst void* genericContainerHandle;\n")
        } else {
            body.append("\t\tconst void* genericContainer;\n")
        }
        body.append("\t};\n")
        if (version <= 24.0) {
            body.append("\tint32_t customAttributeIndex;\n")
        }
        body.append("\tuint32_t token;\n")
        body.append("\tuint16_t flags;\n")
        body.append("\tuint16_t iflags;\n")
        body.append("\tuint16_t slot;\n")
        body.append("\tuint8_t parameters_count;\n")
        body.append("\tuint8_t bitflags;\n")
        body.append("};\n")
        out.write(body.toString())
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
            val typeDef = typeDefs[type.klassIndex]
            if (typeDef.isEnum) parseType(binary.types[typeDef.elementTypeIndex], null)
            else structNames[type.klassIndex]!! + "_o"
        }
        Il2CppTypeEnum.IL2CPP_TYPE_CLASS -> structNames[type.klassIndex]!! + "_o*"
        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> {
            val inst = context?.classInst
            if (inst == null) "Il2CppObject*"
            else parseType(genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num), null)
        }
        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY -> {
            val arrayType = binary.arrayTypeAt(type.arrayType)
                ?: throw IllegalStateException("array type at 0x${type.arrayType.toString(16)} is not mapped")
            arrayStructName(typeAt(arrayType.etype), context) + "*"
        }
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
            val pointer = type.genericClass
            val typeStructName = genericClassStructNames[pointer]
                ?: throw IllegalStateException("generic class at 0x${pointer.toString(16)} has no struct name")
            if (structNameHashSet.add(typeStructName)) genericClassList.add(pointer)
            val typeDef = typeDefs[genericClassTypeDefIndexes[pointer]!!]
            when {
                !typeDef.isValueType -> typeStructName + "_o*"
                typeDef.isEnum -> parseType(binary.types[typeDef.elementTypeIndex], null)
                else -> typeStructName + "_o"
            }
        }
        Il2CppTypeEnum.IL2CPP_TYPE_TYPEDBYREF -> "Il2CppObject*"
        Il2CppTypeEnum.IL2CPP_TYPE_I -> "intptr_t"
        Il2CppTypeEnum.IL2CPP_TYPE_U -> "uintptr_t"
        Il2CppTypeEnum.IL2CPP_TYPE_OBJECT -> "Il2CppObject*"
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY -> arrayStructName(typeAt(type.elementType), context) + "*"
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> {
            val methodInst = context?.methodInst
            val classInst = context?.classInst
            when {
                methodInst != null ->
                    parseType(genericArgument(methodInst, executor.getGenericParameterFromIl2CppType(type).num), null)
                classInst != null ->
                    parseType(genericArgument(classInst, executor.getGenericParameterFromIl2CppType(type).num), null)
                else -> "Il2CppObject*"
            }
        }
        else -> throw UnsupportedOperationException("cannot render type ${type.type} as a C declaration")
    }

    private fun arrayStructName(elementType: Il2CppType, context: GenericContext?): String {
        val elementStructName = getIl2CppStructName(elementType, context)
        val typeStructName = elementStructName + "_array"
        if (structNameHashSet.add(typeStructName)) parseArrayClassStruct(elementType, context)
        return typeStructName
    }

    private fun parseArrayClassStruct(elementType: Il2CppType, context: GenericContext?) {
        val structName = getIl2CppStructName(elementType, context)
        val itemTypeName = parseType(elementType, context)
        arrayEntries.add(ArrayEntry(intern(structName), intern(itemTypeName)))
    }

    private fun getIl2CppStructName(type: Il2CppType, context: GenericContext?): String = when (type.type) {
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
        Il2CppTypeEnum.IL2CPP_TYPE_OBJECT -> structNames[type.klassIndex]!!
        Il2CppTypeEnum.IL2CPP_TYPE_PTR -> getIl2CppStructName(typeAt(type.elementType), null)
        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY -> {
            val arrayType = binary.arrayTypeAt(type.arrayType)
                ?: throw IllegalStateException("array type at 0x${type.arrayType.toString(16)} is not mapped")
            arrayStructName(typeAt(arrayType.etype), context)
        }
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY -> arrayStructName(typeAt(type.elementType), context)
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
            val pointer = type.genericClass
            val typeStructName = genericClassStructNames[pointer]
                ?: throw IllegalStateException("generic class at 0x${pointer.toString(16)} has no struct name")
            if (structNameHashSet.add(typeStructName)) genericClassList.add(pointer)
            typeStructName
        }
        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> {
            val inst = context?.classInst
            if (inst == null) "System_Object"
            else getIl2CppStructName(genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num), null)
        }
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> {
            val inst = context?.methodInst
            if (inst == null) "System_Object"
            else getIl2CppStructName(genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num), null)
        }
        else -> throw UnsupportedOperationException("cannot name type ${type.type}")
    }

    private fun isValueType(type: Il2CppType, context: GenericContext?): Boolean = when (type.type) {
        Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE -> !typeDefs[type.klassIndex].isEnum
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
            val typeDefIndex = genericClassTypeDefIndexes[type.genericClass]
            if (typeDefIndex == null) false
            else typeDefs[typeDefIndex].isValueType && !typeDefs[typeDefIndex].isEnum
        }
        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> {
            val inst = context?.classInst
            inst != null && isValueType(
                genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num),
                null
            )
        }
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> {
            val inst = context?.methodInst
            inst != null && isValueType(
                genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num),
                null
            )
        }
        else -> false
    }

    private fun isCustomType(type: Il2CppType, context: GenericContext?): Boolean = when (type.type) {
        Il2CppTypeEnum.IL2CPP_TYPE_PTR -> isCustomType(typeAt(type.elementType), context)
        Il2CppTypeEnum.IL2CPP_TYPE_STRING,
        Il2CppTypeEnum.IL2CPP_TYPE_CLASS,
        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY,
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY -> true
        Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE -> {
            val typeDef = typeDefs[type.klassIndex]
            if (typeDef.isEnum) isCustomType(binary.types[typeDef.elementTypeIndex], context) else true
        }
        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
            val typeDefIndex = genericClassTypeDefIndexes[type.genericClass]
            if (typeDefIndex != null && typeDefs[typeDefIndex].isEnum) {
                isCustomType(binary.types[typeDefs[typeDefIndex].elementTypeIndex], context)
            } else {
                true
            }
        }
        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> {
            val inst = context?.classInst
            inst != null && isCustomType(
                genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num),
                null
            )
        }
        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> {
            val inst = context?.methodInst
            inst != null && isCustomType(
                genericArgument(inst, executor.getGenericParameterFromIl2CppType(type).num),
                null
            )
        }
        else -> false
    }

    private fun genericClassTypeDefIndex(genericClass: Il2CppGenericClass): Int? {
        if (version < 27.0) {
            val index = genericClass.typeDefinitionIndex
            if (index == 0xFFFFFFFFL || index == -1L) return null
            return index.toInt()
        }
        if (genericClass.type == 0L) return null
        return typeAt(genericClass.type).klassIndex
    }

    private fun genericArgument(inst: Il2CppGenericInst, num: Int): Il2CppType {
        val offset = binary.mapVaToOffset(inst.typeArgv)
        if (offset < 0L) throw IllegalStateException("generic inst arguments at 0x${inst.typeArgv.toString(16)} are not mapped")
        return typeAt(reader.pointerAt(offset + num.toLong() * pointerSize))
    }

    private fun typeAt(address: Long): Il2CppType = binary.typeByAddress[address] ?: binary.readType(address)

    private fun uniqueName(name: String): String {
        var candidate = name
        var counter = 1
        while (!structNameHashSet.add(candidate)) {
            candidate = "${name}_${counter++}"
        }
        return intern(candidate)
    }

    private fun intern(value: String): String = pool.getOrPut(value) { value }

    private fun fixName(name: String): String {
        var text = name
        if (text in KEYWORDS) text = "_$text"
        else if (text in SPECIAL_KEYWORDS) text = "_${text}_"
        if (text.isNotEmpty() && text[0] in '0'..'9') return "_$text"
        val builder = StringBuilder(text.length)
        for (character in text) {
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

    private class GenericContext(val classInst: Il2CppGenericInst?, val methodInst: Il2CppGenericInst?)

    private data class StructField(
        val typeName: String,
        val name: String,
        val isValueType: Boolean,
        val isCustomType: Boolean
    )

    private data class StructRgctx(val kind: Int, val name: String)

    private data class ArrayEntry(val structName: String, val itemTypeName: String)

    private data class MethodInfoEntry(
        val address: Long,
        val typeIndex: Int,
        val methodIndex: Int,
        val imageName: String
    )

    private class StructInfo(
        val typeName: String,
        val isValueType: Boolean,
        val parent: String?,
        val fields: List<StructField>,
        val staticFields: List<StructField>,
        val vtable: Array<String?>,
        val rgctxs: List<StructRgctx>
    ) {
        var emitted = false
    }

    private companion object {
        const val RGCTX_DATA_TYPE = 1
        const val RGCTX_DATA_CLASS = 2
        const val RGCTX_DATA_METHOD = 3

        val EMPTY_VTABLE = arrayOfNulls<String>(0)

        val KEYWORDS = hashSetOf(
            "klass", "monitor", "register", "_cs", "auto", "friend", "template", "flat", "default", "_ds",
            "interrupt", "unsigned", "signed", "asm", "if", "case", "break", "continue", "do", "new", "_",
            "short", "union", "class", "namespace"
        )

        val SPECIAL_KEYWORDS = hashSetOf("inline", "near", "far")
    }
}
