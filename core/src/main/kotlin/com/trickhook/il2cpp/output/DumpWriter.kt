package com.trickhook.il2cpp.output

import com.trickhook.il2cpp.il2cpp.BlobValue
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_LITERAL
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_STATIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.METHOD_ATTRIBUTE_ABSTRACT
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.PARAM_ATTRIBUTE_IN
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.PARAM_ATTRIBUTE_OUT
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_ABSTRACT
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_INTERFACE
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NESTED_ASSEMBLY
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NESTED_FAMILY
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NESTED_FAM_AND_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NESTED_FAM_OR_ASSEM
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NESTED_PRIVATE
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NESTED_PUBLIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_NOT_PUBLIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_PUBLIC
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_SEALED
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_SERIALIZABLE
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.TYPE_ATTRIBUTE_VISIBILITY_MASK
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.il2cpp.Il2CppType
import com.trickhook.il2cpp.il2cpp.ManagedNumber
import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.metadata.Il2CppImageDefinition
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition
import java.io.BufferedWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.util.Locale
import kotlin.math.abs

data class DumpOptions(
    val dumpMethod: Boolean = true,
    val dumpField: Boolean = true,
    val dumpProperty: Boolean = false,
    val dumpAttribute: Boolean = false,
    val dumpFieldOffset: Boolean = true,
    val dumpMethodOffset: Boolean = true,
    val dumpTypeDefIndex: Boolean = true
)

class DumpWriter(private val executor: Il2CppExecutor, private val options: DumpOptions = DumpOptions()) {

    private val metadata = executor.metadata
    private val binary = executor.binary
    private val blobReader = BinaryReader(metadata.raw)
    private val parameters = StringBuilder(256)

    fun write(out: Writer) {
        val writer = BufferedWriter(out, BUFFER_SIZE)
        val images = metadata.imageDefs
        for (index in images.indices) {
            writer.write("// Image ")
            writer.write(index.toString())
            writer.write(": ")
            writer.write(metadata.getString(images[index].nameIndex))
            writer.write(" - ")
            writer.write(images[index].typeStart.toString())
            writer.write("\n")
        }
        for (imageDef in images) {
            try {
                writeImage(writer, imageDef)
            } catch (error: Throwable) {
                writer.write("/*")
                writer.write(describe(error))
                writer.write("*/\n}\n")
            }
        }
        writer.flush()
    }

    private fun writeImage(writer: Writer, imageDef: Il2CppImageDefinition) {
        val imageName = metadata.getString(imageDef.nameIndex)
        val typeEnd = imageDef.typeStart + imageDef.typeCount
        for (typeDefIndex in imageDef.typeStart until typeEnd) {
            val typeDef = metadata.typeDefs[typeDefIndex]
            val extends = ArrayList<String>()
            if (typeDef.parentIndex >= 0) {
                val parentName = executor.getTypeName(binary.types[typeDef.parentIndex], false, false)
                if (!typeDef.isValueType && !typeDef.isEnum && parentName != "object") {
                    extends.add(parentName)
                }
            }
            for (index in 0 until typeDef.interfacesCount) {
                val interfaceType = binary.types[metadata.interfaceIndices[typeDef.interfacesStart + index]]
                extends.add(executor.getTypeName(interfaceType, false, false))
            }
            writer.write("\n// Namespace: ")
            writer.write(metadata.getString(typeDef.namespaceIndex))
            writer.write("\n")
            if (options.dumpAttribute) {
                writeCustomAttribute(writer, imageDef, typeDef.customAttributeIndex, typeDef.token, "")
                if (typeDef.flags and TYPE_ATTRIBUTE_SERIALIZABLE != 0) writer.write("[Serializable]\n")
            }
            when (typeDef.flags and TYPE_ATTRIBUTE_VISIBILITY_MASK) {
                TYPE_ATTRIBUTE_PUBLIC, TYPE_ATTRIBUTE_NESTED_PUBLIC -> writer.write("public ")
                TYPE_ATTRIBUTE_NOT_PUBLIC,
                TYPE_ATTRIBUTE_NESTED_FAM_AND_ASSEM,
                TYPE_ATTRIBUTE_NESTED_ASSEMBLY -> writer.write("internal ")
                TYPE_ATTRIBUTE_NESTED_PRIVATE -> writer.write("private ")
                TYPE_ATTRIBUTE_NESTED_FAMILY -> writer.write("protected ")
                TYPE_ATTRIBUTE_NESTED_FAM_OR_ASSEM -> writer.write("protected internal ")
            }
            val abstract = typeDef.flags and TYPE_ATTRIBUTE_ABSTRACT != 0
            val sealed = typeDef.flags and TYPE_ATTRIBUTE_SEALED != 0
            val isInterface = typeDef.flags and TYPE_ATTRIBUTE_INTERFACE != 0
            if (abstract && sealed) {
                writer.write("static ")
            } else if (!isInterface && abstract) {
                writer.write("abstract ")
            } else if (!typeDef.isValueType && !typeDef.isEnum && sealed) {
                writer.write("sealed ")
            }
            when {
                isInterface -> writer.write("interface ")
                typeDef.isEnum -> writer.write("enum ")
                typeDef.isValueType -> writer.write("struct ")
                else -> writer.write("class ")
            }
            writer.write(executor.getTypeDefName(typeDef, false, true))
            if (extends.isNotEmpty()) {
                writer.write(" : ")
                writer.write(extends.joinToString(", "))
            }
            if (options.dumpTypeDefIndex) {
                writer.write(" // TypeDefIndex: ")
                writer.write(typeDefIndex.toString())
                writer.write("\n{")
            } else {
                writer.write("\n{")
            }
            if (options.dumpField && typeDef.fieldCount > 0) writeFields(writer, imageDef, typeDef, typeDefIndex)
            if (options.dumpProperty && typeDef.propertyCount > 0) writeProperties(writer, imageDef, typeDef)
            if (options.dumpMethod && typeDef.methodCount > 0) writeMethods(writer, imageDef, imageName, typeDef)
            writer.write("}\n")
        }
    }

