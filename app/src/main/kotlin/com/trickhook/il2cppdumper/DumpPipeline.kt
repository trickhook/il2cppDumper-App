package com.trickhook.il2cppdumper

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.output.DumpOptions
import com.trickhook.il2cpp.output.DummyDllWriter
import com.trickhook.il2cpp.output.DumpWriter
import com.trickhook.il2cpp.output.HeaderWriter
import com.trickhook.il2cpp.output.ScriptWriter
import com.trickhook.il2cpp.protector.FFProtector
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

object DumpPipeline {

    private const val BUFFER = 1 shl 20

    fun run(
        target: Il2CppTarget,
        outputDir: File,
        onStage: (String) -> Unit,
        onLog: (String) -> Unit,
        dummyDllAttributes: ByteArray? = null
    ) {
        outputDir.mkdirs()
        val started = System.currentTimeMillis()

        onStage("Extraindo do APK")
        val library = ApkSource.readLibrary(target)
        onLog("libil2cpp.so  ${library.size} bytes  ${target.abi}")

        onStage("Desempacotando")
        val unpack = FFProtector.tryUnpack(library, inPlace = true)
        if (unpack.detected) onLog(FFProtector.describe(unpack)) else onLog("Nenhum protector detectado")

        onStage("Lendo o ELF")
        val elf = ElfImage.parse(unpack.data)
        elf.applyRelocations()
        onLog("ELF ${if (elf.is64) 64 else 32} bits, ${elf.segments.size} segmentos")

        onStage("Lendo o metadata")
        val metadataBytes = ApkSource.readMetadata(target)
        val metadata = runCatching { Metadata(metadataBytes) }.getOrElse { error ->
            val magic = if (metadataBytes.size >= 4) {
                (metadataBytes[0].toInt() and 0xFF) or ((metadataBytes[1].toInt() and 0xFF) shl 8) or
                    ((metadataBytes[2].toInt() and 0xFF) shl 16) or ((metadataBytes[3].toInt() and 0xFF) shl 24)
            } else 0
            if (magic != -0x54ee451) {
                throw IllegalStateException(
                    "global-metadata.dat esta criptografado ou nao e IL2CPP " +
                        "(magic 0x${magic.toUInt().toString(16)}). Este jogo nao da pra dumpar so do APK."
                )
            }
            throw error
        }
        onLog("metadata v${metadata.version}, ${metadata.typeDefs.size} tipos, ${metadata.methodDefs.size} metodos")
        val layout = metadata.methodDefLayout
        if (!layout.isStandard) {
            onLog("Il2CppMethodDefinition ${layout.stride} bytes, ${layout.padSize} extra em +0x${layout.padOffset.toString(16)}")
        }

        onStage("Localizando as registrations")
        val binary = Il2CppBinary.load(elf, metadata)
        onLog("CodeRegistration 0x${binary.codeRegistrationAddress.toString(16)}")
        onLog("MetadataRegistration 0x${binary.metadataRegistrationAddress.toString(16)}")
        val executor = Il2CppExecutor(metadata, binary)

        write(outputDir, "dump.cs", onStage, onLog) { DumpWriter(executor, DumpOptions()).write(it) }
        write(outputDir, "script.json", onStage, onLog) { ScriptWriter(executor).writeScript(it) }
        write(outputDir, "stringliteral.json", onStage, onLog) { ScriptWriter(executor).writeStringLiterals(it) }
        write(outputDir, "il2cpp.h", onStage, onLog) { HeaderWriter(executor).write(it) }

        writeDummyDll(outputDir, executor, dummyDllAttributes, onStage, onLog)

        onStage("Concluido")
        onLog("Total ${(System.currentTimeMillis() - started) / 1000}s")
        onLog(outputDir.absolutePath)
    }

    /**
     * Grava os assemblies do DummyDll. Eles saem um de cada vez porque o
     * Assembly-CSharp de um jogo grande sozinho passa de 80 MB.
     *
     * O Il2CppDummyDll.dll nao e gerado: ele so define os cinco atributos que
     * os outros assemblies referenciam e vai junto como asset, exatamente o
     * mesmo arquivo que o dumper de PC usa.
     */
    private fun writeDummyDll(
        outputDir: File,
        executor: Il2CppExecutor,
        attributes: ByteArray?,
        onStage: (String) -> Unit,
        onLog: (String) -> Unit
    ) {
        onStage("Gerando DummyDll")
        val started = System.currentTimeMillis()
        val dir = File(outputDir, "DummyDll")
        dir.deleteRecursively()
        dir.mkdirs()
        attributes?.let { File(dir, "Il2CppDummyDll.dll").writeBytes(it) }

        val writer = DummyDllWriter(executor)
        var total = 0L
        var count = 0
        writer.forEachAssembly { name, bytes ->
            File(dir, name).writeBytes(bytes)
            total += bytes.size
            count++
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        onLog("DummyDll  $count assemblies  $total bytes  ${"%.1f".format(seconds)}s")
        if (writer.skippedAssemblies.isNotEmpty()) {
            onLog("  sem memoria para: ${writer.skippedAssemblies.joinToString(", ")}")
        }
        if (writer.outOfRangeGenericParams > 0) {
            onLog("  ${writer.outOfRangeGenericParams} genericos fora de alcance viraram object")
        }
    }

    private fun write(
        outputDir: File,
        name: String,
        onStage: (String) -> Unit,
        onLog: (String) -> Unit,
        body: (BufferedWriter) -> Unit
    ) {
        onStage("Gerando $name")
        val started = System.currentTimeMillis()
        val target = File(outputDir, name)
        target.outputStream().use { stream ->
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), BUFFER).use(body)
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        onLog("$name  ${target.length()} bytes  ${"%.1f".format(seconds)}s")
    }
}
