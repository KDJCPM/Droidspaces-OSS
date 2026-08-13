package com.droidspaces.app.util

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ContainerInstaller {
    private const val BUSYBOX_PATH = Constants.BUSYBOX_BINARY_PATH

    /**
     * Extract tarball and install container.
     * Returns Result.success on success, Result.failure on error.
     * On failure, automatically cleans up created files.
     */
    suspend fun installContainer(
        context: Context,
        tarballUri: Uri,
        config: ContainerInfo,
        logger: ContainerLogger
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Use sanitized name for directory (spaces -> dashes)
        val sanitizedName = ContainerManager.sanitizeContainerName(config.name)
        val containerPath = ContainerManager.getContainerDirectory(config.name)
        // Use whatever rootfs location the caller already resolved (internal default,
        // or a custom/external storage path picked in the wizard) instead of
        // recomputing it -- config.rootfsPath is the single source of truth.
        val rootfsPath = config.rootfsPath
        // Parent directory of the rootfs/rootfs.img -- normally == containerPath, but
        // differs when a custom external storage location was chosen.
        val rootfsParentDir = rootfsPath.substringBeforeLast("/", containerPath)
        val isExternalStorage = rootfsParentDir != containerPath
        val configFilePath = "$containerPath/${Constants.CONTAINER_CONFIG_FILE}"
        val createdPaths = mutableListOf<String>()

        try {
            // Reject control chars in single-line config values (VULN V11).
            ValidationUtils.validateConfigValues(config).errorMessage?.let {
                logger.e(it)
                return@withContext Result.failure(Exception(it))
            }

            // Step 1: Create container directory (metadata: config, .env, pidfile --
            // this always stays on internal storage regardless of rootfs location)
            logger.i("Creating container directory: $containerPath")
            val mkdirResult = Shell.cmd("mkdir -p \"$containerPath\" 2>&1").exec()
            if (!mkdirResult.isSuccess) {
                val errorOutput = (mkdirResult.out + mkdirResult.err).joinToString("\n").trim()
                val errorMsg = if (errorOutput.isNotEmpty()) errorOutput else "Unknown error (exit code: ${mkdirResult.code})"
                throw Exception("Failed to create container directory: $errorMsg")
            }
            createdPaths.add(containerPath)

            // Step 1.1: If the rootfs lives on external/custom storage, make sure
            // its parent directory exists too (it won't have been created above).
            // This MUST happen before the free-space check below -- stat/df need
            // the target path to already exist.
            if (isExternalStorage) {
                StorageMountManager.ensureVolumeMounted(rootfsParentDir)
                logger.i("Creating external storage directory: $rootfsParentDir")
                val mkdirExtResult = RootNamespace.exec("mkdir -p \"$rootfsParentDir\" 2>&1", targetPath = rootfsParentDir)
                if (!mkdirExtResult.isSuccess) {
                    val errorOutput = (mkdirExtResult.out + mkdirExtResult.err).joinToString("\n").trim()
                    val errorMsg = if (errorOutput.isNotEmpty()) errorOutput else "Unknown error (exit code: ${mkdirExtResult.code})"
                    throw Exception("Failed to create external storage directory: $errorMsg")
                }
                createdPaths.add(rootfsParentDir)
            }

            // Step 2: Check storage space on the actual target volume (the external
            // storage's mount point if one was chosen, otherwise /data). Runs after
            // directory creation so the path being checked is guaranteed to exist.
            logger.i("Checking available storage space...")
            val spaceCheckPath = if (isExternalStorage) rootfsParentDir else "/data"
            val freeGB = StorageChecker.getFreeSpaceGB(spaceCheckPath)
            if (freeGB != null) {
                logger.i("$spaceCheckPath has ${freeGB}GB free space")
                val requiredGB = if (config.useSparseImage) {
                    (config.sparseImageSizeGB ?: 8) + Constants.MIN_STORAGE_GB
                } else {
                    Constants.MIN_STORAGE_GB
                }
                if (freeGB < requiredGB) {
                    logger.w("Warning: Less than ${requiredGB}GB available. Installation may fail.")
                }
            } else {
                logger.w("Warning: Unable to determine free space. Proceeding anyway...")
            }

            // Step 2.1: A sparse rootfs.img is one big file. FAT32 caps any single
            // file at 4 GiB - 1 byte, so a "4GB" image (4294967296 bytes) is
            // already 1 byte over the line -- mkfs.ext4/mke2fs then fails (or,
            // worse, silently truncates) while writing the last block group's
            // superblock backups near the end of the file, since the file can
            // never actually reach its requested size. exFAT and ext4 have no
            // such limit. Catch it up front with a clear message instead of a
            // confusing low-level mkfs failure deep into the install.
            if (isExternalStorage && config.useSparseImage) {
                val fsTypeResult = RootNamespace.exec(
                    "stat -f -c '%T' \"$rootfsParentDir\" 2>/dev/null",
                    targetPath = rootfsParentDir
                )
                val destFsType = fsTypeResult.out.firstOrNull()?.trim()?.lowercase()
                val isFat32 = destFsType == "msdos" || destFsType == "vfat" || destFsType == "fat"
                val requestedSizeGB = config.sparseImageSizeGB ?: 8
                if (isFat32 && requestedSizeGB >= 4) {
                    throw Exception(
                        "Destination is FAT32, which can't hold a single file of 4GB or more " +
                        "(the sparse image would be ${requestedSizeGB}GB). Reformat the drive as " +
                        "exFAT or ext4, or choose a sparse image size under 4GB."
                    )
                }
            }

            // Step 3: Copy tarball to temp location
            logger.i("Copying tarball to temporary location...")
            val tarballExtension = getTarballExtension(context, tarballUri)
            val tempTarball = File("${context.cacheDir}/container_${sanitizedName}.tar$tarballExtension")
            context.contentResolver.openInputStream(tarballUri)?.use { inputStream ->
                FileOutputStream(tempTarball).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw Exception("Failed to open tarball input stream")

            logger.i("Tarball copied: ${tempTarball.absolutePath}")

            // Step 3.5: Verify the tarball is actually a Linux rootfs before we
            // extract anything, so users can't install arbitrary archives.
            validateRootfsTarball(context, tempTarball, logger)

            // Step 4: Extract tarball (either to directory or sparse image)
            if (config.useSparseImage) {
                SparseImageInstaller.extract(
                    context = context,
                    tarball = tempTarball,
                    imgPath = rootfsPath,
                    mountPoint = "${containerPath}/rootfs",
                    sizeGB = config.sparseImageSizeGB ?: 8,
                    configContent = config.toConfigContent(),
                    logger = logger
                )
            } else {
                // Create rootfs subdirectory
                val mkdirRootfsResult = RootNamespace.exec("mkdir -p \"$rootfsPath\" 2>&1", targetPath = rootfsPath)
                if (!mkdirRootfsResult.isSuccess) {
                    val errorOutput = (mkdirRootfsResult.out + mkdirRootfsResult.err).joinToString("\n").trim()
                    val errorMsg = if (errorOutput.isNotEmpty()) errorOutput else "Unknown error (exit code: ${mkdirRootfsResult.code})"
                    throw Exception("Failed to create rootfs directory: $errorMsg")
                }

                logger.i("Extracting tarball to $rootfsPath...")
                val isXz = tempTarball.name.lowercase().endsWith(".xz")
                val extractCmd = if (isXz) {
                    "cd \"$rootfsPath\" && $BUSYBOX_PATH xzcat \"${tempTarball.absolutePath}\" | $BUSYBOX_PATH tar -xpf - 2>&1"
                } else {
                    "cd \"$rootfsPath\" && $BUSYBOX_PATH tar -xzpf \"${tempTarball.absolutePath}\" 2>&1"
                }

                val extractResult = RootNamespace.exec(extractCmd, targetPath = rootfsPath)
                if (!extractResult.isSuccess) {
                    val errorMsg = (extractResult.out + extractResult.err).joinToString("\n")
                    logger.e("Extraction failed: $errorMsg")
                    throw Exception("Failed to extract tarball: $errorMsg")
                }

                logger.i("Tarball extracted successfully")

                // Apply post-extraction fixes
                applyPostExtractionFixes(context, rootfsPath, logger)
            }

            // Step 5: Write container config
            logger.i("Writing container configuration...")
            val effectiveRootfsPath = if (isExternalStorage && config.useSparseImage) {
                StorageMountManager.resolveToPhysicalPath(rootfsPath)
            } else {
                rootfsPath
            }
            val effectiveConfig = config.copy(rootfsPath = effectiveRootfsPath)
            val configContent = effectiveConfig.toConfigContent()

            // Write config to temp file first (app can write to cache dir)
            val tempConfigFile = File("${context.cacheDir}/container_${sanitizedName}.config")
            tempConfigFile.writeText(configContent)

            // Copy temp config to final location using shell (root required)
            val copyResult = Shell.cmd("cp \"${tempConfigFile.absolutePath}\" \"$configFilePath\" 2>&1").exec()
            if (!copyResult.isSuccess) {
                val errorOutput = (copyResult.out + copyResult.err).joinToString("\n").trim()
                val errorMsg = if (errorOutput.isNotEmpty()) errorOutput else "Unknown error (exit code: ${copyResult.code})"
                logger.e("Failed to copy config: $errorMsg")
                logger.e("Source: ${tempConfigFile.absolutePath}")
                logger.e("Destination: $configFilePath")
                throw Exception("Failed to write container config: $errorMsg")
            }

            // Set proper permissions
            val chmodResult = Shell.cmd("chmod 644 \"$configFilePath\" 2>&1").exec()
            if (!chmodResult.isSuccess) {
                logger.w("Warning: Failed to set config file permissions")
            }

            // Clean up temp config file
            tempConfigFile.delete()

            logger.i("Container configuration saved")
            createdPaths.add(configFilePath)

            // Step 5.1: Write .env file if content exists
            if (!config.envFileContent.isNullOrBlank()) {
                logger.i("Writing environment variables (.env)...")
                val envFilePath = "$containerPath/.env"
                val tempEnvFile = File("${context.cacheDir}/.env_${sanitizedName}")

                try {
                    tempEnvFile.writeText(config.envFileContent + "\n")

                    val envCopyResult = Shell.cmd("cp \"${tempEnvFile.absolutePath}\" \"$envFilePath\" 2>&1").exec()
                    if (!envCopyResult.isSuccess) {
                        val errorMsg = envCopyResult.err.joinToString("\n")
                        logger.w("Warning: Failed to copy .env file: $errorMsg")
                    } else {
                        Shell.cmd("chmod 644 \"$envFilePath\"").exec()
                        logger.i("Environment variables saved")
                        createdPaths.add(envFilePath)
                    }
                } catch (e: Exception) {
                    logger.w("Warning: Failed to write environment variables: ${e.message}")
                } finally {
                    tempEnvFile.delete()
                }
            }

            // Step 6: Verify installation
            logger.i("Verifying installation...")
            if (config.useSparseImage) {
                val imgExists = RootNamespace.exec("test -f \"$rootfsPath\" && echo 'exists' || echo 'not_found'", targetPath = rootfsPath)
                if (!imgExists.isSuccess || !imgExists.out.any { it.contains("exists") }) {
                    throw Exception("Container sparse image not found after extraction")
                }
            } else {
                val rootfsExists = RootNamespace.exec("test -d \"$rootfsPath\" && echo 'exists' || echo 'not_found'", targetPath = rootfsPath)
                if (!rootfsExists.isSuccess || !rootfsExists.out.any { it.contains("exists") }) {
                    throw Exception("Container rootfs directory not found after extraction")
                }
            }

            logger.i("Container installed successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Installation failed: ${e.message}")
            logger.e(e.stackTraceToString())

            // Cleanup on failure
            logger.i("Cleaning up created files...")
            cleanup(createdPaths, logger)

            Result.failure(e)
        } finally {
            // Clean up temp tarball
            try {
                File("${context.cacheDir}/container_${sanitizedName}.tar.xz").delete()
                File("${context.cacheDir}/container_${sanitizedName}.tar.gz").delete()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Get the tarball extension (.xz or .gz) from the URI.
     * Uses FilePickerUtils.getFileName() to reliably get the filename even for recent files.
     */
    private suspend fun getTarballExtension(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        // First, try to get the filename using FilePickerUtils (handles content URIs)
        val fileName = FilePickerUtils.getFileName(context, uri)

        if (fileName != null) {
            val fileNameLower = fileName.lowercase()
            return@withContext when {
                fileNameLower.endsWith(".tar.xz") -> ".xz"
                fileNameLower.endsWith(".tar.gz") -> ".gz"
                else -> ".gz"
            }
        }

        // Fallback: Check URI string directly (for file:// URIs)
        val uriString = uri.toString().lowercase()
        when {
            uriString.endsWith(".tar.xz") -> ".xz"
            uriString.endsWith(".tar.gz") -> ".gz"
            else -> ".gz"
        }
    }

    /**
     * Inspect the tarball (without extracting) to confirm it contains a Linux
     * rootfs, so users can't install arbitrary archives.
     */
    private suspend fun validateRootfsTarball(
        context: Context,
        tarball: File,
        logger: ContainerLogger
    ) {
        logger.i("Inspecting tarball to verify it is a Linux rootfs...")

        val scriptFile = File("${context.cacheDir}/validate_rootfs.sh")
        try {
            context.assets.open("validate_rootfs.sh").use { inputStream ->
                FileOutputStream(scriptFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            // Fail CLOSED: if the validator itself can't be loaded, do not install an
            // unverified rootfs — it is later run as root. See FINDINGS_APP_VULN V12.
            logger.e("Failed to load rootfs validator: ${e.message}")
            throw Exception("Could not verify rootfs: validator unavailable (${e.message})")
        }

        try {
            val chmodResult = Shell.cmd("chmod 755 \"${scriptFile.absolutePath}\" 2>&1").exec()
            if (!chmodResult.isSuccess) {
                logger.e("Failed to make rootfs validator executable")
                throw Exception("Could not verify rootfs: validator not executable")
            }

            val result = Shell.cmd(
                "BUSYBOX_PATH=$BUSYBOX_PATH sh \"${scriptFile.absolutePath}\" \"${tarball.absolutePath}\" 2>&1"
            ).exec()

            if (!result.isSuccess) {
                val reason = result.out
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: "selected file does not look like a Linux rootfs"
                logger.e(reason)
                throw Exception(reason)
            }
        } finally {
            try {
                scriptFile.delete()
            } catch (e: Exception) {
                logger.w("Warning: Failed to clean up validator script: ${e.message}")
            }
        }
    }

    /**
     * Apply post-extraction fixes to the rootfs (directory mode).
     */
    private suspend fun applyPostExtractionFixes(
        context: Context,
        rootfsPath: String,
        logger: ContainerLogger
    ) {
        logger.i("Applying post-extraction fixes...")

        // Copy post-extraction fix script from assets
        val postFixScriptFile = File("${context.cacheDir}/post_extract_fixes.sh")
        try {
            context.assets.open("post_extract_fixes.sh").use { inputStream ->
                FileOutputStream(postFixScriptFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            logger.w("Warning: Failed to load post_extract_fixes.sh from assets: ${e.message}")
            return
        }

        // Make script executable
        val chmodResult = Shell.cmd("chmod 755 \"${postFixScriptFile.absolutePath}\" 2>&1").exec()
        if (!chmodResult.isSuccess) {
            logger.w("Warning: Failed to make post-fix script executable")
            postFixScriptFile.delete()
            return
        }

        try {
            // Execute the script
            val result = RootNamespace.exec(
                "BUSYBOX_PATH=$BUSYBOX_PATH sh \"${postFixScriptFile.absolutePath}\" \"$rootfsPath\" 2>&1",
                targetPath = rootfsPath
            )

            // Log all output from the script
            result.out.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    when {
                        trimmed.startsWith("[POST-FIX-WARN]") -> logger.w(trimmed)
                        trimmed.startsWith("[POST-FIX]") -> logger.i(trimmed)
                        else -> logger.d(trimmed)
                    }
                }
            }

            // Log errors
            result.err.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    logger.w(trimmed)
                }
            }

            if (!result.isSuccess) {
                logger.w("Warning: Post-extraction fixes failed, but continuing installation")
            } else {
                logger.i("Post-extraction fixes completed successfully")
            }
        } finally {
            // Clean up script file
            try {
                postFixScriptFile.delete()
            } catch (e: Exception) {
                logger.w("Warning: Failed to clean up post-fix script file: ${e.message}")
            }
        }
    }

    private suspend fun cleanup(paths: List<String>, logger: ContainerLogger) {
        paths.reversed().forEach { path ->
            try {
                val result = RootNamespace.exec(
                    "rm -rf ${ContainerCommandBuilder.quote(path)} 2>&1",
                    targetPath = path
                )
                if (result.isSuccess) {
                    logger.d("Cleaned up: $path")
                } else {
                    logger.w("Failed to clean up: $path")
                }
            } catch (e: Exception) {
                logger.w("Error cleaning up $path: ${e.message}")
            }
        }
    }

    /**
     * Install / import a container from an existing sparse ext4 rootfs image.
     * Sets up container metadata on internal storage, verifies or copies the image,
     * applies host permissions, and syncs the embedded configuration inside the image.
     */
    suspend fun installFromExistingImage(
        context: Context,
        config: ContainerInfo,
        existingImagePath: String,
        logger: ContainerLogger
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val sanitizedName = ContainerManager.sanitizeContainerName(config.name)
        val containerPath = ContainerManager.getContainerDirectory(config.name)
        val rootfsPath = config.rootfsPath
        val rootfsParentDir = rootfsPath.substringBeforeLast("/", containerPath)
        val isExternalStorage = rootfsParentDir != containerPath
        val configFilePath = "$containerPath/${Constants.CONTAINER_CONFIG_FILE}"
        val createdPaths = mutableListOf<String>()

        try {
            logger.i("Importing container from existing image...")
            logger.i("Container: ${config.name}")
            logger.i("Source Image: $existingImagePath")
            logger.i("Target Rootfs: $rootfsPath")

            // Reject control chars in config
            ValidationUtils.validateConfigValues(config).errorMessage?.let {
                logger.e(it)
                return@withContext Result.failure(Exception(it))
            }

            // Step 1: Create container directory for metadata
            logger.i("Creating container directory: $containerPath")
            val mkdirResult = Shell.cmd("mkdir -p \"$containerPath\" 2>&1").exec()
            if (!mkdirResult.isSuccess) {
                val err = (mkdirResult.out + mkdirResult.err).joinToString("\n").trim()
                throw Exception("Failed to create container directory: $err")
            }
            createdPaths.add(containerPath)

            // Step 1.1: Ensure target parent directory exists if external
            if (isExternalStorage) {
                StorageMountManager.ensureVolumeMounted(rootfsParentDir)
                logger.i("Creating external storage directory: $rootfsParentDir")
                val mkdirExtResult = RootNamespace.exec("mkdir -p \"$rootfsParentDir\" 2>&1", targetPath = rootfsParentDir)
                if (!mkdirExtResult.isSuccess) {
                    val err = (mkdirExtResult.out + mkdirExtResult.err).joinToString("\n").trim()
                    throw Exception("Failed to create external storage directory: $err")
                }
                createdPaths.add(rootfsParentDir)
            }

            // Step 2: Handle Image placement
            val isSamePath = existingImagePath.trim() == rootfsPath.trim()
            if (!isSamePath) {
                logger.i("Copying image to target storage location...")
                val qSrc = ContainerCommandBuilder.quote(existingImagePath)
                val qDst = ContainerCommandBuilder.quote(rootfsPath)
                val copyResult = RootNamespace.exec(
                    "cp -a $qSrc $qDst 2>&1",
                    forceNsenter = isExternalStorage || RootNamespace.isExternalStorage(existingImagePath)
                )
                if (!copyResult.isSuccess) {
                    val err = (copyResult.out + copyResult.err).joinToString("\n").trim()
                    throw Exception("Failed to copy image to $rootfsPath: $err")
                }
                createdPaths.add(rootfsPath)
            } else {
                logger.i("Using existing image in-place at $rootfsPath")
            }

            // Step 3: Set permissions and SELinux context
            val qRootfs = ContainerCommandBuilder.quote(rootfsPath)
            RootNamespace.exec("chmod 644 $qRootfs 2>&1", targetPath = rootfsPath)
            if (rootfsPath.startsWith("/data/")) {
                RootNamespace.exec("chcon u:object_r:vold_data_file:s0 $qRootfs 2>/dev/null", targetPath = rootfsPath)
            }

            // Step 4: Verify filesystem integrity
            logger.i("Verifying image filesystem integrity (e2fsck)...")
            val checkResult = RootNamespace.exec("e2fsck -fy $qRootfs", targetPath = rootfsPath)
            if (checkResult.code >= 4) {
                val err = (checkResult.out + checkResult.err).joinToString("\n").trim()
                throw Exception("Filesystem check failed (e2fsck error ${checkResult.code}): $err")
            }
            logger.i("Filesystem verified.")

            // Step 5: Write container config on host
            logger.i("Writing container configuration...")
            val effectiveRootfsPath = if (isExternalStorage && config.useSparseImage) {
                StorageMountManager.resolveToPhysicalPath(rootfsPath)
            } else {
                rootfsPath
            }
            val effectiveConfig = config.copy(rootfsPath = effectiveRootfsPath)
            val configContent = effectiveConfig.toConfigContent()
            val tempConfigFile = File("${context.cacheDir}/container_${sanitizedName}.config")
            tempConfigFile.writeText(configContent)

            val copyConfigRes = Shell.cmd("cp \"${tempConfigFile.absolutePath}\" \"$configFilePath\" 2>&1").exec()
            tempConfigFile.delete()
            if (!copyConfigRes.isSuccess) {
                val err = (copyConfigRes.out + copyConfigRes.err).joinToString("\n").trim()
                throw Exception("Failed to save container config: $err")
            }
            Shell.cmd("chmod 644 \"$configFilePath\" 2>&1").exec()
            createdPaths.add(configFilePath)

            // Step 5.1: Write .env file if present
            if (!config.envFileContent.isNullOrBlank()) {
                val envFilePath = "$containerPath/.env"
                val tempEnvFile = File("${context.cacheDir}/.env_${sanitizedName}")
                try {
                    tempEnvFile.writeText(config.envFileContent + "\n")
                    Shell.cmd("cp \"${tempEnvFile.absolutePath}\" \"$envFilePath\" 2>&1").exec()
                    Shell.cmd("chmod 644 \"$envFilePath\"").exec()
                    createdPaths.add(envFilePath)
                } finally {
                    tempEnvFile.delete()
                }
            }

            // Step 5.2: Refresh embedded config inside the image
            logger.i("Syncing embedded configuration inside image for portability...")
            ExistingImageManager.embedConfig(rootfsPath, configContent, context)

            // Step 6: Verify installation
            val imgExists = RootNamespace.exec("test -f $qRootfs && echo 'exists' || echo 'not_found'", targetPath = rootfsPath)
            if (!imgExists.isSuccess || !imgExists.out.any { it.contains("exists") }) {
                throw Exception("Container rootfs image not found after import")
            }

            logger.i("Container imported and configured successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Import failed: ${e.message}")
            logger.e(e.stackTraceToString())
            cleanup(createdPaths, logger)
            Result.failure(e)
        }
    }
}
