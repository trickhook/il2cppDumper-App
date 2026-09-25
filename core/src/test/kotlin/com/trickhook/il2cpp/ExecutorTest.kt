package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.CustomAttributeEntry
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppConstants.FIELD_ATTRIBUTE_LITERAL
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.il2cpp.Il2CppTypeEnum
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.protector.FFProtector
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutorTest {

    @Test
    fun typeDefNamesMatchReferenceDump() {
        val sampled = (0 until metadata.typeDefs.size step SAMPLE_STRIDE).toList()
        val mismatches = ArrayList<String>()
        var compared = 0
        for (index in sampled) {
            val expected = expectedTypeDefName(index) ?: continue
            compared++
            val actual = executor.getTypeDefName(metadata.typeDefs[index], true, false)
            if (actual != expected) mismatches.add("[$index] expected=$expected actual=$actual")
        }
        println("compared $compared of ${sampled.size} sampled type definitions, ${mismatches.size} mismatched")
        mismatches.take(40).forEach { println("  MISMATCH $it") }
        assertTrue(compared >= 200, "needed at least 200 comparable samples, got $compared")
        assertEquals(0, mismatches.size, "type definition names diverged from the reference dump")
    }

    @Test
    fun rendersGenericAndNestedTypeNames() {
        val samples = referenceDump.keys.asSequence()
            .filter {
                val entry = referenceDump.getValue(it)
                entry.declaration.contains('<') || entry.simpleChain.contains('.')
            }
            .take(600)
            .toList()
        var compared = 0
        val mismatches = ArrayList<String>()
        for (index in samples) {
            val expected = expectedTypeDefName(index) ?: continue
            compared++
            val actual = executor.getTypeDefName(metadata.typeDefs[index], true, false)
            if (actual != expected) mismatches.add("[$index] expected=$expected actual=$actual")
        }
        println("generic-shaped samples compared = $compared, mismatched = ${mismatches.size}")
        mismatches.take(20).forEach { println("  MISMATCH $it") }
        assertTrue(compared >= 100, "expected at least 100 generic-shaped samples, got $compared")
        assertEquals(0, mismatches.size)
    }

    @Test
    fun rendersTypeReferenceNames() {
        val rendered = (0 until binary.types.size step 499)
            .map { executor.getTypeName(binary.types[it], true, false) }
        println("rendered ${rendered.size} type references, sample = ${rendered.take(12)}")
        println("arrays = ${rendered.filter { it.endsWith("[]") }.take(4)}")
        println("generic instances = ${rendered.filter { it.contains('<') }.take(4)}")
        assertTrue(rendered.size >= 200, "expected at least 200 type references, got ${rendered.size}")
        assertTrue(rendered.none { it.isEmpty() }, "every type reference must render to a name")
        assertTrue(rendered.any { it.endsWith("[]") }, "expected at least one array type")
        assertTrue(rendered.any { it.contains('<') }, "expected at least one generic instance")
        assertTrue(rendered.any { it.contains('.') }, "expected at least one namespaced name")
    }

    @Test
    fun declarationNamesMatchReferenceDump() {
        val mismatches = ArrayList<String>()
        var compared = 0
        for (index in 0 until metadata.typeDefs.size step SAMPLE_STRIDE) {
            val entry = referenceDump[index] ?: continue
            compared++
            val actual = executor.getTypeDefName(metadata.typeDefs[index], false, true)
            if (actual != entry.declaration) {
                mismatches.add("[$index] expected=${entry.declaration} actual=$actual")
            }
        }
        println("compared $compared declaration names, ${mismatches.size} mismatched")
        mismatches.take(40).forEach { println("  MISMATCH $it") }
        assertTrue(compared >= 200, "needed at least 200 comparable samples, got $compared")
        assertEquals(0, mismatches.size, "declaration names diverged from the reference dump")
    }

    @Test
    fun rendersMethodSpecNames() {
        val rendered = (0 until binary.methodSpecs.size step 733)
            .map { executor.getMethodSpecName(binary.methodSpecs[it], true) }
        println("rendered ${rendered.size} method spec names")
        println("sample = ${rendered.take(8).map { "${it.first}.${it.second}" }}")
        assertTrue(rendered.size >= 200, "expected at least 200 method specs, got ${rendered.size}")
        assertTrue(rendered.none { it.first.isEmpty() || it.second.isEmpty() })
        assertTrue(rendered.any { it.first.contains('<') }, "expected a generic declaring type")
        assertTrue(rendered.any { it.second.contains('<') }, "expected a generic method")
    }

    @Test
    fun rendersConstantFieldDefaults() {
        val callerLocale = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.Category.FORMAT, REFERENCE_DUMP_LOCALE)
        try {
            compareConstantFieldDefaults()
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, callerLocale)
        }
    }

    private fun compareConstantFieldDefaults() {
        var compared = 0
        var comparedReals = 0
        val mismatches = ArrayList<String>()
        for (typeIndex in constantLiterals.keys.sorted()) {
            val literals = constantLiterals.getValue(typeIndex)
            val typeDef = metadata.typeDefs[typeIndex]
            for (fieldIndex in typeDef.fieldStart until typeDef.fieldStart + typeDef.fieldCount) {
                val fieldDef = metadata.fieldDefs[fieldIndex]
                if (binary.types[fieldDef.typeIndex].attrs and FIELD_ATTRIBUTE_LITERAL == 0) continue
                val expected = literals[metadata.getString(fieldDef.nameIndex)] ?: continue
                val default = metadata.getFieldDefaultValue(fieldIndex) ?: continue
                if (default.dataIndex == -1) continue
                val blobType = binary.types[default.typeIndex]
                val actual = executor.getTypeDefaultValue(blobType, default.dataIndex)
                if (blobType.type == Il2CppTypeEnum.IL2CPP_TYPE_R4 || blobType.type == Il2CppTypeEnum.IL2CPP_TYPE_R8) {
                    comparedReals++
                }
                compared++
                if (actual != expected) {
                    mismatches.add("[$typeIndex] ${metadata.getString(fieldDef.nameIndex)} expected=$expected actual=$actual")
                }
            }
        }
        println("constant defaults compared = $compared, mismatched = ${mismatches.size}, reals compared = $comparedReals")
        mismatches.take(20).forEach { println("  MISMATCH $it") }
        assertTrue(compared >= 500, "expected at least 500 comparable constants, got $compared")
        assertTrue(comparedReals >= 1000, "expected at least 1000 float or double constants, got $comparedReals")
        assertEquals(0, mismatches.size, "constant default values diverged from the reference dump")
    }

    @Test
    fun readsCustomAttributeData() {
        val image = metadata.imageDefs.first { metadata.getString(it.nameIndex) == "Assembly-CSharp.dll" }
        val entries = ArrayList<CustomAttributeEntry>()
        var typesWithAttributes = 0
        for (typeIndex in image.typeStart until minOf(image.typeStart + image.typeCount, image.typeStart + 4000)) {
            val typeDef = metadata.typeDefs[typeIndex]
            val read = executor.getCustomAttributeData(image, typeDef.customAttributeIndex, typeDef.token.toLong())
            if (read.isEmpty()) continue
            typesWithAttributes++
            if (entries.size < 4000) entries.addAll(read)
        }
        println("types carrying attributes = $typesWithAttributes, attributes read = ${entries.size}")
        println("sample = ${entries.map { it.toString() }.distinct().take(16)}")
        assertTrue(typesWithAttributes > 0, "expected at least one type with custom attribute data")
        assertTrue(entries.none { it.typeName.isEmpty() }, "every attribute must resolve to a type name")
    }

    @Test
    fun rendersMethodModifiers() {
        val modifiers = metadata.methodDefs.asSequence()
            .take(4096)
            .map { executor.getModifiers(it) }
            .toSet()
        println("distinct modifier strings in the first 4096 methods = $modifiers")
        assertTrue(modifiers.any { it.startsWith("public ") })
        assertTrue(modifiers.any { it.contains("static") })
    }

    private fun expectedTypeDefName(index: Int): String? {
        val entry = referenceDump[index] ?: return null
        val root = rootTypeIndex(index) ?: return null
        val rootNamespace = referenceDump[root]?.namespace ?: return null
        return if (rootNamespace.isEmpty()) entry.simpleChain else "$rootNamespace.${entry.simpleChain}"
    }

    private fun rootTypeIndex(index: Int): Int? {
        var current = index
        repeat(64) {
            val declaringTypeIndex = metadata.typeDefs[current].declaringTypeIndex
            if (declaringTypeIndex < 0) return current
            val outer = binary.types[declaringTypeIndex].klassIndex
            if (outer !in metadata.typeDefs.indices) return null
            current = outer
        }
        return null
    }

    private data class DumpEntry(val namespace: String, val declaration: String, val simpleChain: String)

    private companion object {
        const val BINARY_PATH = "C:/Users/danie/Desktop/dump/arm64/libil2cpp.so"
        const val METADATA_PATH = "C:/Users/danie/Desktop/dump/arm64/global-metadata.dat"
        const val REFERENCE_DUMP_PATH = "C:/Users/danie/Desktop/dump/split/dump.cs"
        const val SAMPLE_STRIDE = 97

        val REFERENCE_DUMP_LOCALE: Locale = Locale.forLanguageTag("pt-BR")

        val metadata: Metadata by lazy {
            val file = File(METADATA_PATH)
            assertTrue(file.exists(), "test metadata missing at $METADATA_PATH")
            Metadata(file.readBytes())
        }

        val binary: Il2CppBinary by lazy {
            val file = File(BINARY_PATH)
            assertTrue(file.exists(), "test binary missing at $BINARY_PATH")
            val elf = ElfImage.parse(FFProtector.tryUnpack(file.readBytes()).data)
            elf.applyRelocations()
            Il2CppBinary(elf, metadata)
        }

        val executor: Il2CppExecutor by lazy { Il2CppExecutor(metadata, binary) }

        val parsedDump: Pair<Map<Int, DumpEntry>, Map<Int, Map<String, String>>> by lazy {
            parseReferenceDump(File(REFERENCE_DUMP_PATH))
        }

        val referenceDump: Map<Int, DumpEntry> get() = parsedDump.first

        val constantLiterals: Map<Int, Map<String, String>> get() = parsedDump.second

        val typeDefIndexMarker = Regex("// TypeDefIndex: (\\d+)$")

        val declarationKeyword = Regex("(?:^|\\s)(?:class|struct|enum|interface)\\s+(.+)$")

        val constantField = Regex("\\bconst\\s+\\S+\\s+(\\S+) = (.*);$")

        fun parseReferenceDump(file: File): Pair<Map<Int, DumpEntry>, Map<Int, Map<String, String>>> {
            assertTrue(file.exists(), "reference dump missing at ${file.path}")
            val entries = HashMap<Int, DumpEntry>(48000)
            val constants = HashMap<Int, MutableMap<String, String>>()
            var namespace = ""
            var currentType = -1
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("// Namespace: ")) {
                        namespace = line.removePrefix("// Namespace: ")
                        continue
                    }
                    val marker = typeDefIndexMarker.find(line)
                    if (marker == null) {
                        if (currentType < 0 || !line.startsWith("\t")) continue
                        val constant = constantField.find(line) ?: continue
                        constants.getOrPut(currentType) { HashMap() }[constant.groupValues[1]] =
                            constant.groupValues[2]
                        continue
                    }
                    val declaration = line.substring(0, marker.range.first).trim()
                    val name = declarationKeyword.find(declaration.substringBefore(" : "))
                        ?.groupValues?.get(1)?.trim() ?: continue
                    currentType = marker.groupValues[1].toInt()
                    entries[currentType] = DumpEntry(namespace, name, stripGenericSuffix(name))
                }
            }
            return entries to constants
        }

        fun stripGenericSuffix(name: String): String {
            if (!name.endsWith(">")) return name
            var depth = 0
            for (i in name.indices.reversed()) {
                when (name[i]) {
                    '>' -> depth++
                    '<' -> {
                        depth--
                        if (depth == 0) {
                            val head = name.substring(0, i)
                            return if (head.isEmpty() || head.endsWith('.')) name else head
                        }
                    }
                }
            }
            return name
        }
    }
}
