package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.output.HeaderWriter
import com.trickhook.il2cpp.protector.FFProtector
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HeaderWriterTest {

    private val libraryFile = File("C:/Users/danie/Desktop/dump/arm64/libil2cpp.so")
    private val metadataFile = File("C:/Users/danie/Desktop/dump/arm64/global-metadata.dat")
    private val referenceFile = File("C:/Users/danie/Desktop/dump/split/il2cpp.h")

    @Test
    fun matchesReferenceHeader() {
        if (!libraryFile.exists() || !metadataFile.exists() || !referenceFile.exists()) return

        val generated = File(System.getProperty("java.io.tmpdir"), "trickhook-il2cpp.h")
        generated.delete()
        val started = System.currentTimeMillis()
        generate(generated)
        val elapsed = System.currentTimeMillis() - started
        println("generated ${generated.length()} bytes in ${elapsed}ms, reference ${referenceFile.length()} bytes")

        assertNotEquals(
            referenceFile.canonicalPath,
            generated.canonicalPath,
            "the generated file and the reference are the same path"
        )
        assertTrue(generated.length() > 0L, "the generator produced an empty file")

        val difference = firstDifference(generated, referenceFile)
        if (difference >= 0L || generated.length() != referenceFile.length()) {
            report(generated)
        }
        assertEquals(referenceFile.length(), generated.length(), "byte length mismatch")
        assertEquals(-1L, difference, "first differing byte offset")
    }

    private fun generate(target: File) {
        val unpacked = FFProtector.tryUnpack(libraryFile.readBytes()).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()
        val metadata = Metadata(metadataFile.readBytes())
        val binary = Il2CppBinary.load(elf, metadata)
        val executor = Il2CppExecutor(metadata, binary)
        BufferedWriter(OutputStreamWriter(FileOutputStream(target), Charsets.UTF_8), 1 shl 20).use {
            HeaderWriter(executor).write(it)
        }
    }

    private fun firstDifference(left: File, right: File): Long {
        val leftBuffer = ByteArray(1 shl 20)
        val rightBuffer = ByteArray(1 shl 20)
        var position = 0L
        left.inputStream().use { leftStream ->
            right.inputStream().use { rightStream ->
                while (true) {
                    val leftCount = leftStream.readNBytes(leftBuffer, 0, leftBuffer.size)
                    val rightCount = rightStream.readNBytes(rightBuffer, 0, rightBuffer.size)
                    val shared = minOf(leftCount, rightCount)
                    for (index in 0 until shared) {
                        if (leftBuffer[index] != rightBuffer[index]) return position + index
                    }
                    if (leftCount != rightCount) return position + shared
                    if (leftCount == 0) break
                    position += leftCount
                }
            }
        }
        return -1L
    }

    private fun report(generated: File) {
        var generatedLines = 0L
        var referenceLines = 0L
        var differingLines = 0L
        var shown = 0
        reader(generated).use { left ->
            reader(referenceFile).use { right ->
                while (true) {
                    val leftLine = left.readLine()
                    val rightLine = right.readLine()
                    if (leftLine == null && rightLine == null) break
                    if (leftLine != null) generatedLines++
                    if (rightLine != null) referenceLines++
                    if (leftLine != rightLine) {
                        differingLines++
                        if (shown < 20) {
                            shown++
                            val number = maxOf(generatedLines, referenceLines)
                            println("line $number")
                            println("  generated: ${leftLine ?: "<eof>"}")
                            println("  reference: ${rightLine ?: "<eof>"}")
                        }
                    }
                }
            }
        }
        println("generated lines $generatedLines, reference lines $referenceLines, differing $differingLines")

        val generatedStructs = structNames(generated)
        val referenceStructs = structNames(referenceFile)
        println("generated struct declarations ${generatedStructs.size}, reference ${referenceStructs.size}")
        val missing = referenceStructs - generatedStructs
        val extra = generatedStructs - referenceStructs
        println("missing structs ${missing.size}, extra structs ${extra.size}")
        missing.take(20).forEach { println("missing struct $it") }
        extra.take(20).forEach { println("extra struct $it") }
    }

    private fun structNames(file: File): Set<String> {
        val names = HashSet<String>(1 shl 19)
        reader(file).use { source ->
            while (true) {
                val line = source.readLine() ?: break
                if (!line.startsWith("struct ")) continue
                var end = 7
                while (end < line.length) {
                    val character = line[end]
                    if (character == ' ' || character == '{' || character == ':' || character == ';') break
                    end++
                }
                names.add(line.substring(7, end))
            }
        }
        return names
    }

    private fun reader(file: File): BufferedReader =
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8), 1 shl 20)
}
