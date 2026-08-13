package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detailed capability report for a storage path/filesystem.
 */
data class FilesystemCapabilityReport(
    val path: String,
    val fsType: String = "unknown",
    val isLinuxNativeFs: Boolean = false,
    val supportsChmodChown: Boolean = false,
    val supportsSetuid: Boolean = false,
    val supportsSymlinks: Boolean = false,
    val supportsHardlinks: Boolean = false,
    val supportsFifo: Boolean = false,
    val supportsDeviceNodes: Boolean = false,
    val isNoExec: Boolean = false,
    val isNodev: Boolean = false,
    val isReadOnly: Boolean = false,
    val isExternalStorage: Boolean = false,
    val errors: List<String> = emptyList()
) {
    /**
     * Directory mode requires:
     * - Linux native filesystem (not vfat/exfat/ntfs/sdcardfs/fuse)
     * - Full chmod/chown support (POSIX DAC)
     * - Symlink creation
     * - Not mounted noexec or ro
     */
    val isFullyCompatibleWithDirectoryMode: Boolean
        get() = isLinuxNativeFs && supportsChmodChown && supportsSymlinks && !isNoExec && !isReadOnly && errors.isEmpty()
}

object StorageChecker {
    private const val BUSYBOX_PATH = Constants.BUSYBOX_BINARY_PATH

    /**
     * Normalizes a storage directory path, ensuring leading slash and clean structure.
     */
    fun normalizeStorageDir(path: String): String {
        return path.trim().trimEnd('/')
    }

