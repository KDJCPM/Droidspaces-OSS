package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Native Kotlin implementation of the Sparse Image Installer.
 * Optimized for stability on Samsung and other devices with strict kernel policies.
 * Manages the lifecycle: Truncate -> Format -> Mount -> Extract -> PostFix -> EmbedConfig -> Unmount.
 *
 * Uses [RootNamespace] and [StorageMountManager] when [imgPath] is located on external storage
 * (SD card / USB-OTG) so operations target the real mount points.
 */
object SparseImageInstaller {

    /**
     * Extracts a tarball into a sparse image file.
     *
     * @param context App context
     * @param tarball The source tarball file in app cache
     * @param imgPath The target path for the rootfs.img
     * @param mountPoint The temporary directory where the image will be mounted
     * @param sizeGB The desired size of the sparse image in GB
     * @param configContent Optional container configuration content to embed into image for portability
     * @param logger Logger for real-time progress updates
     */
    suspend fun extract(
        context: Context,
        tarball: File,
        imgPath: String,
        mountPoint: String,
        sizeGB: Int,
        configContent: String? = null,
        logger: ContainerLogger
    ) = withContext(Dispatchers.IO) {
        val busybox = Constants.BUSYBOX_BINARY_PATH
        val quotedImg = ContainerCommandBuilder.quote(imgPath)
        val postFixScriptFile = File("${context.cacheDir}/post_extract_fixes.sh")
        val tempConfigFile = if (!configContent.isNullOrBlank()) {
            File("${context.cacheDir}/.ds_embed_init_${System.currentTimeMillis()}.config")
        } else null

        try {
            // Ensure volume is synchronized if on external storage
            if (RootNamespace.isExternalStorage(imgPath)) {
                StorageMountManager.ensureVolumeMounted(imgPath)
            }

            // 1. Create Sparse Image
            logger.i("[SPARSE] Creating sparse image: ${sizeGB}GB")
            val truncateCmd = "truncate -s ${sizeGB}G $quotedImg || $busybox truncate -s ${sizeGB}G $quotedImg"
            val truncateRes = RootNamespace.exec(truncateCmd, targetPath = imgPath)
            if (!truncateRes.isSuccess) {
                val err = (truncateRes.out + truncateRes.err).joinToString("\n").trim()
                throw Exception("Failed to create sparse image file: $err")
            }

            // Settle filesystem
            RootNamespace.exec("$busybox sync 2>/dev/null || sync 2>/dev/null", targetPath = imgPath)
            delay(1000)

            // 2. Format as ext4
            logger.i("[SPARSE] Formatting sparse image as ext4...")
            val mkfsCmd = "mkfs.ext4 -F -E lazy_itable_init=0,lazy_journal_init=0 -L \"droidspaces-rootfs\" $quotedImg || " +
                    "mke2fs -t ext4 -F -E lazy_itable_init=0,lazy_journal_init=0 -L \"droidspaces-rootfs\" $quotedImg"
            val mkfsRes = RootNamespace.exec(mkfsCmd, targetPath = imgPath)
            if (!mkfsRes.isSuccess) {
                val err = (mkfsRes.out + mkfsRes.err).joinToString("\n").trim()
                throw Exception("Failed to format sparse image as ext4: $err")
            }

            // 2b. Reclaim reserved blocks (tune2fs -m 0)
            logger.i("[SPARSE] Reclaiming reserved disk space (tune2fs -m 0)...")
            RootNamespace.exec("tune2fs -m 0 $quotedImg", targetPath = imgPath)

            // 2c. Verify with e2fsck
            logger.i("[SPARSE] Verifying filesystem integrity (e2fsck)...")
            val checkResult = RootNamespace.exec("e2fsck -fy $quotedImg", targetPath = imgPath)
            // e2fsck exit codes: 0 (Clean), 1 (Corrected), 2 (Reboot suggested - safe for us), 4+ (Uncorrected/Error)
            if (checkResult.code >= 4) {
                val err = (checkResult.out + checkResult.err).joinToString("\n").trim()
                logger.e("[SPARSE] e2fsck failed with exit code ${checkResult.code}: $err")
                throw Exception("Filesystem verification failed (e2fsck error ${checkResult.code})")
            }
            logger.i("[SPARSE] Filesystem integrity verified (code ${checkResult.code})")

            // 2d. Settle Delay (Samsung/Kernel stability)
            logger.i("[SPARSE] Waiting for filesystem to settle (2.5s)...")
            RootNamespace.exec("$busybox sync 2>/dev/null || sync 2>/dev/null", targetPath = imgPath)
            delay(2500)

            // 3. Prepare post-extraction fix script
            try {
                context.assets.open("post_extract_fixes.sh").use { inputStream ->
                    FileOutputStream(postFixScriptFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(postFixScriptFile.absolutePath)}").exec()
            } catch (e: Exception) {
                logger.w("[POST-FIX] Warning: Failed to load post_extract_fixes.sh: ${e.message}")
            }

            // Write temp config to embed if provided
            if (tempConfigFile != null && configContent != null) {
                FileOutputStream(tempConfigFile).use { fos ->
                    fos.write(configContent.toByteArray())
                }
            }

            // 4. Run Mount -> Extract -> PostFix -> EmbedConfig -> Unmount in a single atomic script
            logger.i("[SPARSE] Mounting and extracting container filesystem...")
            val isXz = tarball.name.lowercase().endsWith(".xz")
            val scriptContent = buildInstallerScript(
                busybox = busybox,
                imgPath = imgPath,
                mountPoint = mountPoint,
                tarballPath = tarball.absolutePath,
                postFixScriptPath = postFixScriptFile.absolutePath,
                tempConfigPath = tempConfigFile?.absolutePath,
                isXz = isXz
            )

            val installResult = RootNamespace.runScript(
                scriptContent = scriptContent,
                context = context,
                targetPath = imgPath
            )

            // Process script output
            var installFailed = !installResult.isSuccess
            var failureReason = ""

            installResult.out.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    when {
                        trimmed.startsWith("[SPARSE-MOUNT-OK]") -> logger.i("[SPARSE] Sparse image mounted successfully")
                        trimmed.startsWith("[SPARSE-EXTRACTING]") -> logger.i("[SPARSE] Extracting tarball to mount point...")
                        trimmed.startsWith("[SPARSE-EXTRACT-WARN]") -> logger.w(trimmed)
                        trimmed.startsWith("[SPARSE-POSTFIX]") -> logger.i("[POST-FIX] Running post-extraction fixes...")
                        trimmed.startsWith("[SPARSE-EMBED-OK]") -> logger.i("[SPARSE] Embedded container configuration into image")
                        trimmed.startsWith("[SPARSE-EMBED-FAIL]") -> logger.w("[SPARSE] Warning: $trimmed")
                        trimmed.startsWith("[POST-FIX-WARN]") -> logger.w(trimmed)
                        trimmed.startsWith("[POST-FIX]") -> logger.i(trimmed)
                        trimmed.startsWith("[SPARSE-ERROR]") -> {
                            installFailed = true
                            failureReason = trimmed.removePrefix("[SPARSE-ERROR]").trim()
                            logger.e(trimmed)
                        }
                        trimmed.startsWith("[SPARSE-SUCCESS]") -> logger.i("[SPARSE] Extraction completed successfully")
                        else -> logger.d(trimmed)
                    }
                }
            }

            installResult.err.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    logger.w(trimmed)
                    if (failureReason.isEmpty()) {
                        failureReason = trimmed
                    }
                }
            }

            if (installFailed) {
                val reason = if (failureReason.isNotEmpty()) failureReason else "Exit code: ${installResult.code}"
                throw Exception("Failed to mount or extract sparse image: $reason")
            }

            logger.i("[SPARSE] Sparse image setup completed successfully.")

        } catch (e: Exception) {
            logger.e("[SPARSE] Error: ${e.message}")
            // Cleanup incomplete image on failure
            RootNamespace.exec("rm -f $quotedImg 2>/dev/null", targetPath = imgPath)
            throw e
        } finally {
            try {
                postFixScriptFile.delete()
                tempConfigFile?.delete()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Builds the self-contained shell script that handles mount, extract, post-fixes,
     * config embedding, and guaranteed unmount/cleanup via shell trap.
     */
    private fun buildInstallerScript(
        busybox: String,
        imgPath: String,
        mountPoint: String,
        tarballPath: String,
        postFixScriptPath: String,
        tempConfigPath: String?,
        isXz: Boolean
    ): String = buildString {
        val qImg = ContainerCommandBuilder.quote(imgPath)
        val qMnt = ContainerCommandBuilder.quote(mountPoint)
        val qTar = ContainerCommandBuilder.quote(tarballPath)
        val qPost = ContainerCommandBuilder.quote(postFixScriptPath)
        val qCfg = tempConfigPath?.let { ContainerCommandBuilder.quote(it) }

        appendLine("#!/system/bin/sh")
        appendLine("set -e")
        appendLine()
        appendLine("BUSYBOX=\"$busybox\"")
        appendLine("IMG=$qImg")
        appendLine("MNT=$qMnt")
        appendLine("TARBALL=$qTar")
        appendLine("POST_FIX=$qPost")
        if (qCfg != null) {
            appendLine("TMP_CFG=$qCfg")
        }
        appendLine("LOOP_DEV=\"\"")
        appendLine()
        appendLine("# Ensure basic loop device nodes exist")
        appendLine("for i in 0 1 2 3 4 5 6 7; do")
        appendLine("    [ -e \"/dev/block/loop\$i\" ] || mknod \"/dev/block/loop\$i\" b 7 \"\$i\" 2>/dev/null || true")
        appendLine("    [ -e \"/dev/loop\$i\" ] || mknod \"/dev/loop\$i\" b 7 \"\$i\" 2>/dev/null || true")
        appendLine("done")
        appendLine()
        appendLine("# Create mount point")
        appendLine("mkdir -p \"\$MNT\" 2>/dev/null || \"\$BUSYBOX\" mkdir -p \"\$MNT\" 2>/dev/null || true")
        appendLine()
        appendLine("# Best-effort SELinux context on /data")
        appendLine("if echo \"\$IMG\" | grep -q \"^/data/\"; then")
        appendLine("    chcon u:object_r:vold_data_file:s0 \"\$IMG\" 2>/dev/null || true")
        appendLine("fi")
        appendLine()
        appendLine("# Cleanup handler - always unmounts and removes mount point")
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
        appendLine("# 1. Mount image")
        appendLine("MOUNT_OK=0")
        appendLine("if mount -t ext4 -o loop,rw \"\$IMG\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("   \"\$BUSYBOX\" mount -t ext4 -o loop,rw \"\$IMG\" \"\$MNT\" 2>/dev/null; then")
        appendLine("    MOUNT_OK=1")
        appendLine("else")
        appendLine("    # Fallback: allocate loop device explicitly with losetup")
        appendLine("    LOOP_DEV=\$(losetup -f 2>/dev/null || \"\$BUSYBOX\" losetup -f 2>/dev/null || true)")
        appendLine("    if [ -n \"\$LOOP_DEV\" ]; then")
        appendLine("        # Android reserves low loop indices (0-40+) for APEX modules mounted")
        appendLine("        # at boot, so losetup -f often returns a high index (e.g. loop43) whose")
        appendLine("        # device node was never created -- only 0-7 are pre-created above. Make")
        appendLine("        # sure the SPECIFIC node losetup picked actually exists before using it.")
        appendLine("        LOOP_NUM=\$(echo \"\$LOOP_DEV\" | \"\$BUSYBOX\" sed 's#.*loop##' 2>/dev/null || echo \"\$LOOP_DEV\" | sed 's#.*loop##')")
        appendLine("        if [ -n \"\$LOOP_NUM\" ]; then")
        appendLine("            [ -e \"\$LOOP_DEV\" ] || mknod \"\$LOOP_DEV\" b 7 \"\$LOOP_NUM\" 2>/dev/null || \"\$BUSYBOX\" mknod \"\$LOOP_DEV\" b 7 \"\$LOOP_NUM\" 2>/dev/null || true")
        appendLine("            [ -e \"/dev/block/loop\$LOOP_NUM\" ] || mknod \"/dev/block/loop\$LOOP_NUM\" b 7 \"\$LOOP_NUM\" 2>/dev/null || true")
        appendLine("            [ -e \"/dev/loop\$LOOP_NUM\" ] || mknod \"/dev/loop\$LOOP_NUM\" b 7 \"\$LOOP_NUM\" 2>/dev/null || true")
        appendLine("        fi")
        appendLine("        if losetup \"\$LOOP_DEV\" \"\$IMG\" 2>/dev/null || \"\$BUSYBOX\" losetup \"\$LOOP_DEV\" \"\$IMG\" 2>/dev/null; then")
        appendLine("            if mount -t ext4 -o rw \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null || \\")
        appendLine("               \"\$BUSYBOX\" mount -t ext4 -o rw \"\$LOOP_DEV\" \"\$MNT\" 2>/dev/null; then")
        appendLine("                MOUNT_OK=1")
        appendLine("            else")
        appendLine("                losetup -d \"\$LOOP_DEV\" 2>/dev/null || \"\$BUSYBOX\" losetup -d \"\$LOOP_DEV\" 2>/dev/null || true")
        appendLine("                LOOP_DEV=\"\"")
        appendLine("            fi")
        appendLine("        fi")
        appendLine("    fi")
        appendLine("fi")
        appendLine()
        appendLine("if [ \"\$MOUNT_OK\" -ne 1 ]; then")
        appendLine("    echo \"[SPARSE-ERROR] Mount failed. Diagnostic output:\"")
        appendLine("    echo \"[SPARSE-DIAG] LOOP_DEV=\$LOOP_DEV\"")
        appendLine("    losetup -a 2>&1 || \"\$BUSYBOX\" losetup -a 2>&1 || true")
        appendLine("    ls -la /dev/block/loop* 2>&1 || true")
        appendLine("    mount -t ext4 -o loop,rw \"\$IMG\" \"\$MNT\" 2>&1 || true")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine()
        appendLine("echo \"[SPARSE-MOUNT-OK]\"")
        appendLine()
        appendLine("# 2. Extract tarball")
        appendLine("echo \"[SPARSE-EXTRACTING]\"")
        appendLine("cd \"\$MNT\"")
        appendLine("TAR_EXIT=0")
        if (isXz) {
            appendLine("\"$busybox\" xzcat \"$tarballPath\" | \"$busybox\" tar -xpf - 2>&1 || TAR_EXIT=\$?")
        } else {
            appendLine("\"$busybox\" tar -xzpf \"$tarballPath\" 2>&1 || TAR_EXIT=\$?")
        }
        appendLine("if [ \"\$TAR_EXIT\" -ne 0 ]; then")
        appendLine("    echo \"[SPARSE-EXTRACT-WARN] tar exited \$TAR_EXIT (commonly harmless -- device nodes/xattrs/perm warnings on a real rootfs); continuing\"")
        appendLine("fi")
        appendLine("cd /")
        appendLine()
        appendLine("# 3. Post-extraction fixes")
        appendLine("echo \"[SPARSE-POSTFIX]\"")
        appendLine("if [ -f \"\$POST_FIX\" ]; then")
        appendLine("    BUSYBOX_PATH=\"\$BUSYBOX\" sh \"\$POST_FIX\" \"\$MNT\" 2>&1 || true")
        appendLine("fi")
        appendLine()
        if (qCfg != null) {
            appendLine("# 4. Embed self-describing container configuration as a plain file directly")
            appendLine("#    under /etc -- guaranteed to already exist as a real directory in a")
            appendLine("#    validated rootfs, so no mkdir and no possible directory/file collision.")
            appendLine("EMBED_DEST=\"\$MNT/${Constants.EMBEDDED_CONFIG_RELATIVE_PATH}\"")
            appendLine("if [ ! -f \"\$TMP_CFG\" ]; then")
            appendLine("    echo \"[SPARSE-EMBED-FAIL] source config missing at \$TMP_CFG\"")
            appendLine("elif ! CP_ERR=\$(cp \"\$TMP_CFG\" \"\$EMBED_DEST\" 2>&1); then")
            appendLine("    echo \"[SPARSE-EMBED-FAIL] cp to \$EMBED_DEST failed: \$CP_ERR\"")
            appendLine("else")
            appendLine("    chmod 644 \"\$EMBED_DEST\" 2>/dev/null || true")
            appendLine("    if [ -f \"\$EMBED_DEST\" ]; then")
            appendLine("        echo \"[SPARSE-EMBED-OK]\"")
            appendLine("    else")
            appendLine("        echo \"[SPARSE-EMBED-FAIL] cp reported success but \$EMBED_DEST still missing\"")
            appendLine("    fi")
            appendLine("fi")
            appendLine()
        }
        appendLine("echo \"[SPARSE-SUCCESS]\"")
        appendLine("exit 0")
    }
}
