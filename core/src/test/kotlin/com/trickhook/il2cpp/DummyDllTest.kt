package com.trickhook.il2cpp

import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.il2cpp.Il2CppBinary
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.metadata.Metadata
import com.trickhook.il2cpp.output.DummyDllWriter
import com.trickhook.il2cpp.protector.FFProtector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DummyDllTest {

    private val library = File("C:/Users/danie/Desktop/dump/arm64/libil2cpp.so")
    private val metadataFile = File("C:/Users/danie/Desktop/dump/arm64/global-metadata.dat")
    private val outDir = File("C:/Users/danie/Desktop/dump/arm64/DummyDll")

    @Test
    fun `generates the dummy assemblies for a real game`() {
        if (!library.isFile || !metadataFile.isFile) {
            println("amostra ausente, pulando")
            return
        }

        val unpacked = FFProtector.tryUnpack(library.readBytes(), inPlace = true).data
        val elf = ElfImage.parse(unpacked)
        elf.applyRelocations()
        val metadata = Metadata(metadataFile.readBytes())
        val binary = Il2CppBinary.load(elf, metadata)
        val executor = Il2CppExecutor(metadata, binary)

        outDir.deleteRecursively()
        outDir.mkdirs()

        val writer = DummyDllWriter(executor)
        println("gerando ${writer.assemblyCount} assemblies")
        var total = 0L
        var count = 0
        val started = System.currentTimeMillis()
        writer.forEachAssembly { name, bytes ->
            File(outDir, name).writeBytes(bytes)
            total += bytes.size
            count++
        }
        val elapsed = System.currentTimeMillis() - started
        println("gerados $count arquivos, ${total / 1048576} MB, em ${elapsed}ms")
        println("genericos fora de alcance degradados para object: ${writer.outOfRangeGenericParams}")

        assertTrue(count == writer.assemblyCount, "faltaram assemblies")
        assertTrue(total > 1_000_000, "saida pequena demais: $total")
    }
}
