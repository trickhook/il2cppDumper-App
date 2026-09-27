package com.trickhook.il2cppdumper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.trickhook.il2cpp.elf.ElfImage
import com.trickhook.il2cpp.io.ByteSource
import com.trickhook.il2cpp.io.MappedByteSource
import java.io.File
import java.util.zip.ZipFile

data class Il2CppTarget(
    val packageName: String,
    val label: String,
    val versionName: String,
    val abi: String,
    val libraryPath: String,
    val librarySource: String,
    val libraryName: String,
    val libraryReason: String,
    val librarySize: Long,
    val metadataSource: String,
    val metadataEntry: String
)

object ApkSource {

    private const val METADATA_NAME = "global-metadata.dat"
    private const val LIBRARY_NAME = "libil2cpp.so"

    /**
     * The marker we look for when the host is not called libil2cpp.so. Games that
     * link IL2CPP into libunity.so keep symbols around it even when the rest of
     * the library is stripped, so a *substring* match is what actually works:
     * Call of Duty Mobile exports ELF_HOOK_il2cpp_init and not il2cpp_init.
     */
    private const val HOST_SYMBOL = "il2cpp_init"
    private const val HOST_SYMBOL_WEAK = "il2cpp"

    private const val MAX_PROBED_LIBRARIES = 80

    private const val COPY_BUFFER = 1 shl 20