    private fun writeFields(
        writer: Writer,
        imageDef: Il2CppImageDefinition,
        typeDef: Il2CppTypeDefinition,
        typeDefIndex: Int
    ) {
        writer.write("\n\t// Fields\n")
        val fieldEnd = typeDef.fieldStart + typeDef.fieldCount
        for (index in typeDef.fieldStart until fieldEnd) {
            val fieldDef = metadata.fieldDefs[index]
            val fieldType = binary.types[fieldDef.typeIndex]
            if (options.dumpAttribute) {
                writeCustomAttribute(writer, imageDef, fieldDef.customAttributeIndex, fieldDef.token, "\t")
            }
            writer.write("\t")
            writer.write(executor.getFieldModifiers(fieldDef, fieldType.attrs))
            val isConst = fieldType.attrs and FIELD_ATTRIBUTE_LITERAL != 0
            val isStatic = !isConst && fieldType.attrs and FIELD_ATTRIBUTE_STATIC != 0
            writer.write(executor.getTypeName(fieldType, false, false))
            writer.write(" ")
            writer.write(metadata.getString(fieldDef.nameIndex))
            val defaultValue = metadata.getFieldDefaultValue(index)
            if (defaultValue != null && defaultValue.dataIndex != -1) {
                writeDefaultValue(writer, binary.types[defaultValue.typeIndex], defaultValue.dataIndex)
            }
            if (options.dumpFieldOffset && !isConst) {
                val offset = binary.getFieldOffsetFromIndex(
                    typeDefIndex,
                    index - typeDef.fieldStart,
                    index,
                    typeDef.isValueType,
                    isStatic
                )
                writer.write("; // 0x")
                writer.write(hex(offset))
                writer.write("\n")
            } else {
                writer.write(";\n")
            }
        }
    }

    private fun writeProperties(
        writer: Writer,
        imageDef: Il2CppImageDefinition,
        typeDef: Il2CppTypeDefinition
    ) {
        writer.write("\n\t// Properties\n")
        val propertyEnd = typeDef.propertyStart + typeDef.propertyCount
        for (index in typeDef.propertyStart until propertyEnd) {
            val propertyDef = metadata.propertyDefs[index]
            if (options.dumpAttribute) {
                writeCustomAttribute(writer, imageDef, propertyDef.customAttributeIndex, propertyDef.token, "\t")
            }
            writer.write("\t")
            if (propertyDef.get >= 0) {
                val methodDef = metadata.methodDefs[typeDef.methodStart + propertyDef.get]
                writer.write(executor.getModifiers(methodDef))
                writer.write(executor.getTypeName(binary.types[methodDef.returnType], false, false))
                writer.write(" ")
                writer.write(metadata.getString(propertyDef.nameIndex))
                writer.write(" { ")
            } else if (propertyDef.set >= 0) {
                val methodDef = metadata.methodDefs[typeDef.methodStart + propertyDef.set]
                writer.write(executor.getModifiers(methodDef))
                val parameterDef = metadata.parameterDefs[methodDef.parameterStart]
                writer.write(executor.getTypeName(binary.types[parameterDef.typeIndex], false, false))
                writer.write(" ")
                writer.write(metadata.getString(propertyDef.nameIndex))
                writer.write(" { ")
            }
            if (propertyDef.get >= 0) writer.write("get; ")
            if (propertyDef.set >= 0) writer.write("set; ")
            writer.write("}")
            writer.write("\n")
        }
    }

