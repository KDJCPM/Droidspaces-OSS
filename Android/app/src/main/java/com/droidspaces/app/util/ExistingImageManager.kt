package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Result of inspecting an existing sparse rootfs image (.img).
 */
data class ImageInspectionResult(
    val path: String,
    val sizeGB: Int = 0,
    val actualSizeBytes: Long = 0L,
    val isExt4: Boolean = false,
    val embeddedConfig: ContainerInfo? = null,
    val rawEmbeddedConfig: String? = null,
    val osName: String? = null,
    val isValid: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Manages operations on existing sparse rootfs images:
 * - Read-only inspection of filesystem integrity, size, OS release, and embedded config.
 * - Embedding or refreshing self-describing container configuration inside the image.
 *
 * Direct physical mount operations are executed in the global root mount namespace
 * on raw kernel mount paths (/mnt/media_rw/<volId>/...) using /data/local/tmp or /mnt/
 * mountpoints to avoid Android SELinux app_data_file mounton blocks and FUSE loop blocks.
 */
object ExistingImageManager {

    /**
     * Inspects an existing .img file:
     * - Validates ext4 filesystem integrity using `e2fsck -fy`.
     * - Determines total image size and allocated disk space.
     * - Mounts the image read-only to check for embedded configuration at the
     *   single canonical location, [Constants.EMBEDDED_CONFIG_RELATIVE_PATH]
     *   (`/etc/droidspaces/container.config` once mounted), and OS release info.
     */
    suspend fun inspect(imgPath: String, context: Context): ImageInspectionResult = withContext(Dispatchers.IO) {
        val busybox = Constants.BUSYBOX_BINARY_PATH
        val normalizedPath = SafPathResolver.normalizePath(imgPath)

        try {
            StorageMountManager.cleanupStaleMountsAndLoopDevices()
            if (RootNamespace.isExternalStorage(normalizedPath)) {
                StorageMountManager.ensureVolumeMounted(normalizedPath)
            }

            // Always resolve to physical mount path (e.g. /mnt/media_rw/FFF6-F07B/...) for loop/kernel operations
            val physicalPath = StorageMountManager.resolveToPhysicalPath(normalizedPath)
            val qPhys = ContainerCommandBuilder.quote(physicalPath)
            val qNorm = ContainerCommandBuilder.quote(normalizedPath)

            // Step 1: Check if file exists
            val existsRes = Shell.cmd("[ -f $qPhys ] && echo yes || [ -f $qNorm ] && echo yes || echo no").exec()
            if (existsRes.out.firstOrNull()?.trim() != "yes") {
                return@withContext ImageInspectionResult(
                    path = normalizedPath,
                    errorMessage = "File does not exist or is not accessible at $normalizedPath"
                )
            }

            // Target path to use for kernel operations (prefer physical to bypass FUSE)
            val targetKernelPath = if (Shell.cmd("[ -f $qPhys ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes") {
                physicalPath
            } else {
                normalizedPath
            }
            val qTarget = ContainerCommandBuilder.quote(targetKernelPath)

            // Step 2: Get image size in bytes
            val sizeRes = Shell.cmd("stat -c%s $qTarget 2>/dev/null || stat -f -c '%z' $qTarget 2>/dev/null || $busybox stat -c%s $qTarget 2>/dev/null").exec()
            val sizeBytes = sizeRes.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val sizeGB = ((sizeBytes + (1024L * 1024L * 1024L - 1L)) / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)

            // Step 3: Check ext4 filesystem integrity (non-fatal if errors are auto-corrected)
            val fsckRes = Shell.cmd("e2fsck -fy $qTarget").exec()
            if (fsckRes.code >= 4) {
                return@withContext ImageInspectionResult(
                    path = normalizedPath,
                    sizeGB = sizeGB,
                    actualSizeBytes = sizeBytes,
                    errorMessage = "Corrupted or invalid filesystem (e2fsck error ${fsckRes.code})"
                )
            }

            // Step 4: Mount read-only to inspect embedded files
            // Use /mnt/ or /data/local/tmp for mountpoint so SELinux allows mounton (denied on app_data_file)
            val tempMountPoint = "/mnt/ds_inspect_mnt_${System.currentTimeMillis()}"
            val scriptContent = buildInspectScript(
                busybox = busybox,
                primaryImgPath = targetKernelPath,
                fallbackImgPath = normalizedPath,
                mountPoint = tempMountPoint
            )

            val scriptFile = File("${context.cacheDir}/.ds_inspect_${System.currentTimeMillis()}.sh")
            val inspectRes = try {
                FileOutputStream(scriptFile).use { fos ->
                    fos.write(scriptContent.toByteArray())
                }
                Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(scriptFile.absolutePath)}").exec()
                // Execute via /system/bin/sh to avoid SELinux noexec denial on app-private cache files
                Shell.cmd("/system/bin/sh ${ContainerCommandBuilder.quote(scriptFile.absolutePath)}").exec()
            } finally {
                try { scriptFile.delete() } catch (e: Exception) {}
            }

            var isExt4 = false
            var embeddedConfigContent: String? = null
            var osName: String? = null
            val configLines = mutableListOf<String>()
            var capturingConfig = false
            var capturingOs = false

            inspectRes.out.forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed == "[INSPECT-MOUNT-OK]" -> isExt4 = true
                    trimmed == "---BEGIN_CONFIG---" -> capturingConfig = true
                    trimmed == "---END_CONFIG---" -> capturingConfig = false
                    trimmed == "---BEGIN_OS---" -> capturingOs = true
                    trimmed == "---END_OS---" -> capturingOs = false
                    capturingConfig -> configLines.add(line)
                    capturingOs && trimmed.isNotEmpty() -> {
                        if (osName == null) osName = trimmed
                    }
                }
            }

            if (configLines.isNotEmpty()) {
                embeddedConfigContent = configLines.joinToString("\n")
            }

            val parsedConfig = if (!embeddedConfigContent.isNullOrBlank()) {
                ContainerManager.parseConfig(embeddedConfigContent, "imported")
            } else null

            ImageInspectionResult(
                path = normalizedPath,
                sizeGB = sizeGB,
                actualSizeBytes = sizeBytes,
                isExt4 = isExt4,
                embeddedConfig = parsedConfig,
                rawEmbeddedConfig = embeddedConfigContent,
                osName = osName,
                isValid = isExt4,
                errorMessage = if (!isExt4) "Could not mount image as ext4 filesystem" else null
            )
        } catch (e: Exception) {
            ImageInspectionResult(
                path = normalizedPath,
                errorMessage = e.message ?: "Failed to inspect image"
            )
        }
    }

    /**
     * Mounts the image read-write and writes/refreshes the embedded container configuration.
     */
    suspend fun embedConfig(imgPath: String, configContent: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val busybox = Constants.BUSYBOX_BINARY_PATH
        val normalizedPath = SafPathResolver.normalizePath(imgPath)
        val physicalPath = StorageMountManager.resolveToPhysicalPath(normalizedPath)
        val tempConfigFile = File("${context.cacheDir}/.ds_embed_${System.currentTimeMillis()}.config")
        val tempMountPoint = "/mnt/ds_embed_mnt_${System.currentTimeMillis()}"
        val scriptFile = File("${context.cacheDir}/.ds_embed_script_${System.currentTimeMillis()}.sh")

        try {
            if (RootNamespace.isExternalStorage(normalizedPath)) {
                StorageMountManager.ensureVolumeMounted(normalizedPath)
            }

            FileOutputStream(tempConfigFile).use { fos ->
                fos.write(configContent.toByteArray())
            }

            val targetKernelPath = if (Shell.cmd("[ -f ${ContainerCommandBuilder.quote(physicalPath)} ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes") {
                physicalPath
            } else {
                normalizedPath
            }

            val scriptContent = buildEmbedScript(
                busybox = busybox,
                imgPath = targetKernelPath,
                mountPoint = tempMountPoint,
                configFilePath = tempConfigFile.absolutePath
            )

            FileOutputStream(scriptFile).use { fos ->
                fos.write(scriptContent.toByteArray())
            }
            Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(scriptFile.absolutePath)}").exec()
            // Execute via /system/bin/sh to avoid SELinux noexec denial on app-private cache files
            val result = Shell.cmd("/system/bin/sh ${ContainerCommandBuilder.quote(scriptFile.absolutePath)}").exec()

            if (result.isSuccess && result.out.any { it.contains("[EMBED-OK]") }) {
                Result.success(Unit)
            } else {
                val err = (result.out + result.err).joinToString("\n")
                Result.failure(Exception("Failed to embed configuration: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { tempConfigFile.delete() } catch (e: Exception) {}
            try { scriptFile.delete() } catch (e: Exception) {}
        }
    }

    private fun buildInspectScript(
        busybox: String,
        primaryImgPath: String,
        fallbackImgPath: String,
        mountPoint: String
    ): String = buildString {
        val qPri = ContainerCommandBuilder.quote(primaryImgPath)
        val qFall = ContainerCommandBuilder.quote(fallbackImgPath)
        val qMnt = ContainerCommandBuilder.quote(mountPoint)

        appendLine("#!/system/bin/sh")
        appendLine("BUSYBOX=\"$busybox\"")
        appendLine("IMG_PRI=$qPri")
        appendLine("IMG_FALL=$qFall")
        appendLine("MNT=$qMnt")
        appendLine("LOOP_DEV=\"\"")
        appendLine()
        appendLine("mkdir -p \"\$MNT\" 2>/dev/null || \"\$BUSYBOX\" mkdir -p \"\$MNT\" 2>/dev/null || true")
        appendLine()
        appendLine("cleanup() {")
        appendLine("    \"\$BUSYBOX\" umount -l \"\$MNT\" 2>/dev/null || umount -l \"\$MNT\" 2>/dev/null || true")
        appendLine("    if [ -n \"\$LOOP_DEV\" ]; then")
        appendLine("        \"\$BUSYBOX\" losetup -d \"\$LOOP_DEV\" 2>/dev/null || losetup -d \"\$LOOP_DEV\" 2>/dev/null || true")
        appendLine("    fi")
        appendLine("    \"\$BUSYBOX\" rmdir \"\$MNT\" 2>/dev/null || rmdir \"\$MNT\" 2>/dev/null || true")
        appendLine("}")
        appendLine("trap cleanup EXIT")
        appendLine()
        appendLine("MOUNT_OK=0")
        appendLine("for target in \"\$IMG_PRI\" \"\$IMG_FALL\"; do")
        appendLine("    [ -f \"\$target\" ] || continue")
        appendLine("    LOOP_DEV=\$(losetup -f 2>/dev/null || \"\$BUSYBOX\" losetup -f 2>/dev/null || true)")
        appendLine("    if [ -n \"\$LOOP_DEV\" ]; then")
        appendLine("        if losetup -r \"\$LOOP_DEV\" \"\$target\" 2>/dev/null || \"\$BUSYBOX\" losetup -r \"\$LOOP_DEV\" \"\$target\" 2>/dev/null || \\")
        appendLine("           losetup \"\$LOOP_DEV\" \"\$target\" 2>/dev/null || \"\$BUSYBOX\" losetup \"\$LOOP_DEV\" \"\$target\" 2>/dev/null; then")
        appendLine("            if mount -t ext4 -o ro,noload \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("               \"\$BUSYBOX\" mount -t ext4 -o ro,noload \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("               mount -t ext4 -o ro \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("               \"\$BUSYBOX\" mount -t ext4 -o ro \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null; then")
        appendLine("                MOUNT_OK=1")
        appendLine("                break")
        appendLine("            else")
        appendLine("                losetup -d \"\$LOOP_DEV\" 2>/dev/null || \"\$BUSYBOX\" losetup -d \"\$LOOP_DEV\" 2>/dev/null || true")
        appendLine("                LOOP_DEV=\"\"")
        appendLine("            fi")
        appendLine("        fi")
        appendLine("    fi")
        appendLine("    if [ \"\$MOUNT_OK\" -ne 1 ]; then")
        appendLine("        if mount -t ext4 -o loop,ro,noload \"\$target\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("           \"\$BUSYBOX\" mount -t ext4 -o loop,ro,noload \"\$target\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("           mount -t ext4 -o loop,ro \"\$target\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("           \"\$BUSYBOX\" mount -t ext4 -o loop,ro \"\$target\" \"\$MNT\" 2>/dev/null; then")
        appendLine("            MOUNT_OK=1")
        appendLine("            break")
        appendLine("        fi")
        appendLine("    fi")
        appendLine("done")
        appendLine()
        appendLine("if [ \"\$MOUNT_OK\" -ne 1 ]; then")
        appendLine("    echo \"[INSPECT-MOUNT-FAIL]\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine()
        appendLine("echo \"[INSPECT-MOUNT-OK]\"")
        appendLine()
        appendLine("# Embedded config")
        appendLine("if [ -f \"\$MNT/${Constants.EMBEDDED_CONFIG_RELATIVE_PATH}\" ]; then")
        appendLine("    echo \"---BEGIN_CONFIG---\"")
        appendLine("    cat \"\$MNT/${Constants.EMBEDDED_CONFIG_RELATIVE_PATH}\"")
        appendLine("    echo \"---END_CONFIG---\"")
        appendLine("fi")
        appendLine()
        appendLine("# Check for /etc/os-release or /usr/lib/os-release")
        appendLine("if [ -f \"\$MNT/etc/os-release\" ]; then")
        appendLine("    echo \"---BEGIN_OS---\"")
        appendLine("    grep \"^PRETTY_NAME=\" \"\$MNT/etc/os-release\" | cut -d'=' -f2 | tr -d '\"' || true")
        appendLine("    echo \"---END_OS---\"")
        appendLine("elif [ -f \"\$MNT/usr/lib/os-release\" ]; then")
        appendLine("    echo \"---BEGIN_OS---\"")
        appendLine("    grep \"^PRETTY_NAME=\" \"\$MNT/usr/lib/os-release\" | cut -d'=' -f2 | tr -d '\"' || true")
        appendLine("    echo \"---END_OS---\"")
        appendLine("fi")
        appendLine()
        appendLine("exit 0")
    }

    private fun buildEmbedScript(
        busybox: String,
        imgPath: String,
        mountPoint: String,
        configFilePath: String
    ): String = buildString {
        val qImg = ContainerCommandBuilder.quote(imgPath)
        val qMnt = ContainerCommandBuilder.quote(mountPoint)
        val qCfg = ContainerCommandBuilder.quote(configFilePath)

        appendLine("#!/system/bin/sh")
        appendLine("BUSYBOX=\"$busybox\"")
        appendLine("IMG=$qImg")
        appendLine("MNT=$qMnt")
        appendLine("CFG=$qCfg")
        appendLine("LOOP_DEV=\"\"")
        appendLine()
        appendLine("mkdir -p \"\$MNT\" 2>/dev/null || \"\$BUSYBOX\" mkdir -p \"\$MNT\" 2>/dev/null || true")
        appendLine()
        appendLine("cleanup() {")
        appendLine("    \"\$BUSYBOX\" sync 2>/dev/null || sync 2>/dev/null || true")
        appendLine("    \"\$BUSYBOX\" umount -l \"\$MNT\" 2>/dev/null || umount -l \"\$MNT\" 2>/dev/null || true")
        appendLine("    if [ -n \"\$LOOP_DEV\" ]; then")
        appendLine("        \"\$BUSYBOX\" losetup -d \"\$LOOP_DEV\" 2>/dev/null || losetup -d \"\$LOOP_DEV\" 2>/dev/null || true")
        appendLine("    fi")
        appendLine("    \"\$BUSYBOX\" rmdir \"\$MNT\" 2>/dev/null || rmdir \"\$MNT\" 2>/dev/null || true")
        appendLine("}")
        appendLine("trap cleanup EXIT")
        appendLine()
        appendLine("MOUNT_OK=0")
        appendLine("LOOP_DEV=\$(losetup -f 2>/dev/null || \"\$BUSYBOX\" losetup -f 2>/dev/null || true)")
        appendLine("if [ -n \"\$LOOP_DEV\" ]; then")
        appendLine("    if losetup \"\$LOOP_DEV\" \"\$IMG\" 2>/dev/null || \"\$BUSYBOX\" losetup \"\$LOOP_DEV\" \"\$IMG\" 2>/dev/null; then")
        appendLine("        if mount -t ext4 -o rw \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("           \"\$BUSYBOX\" mount -t ext4 -o rw \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null; then")
        appendLine("            MOUNT_OK=1")
        appendLine("        else")
        appendLine("            losetup -d \"\$LOOP_DEV\" 2>/dev/null || \"\$BUSYBOX\" losetup -d \"\$LOOP_DEV\" 2>/dev/null || true")
        appendLine("            LOOP_DEV=\"\"")
        appendLine("        fi")
        appendLine("    fi")
        appendLine("fi")
        appendLine("if [ \"\$MOUNT_OK\" -ne 1 ]; then")
        appendLine("    if mount -t ext4 -o loop,rw \"\$IMG\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("       \"\$BUSYBOX\" mount -t ext4 -o loop,rw \"\$IMG\" \"\$MNT\" 2>/dev/null; then")
        appendLine("        MOUNT_OK=1")
        appendLine("    fi")
        appendLine("fi")
        appendLine()
        appendLine("if [ \"\$MOUNT_OK\" -ne 1 ]; then")
        appendLine("    echo \"[EMBED-MOUNT-FAIL]\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine()
        appendLine("# Canonical embedded location: a file directly under /etc, which is")
        appendLine("# guaranteed to already exist as a real directory in a validated rootfs --")
        appendLine("# no mkdir, no possible directory/file collision with anything the rootfs ships.")
        appendLine("EMBED_DEST=\"\$MNT/${Constants.EMBEDDED_CONFIG_RELATIVE_PATH}\"")
        appendLine("cp \"\$CFG\" \"\$EMBED_DEST\" 2>/dev/null || true")
        appendLine("chmod 644 \"\$EMBED_DEST\" 2>/dev/null || true")
        appendLine("if [ -f \"\$EMBED_DEST\" ]; then")
        appendLine("    echo \"[EMBED-OK]\"")
        appendLine("else")
        appendLine("    echo \"[EMBED-FAIL] could not verify write to \$EMBED_DEST\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine("exit 0")
    }
}