    /**
     * Check available space at the given path's mount point.
     *
     * Ensures volume is synchronized via [StorageMountManager] and checks against the
     * canonical physical mount point to avoid querying Android init's phantom 3GB tmpfs.
     * Returns free space in GB, or null if unable to determine.
     */
    suspend fun getFreeSpaceGB(path: String = "/data"): Int? = withContext(Dispatchers.IO) {
        try {
            val normPath = path.trim()
            val isExternal = RootNamespace.isExternalStorage(normPath)

            if (isExternal) {
                // Ensure the external volume is bind-mounted in root's namespace
                StorageMountManager.ensureVolumeMounted(normPath)
            }

            // Check using physical path first, then original path
            val physicalPath = if (isExternal) StorageMountManager.resolveToPhysicalPath(normPath) else normPath
            val pathsToCheck = if (physicalPath != normPath) listOf(physicalPath, normPath) else listOf(normPath)

            for (target in pathsToCheck) {
                val quoted = ContainerCommandBuilder.quote(target)

                // Try stat -f first
                val statResult = Shell.cmd("stat -f -c '%a %S' $quoted 2>/dev/null || $BUSYBOX_PATH stat -f -c '%a %S' $quoted 2>/dev/null").exec()
                if (statResult.isSuccess && statResult.out.isNotEmpty()) {
                    val parts = statResult.out[0].trim().split(" ")
                    if (parts.size == 2) {
                        val availBlocks = parts[0].toLongOrNull()
                        val blockSize = parts[1].toLongOrNull()
                        if (availBlocks != null && blockSize != null && blockSize > 0) {
                            val freeGB = (availBlocks * blockSize / 1024 / 1024 / 1024).toInt()
                            return@withContext freeGB
                        }
                    }
                }

                // Fallback: busybox df
                val dfResult = Shell.cmd(
                    "$BUSYBOX_PATH df -k $quoted 2>/dev/null | $BUSYBOX_PATH tail -n1 | $BUSYBOX_PATH awk '{print \$4}'"
                ).exec()
                if (dfResult.isSuccess && dfResult.out.isNotEmpty()) {
                    val freeKB = dfResult.out[0].trim().toLongOrNull()
                    if (freeKB != null && freeKB > 0) {
                        val freeGB = (freeKB / 1024 / 1024).toInt()
                        return@withContext freeGB
                    }
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the primary shared storage path (usually /storage/emulated/0).
     */
    suspend fun getSharedStoragePath(): String? = withContext(Dispatchers.IO) {
        val result = RootNamespace.exec("[ -d /storage/emulated/0 ] && echo /storage/emulated/0", forceNsenter = true)
        if (result.isSuccess && result.out.isNotEmpty()) {
            result.out[0].trim()
        } else {
            null
        }
    }

    /**
     * Verify if a path exists and is writable by root.
     */
    suspend fun validateWritablePath(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normPath = path.trim()
            if (RootNamespace.isExternalStorage(normPath)) {
                StorageMountManager.ensureVolumeMounted(normPath)
            }

            val quotedPath = ContainerCommandBuilder.quote(normPath)
            val mkdirResult = RootNamespace.exec("mkdir -p $quotedPath 2>&1", targetPath = normPath)
            if (!mkdirResult.isSuccess) {
                val err = (mkdirResult.out + mkdirResult.err).joinToString("\n").trim()
                val reason = if (err.isNotEmpty()) err else "Could not create or access directory"
                return@withContext Result.failure(Exception(reason))
            }
            val testFile = "${normPath.trimEnd('/')}/.ds_write_test_${System.currentTimeMillis()}"
            val quotedTestFile = ContainerCommandBuilder.quote(testFile)
            val touchResult = RootNamespace.exec("touch $quotedTestFile && rm -f $quotedTestFile 2>&1", targetPath = normPath)
            if (touchResult.isSuccess) {
                Result.success(Unit)
            } else {
                val err = (touchResult.out + touchResult.err).joinToString("\n").trim()
                val reason = if (err.isNotEmpty()) err else "Path is not writable (permission denied)"
                Result.failure(Exception(reason))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if sufficient space is available (default 4GB minimum) at the given path.
     */
    suspend fun hasSufficientSpace(requiredGB: Int = Constants.MIN_STORAGE_GB, path: String = "/data"): Boolean? = withContext(Dispatchers.IO) {
        val freeGB = getFreeSpaceGB(path) ?: return@withContext null
        freeGB >= requiredGB
    }

    /**
     * List mounted external storage volumes under /storage.
     */
    suspend fun listStorageVolumes(): List<String> = withContext(Dispatchers.IO) {
        try {
            StorageMountManager.listAllStorageVolumes()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Comprehensive filesystem capability inspection for directory-based containers.
     * Tests filesystem type, POSIX chmod/chown, setuid, symlinks, hardlinks,
     * FIFOs, device nodes, and mount options.
     */
    suspend fun inspectFilesystemCapabilities(path: String, context: Context): FilesystemCapabilityReport = withContext(Dispatchers.IO) {
        val normPath = path.trim()
        val isExternal = RootNamespace.isExternalStorage(normPath)

        if (isExternal) {
            StorageMountManager.ensureVolumeMounted(normPath)
        }

        val physicalPath = if (isExternal) StorageMountManager.resolveToPhysicalPath(normPath) else normPath
        val qPath = ContainerCommandBuilder.quote(physicalPath)
        val errorList = mutableListOf<String>()

        try {
            // Ensure target directory exists on physical volume
            Shell.cmd("mkdir -p $qPath 2>/dev/null").exec()

            val script = buildString {
                appendLine("#!/system/bin/sh")
                appendLine("BUSYBOX=\"${Constants.BUSYBOX_BINARY_PATH}\"")
                appendLine("TARGET=$qPath")
                appendLine("TEST_DIR=\"\$TARGET/.ds_compat_test_\$\$\"")
                appendLine()
                appendLine("mkdir -p \"\$TEST_DIR\" 2>/dev/null || \"\$BUSYBOX\" mkdir -p \"\$TEST_DIR\" 2>/dev/null || true")
                appendLine()
                appendLine("cleanup() {")
                appendLine("    rm -rf \"\$TEST_DIR\" 2>/dev/null || \"\$BUSYBOX\" rm -rf \"\$TEST_DIR\" 2>/dev/null || true")
                appendLine("}")
                appendLine("trap cleanup EXIT")
                appendLine()
                appendLine("# 1. Get filesystem type")
                appendLine("FS_TYPE=\$(stat -f -c %T \"\$TARGET\" 2>/dev/null || \"\$BUSYBOX\" stat -f -c %T \"\$TARGET\" 2>/dev/null || echo 'unknown')")
                appendLine("echo \"FS_TYPE=\$FS_TYPE\"")
                appendLine()
                appendLine("# 2. Mount options")
                appendLine("MOUNT_OPTS=\$(grep -F \" \$TARGET \" /proc/mounts 2>/dev/null | awk '{print \$4}' || echo '')")
                appendLine("if [ -z \"\$MOUNT_OPTS\" ]; then")
                appendLine("    MOUNT_OPTS=\$(\"\$BUSYBOX\" grep -F \" \$TARGET \" /proc/mounts 2>/dev/null | awk '{print \$4}' || echo '')")
                appendLine("fi")
                appendLine("echo \"MOUNT_OPTS=\$MOUNT_OPTS\"")
                appendLine()
                appendLine("# 3. Test POSIX permissions (chmod)")
                appendLine("CHMOD_OK=0")
                appendLine("echo test > \"\$TEST_DIR/perm_test\" 2>/dev/null || true")
                appendLine("if chmod 700 \"\$TEST_DIR/perm_test\" 2>/dev/null; then")
                appendLine("    PERM=\$(stat -c %a \"\$TEST_DIR/perm_test\" 2>/dev/null || echo '')")
                appendLine("    if [ \"\$PERM\" = \"700\" ]; then")
                appendLine("        CHMOD_OK=1")
                appendLine("    fi")
                appendLine("fi")
                appendLine("echo \"CHMOD_OK=\$CHMOD_OK\"")
                appendLine()
                appendLine("# 4. Test setuid")
                appendLine("SETUID_OK=0")
                appendLine("if chmod 4755 \"\$TEST_DIR/perm_test\" 2>/dev/null; then")
                appendLine("    PERM=\$(stat -c %a \"\$TEST_DIR/perm_test\" 2>/dev/null || echo '')")
                appendLine("    if [ \"\$PERM\" = \"4755\" ]; then")
                appendLine("        SETUID_OK=1")
                appendLine("    fi")
                appendLine("fi")
                appendLine("echo \"SETUID_OK=\$SETUID_OK\"")
                appendLine()
                appendLine("# 5. Test Symlinks")
                appendLine("SYMLINK_OK=0")
                appendLine("if ln -s \"perm_test\" \"\$TEST_DIR/symlink_test\" 2>/dev/null; then")
                appendLine("    if [ -L \"\$TEST_DIR/symlink_test\" ]; then")
                appendLine("        SYMLINK_OK=1")
                appendLine("    fi")
                appendLine("fi")
                appendLine("echo \"SYMLINK_OK=\$SYMLINK_OK\"")
                appendLine()
                appendLine("# 6. Test Hardlinks")
                appendLine("HARDLINK_OK=0")
                appendLine("if ln \"\$TEST_DIR/perm_test\" \"\$TEST_DIR/hardlink_test\" 2>/dev/null; then")
                appendLine("    HARDLINK_OK=1")
                appendLine("fi")
                appendLine("echo \"HARDLINK_OK=\$HARDLINK_OK\"")
                appendLine()
                appendLine("# 7. Test FIFO (named pipes)")
                appendLine("FIFO_OK=0")
                appendLine("if mkfifo \"\$TEST_DIR/fifo_test\" 2>/dev/null || \"\$BUSYBOX\" mkfifo \"\$TEST_DIR/fifo_test\" 2>/dev/null; then")
                appendLine("    if [ -p \"\$TEST_DIR/fifo_test\" ]; then")
                appendLine("        FIFO_OK=1")
                appendLine("    fi")
                appendLine("fi")
                appendLine("echo \"FIFO_OK=\$FIFO_OK\"")
                appendLine()
                appendLine("# 8. Test device nodes (mknod)")
                appendLine("MKNOD_OK=0")
                appendLine("if mknod \"\$TEST_DIR/null_test\" c 1 3 2>/dev/null || \"\$BUSYBOX\" mknod \"\$TEST_DIR/null_test\" c 1 3 2>/dev/null; then")
                appendLine("    if [ -c \"\$TEST_DIR/null_test\" ]; then")
                appendLine("        MKNOD_OK=1")
                appendLine("    fi")
                appendLine("fi")
                appendLine("echo \"MKNOD_OK=\$MKNOD_OK\"")
                appendLine()
                appendLine("exit 0")
            }

            val scriptRes = RootNamespace.runScript(script, context, targetPath = normPath)

            var rawFsType = "unknown"
            var rawMountOpts = ""
            var chmodOk = false
            var setuidOk = false
            var symlinkOk = false
            var hardlinkOk = false
            var fifoOk = false
            var mknodOk = false

            scriptRes.out.forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("FS_TYPE=") -> rawFsType = trimmed.removePrefix("FS_TYPE=").trim()
                    trimmed.startsWith("MOUNT_OPTS=") -> rawMountOpts = trimmed.removePrefix("MOUNT_OPTS=").trim()
                    trimmed == "CHMOD_OK=1" -> chmodOk = true
                    trimmed == "SETUID_OK=1" -> setuidOk = true
                    trimmed == "SYMLINK_OK=1" -> symlinkOk = true
                    trimmed == "HARDLINK_OK=1" -> hardlinkOk = true
                    trimmed == "FIFO_OK=1" -> fifoOk = true
                    trimmed == "MKNOD_OK=1" -> mknodOk = true
                }
            }

            val lowerFs = rawFsType.lowercase()
            val isNative = lowerFs.contains("ext4") || lowerFs.contains("f2fs") || lowerFs.contains("btrfs") ||
                    lowerFs.contains("xfs") || lowerFs.contains("zfs")

            val isNoExec = rawMountOpts.contains("noexec")
            val isNodev = rawMountOpts.contains("nodev")
            val isRo = rawMountOpts.contains("ro") && !rawMountOpts.contains("rw")

            if (!isNative) {
                errorList.add("Filesystem type is '$rawFsType'. Non-native filesystems (FAT32/exFAT/NTFS) do not support POSIX container semantics.")
            }
            if (!chmodOk) {
                errorList.add("POSIX permission control (chmod/chown) is not supported.")
            }
            if (!symlinkOk) {
                errorList.add("Symbolic links (symlinks) are not supported on this volume.")
            }
            if (isNoExec) {
                errorList.add("Volume is mounted with 'noexec' flag (binary execution will be blocked).")
            }
            if (isRo) {
                errorList.add("Volume is mounted read-only.")
            }

            FilesystemCapabilityReport(
                path = normPath,
                fsType = rawFsType,
                isLinuxNativeFs = isNative,
                supportsChmodChown = chmodOk,
                supportsSetuid = setuidOk,
                supportsSymlinks = symlinkOk,
                supportsHardlinks = hardlinkOk,
                supportsFifo = fifoOk,
                supportsDeviceNodes = mknodOk,
                isNoExec = isNoExec,
                isNodev = isNodev,
                isReadOnly = isRo,
                isExternalStorage = isExternal,
                errors = errorList
            )
        } catch (e: Exception) {
            FilesystemCapabilityReport(
                path = normPath,
                isExternalStorage = isExternal,
                errors = listOf("Failed to inspect filesystem: ${e.message}")
            )
        }
    }
}