    private fun writeMethods(
        writer: Writer,
        imageDef: Il2CppImageDefinition,
        imageName: String,
        typeDef: Il2CppTypeDefinition
    ) {
        writer.write("\n\t// Methods\n")
        val methodEnd = typeDef.methodStart + typeDef.methodCount
        for (index in typeDef.methodStart until methodEnd) {
            writer.write("\n")
            val methodDef = metadata.methodDefs[index]
            val isAbstract = methodDef.flags and METHOD_ATTRIBUTE_ABSTRACT != 0
            if (options.dumpAttribute) {
                writeCustomAttribute(writer, imageDef, methodDef.customAttributeIndex, methodDef.token, "\t")
            }
            if (options.dumpMethodOffset) {
                val methodPointer = binary.getMethodPointer(imageName, methodDef, index)
                if (!isAbstract && methodPointer > 0) {
                    writer.write("\t// RVA: 0x")
                    writer.write(hex(binary.rva(methodPointer)))
                    writer.write(" Offset: 0x")
                    writer.write(hex(binary.mapVaToOffset(methodPointer)))
                    writer.write(" VA: 0x")
                    writer.write(hex(methodPointer))
                } else {
                    writer.write("\t// RVA: -1 Offset: -1")
                }
                if (methodDef.slot != SLOT_NONE) {
                    writer.write(" Slot: ")
                    writer.write(methodDef.slot.toString())
                }
                writer.write("\n")
            }
            writer.write("\t")
            writer.write(executor.getModifiers(methodDef))
            val returnType = binary.types[methodDef.returnType]
            if (returnType.byref) writer.write("ref ")
            writer.write(executor.getTypeName(returnType, false, false))
            writer.write(" ")
            writer.write(metadata.getString(methodDef.nameIndex))
            if (methodDef.genericContainerIndex >= 0) {
                val container = metadata.genericContainers[methodDef.genericContainerIndex]
                writer.write(executor.getGenericContainerParams(container).joinToString(", ", "<", ">"))
            }
            writer.write("(")
            parameters.setLength(0)
            for (position in 0 until methodDef.parameterCount) {
                if (position > 0) parameters.append(", ")
                val parameterDef = metadata.parameterDefs[methodDef.parameterStart + position]
                val parameterType = binary.types[parameterDef.typeIndex]
                if (parameterType.byref) {
                    val out = parameterType.attrs and PARAM_ATTRIBUTE_OUT != 0
                    val into = parameterType.attrs and PARAM_ATTRIBUTE_IN != 0
                    parameters.append(if (out && !into) "out " else if (!out && into) "in " else "ref ")
                } else {
                    if (parameterType.attrs and PARAM_ATTRIBUTE_IN != 0) parameters.append("[In] ")
                    if (parameterType.attrs and PARAM_ATTRIBUTE_OUT != 0) parameters.append("[Out] ")
                }
                parameters.append(executor.getTypeName(parameterType, false, false))
                parameters.append(' ')
                parameters.append(metadata.getString(parameterDef.nameIndex))
                val defaultValue = metadata.getParameterDefaultValue(methodDef.parameterStart + position)
                if (defaultValue != null && defaultValue.dataIndex != -1) {
                    val rendered = renderDefaultValue(binary.types[defaultValue.typeIndex], defaultValue.dataIndex)
                    if (rendered.startsWith(METADATA_OFFSET_PREFIX)) {
                        parameters.append(' ').append(rendered)
                    } else {
                        parameters.append(" = ")
                        if (rendered == "null") writer.write("null") else parameters.append(rendered)
                    }
                }
            }
            writer.write(parameters.toString())
            writer.write(if (isAbstract) ");\n" else ") { }\n")
            val specs = binary.methodDefinitionMethodSpecs[index] ?: continue
            writer.write("\t/* GenericInstMethod :\n")
            for ((pointer, group) in specs.groupBy { binary.methodSpecGenericMethodPointers[it] }) {
                writer.write("\t|\n")
                if (pointer != null && pointer > 0) {
                    writer.write("\t|-RVA: 0x")
                    writer.write(hex(binary.rva(pointer)))
                    writer.write(" Offset: 0x")
                    writer.write(hex(binary.mapVaToOffset(pointer)))
                    writer.write(" VA: 0x")
                    writer.write(hex(pointer))
                    writer.write("\n")
                } else {
                    writer.write("\t|-RVA: -1 Offset: -1\n")
                }
                for (spec in group) {
                    val (specTypeName, specMethodName) = executor.getMethodSpecName(spec, false)
                    writer.write("\t|-")
                    writer.write(specTypeName)
                    writer.write(".")
                    writer.write(specMethodName)
                    writer.write("\n")
                }
            }
            writer.write("\t*/\n")
        }
    }

