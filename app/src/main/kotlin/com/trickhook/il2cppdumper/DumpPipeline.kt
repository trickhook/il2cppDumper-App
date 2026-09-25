package com.trickhook.il2cppdumper

import com.trickhook.il2cpp.protector.FFProtector
import java.io.File

object DumpPipeline {

    fun run(
        target: Il2CppTarget,
        outputDir: File,
        onStage: (String) -> Unit,
        onLog: (String) -> Unit
    ) {
        onStage("Extraindo do APK")
        val library = ApkSource.readLibrary(target)
        onLog("libil2cpp.so  ${library.size} bytes  ${target.abi}")

        onStage("Desempacotando")
        val unpack = FFProtector.tryUnpack(library, inPlace = true)
        if (unpack.detected) {
            onLog(FFProtector.describe(unpack))
        } else {
            onLog("Nenhum protector detectado")
        }

        val metadata = ApkSource.readMetadata(target)
        onLog("global-metadata.dat  ${metadata.size} bytes")

        outputDir.mkdirs()
        onStage("Pronto")
        onLog("Saida em ${outputDir.absolutePath}")
    }
}
