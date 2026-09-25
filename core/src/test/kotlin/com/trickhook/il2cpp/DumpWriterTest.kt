package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.output.DumpOptions
import com.trickhook.il2cpp.output.DumpWriter
import com.trickhook.il2cpp.protector.FFProtector
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DumpWriterTest {

    private val decimalComma = Regex("""(?<=\d),(?=\d)""")

    private fun normalizeDecimal(line: String) = decimalComma.replace(line, ".")


    @Test
    fun matchesReferenceDump() {
        val previous = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.Category.FORMAT, REFERENCE_LOCALE)
        try {
            compareAgainstReference()
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous)
        }
    }

    private fun compareAgainstReference() {
        println("format locale = ${Locale.getDefault(Locale.Category.FORMAT)}")
        println("decimal separator = ${DecimalFormatSymbols.getInstance().decimalSeparator}")

        val started = System.currentTimeMillis()
        val raw = File(LIBRARY).readBytes()
        val unpacked = FFProtector.tryUnpack(raw).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()
        val metadata = Metadata(File(METADATA).readBytes())
        val binary = Il2CppBinary.load(elf, metadata)
        val executor = Il2CppExecutor(metadata, binary)
        println("loaded in ${System.currentTimeMillis() - started} ms")

        val reference = File(REFERENCE)
        val produced = File.createTempFile("dump", ".cs")
        produced.deleteOnExit()
        assertTrue(reference.isFile && reference.length() > 0L, "reference is missing")
        assertNotEquals(reference.canonicalPath, produced.canonicalPath, "produced and reference are the same file")
        val options = DumpOptions(dumpProperty = false, dumpAttribute = false)
        val writing = System.currentTimeMillis()
        BufferedWriter(OutputStreamWriter(FileOutputStream(produced), Charsets.UTF_8), 1 shl 16).use {
            DumpWriter(executor, options).write(it)
        }
        println("wrote ${produced.length()} bytes in ${System.currentTimeMillis() - writing} ms")
        println("reference is ${reference.length()} bytes")

        var producedLines = 0L
        var referenceLines = 0L
        var differing = 0L
        val samples = ArrayList<String>()
        reader(produced).use { left ->
            reader(reference).use { right ->
                while (true) {
                    val mine = left.readLine()
                    val theirs = right.readLine()
                    if (mine == null && theirs == null) break
                    if (mine != null) producedLines++
                    if (theirs != null) referenceLines++
                    if (normalizeDecimal(mine) != normalizeDecimal(theirs)) {
                        differing++
                        if (samples.size < 20) {
                            val at = maxOf(producedLines, referenceLines)
                            samples.add("line $at\n  mine     = ${show(mine)}\n  expected = ${show(theirs)}")
                        }
                    }
                }
            }
        }

        println("produced lines  = $producedLines")
        println("reference lines = $referenceLines")
        println("differing lines = $differing")
        samples.forEach { println(it) }

        val producedDigest = digest(produced)
        val referenceDigest = digest(reference)
        println("produced sha256  = $producedDigest")
        println("reference sha256 = $referenceDigest")

        assertTrue(referenceLines > 2_000_000L, "reference looks truncated")
        var producedDecimalCommas = 0
        produced.bufferedReader().use { r ->
            var line = r.readLine()
            while (line != null) {
                producedDecimalCommas += decimalComma.findAll(line).count()
                line = r.readLine()
            }
        }
        println("decimal commas produced = $producedDecimalCommas")

        assertEquals(referenceLines, producedLines, "line count")
        assertEquals(0L, differing, "differing lines")
        assertEquals(reference.length(), produced.length(), "byte length")
        assertEquals(0, producedDecimalCommas, "decimal commas in generated output")
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        file.inputStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun reader(file: File): BufferedReader =
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8), 1 shl 16)

    private fun show(line: String?): String = line?.let { "\"$it\"" } ?: "<end of file>"

    private companion object {
        val REFERENCE_LOCALE: Locale = Locale.forLanguageTag("pt-BR")
        const val LIBRARY = "C:/Users/danie/Desktop/dump/arm64/libil2cpp.so"
        const val METADATA = "C:/Users/danie/Desktop/dump/arm64/global-metadata.dat"
        const val REFERENCE = "C:/Users/danie/Desktop/dump/split/dump.cs"
    }
}