    private fun writeDefaultValue(writer: Writer, type: Il2CppType, dataIndex: Int) {
        val rendered = renderDefaultValue(type, dataIndex)
        if (rendered.startsWith(METADATA_OFFSET_PREFIX)) {
            writer.write(" ")
            writer.write(rendered)
        } else {
            writer.write(" = ")
            writer.write(rendered)
        }
    }

    private fun renderDefaultValue(type: Il2CppType, dataIndex: Int): String {
        val pointer = metadata.getDefaultValueData(dataIndex)
        val typeEnum = type.type ?: return metadataOffset(pointer)
        blobReader.seek(pointer)
        val blob = executor.readConstantValueFromBlob(typeEnum, blobReader) ?: return metadataOffset(pointer)
        return render(blob.value)
    }

    private fun metadataOffset(pointer: Long): String =
        METADATA_OFFSET_PREFIX + java.lang.Long.toHexString(pointer).uppercase() + "*/"

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + escape(value) + "\""
        is Char -> "'\\x" + value.code.toString(16) + "'"
        is Boolean -> if (value) "True" else "False"
        is Float -> ManagedNumber.format(value)
        is Double -> ManagedNumber.format(value)
        is Il2CppType -> "typeof(" + executor.getTypeName(value, false, false) + ")"
        is Array<*> -> value.joinToString(", ", "new[] { ", " }") {
            render(if (it is BlobValue) it.value else it)
        }
        else -> value.toString()
    }

    private fun writeCustomAttribute(
        writer: Writer,
        imageDef: Il2CppImageDefinition,
        customAttributeIndex: Int,
        token: Int,
        padding: String
    ) {
        if (binary.version < 21.0) return
        val attributeIndex = executor.getCustomAttributeIndex(imageDef, customAttributeIndex, token.toLong())
        if (attributeIndex < 0) return
        if (binary.version < 29.0) {
            val methodPointer = binary.customAttributeGenerators[attributeIndex]
            val range = metadata.attributeTypeRanges[attributeIndex]
            for (offset in 0 until range.count) {
                val typeIndex = metadata.attributeTypes[range.start + offset]
                writer.write(padding)
                writer.write("[")
                writer.write(executor.getTypeName(binary.types[typeIndex], false, false))
                writer.write("] // RVA: 0x")
                writer.write(hex(binary.rva(methodPointer)))
                writer.write(" Offset: 0x")
                writer.write(hex(binary.mapVaToOffset(methodPointer)))
                writer.write(" VA: 0x")
                writer.write(hex(methodPointer))
                writer.write("\n")
            }
            return
        }
        for (entry in executor.getCustomAttributeData(imageDef, customAttributeIndex, token.toLong())) {
            writer.write(padding)
            writer.write(entry.toString())
            writer.write("\n")
        }
    }

    private fun hex(value: Long): String = java.lang.Long.toHexString(value).uppercase()

    private fun hex(value: Int): String = Integer.toHexString(value).uppercase()

    private fun describe(error: Throwable): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { error.printStackTrace(it) }
        return buffer.toString()
    }

    private fun escape(source: String): String {
        val builder = StringBuilder(source.length)
        for (character in source) {
            when (character.code) {
                39 -> builder.append("\\'")
                34 -> builder.append("\\\"")
                92 -> builder.append("\\\\")
                0 -> builder.append("\\0")
                7 -> builder.append("\\a")
                8 -> builder.append("\\b")
                12 -> builder.append("\\f")
                10 -> builder.append("\\n")
                13 -> builder.append("\\r")
                9 -> builder.append("\\t")
                11 -> builder.append("\\v")
                133 -> builder.append("\\u0085")
                8232 -> builder.append("\\u2028")
                8233 -> builder.append("\\u2029")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
        const val SLOT_NONE = 0xFFFF
        const val METADATA_OFFSET_PREFIX = "/*Metadata offset 0x"
    }
}
