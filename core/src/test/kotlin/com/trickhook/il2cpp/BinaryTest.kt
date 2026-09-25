package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.protector.FFProtector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ARM32_SO = "C:/Users/danie/Desktop/dump/libil2cpp.so"
private const val ARM64_SO = "C:/Users/danie/Desktop/dump/arm64/libil2cpp.so"
private const val METADATA = "C:/Users/danie/Desktop/dump/arm64/global-metadata.dat"

private const val ARM32_CODE_REGISTRATION = 0xa1b9ff4L
private const val ARM32_METADATA_REGISTRATION = 0xa28fc80L
private const val ARM64_CODE_REGISTRATION = 0xa41c8b8L
private const val ARM64_METADATA_REGISTRATION = 0xa5c7f60L

private const val TYPE_COUNT = 43627
private const val METHOD_COUNT = 336329
private const val IMAGE_COUNT = 56
private const val STRING_LITERAL_COUNT = 51009

private const val TYPES_SIZE = 103553
private const val METHOD_SPECS_SIZE = 150683

private object Fixtures {

    val metadata: Metadata by lazy { Metadata(File(METADATA).readBytes()) }
    val arm32: Il2CppBinary by lazy { load(ARM32_SO) }
    val arm64: Il2CppBinary by lazy { load(ARM64_SO) }

    fun present(vararg paths: String): Boolean = paths.all { File(it).isFile }

    private fun load(path: String): Il2CppBinary {
        val unpacked = FFProtector.tryUnpack(File(path).readBytes()).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()
        return Il2CppBinary.load(elf, metadata)
    }
}

class BinaryTest {

    private fun skipUnlessPresent(vararg paths: String): Boolean {
        if (Fixtures.present(*paths)) return false
        println("SKIPPED, test data absent: ${paths.joinToString()}")
        return true
    }

    @Test
    fun `metadata fixture matches the known free fire build`() {
        if (skipUnlessPresent(METADATA)) return
        val metadata = Fixtures.metadata
        assertEquals(31.0, metadata.version, 0.0)
        assertEquals(TYPE_COUNT, metadata.typeDefs.size)
        assertEquals(METHOD_COUNT, metadata.methodDefs.size)
        assertEquals(IMAGE_COUNT, metadata.imageDefs.size)
        assertEquals(STRING_LITERAL_COUNT, metadata.stringLiterals.size)
    }

    @Test
    fun `arm32 registrations are found at the known addresses`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val binary = Fixtures.arm32

        println("arm32 version ${binary.version}")
        println("arm32 CodeRegistration 0x${binary.codeRegistrationAddress.toString(16)}")
        println("arm32 MetadataRegistration 0x${binary.metadataRegistrationAddress.toString(16)}")

