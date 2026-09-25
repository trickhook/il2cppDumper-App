package com.trickhook.il2cpp

import com.trickhook.il2cpp.metadata.Metadata
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataTest {

    @Test
    fun parsesVersion31() {
        println("version = ${metadata.version}")
        assertEquals(31.0, metadata.version, 0.0)
    }

    @Test
    fun parsesTypeDefinitions() {
        println("typeDefs = ${metadata.typeDefs.size}")
        assertEquals(43627, metadata.typeDefs.size)
    }

    @Test
    fun parsesImages() {
        val names = metadata.imageDefs.map { metadata.getString(it.nameIndex) }
        println("images = ${metadata.imageDefs.size}, first = ${names.first()}")
        assertEquals(56, metadata.imageDefs.size)
        assertEquals("Assembly-CSharp.dll", names.first())
    }

    @Test
    fun parsesStringLiterals() {
        println("stringLiterals = ${metadata.stringLiterals.size}")
        assertEquals(51009, metadata.stringLiterals.size)
        val sample = (0 until 8).map { metadata.getStringLiteral(it) }
        println("stringLiterals[0..7] = $sample")
        assertTrue(sample.any { it.isNotEmpty() })
    }

    @Test
    fun detectsNonStandardMethodLayout() {
        val layout = metadata.methodDefLayout
        println("layout = $layout")
        assertEquals(40, layout.stride)
        assertEquals(36, layout.expectedSize)
        assertEquals(0x18, layout.padOffset)
        assertEquals(4, layout.padSize)
        assertEquals(1.0, layout.confidence, 0.0)
        assertEquals(336329, layout.count)
        assertEquals(336329, metadata.methodDefs.size)
        assertTrue(!layout.isStandard)
    }

    @Test
    fun resolvesTypeAndItsMethods() {
        val type = metadata.typeDefs[4]
        val typeName = metadata.getString(type.nameIndex)
        println("typeDefs[4] = $typeName methodStart=${type.methodStart} methodCount=${type.methodCount}")
        assertEquals("UVmove", typeName)
        assertEquals(8, type.methodStart)
        assertEquals(3, type.methodCount)
        val methodNames = (8..10).map { metadata.getString(metadata.methodDefs[it].nameIndex) }
        println("methods 8..10 = $methodNames")
        assertEquals(listOf("Start", "Update", ".ctor"), methodNames)
        (8..10).forEach { assertEquals(4, metadata.methodDefs[it].declaringType) }
    }

    @Test
    fun parsesAssemblies() {
        val names = metadata.assemblyDefs.map { metadata.getString(it.aname.nameIndex) }
        println("assemblyDefs = ${metadata.assemblyDefs.size}, first = ${names.first()}, last = ${names.last()}")
        assertEquals(metadata.imageDefs.size, metadata.assemblyDefs.size)
        assertEquals(56, metadata.assemblyDefs.size)
        assertEquals("Assembly-CSharp", names.first())
        assertEquals("__Generated", names.last())
        metadata.assemblyDefs.forEachIndexed { index, assembly -> assertEquals(index, assembly.imageIndex) }
        val mscorlib = metadata.assemblyDefs[1]
        val token = mscorlib.aname.publicKeyToken.joinToString("") { "%02x".format(it) }
        println("mscorlib version = ${mscorlib.aname.major}.${mscorlib.aname.minor}, token = $token, hashAlg = ${mscorlib.aname.hashAlg}")
        assertEquals("mscorlib", metadata.getString(mscorlib.aname.nameIndex))
        assertEquals(4, mscorlib.aname.major)
        assertEquals(0x8004, mscorlib.aname.hashAlg)
        assertEquals("b77a5c561934e089", token)
        assertEquals(0x20000001, metadata.assemblyDefs[0].token)
    }

    @Test
    fun parsesTypeDefinitionEdges() {
        val first = metadata.typeDefs.first()
        val last = metadata.typeDefs.last()
        println("typeDefs[0] = ${metadata.getString(first.nameIndex)} token=${first.token.toString(16)}")
        println("typeDefs[last] = ${metadata.getString(last.namespaceIndex)}.${metadata.getString(last.nameIndex)}")
        assertEquals("<Module>", metadata.getString(first.nameIndex))
        assertEquals(-1, first.methodStart)
        assertEquals(0, first.methodCount)
        assertEquals(0x2000001, first.token)
        assertEquals("__Il2CppFullySharedGenericStructType", metadata.getString(last.nameIndex))
        assertEquals("Unity.IL2CPP.Metadata", metadata.getString(last.namespaceIndex))
        assertEquals(759733, last.vtableStart)
        assertEquals(4, last.vtableCount)
        assertTrue(last.isValueType)
        assertTrue(!last.isEnum)
        assertTrue(!first.isValueType)
        val uvmove = metadata.typeDefs[4]
        assertEquals(0x100001, uvmove.flags)
        assertEquals(7, uvmove.fieldCount)
        assertEquals(2, uvmove.fieldStart)
        assertEquals(44410, uvmove.parentIndex)
    }

    @Test
    fun parsesMethodDefinitionEdges() {
        val first = metadata.methodDefs.first()
        val last = metadata.methodDefs.last()
        println("methodDefs[0] = ${metadata.getString(first.nameIndex)} token=${first.token.toString(16)}")
        println("methodDefs[last] = ${metadata.getString(last.nameIndex)} declaringType=${last.declaringType}")
        assertEquals("get_info", metadata.getString(first.nameIndex))
        assertEquals(1, first.declaringType)
        assertEquals(0x6000001, first.token)
        assertEquals(0x8000000, first.returnParameterToken)
        assertEquals(0x886, first.flags)
        assertEquals(65535, first.slot)
        assertEquals("Finalize", metadata.getString(last.nameIndex))
        assertEquals(43624, last.declaringType)
        assertEquals(0x6000003, last.token)
        assertEquals(3, last.iflags)
        assertEquals(1, last.slot)
        metadata.methodDefs.forEach { assertEquals(0x06, it.token ushr 24) }
    }

    @Test
    fun parsesSecondaryTables() {
        assertEquals("info", metadata.getString(metadata.parameterDefs.first().nameIndex))
        assertEquals("saveMode", metadata.getString(metadata.parameterDefs.last().nameIndex))
        assertEquals(29799, metadata.parameterDefs.last().typeIndex)
        assertEquals("Normal", metadata.getString(metadata.fieldDefs[38].nameIndex))
        assertEquals(0x4000027, metadata.fieldDefs[38].token)
        assertEquals("info", metadata.getString(metadata.propertyDefs.first().nameIndex))
        assertEquals(-1, metadata.propertyDefs.first().set)
        assertEquals("onWeatherChanged", metadata.getString(metadata.eventDefs.first().nameIndex))
        assertEquals(6, metadata.eventDefs.first().add)
        assertEquals(listOf(1, 2, 0, 0), metadata.genericContainers.first().let {
            listOf(it.ownerIndex, it.typeArgc, it.isMethod, it.genericParameterStart)
        })
        assertEquals(listOf(336243, 1, 1, 2015), metadata.genericContainers.last().let {
            listOf(it.ownerIndex, it.typeArgc, it.isMethod, it.genericParameterStart)
        })
        assertEquals("<info>j__TPar", metadata.getString(metadata.genericParameters.first().nameIndex))
        assertEquals("T", metadata.getString(metadata.genericParameters.last().nameIndex))
        assertEquals(1693, metadata.genericParameters.last().ownerIndex)
        assertEquals(21940, metadata.fieldRefs.first().typeIndex)
        assertEquals(23363, metadata.fieldRefs.last().typeIndex)
        assertEquals(38730, metadata.interfaceIndices.first())
        assertEquals(11, metadata.nestedTypeIndices.first())
        assertEquals(46252, metadata.constraintIndices.first())
    }

    @Test
    fun decodesEncodedVtableIndices() {
        val first = metadata.vtableMethods.first()
        println("vtableMethods[0] = ${first.toUInt().toString(16)} kind=${metadata.encodedIndexType(first)}")
        assertEquals(0x60000007, first)
        assertEquals(3, metadata.encodedIndexType(first))
        val signed = metadata.vtableMethods[205]
        println("vtableMethods[205] = ${signed.toUInt().toString(16)} kind=${metadata.encodedIndexType(signed)}")
        assertTrue(signed < 0)
        assertEquals(6, metadata.encodedIndexType(signed))
        assertEquals(61146, metadata.decodeMethodIndex(signed))
        assertEquals(61146L, metadata.decodeMethodIndex(signed.toLong()))
        val negatives = metadata.vtableMethods.count { it < 0 }
        println("vtable entries above Int.MAX_VALUE = $negatives")
        assertEquals(5321, negatives)
    }

    @Test
    fun resolvesCustomAttributesByToken() {
        val image = metadata.imageDefs.first()
        println("image0 customAttributeStart=${image.customAttributeStart} count=${image.customAttributeCount}")
        assertEquals(0, image.customAttributeStart)
        assertEquals(73194, image.customAttributeCount)
        assertEquals(0x2000002, metadata.attributeDataRanges.first().token)
        assertEquals(0, metadata.getCustomAttributeIndex(image, -1, 0x2000002))
        assertEquals(-1, metadata.getCustomAttributeIndex(image, -1, 0x7FFFFFFF))
        assertEquals(84897, metadata.attributeDataRanges.size)
        assertEquals(1198162L, metadata.attributeDataRanges.last().startOffset)
    }

    @Test
    fun reportsSectionCounts() {
        println("methodDefs = ${metadata.methodDefs.size}")
        println("parameterDefs = ${metadata.parameterDefs.size}")
        println("fieldDefs = ${metadata.fieldDefs.size}")
        println("propertyDefs = ${metadata.propertyDefs.size}")
        println("eventDefs = ${metadata.eventDefs.size}")
        println("genericContainers = ${metadata.genericContainers.size}")
        println("genericParameters = ${metadata.genericParameters.size}")
        println("fieldRefs = ${metadata.fieldRefs.size}")
        println("interfaceIndices = ${metadata.interfaceIndices.size}")
        println("nestedTypeIndices = ${metadata.nestedTypeIndices.size}")
        println("constraintIndices = ${metadata.constraintIndices.size}")
        println("vtableMethods = ${metadata.vtableMethods.size}")
        println("attributeDataRanges = ${metadata.attributeDataRanges.size}")
        assertTrue(metadata.parameterDefs.isNotEmpty())
        assertTrue(metadata.fieldDefs.isNotEmpty())
        assertTrue(metadata.attributeDataRanges.isNotEmpty())
    }

    @Test
    fun resolvesDefaultValues() {
        val fieldIndex = metadata.fieldDefs.indices.firstOrNull { metadata.getFieldDefaultValue(it) != null }
        println("first field with a default value = $fieldIndex")
        assertNotNull(fieldIndex)
        val defaultValue = assertNotNull(metadata.getFieldDefaultValue(fieldIndex))
        val dataOffset = metadata.getDefaultValueData(defaultValue.dataIndex)
        println("default value data offset = $dataOffset")
        assertTrue(dataOffset > 0L)
    }

    private companion object {
        const val METADATA_PATH = "C:/Users/danie/Desktop/dump/arm64/global-metadata.dat"

        val metadata: Metadata by lazy {
            val file = File(METADATA_PATH)
            assertTrue(file.exists(), "test metadata missing at $METADATA_PATH")
            val started = System.currentTimeMillis()
            val parsed = Metadata(file.readBytes())
            println("parsed ${file.length()} bytes in ${System.currentTimeMillis() - started} ms")
            parsed
        }
    }
}
