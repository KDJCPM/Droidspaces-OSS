package com.droidspaces.app.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility functions for file picker operations.
 */
object FilePickerUtils {
    private const val TAG = "FilePickerUtils"

    /**
     * Get the display name (filename) from a content URI.
     * Handles both regular file URIs and content provider URIs (like recent files).
     *
     * @param context The context to use for ContentResolver
     * @param uri The URI to get the filename from
     * @return The filename, or null if it cannot be determined
     */
    suspend fun getFileName(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Method 1: Try ContentResolver query (works for content:// URIs including recent files)
            // This is the most reliable method for content URIs from file pickers
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val displayName = it.getString(nameIndex)
                        if (!displayName.isNullOrEmpty()) {
                            Log.d(TAG, "Got filename from DISPLAY_NAME: $displayName")
                            return@withContext displayName
                        }
                    }
                    // Also try _display_name column (some providers use this)
                    val displayNameIndex = it.getColumnIndex("_display_name")
                    if (displayNameIndex >= 0) {
                        val displayName = it.getString(displayNameIndex)
                        if (!displayName.isNullOrEmpty()) {
                            Log.d(TAG, "Got filename from _display_name: $displayName")
                            return@withContext displayName
                        }
                    }
                }
            }

            // Method 2: Try lastPathSegment (works for file:// URIs and some content URIs)
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrEmpty()) {
                // Skip document IDs like "document:1000233741" - these need ContentResolver query (already tried above)
                if (lastSegment.startsWith("document:") || lastSegment.startsWith("msf:")) {
                    // This is a document ID, ContentResolver query should have handled it
                    // If we got here, the query failed, so skip to next method
                } else {
                    // Handle content URI format like "primary:rootfs.tar.xz"
                    val fileName = if (lastSegment.contains(":")) {
                        // For content URIs like "primary:rootfs.tar.xz", extract after colon
                        lastSegment.substringAfterLast(":")
                    } else {
                        lastSegment
                    }

                    // Only return if it looks like a filename (has extension)
                    if (fileName.contains(".") && fileName.length > 1) {
                        Log.d(TAG, "Got filename from lastPathSegment: $fileName")
                        return@withContext fileName
                    }
                }
            }

            // Method 3: Try to extract from URI string
            val uriString = uri.toString()
            val extracted = uriString.substringAfterLast("/").substringBefore("?")
            if (extracted.contains(".") && !extracted.startsWith("msf:") && extracted.length > 1) {
                Log.d(TAG, "Got filename from URI string: $extracted")
                return@withContext extracted
            }

            Log.w(TAG, "Could not determine filename from URI: $uri")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting filename from URI: $uri", e)
            null
        }
    }

    /**
     * Check if a URI points to a valid tar.xz, tar.gz, or sparse .img file.
     *
     * @param context The context to use for ContentResolver
     * @param uri The URI to check
     * @return Pair of (isValid, fileName) where isValid is true if the file is a supported archive or image
     */
    suspend fun isValidTarball(context: Context, uri: Uri): Pair<Boolean, String?> {
        val fileName = getFileName(context, uri)
        if (fileName == null) {
            return Pair(false, null)
        }

        val fileNameLower = fileName.lowercase()
        val isValid = fileNameLower.endsWith(".tar.xz") ||
                fileNameLower.endsWith(".tar.gz") ||
                fileNameLower.endsWith(".tar") ||
                fileNameLower.endsWith(".img")

        return Pair(isValid, fileName)
    }

    /** Returns true if the URI points to an ext4 / sparse .img file. */
    suspend fun isImageFile(context: Context, uri: Uri): Boolean {
        val fileName = getFileName(context, uri) ?: return false
        return fileName.lowercase().endsWith(".img")
    }
}

