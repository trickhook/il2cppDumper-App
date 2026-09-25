package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.output.ScriptWriter
import com.trickhook.il2cpp.protector.FFProtector
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptWriterTest {

    @Test
    fun arrayLengthsMatchReference() {
        println("counts mine      = ${generatedScan.counts}")
        println("counts reference = ${referenceScan.counts}")
        assertEquals(403993, referenceScan.counts["ScriptMethod"])
        assertEquals(50985, referenceScan.counts["ScriptString"])
        assertEquals(9167, referenceScan.counts["ScriptMetadata"])
        assertEquals(38260, referenceScan.counts["ScriptTypeInfo"])
        assertEquals(105725, referenceScan.counts["ScriptMetadataMethod"])
        assertEquals(427690, referenceScan.counts["Addresses"])
        assertEquals(referenceScan.counts, generatedScan.counts)
    }

    @Test
    fun typeInfoPairsMatchReference() {
        println("ScriptTypeInfo pairs mine = ${generatedScan.typeInfo.size}, reference = ${referenceScan.typeInfo.size}")
        reportDifference("ScriptTypeInfo", generatedScan.typeInfo, referenceScan.typeInfo)
        assertEquals(referenceScan.typeInfo, generatedScan.typeInfo)
    }

    @Test
    fun stringPairsMatchReference() {
        println("ScriptString pairs mine = ${generatedScan.strings.size}, reference = ${referenceScan.strings.size}")
        reportDifference("ScriptString", generatedScan.strings, referenceScan.strings)
        assertEquals(referenceScan.strings, generatedScan.strings)
    }

    @Test
    fun stringLiteralsMatchReference() {
        println("stringliteral entries mine = ${generatedLiteralScan.count}, reference = ${referenceLiteralScan.count}")
        assertEquals(50985, referenceLiteralScan.count)
        assertEquals(referenceLiteralScan.count, generatedLiteralScan.count)
        reportDifference("stringliteral", generatedLiteralScan.pairs, referenceLiteralScan.pairs)
        assertEquals(referenceLiteralScan.pairs, generatedLiteralScan.pairs)
    }

    @Test
    fun scriptBytesMatchReference() {
        val length = generatedScript.length()
        println("script.json generated = $length bytes, reference raw = ${File(REFERENCE_SCRIPT).length()} bytes")
        assertTrue(length > 100_000_000L, "generated script.json looks truncated at $length bytes")
        val difference = firstDifference(generatedScript, File(REFERENCE_SCRIPT))
        println("script.json first difference at ${difference.position}")
        assertEquals(
            -1L,
            difference.position,
            "byte ${difference.position}: generated ${difference.actual}, reference ${difference.expected}"
        )
    }

    @Test
    fun stringLiteralBytesMatchReference() {
        val length = generatedStringLiterals.length()
        println(
            "stringliteral.json generated = $length bytes, " +
                "reference raw = ${File(REFERENCE_STRING_LITERALS).length()} bytes"
        )
        assertTrue(length > 4_000_000L, "generated stringliteral.json looks truncated at $length bytes")
        val difference = firstDifference(generatedStringLiterals, File(REFERENCE_STRING_LITERALS))
        println("stringliteral.json first difference at ${difference.position}")
        assertEquals(
            -1L,
            difference.position,
            "byte ${difference.position}: generated ${difference.actual}, reference ${difference.expected}"
        )
    }

    private class Difference(val position: Long, val actual: Int, val expected: Int)

    private class ScriptScan(
        val counts: Map<String, Int>,
        val typeInfo: Set<Pair<String, String>>,
        val strings: Set<Pair<String, String>>
    )

    private class LiteralScan(val count: Int, val pairs: Set<Pair<String, String>>)

    private companion object {

        const val LIBRARY_PATH = "C:/Users/danie/Desktop/dump/arm64/libil2cpp.so"
        const val METADATA_PATH = "C:/Users/danie/Desktop/dump/arm64/global-metadata.dat"
        const val REFERENCE_SCRIPT = "C:/Users/danie/Desktop/dump/split/script.json"
        const val REFERENCE_STRING_LITERALS = "C:/Users/danie/Desktop/dump/split/stringliteral.json"

        val writer: ScriptWriter by lazy {
            val library = File(LIBRARY_PATH)
            val metadataFile = File(METADATA_PATH)
            assertTrue(library.exists(), "test library missing at $LIBRARY_PATH")
            assertTrue(metadataFile.exists(), "test metadata missing at $METADATA_PATH")
            val started = System.currentTimeMillis()
            val unpacked = FFProtector.tryUnpack(library.readBytes()).data
            val elf = ElfImage.parse(unpacked)
            elf.applyRelocations()
            val metadata = Metadata(metadataFile.readBytes())
            val binary = Il2CppBinary.load(elf, metadata)
            println("loaded binary version ${binary.version} in ${System.currentTimeMillis() - started} ms")
            ScriptWriter(Il2CppExecutor(metadata, binary))
        }

        val outputDirectory: File by lazy {
            val name = "trickhook-scriptwriter-" + ProcessHandle.current().pid()
            File(System.getProperty("java.io.tmpdir"), name).apply { mkdirs() }
        }

        val generatedScript: File by lazy {
            val target = File(outputDirectory, "script.json")
            val started = System.currentTimeMillis()
            target.outputStream().use { stream ->
                val sink = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20)
                writer.writeScript(sink)
                sink.flush()
            }
            println("wrote ${target.length()} bytes of script.json in ${System.currentTimeMillis() - started} ms")
            target
        }

        val generatedStringLiterals: File by lazy {
            val target = File(outputDirectory, "stringliteral.json")
            val started = System.currentTimeMillis()
            target.outputStream().use { stream ->
                val sink = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20)
                writer.writeStringLiterals(sink)
                sink.flush()
            }
            println("wrote ${target.length()} bytes of stringliteral.json in ${System.currentTimeMillis() - started} ms")
            target
        }

        val generatedScan: ScriptScan by lazy { scanScript(generatedScript) }
        val referenceScan: ScriptScan by lazy { scanScript(File(REFERENCE_SCRIPT)) }
        val generatedLiteralScan: LiteralScan by lazy { scanStringLiterals(generatedStringLiterals) }
        val referenceLiteralScan: LiteralScan by lazy { scanStringLiterals(File(REFERENCE_STRING_LITERALS)) }

        const val CARRIAGE_RETURN = 13

        fun firstDifference(generated: File, reference: File): Difference {
            generated.inputStream().buffered(1 shl 20).use { mine ->
                reference.inputStream().buffered(1 shl 20).use { theirs ->
                    var position = 0L
                    while (true) {
                        val actual = mine.read()
                        var expected = theirs.read()
                        while (expected == CARRIAGE_RETURN) expected = theirs.read()
                        if (actual != expected) return Difference(position, actual, expected)
                        if (actual == -1) return Difference(-1L, -1, -1)
                        position++
                    }
                }
            }
        }

        fun reportDifference(
            label: String,
            mine: Set<Pair<String, String>>,
            reference: Set<Pair<String, String>>
        ) {
            val missing = reference - mine
            val extra = mine - reference
            println("$label missing = ${missing.size}, extra = ${extra.size}")
            if (missing.isNotEmpty()) println("$label first missing = ${missing.take(5)}")
            if (extra.isNotEmpty()) println("$label first extra = ${extra.take(5)}")
        }

        fun scanScript(file: File): ScriptScan {
            val counts = LinkedHashMap<String, Int>()
            val typeInfo = HashSet<Pair<String, String>>()
            val strings = HashSet<Pair<String, String>>()
            var section = ""
            var address = ""
            var name: String? = null
            var value: String? = null
            val started = System.currentTimeMillis()
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.length > 4 && line.startsWith("\"") && line.endsWith("\": [")) {
                        section = line.substring(1, line.length - 4)
                        counts[section] = 0
                    } else if (line == "]" || line == "],") {
                        section = ""
                    } else if (section.isEmpty()) {
                        continue
                    } else if (line == "{") {
                        address = ""
                        name = null
                        value = null
                    } else if (line == "}" || line == "},") {
                        counts[section] = counts.getValue(section) + 1
                        val currentName = name
                        val currentValue = value
                        if (section == "ScriptTypeInfo" && currentName != null) {
                            typeInfo += address to currentName
                        }
                        if (section == "ScriptString" && currentValue != null) {
                            strings += address to currentValue
                        }
                    } else if (section == "Addresses") {
                        counts[section] = counts.getValue(section) + 1
                    } else {
                        val stop = line.indexOf("\": ")
                        if (stop > 0) {
                            val key = line.substring(1, stop)
                            val body = line.substring(stop + 3).removeSuffix(",")
                            if (key == "Address") address = body
                            if (key == "Name") name = if (body == "null") null else unescape(body)
                            if (key == "Value") value = if (body == "null") null else unescape(body)
                        }
                    }
                }
            }
            println("scanned ${file.name} (${file.length()} bytes) in ${System.currentTimeMillis() - started} ms")
            return ScriptScan(counts, typeInfo, strings)
        }

        fun scanStringLiterals(file: File): LiteralScan {
            val pairs = HashSet<Pair<String, String>>()
            var count = 0
            var address = ""
            var value: String? = null
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line == "{") {
                        address = ""
                        value = null
                    } else if (line == "}" || line == "},") {
                        count++
                        val currentValue = value
                        if (currentValue != null) pairs += address to currentValue
                    } else if (line.startsWith("\"")) {
                        val stop = line.indexOf("\": ")
                        if (stop > 0) {
                            val key = line.substring(1, stop)
                            val body = line.substring(stop + 3).removeSuffix(",")
                            if (key == "address") address = unescape(body)
                            if (key == "value") value = if (body == "null") null else unescape(body)
                        }
                    }
                }
            }
            return LiteralScan(count, pairs)
        }

        fun unescape(literal: String): String {
            val body = literal.substring(1, literal.length - 1)
            if (!body.contains('\\')) return body
            val builder = StringBuilder(body.length)
            var at = 0
            while (at < body.length) {
                val character = body[at]
                if (character != '\\') {
                    builder.append(character)
                    at++
                } else {
                    val escape = body[at + 1]
                    when (escape) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'u' -> builder.append(body.substring(at + 2, at + 6).toInt(16).toChar())
                        else -> builder.append(escape)
                    }
                    at += if (escape == 'u') 6 else 2
                }
            }
            return builder.toString()
        }
    }
}
