package com.trickhook.il2cpp.il2cpp

import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition

data class CustomAttributeEntry(val typeName: String, val arguments: List<String>) {
    override fun toString(): String =
        if (arguments.isEmpty()) "[$typeName]" else "[$typeName(${arguments.joinToString(", ")})]"
}

class CustomAttributeDataReader(private val executor: Il2CppExecutor, buffer: ByteArray) {

    private val metadata = executor.metadata
    private val reader = BinaryReader(buffer)
    private var ctorBuffer: Long
    private var dataBuffer: Long

    val count: Int

    init {
        count = reader.readBlobCompressedUInt32().toInt()
        ctorBuffer = reader.position
        dataBuffer = reader.position + count * 4L
    }

    fun readEntry(): CustomAttributeEntry {
        reader.seek(ctorBuffer)
        val ctorIndex = reader.readInt32()
        ctorBuffer = reader.position
        val methodDef = metadata.methodDefs[ctorIndex]
        val typeDef = metadata.typeDefs[methodDef.declaringType]

        reader.seek(dataBuffer)
        val argumentCount = reader.readBlobCompressedUInt32().toInt()
        val fieldCount = reader.readBlobCompressedUInt32().toInt()
        val propertyCount = reader.readBlobCompressedUInt32().toInt()

        val arguments = ArrayList<String>(argumentCount + fieldCount + propertyCount)
        repeat(argumentCount) { arguments.add(attributeDataToString(readAttributeDataValue())) }
        repeat(fieldCount) {
            val rendered = attributeDataToString(readAttributeDataValue())
            val (declaring, fieldIndex) = readNamedArgumentClassAndIndex(typeDef)
            val fieldDef = metadata.fieldDefs[declaring.fieldStart + fieldIndex]
            arguments.add("${metadata.getString(fieldDef.nameIndex)} = $rendered")
        }
        repeat(propertyCount) {
            val rendered = attributeDataToString(readAttributeDataValue())
            val (declaring, propertyIndex) = readNamedArgumentClassAndIndex(typeDef)
            val propertyDef = metadata.propertyDefs[declaring.propertyStart + propertyIndex]
            arguments.add("${metadata.getString(propertyDef.nameIndex)} = $rendered")
        }
        dataBuffer = reader.position

        return CustomAttributeEntry(metadata.getString(typeDef.nameIndex).replace("Attribute", ""), arguments)
    }

    private fun readAttributeDataValue(): BlobValue {
        val encoded = executor.readEncodedTypeEnum(reader)
        val blob = executor.readConstantValueFromBlob(encoded.typeEnum, reader)
            ?: throw IllegalStateException("unsupported attribute blob element ${encoded.typeEnum}")
        return if (encoded.enumType != null) blob.copy(enumType = encoded.enumType) else blob
    }

    private fun readNamedArgumentClassAndIndex(typeDef: Il2CppTypeDefinition): Pair<Il2CppTypeDefinition, Int> {
        val memberIndex = reader.readBlobCompressedInt32()
        if (memberIndex >= 0) return typeDef to memberIndex
        val typeIndex = reader.readBlobCompressedUInt32().toInt()
        return metadata.typeDefs[typeIndex] to -(memberIndex + 1)
    }

    private fun attributeDataToString(blob: BlobValue): String {
        val value = blob.value ?: return "null"
        return when (blob.typeEnum) {
            Il2CppTypeEnum.IL2CPP_TYPE_STRING -> "\"$value\""
            Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY -> {
                @Suppress("UNCHECKED_CAST")
                val elements = value as Array<BlobValue>
                elements.joinToString(", ", "new[] { ", " }") { attributeDataToString(it) }
            }
            Il2CppTypeEnum.IL2CPP_TYPE_IL2CPP_TYPE_INDEX ->
                "typeof(${executor.getTypeName(value as Il2CppType, false, false)})"
            else -> when (value) {
                is Boolean -> if (value) "True" else "False"
                is Float -> ManagedNumber.format(value)
                is Double -> ManagedNumber.format(value)
                else -> value.toString()
            }
        }
    }
}

internal fun BinaryReader.readBlobCompressedUInt32(): Long {
    val first = readUByte()
    return when {
        first and 0x80 == 0 -> first.toLong()
        first and 0xC0 == 0x80 -> (((first and 0x7F).toLong() shl 8) or readUByte().toLong())
        first and 0xE0 == 0xC0 -> (((first and 0x3F).toLong() shl 24) or
            (readUByte().toLong() shl 16) or
            (readUByte().toLong() shl 8) or
            readUByte().toLong())
        first == 0xF0 -> readUInt32()
        first == 0xFE -> 0xFFFFFFFEL
        first == 0xFF -> 0xFFFFFFFFL
        else -> throw IllegalStateException("invalid compressed integer prefix 0x${first.toString(16)}")
    }
}

internal fun BinaryReader.readBlobCompressedInt32(): Int {
    val encoded = readBlobCompressedUInt32()
    if (encoded == 0xFFFFFFFFL) return Int.MIN_VALUE
    val magnitude = encoded ushr 1
    return if (encoded and 1L != 0L) -(magnitude + 1).toInt() else magnitude.toInt()
}
