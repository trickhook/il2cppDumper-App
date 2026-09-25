package com.trickhook.il2cppdumper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

data class Il2CppTarget(
    val packageName: String,
    val label: String,
    val versionName: String,
    val abi: String,
    val libraryPath: String,
    val librarySource: String,
    val metadataSource: String,
    val metadataEntry: String
)

object ApkSource {

    private const val METADATA_NAME = "global-metadata.dat"
    private const val LIBRARY_NAME = "libil2cpp.so"

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

        val library = findLibrary(info, apks) ?: return null
        val metadata = findMetadata(apks) ?: return null

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
            metadataSource = metadata.first,
            metadataEntry = metadata.second
        )
    }

    private data class LibraryLocation(val abi: String, val path: String, val source: String)

    private fun apkPaths(info: ApplicationInfo): List<String> {
        val all = mutableListOf<String>()
        info.sourceDir?.let { all += it }
        info.splitSourceDirs?.let { all += it }
        return all.filter { File(it).canRead() }
    }

    private fun findLibrary(info: ApplicationInfo, apks: List<String>): LibraryLocation? {
        val extracted = info.nativeLibraryDir?.let { File(it, LIBRARY_NAME) }
        if (extracted != null && extracted.canRead() && extracted.length() > 0) {
            return LibraryLocation(File(info.nativeLibraryDir).name, extracted.absolutePath, "")
        }
        for (abi in abiOrder) {
            val entry = "lib/$abi/$LIBRARY_NAME"
            for (apk in apks) {
                if (hasEntry(apk, entry)) return LibraryLocation(abi, entry, apk)
            }
        }
        return null
    }

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

    fun readLibrary(target: Il2CppTarget): ByteArray =
        if (target.librarySource.isEmpty()) File(target.libraryPath).readBytes()
        else readEntry(target.librarySource, target.libraryPath)

    fun readMetadata(target: Il2CppTarget): ByteArray =
        readEntry(target.metadataSource, target.metadataEntry)

    private fun readEntry(apk: String, entry: String): ByteArray =
        ZipFile(apk).use { zip ->
            val e = zip.getEntry(entry) ?: throw IllegalStateException("$entry ausente em $apk")
            zip.getInputStream(e).use { it.readBytes() }
        }
}
