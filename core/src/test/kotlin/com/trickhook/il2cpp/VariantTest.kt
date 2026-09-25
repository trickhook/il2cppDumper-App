package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.output.DumpOptions
import com.trickhook.il2cpp.output.DumpWriter
import com.trickhook.il2cpp.output.ScriptWriter
import com.trickhook.il2cpp.protector.FFProtector
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import kotlin.test.Test
import kotlin.test.assertTrue

class VariantTest {

    private val root = File("C:/Users/danie/Desktop/dump/variants")

    private fun run(tag: String) {
        val dir = File(root, tag)
        val libFile = File(dir, "libil2cpp.so")
        val mdFile = File(dir, "global-metadata.dat")
        if (!libFile.exists() || !mdFile.exists()) {
            println("$tag: amostra ausente, pulando")
            return
        }

        println("===== $tag =====")
        val unpack = FFProtector.tryUnpack(libFile.readBytes(), inPlace = true)
        println(if (unpack.detected) FFProtector.describe(unpack) else "  sem protector")

        val elf = ElfImage.parse(unpack.data)
        elf.applyRelocations()
        println("  ELF ${if (elf.is64) 64 else 32} bits, ${elf.segments.size} segmentos")

        val metadata = Metadata(mdFile.readBytes())
        val key = metadata.obfuscationKey
        println("  metadata v${metadata.version}" +
            if (key != 0) ", desofuscado com XOR 0x${key.toString(16)}" else ", em claro")
        println("  ${metadata.typeDefs.size} tipos, ${metadata.methodDefs.size} metodos, " +
            "${metadata.imageDefs.size} imagens, ${metadata.stringLiterals.size} literals")
        val layout = metadata.methodDefLayout
        println("  methodDef stride ${layout.stride}, esperado ${layout.expectedSize}, " +
            "padding ${layout.padSize} em +0x${layout.padOffset.toString(16)}")

        val binary = Il2CppBinary.load(elf, metadata)
        println("  CodeRegistration 0x${binary.codeRegistrationAddress.toString(16)}")
        println("  MetadataRegistration 0x${binary.metadataRegistrationAddress.toString(16)}")
        println("  types ${binary.types.size}, methodSpecs ${binary.methodSpecs.size}, " +
            "codeGenModules ${binary.codeGenModules.size}")

        val executor = Il2CppExecutor(metadata, binary)
        val out = File(dir, "dump.cs")
        out.outputStream().use { stream ->
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20).use {
                DumpWriter(executor, DumpOptions()).write(it)
            }
        }
        val script = File(dir, "script.json")
        script.outputStream().use { stream ->
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20).use {
                ScriptWriter(executor).writeScript(it)
            }
        }

        var namespaces = 0
        var rvas = 0
        out.bufferedReader().use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.startsWith("// Namespace:")) namespaces++
                if (line.contains("RVA: 0x")) rvas++
                line = r.readLine()
            }
        }
        println("  dump.cs ${out.length()} bytes, $namespaces tipos, $rvas RVAs")
        println("  script.json ${script.length()} bytes")

        assertTrue(metadata.typeDefs.size > 1000, "$tag typeDefs")
        assertTrue(namespaces == metadata.typeDefs.size, "$tag tipos no dump.cs")
        assertTrue(rvas > 10000, "$tag RVAs")
        assertTrue(binary.codeGenModules.isNotEmpty(), "$tag codeGenModules")
    }

    @Test
    fun `free fire max arm64`() = run("ffmax_2.133.1")

    @Test
    fun `free fire global arm64 from bundle`() = run("ffth_india_1.132.1")

    @Test
    fun `free fire max arm64 2 132`() = run("ffmax64_2.132.1")

    @Test
    fun `free fire max arm32 2 132`() = run("ffmax32_2.132.1")
}
