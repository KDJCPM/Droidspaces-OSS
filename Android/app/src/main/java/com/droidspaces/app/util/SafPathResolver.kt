package com.droidspaces.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/**
 * Resolves real filesystem paths from Android Storage Access Framework (SAF) Uris,
 * supporting both Document Uris (from ACTION_OPEN_DOCUMENT / OpenDocument) and
 * Tree Uris (from ACTION_OPEN_DOCUMENT_TREE / OpenDocumentTree).
 *
 * It seamlessly translates Android document IDs such as "FFF6-F07B:Test/h/rootfs.img"
 * into canonical root paths such as "/storage/FFF6-F07B/Test/h/rootfs.img".
 */
object SafPathResolver {
    private const val TAG = "SafPathResolver"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val MEDIA_AUTHORITY = "com.android.providers.media.documents"

    /**
     * Persist read/write access to [uri] across reboots.
     */
    fun takePersistablePermission(context: Context, uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist permission for $uri", e)
        }
    }

    /**
     * Converts any SAF Uri (Document or Tree) into an absolute filesystem path.
     * E.g. "content://.../document/FFF6-F07B:Test/h/rootfs.img" -> "/storage/FFF6-F07B/Test/h/rootfs.img"
     */
    fun resolvePathFromUri(context: Context, uri: Uri): String? {
        return try {
            // 1. Direct file URI
            if (uri.scheme == "file") {
                return uri.path?.let { normalizePath(it) }
            }

            // 2. Standard ExternalStorageProvider
            if (uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
                val docId = getDocId(context, uri) ?: extractDocIdFromUri(uri)
                if (docId != null) {
                    val resolved = parseDocIdToPath(docId)
                    if (resolved != null) return resolved
                }
            }

            // 3. Downloads Provider
            if (uri.authority == DOWNLOADS_AUTHORITY) {
                val docId = try { DocumentsContract.getDocumentId(uri) } catch (e: Exception) { null }
                if (docId != null) {
                    if (docId.startsWith("raw:")) {
                        return normalizePath(docId.removePrefix("raw:"))
                    }
                    val resolved = parseDocIdToPath(docId)
                    if (resolved != null) return resolved
                }
            }

            // 4. Media Provider
            if (uri.authority == MEDIA_AUTHORITY) {
                try {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val colIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                            val path = cursor.getString(colIndex)
                            if (!path.isNullOrBlank()) {
                                return normalizePath(path)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying MediaStore column for $uri", e)
                }
            }

            // 5. Fallback: Parse path string for encoded document IDs (e.g. /documents/FFF6-F07B:Test/...)
            val rawPath = uri.path
            if (!rawPath.isNullOrBlank()) {
                val normalized = normalizePath(rawPath)
                if (normalized.isNotBlank() && !normalized.startsWith("/documents/") && !normalized.startsWith("/document/")) {
                    return normalized
                }
            }

            uri.path?.let { normalizePath(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve path from URI: $uri", e)
            uri.path?.let { normalizePath(it) }
        }
    }

    /**
     * Backward-compatible alias for [resolvePathFromUri].
     */
    fun resolvePathFromTreeUri(context: Context, treeUri: Uri): String? {
        return resolvePathFromUri(context, treeUri)
    }

    /**
     * Extracts document ID safely from any SAF Document or Tree Uri without throwing.
     */
    private fun getDocId(context: Context, uri: Uri): String? {
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts docId by parsing the URI path directly if DocumentsContract methods fail.
     */
    private fun extractDocIdFromUri(uri: Uri): String? {
        val path = uri.path ?: return null
        val decoded = Uri.decode(path)

        val match = Regex("""/(?:document|documents|tree)/([^/:]+:[^/]*.*)$""").find(decoded)
            ?: Regex("""/(?:document|documents|tree)/([^/:]+:.*)""").find(decoded)
        if (match != null) {
            return match.groupValues[1]
        }

        if (decoded.contains(":")) {
            val matchColon = Regex("""([^/:]+:[^/]*.*)$""").find(decoded)
            if (matchColon != null) {
                return matchColon.groupValues[1]
            }
        }

        return null
    }

    /**
     * Converts a document ID (e.g. "FFF6-F07B:Test/h/rootfs.img" or "primary:Download/img.img")
     * to a filesystem path.
     */
    private fun parseDocIdToPath(docId: String): String? {
        val cleanDocId = docId.removePrefix("raw:")
        val split = cleanDocId.split(":", limit = 2)
        if (split.isEmpty()) return null

        val volumeId = split[0]
        val relativePath = if (split.size > 1) split[1].trimStart('/') else ""

        val basePath = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volumeId"
        }

        val fullPath = if (relativePath.isEmpty()) basePath else "$basePath/$relativePath"
        return StorageChecker.normalizeStorageDir(fullPath)
    }

    /**
     * Normalizes any path string that may contain SAF document syntax
     * (e.g. "/documents/FFF6-F07B:Test/h/rootfs.img" -> "/storage/FFF6-F07B/Test/h/rootfs.img").
     */
    fun normalizePath(rawPath: String): String {
        val trimmed = rawPath.trim()
        val decoded = Uri.decode(trimmed)

        // Check for colon notation: e.g. /documents/FFF6-F07B:Test/h/rootfs.img or FFF6-F07B:Test/h/rootfs.img
        if (decoded.contains(":")) {
            val match = Regex("""(?:^|/)(?:document|documents|tree)?/??([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|primary|[a-zA-Z0-9_-]+):(.*)""").find(decoded)
            if (match != null) {
                val volumeId = match.groupValues[1]
                val relativePath = match.groupValues[2].trimStart('/')
                val basePath = if (volumeId.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/$volumeId"
                }
                val fullPath = if (relativePath.isEmpty()) basePath else "$basePath/$relativePath"
                return StorageChecker.normalizeStorageDir(fullPath)
            }
        }

        return StorageChecker.normalizeStorageDir(decoded)
    }
}