    private val abiOrder: List<String>
        get() = (Build.SUPPORTED_ABIS.toList() + listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
            .distinct()

    fun scan(context: Context): List<Il2CppTarget> {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed.mapNotNull { inspect(pm, it) }.sortedBy { it.label.lowercase() }
    }

    fun inspect(pm: PackageManager, info: ApplicationInfo): Il2CppTarget? {
        val apks = apkPaths(info)
        if (apks.isEmpty()) return null

        // Metadata first: it is the cheap gate. Only a game that ships a
        // global-metadata.dat is worth the cost of probing its native libraries
        // for the IL2CPP host.
        val metadata = findMetadata(apks) ?: return null
        val library = findLibrary(info, apks) ?: return null

        val versionName = runCatching {
            pm.getPackageInfo(info.packageName, 0).versionName
        }.getOrNull().orEmpty()

        return Il2CppTarget(
            packageName = info.packageName,
            label = pm.getApplicationLabel(info).toString(),
            versionName = versionName,
            abi = library.abi,
            libraryPath = library.path,
            librarySource = library.source,
            libraryName = library.name,
            libraryReason = library.reason,
            librarySize = library.size,
            metadataSource = metadata.first,
            metadataEntry = metadata.second
        )
    }

    private data class LibraryLocation(
        val abi: String,
        val path: String,
        val source: String,
        val name: String,
        val reason: String,
        val size: Long
    )

    private fun apkPaths(info: ApplicationInfo): List<String> {
        val all = mutableListOf<String>()
        info.sourceDir?.let { all += it }
        info.splitSourceDirs?.let { all += it }
        return all.filter { File(it).canRead() }
    }

    /**
     * Finds the library that hosts IL2CPP, without assuming its name.
     *
     * Order: the conventional libil2cpp.so first, because that is both the common
     * case and the cheapest; then, over the already extracted libraries in
     * nativeLibraryDir, whichever ELF carries an il2cpp symbol; then the APK by
     * name as a last resort. nativeLibraryDir is a public API, needs no root, and
     * the files there are plain files, so probing and later reading them is far
     * cheaper than inflating them out of the APK.
     */
    private fun findLibrary(info: ApplicationInfo, apks: List<String>): LibraryLocation? {
        val dir = info.nativeLibraryDir?.let { File(it) }
        val abi = dir?.name.orEmpty()

        val conventional = dir?.let { File(it, LIBRARY_NAME) }
        if (conventional != null && conventional.canRead() && conventional.length() > 0) {
            return LibraryLocation(
                abi, conventional.absolutePath, "", LIBRARY_NAME,
                "nome convencional em nativeLibraryDir", conventional.length()
            )
        }

        val bySymbol = dir?.let { probeForHost(it) }
        if (bySymbol != null) return bySymbol.copy(abi = abi)

        for (candidate in abiOrder) {
            val entry = "lib/$candidate/$LIBRARY_NAME"
            for (apk in apks) {
                if (hasEntry(apk, entry)) {
                    return LibraryLocation(
                        candidate, entry, apk, LIBRARY_NAME,
                        "nome convencional dentro do APK", 0L
                    )
                }
            }
        }
        return null
    }

    /**
     * The extracted library in [dir] that looks like the IL2CPP host. Biggest
     * first, because the host dwarfs everything else and that ordering means the
     * usual case is decided by the first probe; size is only the *order*, never
     * the criterion. When more than one library carries the symbol, the biggest
     * wins and the others are named in the reason so the choice is auditable.
     */
    private fun probeForHost(dir: File): LibraryLocation? {
        val candidates = (dir.listFiles() ?: return null)
            .filter { it.isFile && it.name.endsWith(".so") && it.length() > 0L }
            .sortedByDescending { it.length() }
            .take(MAX_PROBED_LIBRARIES)

        val strong = ArrayList<Pair<File, String>>()
        val weak = ArrayList<Pair<File, String>>()
        for (file in candidates) {
            val symbol = il2cppSymbolIn(file) ?: continue
            if (symbol.contains(HOST_SYMBOL)) strong += file to symbol else weak += file to symbol
            // Biggest first, so the first strong hit is already the best one.
            if (strong.isNotEmpty()) break
        }
        val picked = strong.firstOrNull() ?: weak.firstOrNull() ?: return null
        val (file, symbol) = picked
        val others = (strong + weak).drop(1).map { it.first.name }
        val extra = if (others.isEmpty()) "" else "; tambem casaram: ${others.joinToString(", ")}"
        return LibraryLocation(
            abi = dir.name,
            path = file.absolutePath,
            source = "",
            name = file.name,
            reason = "exporta '$symbol' (sem libil2cpp.so)$extra",
            size = file.length()
        )
    }

    /** The first dynamic symbol in [file] whose name mentions il2cpp, or null. */
    private fun il2cppSymbolIn(file: File): String? = runCatching {
        MappedByteSource.readOnly(file).use { source ->
            if (!ElfImage.looksLikeElf(source)) return@use null
            val image = ElfImage.parse(source)
            val names = image.symbols.asSequence().map { it.name }.filter { it.contains(HOST_SYMBOL_WEAK) }
            names.firstOrNull { it.contains(HOST_SYMBOL) } ?: names.firstOrNull()
        }
    }.getOrNull()

    private fun findMetadata(apks: List<String>): Pair<String, String>? {
        for (apk in apks) {
            val entry = runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .firstOrNull { it.endsWith("/$METADATA_NAME") || it == METADATA_NAME }
                }
            }.getOrNull()
            if (entry != null) return apk to entry
        }
        return null
    }

    private fun hasEntry(apk: String, entry: String): Boolean =
        runCatching { ZipFile(apk).use { it.getEntry(entry) != null } }.getOrDefault(false)

    /**
     * Opens the library as an off-heap, writable [ByteSource].
     *
     * A copy-on-write mapping needs a file we may open for writing, and the files
     * under nativeLibraryDir belong to `system`, so the library is staged into our
     * own cache first. That costs disk and one kernel-side copy and keeps the heap
     * cost at zero, which is the whole point: a 247 MB library does not fit in a
     * 512 MB Dalvik heap alongside a 61 MB metadata and the object graph.
     *
     * The staged name carries the size and timestamp of the original, so a rerun
     * reuses it instead of copying again.
     */
    fun openLibrary(target: Il2CppTarget, cacheDir: File, onLog: (String) -> Unit): ByteSource {
        val staged = if (target.librarySource.isEmpty()) {
            val origin = File(target.libraryPath)
            stage(cacheDir, stageName(target.packageName, origin), onLog) { destination ->
                copyFile(origin, destination)
            }
        } else {
            val key = "${target.packageName}-${File(target.libraryPath).name}"
            stage(cacheDir, "$key.so", onLog) { destination ->
                extractEntry(target.librarySource, target.libraryPath, destination)
            }
        }
        return mapPrivate(staged, onLog)
    }

    /** Opens the metadata the same way; it always has to come out of the APK. */
    fun openMetadata(target: Il2CppTarget, cacheDir: File, onLog: (String) -> Unit): ByteSource {
        val staged = stage(cacheDir, "${target.packageName}-$METADATA_NAME", onLog) { destination ->
            extractEntry(target.metadataSource, target.metadataEntry, destination)
        }
        return mapPrivate(staged, onLog)
    }

    fun clearStage(cacheDir: File) {
        stageDir(cacheDir).listFiles()?.forEach { it.delete() }
    }

    private fun stageDir(cacheDir: File): File = File(cacheDir, "stage").apply { mkdirs() }

    private fun stageName(packageName: String, origin: File): String =
        "$packageName-${origin.name}-${origin.length()}-${origin.lastModified()}"

    private fun stage(cacheDir: File, name: String, onLog: (String) -> Unit, write: (File) -> Unit): File {
        val dir = stageDir(cacheDir)
        val target = File(dir, name)
        if (target.isFile && target.length() > 0L) {
            onLog("  reaproveitando ${target.name} (${target.length()} bytes) do cache")
            return target
        }
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }
        val partial = File(dir, "$name.part")
        partial.delete()
        val started = System.currentTimeMillis()
        write(partial)
        require(partial.renameTo(target)) { "nao deu para renomear ${partial.name}" }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        onLog("  preparou ${target.name}  ${target.length()} bytes  ${"%.1f".format(seconds)}s")
        return target
    }

    private fun mapPrivate(file: File, onLog: (String) -> Unit): ByteSource {
        val source = MappedByteSource.privateMap(file)
        onLog("  ${source.describe} (fora do heap)")
        return source
    }

    /** Kernel-side copy: nothing passes through the heap. */
    private fun copyFile(from: File, to: File) {
        from.inputStream().use { input ->
            to.outputStream().use { output ->
                val src = input.channel
                val dst = output.channel
                var moved = 0L
                while (moved < src.size()) {
                    val step = dst.transferFrom(src, moved, src.size() - moved)
                    if (step <= 0L) break
                    moved += step
                }
            }
        }
    }

    private fun extractEntry(apk: String, entry: String, to: File) {
        ZipFile(apk).use { zip ->
            val found = zip.getEntry(entry) ?: throw IllegalStateException("$entry ausente em $apk")
            zip.getInputStream(found).use { input ->
                to.outputStream().buffered(COPY_BUFFER).use { output ->
                    input.copyTo(output, COPY_BUFFER)
                }
            }
        }
    }
}