        assertEquals(31.0, binary.version, 0.0)
        assertEquals(ARM32_CODE_REGISTRATION, binary.codeRegistrationAddress)
        assertEquals(ARM32_METADATA_REGISTRATION, binary.metadataRegistrationAddress)
        assertTrue(binary.elf.is32Bit)
    }

    @Test
    fun `arm32 code registration fields match the c sharp fork`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val code = Fixtures.arm32.codeRegistration

        println("arm32 codeRegistration $code")

        assertEquals(0L, code.methodPointersCount)
        assertEquals(0L, code.methodPointers)
        assertEquals(0L, code.delegateWrappersFromNativeToManagedCount)
        assertEquals(0L, code.delegateWrappersFromManagedToNativeCount)
        assertEquals(0L, code.marshalingFunctionsCount)
        assertEquals(0L, code.ccwMarshalingFunctionsCount)
        assertEquals(0L, code.customAttributeCount)
        assertEquals(0L, code.customAttributeGenerators)
        assertEquals(0L, code.guidCount)
        assertEquals(0L, code.guids)

        assertEquals(0x52L, code.reversePInvokeWrapperCount)
        assertEquals(0xa1cee44L, code.reversePInvokeWrappers)
        assertEquals(0x12d0dL, code.genericMethodPointersCount)
        assertEquals(0xa24484cL, code.genericMethodPointers)
        assertEquals(0xa1de070L, code.genericAdjustorThunks)
        assertEquals(0x5383L, code.invokerPointersCount)
        assertEquals(0xa1ba038L, code.invokerPointers)
        assertEquals(0xaf4L, code.unresolvedVirtualCallCount)
        assertEquals(0xa1cef8cL, code.unresolvedVirtualCallPointers)
        assertEquals(0xa1d1b5cL, code.unresolvedInstanceCallPointers)
        assertEquals(0xa1d472cL, code.unresolvedStaticCallPointers)
        assertEquals(0x2e1L, code.interopDataCount)
        assertEquals(0xa461da4L, code.interopData)
        assertEquals(0L, code.windowsRuntimeFactoryCount)
        assertEquals(0L, code.windowsRuntimeFactoryTable)
        assertEquals(0x38L, code.codeGenModulesCount)
        assertEquals(0xa461cc0L, code.codeGenModules)
    }

    @Test
    fun `arm32 metadata registration fields match the c sharp fork`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val meta = Fixtures.arm32.metadataRegistration

        println("arm32 metadataRegistration $meta")

        assertEquals(27718L, meta.genericClassesCount)
        assertEquals(0xa1eb16cL, meta.genericClasses)
        assertEquals(21286L, meta.genericInstsCount)
        assertEquals(0xa22fbb4L, meta.genericInsts)
        assertEquals(77228L, meta.genericMethodTableCount)
        assertEquals(0x11bf244L, meta.genericMethodTable)
        assertEquals(103553L, meta.typesCount)
        assertEquals(0xa2ca270L, meta.types)
        assertEquals(150683L, meta.methodSpecsCount)
        assertEquals(0x1005b00L, meta.methodSpecs)
        assertEquals(0L, meta.methodReferencesCount)
        assertEquals(0L, meta.methodReferences)
        assertEquals(43627L, meta.fieldOffsetsCount)
        assertEquals(0xa40c968L, meta.fieldOffsets)
        assertEquals(43627L, meta.typeDefinitionsSizesCount)
        assertEquals(0xa437314L, meta.typeDefinitionsSizes)
    }

    @Test
    fun `arm64 registrations are found at the known addresses`() {
        if (skipUnlessPresent(ARM64_SO, METADATA)) return
        val binary = Fixtures.arm64

        println("arm64 version ${binary.version}")
        println("arm64 CodeRegistration 0x${binary.codeRegistrationAddress.toString(16)}")
        println("arm64 MetadataRegistration 0x${binary.metadataRegistrationAddress.toString(16)}")
        println("arm64 codeRegistration ${binary.codeRegistration}")
        println("arm64 metadataRegistration ${binary.metadataRegistration}")

        assertEquals(31.0, binary.version, 0.0)
        assertEquals(ARM64_CODE_REGISTRATION, binary.codeRegistrationAddress)
        assertEquals(ARM64_METADATA_REGISTRATION, binary.metadataRegistrationAddress)
        assertTrue(binary.elf.is64)
    }

    @Test
    fun `arm32 tables have the expected shape`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        verifyTables(Fixtures.arm32, "arm32")
    }

    @Test
    fun `arm64 tables have the expected shape`() {
        if (skipUnlessPresent(ARM64_SO, METADATA)) return
        verifyTables(Fixtures.arm64, "arm64")
    }

    @Test
    fun `arm32 rgctx ranges are read without corruption`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val binary = Fixtures.arm32

        var declaredRanges = 0L
        var keptRanges = 0
        var entries = 0
        for ((name, module) in binary.codeGenModules) {
            declaredRanges += module.rgctxRangesCount
            val byToken = assertNotNull(binary.rgctxs[name], "missing rgctxs for $name")
            keptRanges += byToken.size
            entries += byToken.values.sumOf { it.size }
        }
        println("arm32 rgctx ranges declared $declaredRanges kept $keptRanges entries $entries")
        assertEquals(declaredRanges, keptRanges.toLong(), "every rgctx range survives on an unpacked image")
        assertTrue(entries > 0, "rgctx entries")
    }

    @Test
    fun `a still packed image loads without throwing`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val elf = ElfImage.parse(File(ARM32_SO).readBytes())
        elf.applyRelocations()
        val binary = Il2CppBinary.load(
            elf,
            Fixtures.metadata,
            ARM32_CODE_REGISTRATION,
            ARM32_METADATA_REGISTRATION
        )

        println(
            "packed arm32 codeGenModules ${binary.codeGenModules.size} " +
                "types ${binary.types.size} methodSpecs ${binary.methodSpecs.size} " +
                "methodSpecGenericMethodPointers ${binary.methodSpecGenericMethodPointers.size} " +
                "rgctx tokens ${binary.rgctxs.values.sumOf { it.size }}"
        )
        assertEquals(ARM32_CODE_REGISTRATION, binary.codeRegistrationAddress)
        assertEquals(Fixtures.arm32.codeRegistration, binary.codeRegistration)
        assertEquals(TYPES_SIZE, binary.types.size)
    }

    @Test
    fun `registrations at the very end of the image do not throw`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val unpacked = FFProtector.tryUnpack(File(ARM32_SO).readBytes()).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()

        val lastByte = unpacked.size.toLong() - 1
        val segment = elf.segments.first {
            it.type == 1 && lastByte >= it.offset && lastByte < it.offset + it.memSize
        }
        val address = lastByte - segment.offset + segment.vaddr
        assertEquals(lastByte, elf.mapVaToOffset(address), "address maps to the last byte")

        val binary = Il2CppBinary.load(elf, Fixtures.metadata, address, address)
        println("edge address 0x${address.toString(16)} types ${binary.types.size} modules ${binary.codeGenModules.size}")
        assertEquals(0, binary.types.size)
        assertEquals(0, binary.codeGenModules.size)
        assertEquals(0L, binary.codeRegistration.codeGenModulesCount)
        assertEquals(0L, binary.metadataRegistration.typesCount)
    }

    @Test
    fun `a count that cannot fit the image allocates nothing`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val unpacked = FFProtector.tryUnpack(File(ARM32_SO).readBytes()).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()
        val binary = Il2CppBinary.load(
            elf,
            Fixtures.metadata,
            ARM32_CODE_REGISTRATION,
            ARM32_CODE_REGISTRATION
        )

        val bogus = binary.metadataRegistration
        println(
            "bogus metadataRegistration fieldOffsetsCount ${bogus.fieldOffsetsCount} " +
                "methodSpecsCount ${bogus.methodSpecsCount} image ${unpacked.size}"
        )
        assertTrue(bogus.fieldOffsetsCount * 4L > unpacked.size, "count cannot fit the image")
        assertEquals(0, binary.fieldOffsets.size, "oversized field offset table is refused")
        assertEquals(0, binary.methodSpecs.size, "oversized method spec table is refused")
        assertEquals(Fixtures.arm32.codeRegistration, binary.codeRegistration)
    }

    @Test
    fun `field offsets and type reads resolve`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val binary = Fixtures.arm32
        val metadata = Fixtures.metadata

        val address = binary.typeByAddress.keys.first()
        assertEquals(binary.typeByAddress[address], binary.readType(address), "readType round trip")

        var sampled = 0
        var sane = 0
        for (typeIndex in metadata.typeDefs.indices) {
            val typeDef = metadata.typeDefs[typeIndex]
            if (typeDef.fieldCount <= 0 || typeDef.fieldStart < 0) continue
            val offset = binary.getFieldOffsetFromIndex(typeIndex, 0, typeDef.fieldStart, typeDef.isValueType, false)
            sampled++
            if (offset >= -1 && offset < 0x100000) sane++
            if (sampled >= 20000) break
        }
        println("arm32 field offsets sampled $sampled sane $sane")
        assertEquals(sampled, sane, "field offsets stay in range")
        assertTrue(sampled > 1000, "enough field offsets sampled")
    }

    @Test
    fun `explicit registration addresses skip the search`() {
        if (skipUnlessPresent(ARM32_SO, METADATA)) return
        val unpacked = FFProtector.tryUnpack(File(ARM32_SO).readBytes()).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()
        val binary = Il2CppBinary.load(
            elf,
            Fixtures.metadata,
            ARM32_CODE_REGISTRATION,
            ARM32_METADATA_REGISTRATION
        )

        assertEquals(ARM32_CODE_REGISTRATION, binary.codeRegistrationAddress)
        assertEquals(ARM32_METADATA_REGISTRATION, binary.metadataRegistrationAddress)
        assertEquals(Fixtures.arm32.codeRegistration, binary.codeRegistration)
        assertEquals(Fixtures.arm32.metadataRegistration, binary.metadataRegistration)
        assertEquals(TYPES_SIZE, binary.types.size)
    }

    private fun verifyTables(binary: Il2CppBinary, label: String) {
        println(
            "$label types ${binary.types.size} methodSpecs ${binary.methodSpecs.size} " +
                "genericInsts ${binary.genericInsts.size} fieldOffsets ${binary.fieldOffsets.size} " +
                "typeDefinitionsSizes ${binary.typeDefinitionsSizes.size} " +
                "genericMethodPointers ${binary.genericMethodPointers.size} " +
                "invokerPointers ${binary.invokerPointers.size} " +
                "reversePInvokeWrappers ${binary.reversePInvokeWrappers.size} " +
                "unresolvedVirtualCallPointers ${binary.unresolvedVirtualCallPointers.size} " +
                "codeGenModules ${binary.codeGenModules.size} " +
                "methodDefinitionMethodSpecs ${binary.methodDefinitionMethodSpecs.size} " +
                "methodSpecGenericMethodPointers ${binary.methodSpecGenericMethodPointers.size} " +
                "typeByAddress ${binary.typeByAddress.size} " +
                "fieldOffsetsArePointers ${binary.fieldOffsetsArePointers}"
        )

        assertEquals(TYPES_SIZE, binary.types.size, "$label types")
        assertEquals(METHOD_SPECS_SIZE, binary.methodSpecs.size, "$label methodSpecs")
        assertEquals(TYPE_COUNT, binary.fieldOffsets.size, "$label fieldOffsets")
        assertEquals(TYPE_COUNT, binary.typeDefinitionsSizes.size, "$label typeDefinitionsSizes")
        assertEquals(21286, binary.genericInsts.size, "$label genericInsts")
        assertTrue(binary.fieldOffsetsArePointers, "$label fieldOffsetsArePointers")
        assertEquals(IMAGE_COUNT, binary.codeGenModules.size, "$label codeGenModules")
        assertEquals(IMAGE_COUNT, binary.codeGenModuleMethodPointers.size, "$label module pointers")
        assertEquals(IMAGE_COUNT, binary.rgctxs.size, "$label rgctxs")
        assertTrue(binary.methodDefinitionMethodSpecs.isNotEmpty(), "$label methodDefinitionMethodSpecs")
        assertTrue(binary.methodSpecGenericMethodPointers.isNotEmpty(), "$label methodSpecGenericMethodPointers")
        assertEquals(binary.types.size, binary.typeByAddress.size, "$label typeByAddress")

        val expectedModuleSizes = mapOf(
            "Assembly-CSharp.dll" to 289423,
            "mscorlib.dll" to 12570,
            "UnityEngine.CoreModule.dll" to 4752
        )
        for ((name, count) in expectedModuleSizes) {
            val module = assertNotNull(binary.codeGenModules[name], "$label missing $name")
            val pointers = assertNotNull(binary.codeGenModuleMethodPointers[name], "$label missing $name pointers")
            println("$label $name methodPointerCount ${module.methodPointerCount} pointers ${pointers.size}")
            assertEquals(count.toLong(), module.methodPointerCount, "$label $name methodPointerCount")
            assertEquals(count, pointers.size, "$label $name method pointers")
        }

        for ((name, module) in binary.codeGenModules) {
            val pointers = assertNotNull(binary.codeGenModuleMethodPointers[name], "$label missing $name pointers")
            assertEquals(module.methodPointerCount, pointers.size.toLong(), "$label $name pointer count")
        }
        val totalMethodPointers = binary.codeGenModules.values.sumOf { it.methodPointerCount }
        println("$label total codeGenModule method pointers $totalMethodPointers")
        assertEquals(METHOD_COUNT.toLong(), totalMethodPointers, "$label total method pointers")

        val metadata = Fixtures.metadata
        val image = metadata.imageDefs.first { metadata.getString(it.nameIndex) == "Assembly-CSharp.dll" }
        val methodRange = (image.typeStart until image.typeStart + image.typeCount)
            .map { metadata.typeDefs[it] }
            .filter { it.methodCount > 0 && it.methodStart >= 0 }
            .flatMap { type -> (type.methodStart until type.methodStart + type.methodCount).map { type to it } }
        val resolved = methodRange.map { (type, index) ->
            Triple(type, index, binary.getMethodPointer("Assembly-CSharp.dll", metadata.methodDefs[index], index))
        }
        val (firstType, firstIndex, firstPointer) = resolved.first { it.third != 0L }
        println(
            "$label ${metadata.getString(firstType.nameIndex)}." +
                "${metadata.getString(metadata.methodDefs[firstIndex].nameIndex)} " +
                "pointer 0x${firstPointer.toString(16)} rva 0x${binary.rva(firstPointer).toString(16)}"
        )
        assertTrue(binary.mapVaToOffset(firstPointer) >= 0L, "$label method pointer maps")

        val mapped = resolved.count { it.third != 0L && binary.mapVaToOffset(it.third) >= 0L }
        val nonZero = resolved.count { it.third != 0L }
        println("$label Assembly-CSharp methods ${resolved.size} non null $nonZero mapped $mapped")
        assertEquals(nonZero, mapped, "$label every non null method pointer maps")
        assertTrue(nonZero > resolved.size / 2, "$label most methods have a pointer")

        val decoded = binary.types.count { it.type != null }
        println("$label decoded type codes $decoded of ${binary.types.size}")
        assertEquals(binary.types.size, decoded, "$label decoded types")
    }
}
