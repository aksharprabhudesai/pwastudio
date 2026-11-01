package com.pwadeployer.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.pwadeployer.model.DeployFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object FileImportHelper {

    fun importFromTreeUri(context: Context, treeUri: Uri): List<DeployFile> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val files = mutableListOf<DeployFile>()
        traverseDocumentTree(context.contentResolver, rootDoc, "", files)
        return files
    }

    private fun traverseDocumentTree(
        resolver: ContentResolver,
        dir: DocumentFile,
        prefix: String,
        out: MutableList<DeployFile>
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".git")) continue
            val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                traverseDocumentTree(resolver, child, relPath, out)
            } else if (child.isFile) {
                val bytes = readBytesFromUri(resolver, child.uri) ?: continue
                if (name.endsWith(".zip", ignoreCase = true) && prefix.isEmpty()) {
                    out.addAll(extractZipBytes(bytes))
                } else {
                    out.add(DeployFile(relativePath = relPath, content = bytes))
                }
            }
        }
    }

    fun importFromUris(context: Context, uris: List<Uri>): List<DeployFile> {
        val resolver = context.contentResolver
        val result = mutableListOf<DeployFile>()
        for (uri in uris) {
            val fileName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "index.html"
            val bytes = readBytesFromUri(resolver, uri) ?: continue
            if (fileName.endsWith(".zip", ignoreCase = true)) {
                result.addAll(extractZipBytes(bytes))
            } else {
                result.add(DeployFile(relativePath = fileName, content = bytes))
            }
        }
        return normalizeCommonPrefix(result)
    }

    fun importFromShareIntent(context: Context, intent: Intent?): List<DeployFile> {
        if (intent == null) return emptyList()
        val action = intent.action ?: return emptyList()

        return when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = intent.getParcelableUriExtra(Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    importFromUris(context, listOf(streamUri))
                } else {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        listOf(
                            DeployFile(
                                relativePath = "index.html",
                                content = sharedText.toByteArray(Charsets.UTF_8)
                            )
                        )
                    } else {
                        emptyList()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableUriArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
                importFromUris(context, uris)
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.data
                if (dataUri != null) {
                    importFromUris(context, listOf(dataUri))
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun extractZipBytes(zipBytes: ByteArray): List<DeployFile> {
        val extracted = mutableListOf<DeployFile>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.name.contains("__MACOSX") && !entry.name.startsWith(".git/")) {
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (zis.read(buffer).also { count = it } != -1) {
                        baos.write(buffer, 0, count)
                    }
                    extracted.add(
                        DeployFile(
                            relativePath = entry.name.trimStart('/'),
                            content = baos.toByteArray()
                        )
                    )
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return normalizeCommonPrefix(extracted)
    }

    /**
     * If all extracted files share a single top-level folder (e.g. "my-pwa/index.html", "my-pwa/sw.js"),
     * strip the top-level folder prefix so index.html sits at the root of the repository.
     */
    private fun normalizeCommonPrefix(files: List<DeployFile>): List<DeployFile> {
        if (files.isEmpty()) return files
        val hasRootIndex = files.any { it.relativePath.equals("index.html", ignoreCase = true) }
        if (hasRootIndex) return files

        val firstSegments = files.map { it.relativePath.substringBefore('/', "") }
        val commonFirst = firstSegments.firstOrNull()
        if (!commonFirst.isNullOrEmpty() && firstSegments.all { it == commonFirst && files.all { f -> f.relativePath.contains('/') } }) {
            return files.map { it.copy(relativePath = it.relativePath.removePrefix("$commonFirst/")) }
        }
        return files
    }

    private fun readBytesFromUri(resolver: ContentResolver, uri: Uri): ByteArray? {
        return try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableUriExtra(key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Uri::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableUriArrayListExtra(key: String): List<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            getParcelableArrayListExtra(key)
        }
    }
}
