package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

enum class ContainerStatus {
    RUNNING,
    STOPPED,
    RESTARTING
}

data class BindMount(
    val src: String,
    val dest: String,
    val ro: Boolean = false
)

data class PortForward(
    val hostPort: String,
    val containerPort: String? = null,
    val proto: String = "tcp"
)

data class ContainerInfo(
    val name: String,
    val hostname: String,
    val rootfsPath: String,
    val netMode: String = Constants.DEFAULT_NET_MODE,
    val disableIPv6: Boolean = false,
    val enableAndroidStorage: Boolean = false,
    val enableHwAccess: Boolean = false,
    val enableGpuMode: Boolean = false,
    val enableTermuxX11: Boolean = false,
    val tx11ExtraFlags: String = "",
    val enableVirgl: Boolean = false,
    val virglExtraFlags: String = "",
    val enablePulseaudio: Boolean = false,
    val selinuxPermissive: Boolean = false,
    val allowUserns: Boolean = false,
    val volatileMode: Boolean = false,
    val bindMounts: List<BindMount> = emptyList(),
    val dnsServers: String = "",
    val runAtBoot: Boolean = false,
    val runAtBootPriority: Int = 0,
    val status: ContainerStatus = ContainerStatus.STOPPED,
    val pid: Int? = null,
    val useSparseImage: Boolean = false,
    val sparseImageSizeGB: Int? = null,
    val envFileContent: String? = null,
    val upstreamInterfaces: List<String> = emptyList(),
    val portForwards: List<PortForward> = emptyList(),
    val forceCgroupv1: Boolean = false,
    val blockNestedNs: Boolean = false,
    val staticNatIp: String = "",
    val gatewayContainer: String = "",
    val gatewayNet: String = "",
    val gatewayIface: String = "",
    val gatewayBridge: String = "",
    val privileged: String = "",
    val customInit: String = "",
    val uuid: String = ""
) {
    val isRunning: Boolean
        get() = status == ContainerStatus.RUNNING

    fun toConfigContent(): String = buildString {
        appendLine("# Droidspaces Container Configuration")
        appendLine("# Generated automatically")
        appendLine()
        appendLine("name=$name")
        appendLine("hostname=$hostname")
        appendLine("rootfs_path=$rootfsPath")
        appendLine("net_mode=$netMode")
        appendLine("disable_ipv6=${if (disableIPv6) "1" else "0"}")
        appendLine("enable_android_storage=${if (enableAndroidStorage) "1" else "0"}")
        appendLine("enable_hw_access=${if (enableHwAccess) "1" else "0"}")
        appendLine("enable_gpu_mode=${if (enableGpuMode) "1" else "0"}")
        appendLine("enable_termux_x11=${if (enableTermuxX11) "1" else "0"}")
        if (tx11ExtraFlags.isNotBlank()) appendLine("tx11_extra_flags=$tx11ExtraFlags")
        appendLine("enable_virgl=${if (enableVirgl) "1" else "0"}")
        if (virglExtraFlags.isNotBlank()) appendLine("virgl_extra_flags=$virglExtraFlags")
        appendLine("enable_pulseaudio=${if (enablePulseaudio) "1" else "0"}")
        appendLine("selinux_permissive=${if (selinuxPermissive) "1" else "0"}")
        appendLine("allow_userns=${if (allowUserns) "1" else "0"}")
        appendLine("volatile_mode=${if (volatileMode) "1" else "0"}")
        if (bindMounts.isNotEmpty()) {
            appendLine("bind_mounts=${bindMounts.joinToString(",") { "${it.src}:${it.dest}${if (it.ro) ":ro" else ""}" }}")
        }
        if (netMode == "nat" && upstreamInterfaces.isNotEmpty()) {
            appendLine("upstream_interfaces=${upstreamInterfaces.joinToString(",")}")
        }
        if (netMode == "nat" && portForwards.isNotEmpty()) {
            appendLine("port_forwards=${portForwards.joinToString(",") {
                val mapping = if (it.containerPort != null) "${it.hostPort}:${it.containerPort}" else it.hostPort
                "$mapping/${it.proto}"
            }}")
        }
        if (dnsServers.isNotEmpty()) {
            appendLine("dns_servers=$dnsServers")
        }
        appendLine("run_at_boot=${if (runAtBoot) "1" else "0"}")
        if (runAtBoot && runAtBootPriority > 0) {
            appendLine("run_at_boot_priority=$runAtBootPriority")
        }
        appendLine("force_cgroupv1=${if (forceCgroupv1) "1" else "0"}")
        appendLine("block_nested_ns=${if (blockNestedNs) "1" else "0"}")
        if (netMode == "nat" && staticNatIp.isNotEmpty()) {
            appendLine("static_nat_ip=$staticNatIp")
        }
        // Gateway-mode keys (the C runtime parses gateway_lan_ifname, not gateway_iface).
        // gateway_container is required in gateway mode; the rest are optional overrides.
        if (netMode == "gateway") {
            if (gatewayContainer.isNotBlank()) appendLine("gateway_container=$gatewayContainer")
            if (gatewayNet.isNotBlank()) appendLine("gateway_net=$gatewayNet")
            if (gatewayIface.isNotBlank()) appendLine("gateway_lan_ifname=$gatewayIface")
            if (gatewayBridge.isNotBlank()) appendLine("gateway_bridge=$gatewayBridge")
        }
        appendLine("use_sparse_image=${if (useSparseImage) "1" else "0"}")
        if (sparseImageSizeGB != null) {
            appendLine("sparse_image_size_gb=$sparseImageSizeGB")
        }
        if (envFileContent != null) {
            appendLine("env_file=${Constants.CONTAINERS_BASE_PATH}/${ContainerManager.sanitizeContainerName(name)}/.env")
        }
        if (privileged.isNotEmpty()) {
            appendLine("privileged=$privileged")
        }
        if (customInit.isNotEmpty()) {
            appendLine("custom_init=$customInit")
        }
        if (uuid.isNotEmpty()) {
            appendLine("uuid=$uuid")
        }
    }
}

object ContainerManager {
    private const val CONTAINERS_BASE_PATH = Constants.CONTAINERS_BASE_PATH

    /**
     * Sanitize container name for use in directory paths.
     * Replaces spaces with dashes, but allows dots and other valid characters.
     * This ensures directory names are safe while preserving readable names.
     */
    fun sanitizeContainerName(name: String): String {
        return name.replace(" ", "-")
    }

    /**
     * Get the container directory path (parent directory).
     * Uses sanitized name to handle spaces.
     */
    fun getContainerDirectory(name: String): String {
        val sanitizedName = sanitizeContainerName(name)
        return "$CONTAINERS_BASE_PATH/$sanitizedName"
    }

    /**
     * Get the rootfs path for a container (LXC-style: /rootfs subdirectory).
     *
     * @param customStorageDir Optional external/custom storage root (e.g. an SD card or
     * USB-OTG mount point). When provided, the container's rootfs lives under
     * "$customStorageDir/<sanitizedName>/rootfs" instead of the default internal
     * CONTAINERS_BASE_PATH. The container's own metadata (config file, .env, pidfile)
     * always stays under CONTAINERS_BASE_PATH regardless of this setting.
     */
    fun getRootfsPath(name: String, customStorageDir: String? = null): String {
        return if (customStorageDir != null) {
            "${customStorageDir.trimEnd('/')}/${sanitizeContainerName(name)}/rootfs"
        } else {
            "${getContainerDirectory(name)}/rootfs"
        }
    }

    /**
     * Get the sparse image path for a container.
     *
     * @param customStorageDir Optional external/custom storage root — see [getRootfsPath].
     */
    fun getSparseImagePath(name: String, customStorageDir: String? = null): String {
        return if (customStorageDir != null) {
            "${customStorageDir.trimEnd('/')}/${sanitizeContainerName(name)}/rootfs.img"
        } else {
            "${getContainerDirectory(name)}/rootfs.img"
        }
    }

    /**
     * List all installed containers by scanning the containers directory.
     * Returns a list of ContainerInfo objects.
     */
    suspend fun listContainers(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        val containers = mutableListOf<ContainerInfo>()

        try {
            // List all directories in the containers base path (quoted for safety)
            val listResult = Shell.cmd("ls -d \"$CONTAINERS_BASE_PATH\"/*/ 2>/dev/null").exec()

            if (!listResult.isSuccess) {
                // Directory might not exist or be empty
                return@withContext emptyList()
            }

            // Parse each directory path
            listResult.out.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith(CONTAINERS_BASE_PATH)) {
                    return@forEach
                }

                // Extract sanitized container name from path: /data/local/Droidspaces/Containers/name/
                val sanitizedName = trimmed
                    .removeSuffix("/")
                    .substringAfterLast("/")

                if (sanitizedName.isEmpty()) {
                    return@forEach
                }

                // Try to load container config
                val configPath = "$CONTAINERS_BASE_PATH/$sanitizedName/${Constants.CONTAINER_CONFIG_FILE}"
                // Use sanitizedName as default, but config file will have the real name
                val config = loadContainerConfig(configPath, sanitizedName)

                if (config != null) {
                    // Check if container is running (use the real name from config)
                    val runningInfo = checkContainerStatus(config.name)
                    val status = if (runningInfo.first) {
                        ContainerStatus.RUNNING
                    } else {
                        ContainerStatus.STOPPED
                    }
                    containers.add(config.copy(
                        status = status,
                        pid = runningInfo.second
                    ))
                }
            }
        } catch (e: Exception) {
            // Return empty list on error
        }

        containers
    }

    /**
     * Load container configuration from config file.
     */
    private fun loadContainerConfig(configPath: String, defaultName: String): ContainerInfo? {
        try {
            // Read config file using shell (quoted for safety)
            val readResult = Shell.cmd("cat ${ContainerCommandBuilder.quote(configPath)} 2>/dev/null").exec()

            if (!readResult.isSuccess || readResult.out.isEmpty()) {
                return null
            }

            val configContent = readResult.out.joinToString("\n")
            return parseConfig(configContent, defaultName)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parse container configuration from string content.
     */
    fun parseConfig(configContent: String, defaultName: String): ContainerInfo? {
        try {
            val configMap = mutableMapOf<String, String>()

            // Parse config file (key=value format)
            configContent.lines().forEach { line ->
                val trimmed = line.trim()
                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return@forEach
                }

                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    configMap[parts[0].trim()] = parts[1].trim()
                }
            }

            // Build ContainerInfo from config
            val containerName = configMap["name"] ?: defaultName
            // Drop a container whose on-disk name carries shell metacharacters before
            // it can reach a root command (VULN V10). Over-length-but-safe names still load.
            if (!ValidationUtils.isSafeContainerName(containerName)) {
                android.util.Log.w("ContainerManager", "Skipping container with unsafe name in config")
                return null
            }
            val useSparseImage = configMap["use_sparse_image"] == "1"
            val sparseImageSizeGB = configMap["sparse_image_size_gb"]?.toIntOrNull()

            // Parse bind mounts: src:dest,src2:dest2
            val bindMounts = configMap["bind_mounts"]?.split(",")?.mapNotNull {
                val parts = it.split(":", limit = 3)
                when (parts.size) {
                    2 -> BindMount(parts[0], parts[1])
                    3 -> BindMount(parts[0], parts[1], ro = parts[2].trim() == "ro")
                    else -> null
                }
            } ?: emptyList()

            // Parse upstream interfaces
            val upstreamInterfaces = configMap["upstream_interfaces"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            // Parse port forwards: 8080:80/tcp, 9090:90/udp, 1000-2000/tcp (shorthand)
            val portForwards = configMap["port_forwards"]?.split(",")?.mapNotNull { pfStr ->
                try {
                    val parts = pfStr.trim().split("/")
                    val proto = if (parts.size > 1) parts[1].lowercase() else "tcp"
                    val portParts = parts[0].split(":")
                    if (portParts.size == 2) {
                        PortForward(portParts[0].trim(), portParts[1].trim(), proto)
                    } else if (portParts.size == 1 && portParts[0].isNotBlank()) {
                        PortForward(portParts[0].trim(), null, proto)
                    } else null
                } catch (e: Exception) { null }
            } ?: emptyList()

            return ContainerInfo(
                name = containerName,
                hostname = configMap["hostname"] ?: ValidationUtils.sanitizeHostname(containerName),
                // Use the new rootfs path structure (LXC-style) or sparse image path
                rootfsPath = configMap["rootfs_path"] ?: if (useSparseImage) {
                    getSparseImagePath(containerName)
                } else {
                    getRootfsPath(containerName)
                },
                netMode = configMap["net_mode"] ?: Constants.DEFAULT_NET_MODE,
                disableIPv6 = configMap["disable_ipv6"] == "1",
                enableAndroidStorage = configMap["enable_android_storage"] == "1",
                enableHwAccess = configMap["enable_hw_access"] == "1",
                enableGpuMode = configMap["enable_gpu_mode"] == "1",
                enableTermuxX11 = configMap["enable_termux_x11"] == "1",
                tx11ExtraFlags = configMap["tx11_extra_flags"] ?: "",
                enableVirgl = configMap["enable_virgl"] == "1",
                virglExtraFlags = configMap["virgl_extra_flags"] ?: "",
                enablePulseaudio = configMap["enable_pulseaudio"] == "1",
                selinuxPermissive = configMap["selinux_permissive"] == "1",
                allowUserns = configMap["allow_userns"] == "1",
                volatileMode = configMap["volatile_mode"] == "1",
                bindMounts = bindMounts,
                dnsServers = configMap["dns_servers"] ?: "",
                runAtBoot = configMap["run_at_boot"] == "1",
                runAtBootPriority = configMap["run_at_boot_priority"]?.toIntOrNull() ?: 0,
                status = ContainerStatus.STOPPED,
                useSparseImage = useSparseImage,
                sparseImageSizeGB = sparseImageSizeGB,
                envFileContent = loadEnvFileContent(containerName),
                upstreamInterfaces = upstreamInterfaces,
                portForwards = portForwards,
                forceCgroupv1 = configMap["force_cgroupv1"] == "1",
                blockNestedNs = configMap["block_nested_ns"] == "1",
                staticNatIp = configMap["static_nat_ip"] ?: "",
                gatewayContainer = configMap["gateway_container"] ?: "",
                gatewayNet = configMap["gateway_net"] ?: "",
                gatewayIface = configMap["gateway_lan_ifname"] ?: "",
                gatewayBridge = configMap["gateway_bridge"] ?: "",
                privileged = configMap["privileged"] ?: "",
                customInit = configMap["custom_init"] ?: "",
                uuid = configMap["uuid"] ?: ""
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Load .env file content for a container.
     */
    private fun loadEnvFileContent(containerName: String): String? {
        val envFilePath = "${getContainerDirectory(containerName)}/.env"
        val readResult = Shell.cmd("cat ${ContainerCommandBuilder.quote(envFilePath)} 2>/dev/null").exec()
        return if (readResult.isSuccess && readResult.out.isNotEmpty()) {
            readResult.out.joinToString("\n")
        } else {
            null
        }
    }

    /**
     * Check if a container is running and get its correct init PID.
     * Returns Pair<isRunning, pid>
     *
     * Uses 'droidspaces --name=X pid' which:
     *  - Reads the PID file directly (no pgrep guessing)
     *  - Checks kill(pid, 0) to confirm the process is alive
     *  - Prints just the PID number or "NONE"
     *  - NEVER calls cleanup_container_resources (safe to call post-start)
     */
    suspend fun checkContainerStatus(containerName: String): Pair<Boolean, Int?> = withContext(Dispatchers.IO) {
        try {
            val binary = Constants.DROIDSPACES_BINARY_PATH
            val quotedName = ContainerCommandBuilder.quote(containerName)
            val result = Shell.cmd("\"$binary\" --name=$quotedName pid 2>/dev/null").exec()

            val output = result.out.firstOrNull()?.trim() ?: "NONE"
            if (output == "NONE" || output.isEmpty()) {
                return@withContext Pair(false, null)
            }

            val pid = output.toIntOrNull()
            if (pid != null && pid > 0) {
                return@withContext Pair(true, pid)
            }
        } catch (e: Exception) {
            // Ignore errors - treat as stopped
        }

        Pair(false, null)
    }

    /**
     * Get container info by name.
     * Note: name should be the sanitized directory name (spaces replaced with dashes).
     */
    suspend fun getContainerInfo(name: String): ContainerInfo? = withContext(Dispatchers.IO) {
        val sanitizedName = sanitizeContainerName(name)
        val configPath = "$CONTAINERS_BASE_PATH/$sanitizedName/${Constants.CONTAINER_CONFIG_FILE}"
        val config = loadContainerConfig(configPath, sanitizedName)

        if (config != null) {
            val runningInfo = checkContainerStatus(name)
            val status = if (runningInfo.first) {
                ContainerStatus.RUNNING
            } else {
                ContainerStatus.STOPPED
            }
            config.copy(
                status = status,
                pid = runningInfo.second
            )
        } else {
            null
        }
    }

    /**
     * List active upstream interfaces by scanning all routing tables.
     *
     * Uses `table all` instead of the default table so that CLAT/Qualcomm
     * devices are correctly detected - on these devices every interface has
     * its own per-interface routing table and nothing appears in the main
     * table, so `ip route show default` returns empty.
     */
    suspend fun listUpstreamInterfaces(): List<String> = withContext(Dispatchers.IO) {
        try {
            val busybox = Constants.BUSYBOX_BINARY_PATH
            val cmd = "ip route show table all | $busybox grep '^default' | $busybox awk '{for(i=1;i<=NF;i++) if(\$i==\"dev\") print \$(i+1)}' | $busybox grep -Ev '^(ds-|dummy)' | $busybox sort -u"
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                result.out.map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Update container configuration.
     * Updates the configurable options (hostname, flags, rootfsPath, etc.), not the
     * container's name. Note: changing rootfsPath here only rewrites the config file --
     * it does NOT move any data on disk. Use [moveContainerStorage] to actually relocate
     * a container's rootfs and update its config together.
     *
     * @param context Android context for temporary file creation
     * @param containerName Name of the container to update (will be sanitized)
     * @param newConfig New configuration values (only configurable fields are used)
     * @return Result.success on success, Result.failure on error
     */
    suspend fun updateContainerConfig(
        context: Context,
        containerName: String,
        newConfig: ContainerInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Reject control chars in single-line config values (VULN V11).
            ValidationUtils.validateConfigValues(newConfig).errorMessage?.let {
                return@withContext Result.failure(Exception(it))
            }
            val sanitizedName = sanitizeContainerName(containerName)
            val configPath = "$CONTAINERS_BASE_PATH/$sanitizedName/${Constants.CONTAINER_CONFIG_FILE}"

            // Build new config content using the shared method
            // Preserve the existing UUID -- never overwrite it with an empty value
            val configToWrite = if (newConfig.uuid.isNotEmpty()) {
                newConfig
            } else {
                val existingContent = Shell.cmd("cat ${ContainerCommandBuilder.quote(configPath)} 2>/dev/null").exec()
                    .out.joinToString("\n")
                val existingUuid = existingContent.lines()
                    .firstOrNull { it.startsWith("uuid=") }
                    ?.removePrefix("uuid=")?.trim() ?: ""
                newConfig.copy(uuid = existingUuid)
            }
            val configContent = configToWrite.toConfigContent()

            // Handle .env file
            val envFilePath = "${getContainerDirectory(containerName)}/.env"
            if (newConfig.envFileContent.isNullOrBlank()) {
                Shell.cmd("rm -f ${ContainerCommandBuilder.quote(envFilePath)}").exec()
            } else {
                val tempEnvFile = File("${context.cacheDir}/.env_${sanitizedName}")
                tempEnvFile.writeText(newConfig.envFileContent + "\n")
                Shell.cmd("cp ${ContainerCommandBuilder.quote(tempEnvFile.absolutePath)} ${ContainerCommandBuilder.quote(envFilePath)}").exec()
                Shell.cmd("chmod 644 ${ContainerCommandBuilder.quote(envFilePath)}").exec()
                tempEnvFile.delete()
            }

            // Write config to temp file first (app can write to cache dir)
            // Use sanitizedName to avoid issues with spaces in filename
            val tempConfigFile = File("${context.cacheDir}/container_${sanitizedName}.config")
            tempConfigFile.writeText(configContent)

            // Copy temp config to final location using shell (root required)
            // Quote paths to handle spaces and special characters
            val copyResult = Shell.cmd("cp ${ContainerCommandBuilder.quote(tempConfigFile.absolutePath)} ${ContainerCommandBuilder.quote(configPath)} 2>&1").exec()
            if (!copyResult.isSuccess) {
                // Check both stdout and stderr for error messages
                val errorOutput = (copyResult.out + copyResult.err).joinToString("\n").trim()
                val errorMsg = if (errorOutput.isNotEmpty()) errorOutput else "Unknown error (exit code: ${copyResult.code})"
                tempConfigFile.delete()
                return@withContext Result.failure(Exception("Failed to update container config: $errorMsg"))
            }

            // Set proper permissions
            val chmodResult = Shell.cmd("chmod 644 ${ContainerCommandBuilder.quote(configPath)} 2>&1").exec()
            if (!chmodResult.isSuccess) {
                // Non-fatal, but log warning
            }

            // Clean up temp config file
            tempConfigFile.delete()

            // Best-effort sync embedded config inside sparse image for portability
            if (newConfig.useSparseImage && newConfig.rootfsPath.isNotBlank()) {
                try {
                    ExistingImageManager.embedConfig(newConfig.rootfsPath, configContent, context)
                } catch (e: Exception) {
                    // Non-fatal
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Auto-detects if an external container's rootfs or image path has changed
     * due to USB storage eject/remount (e.g. volume UUID or mount point change).
     * If found on another active external storage volume, updates container.config on disk
     * and returns the updated ContainerInfo.
     */
    suspend fun autoDetectAndRemapContainerPath(
        context: Context,
        container: ContainerInfo,
        logger: ContainerLogger? = null
    ): ContainerInfo = withContext(Dispatchers.IO) {
        val oldPath = container.rootfsPath.trim()
        if (oldPath.isBlank()) return@withContext container

        // Ensure stale loop devices and mounts are cleaned first
        StorageMountManager.cleanupStaleMountsAndLoopDevices()
        StorageMountManager.ensureVolumeMounted(oldPath)

        val oldVolId = StorageMountManager.extractVolumeId(oldPath)

        // For external sparse images, ALWAYS ensure the physical path (/mnt/media_rw/...)
        // is used in container.config so loop mounting bypasses Android FUSE layer (which returns ENXIO).
        if (oldVolId != null && container.useSparseImage) {
            val physicalPath = StorageMountManager.resolveToPhysicalPath(oldPath)
            val qPhys = ContainerCommandBuilder.quote(physicalPath)
            val physExists = Shell.cmd("[ -f $qPhys ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes"
            if (physExists) {
                if (oldPath != physicalPath) {
                    logger?.i("Using raw physical storage path for loop mount: $physicalPath")
                    val updated = container.copy(rootfsPath = physicalPath)
                    updateContainerConfig(context, container.name, updated)
                    return@withContext updated
                }
                return@withContext container
            }
        }

        // Check if current path exists and is accessible
        val qOld = ContainerCommandBuilder.quote(oldPath)
        val exists = Shell.cmd("[ -e $qOld ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes"
        if (exists) {
            return@withContext container
        }

        if (oldVolId == null) return@withContext container

        // Extract subpath after the old volume ID
        val subPath = oldPath.substringAfter(oldVolId, "").trimStart('/')
        val filename = oldPath.substringAfterLast("/")

        // Discover all currently connected storage volumes
        val activeVolumes = StorageMountManager.listAllStorageVolumes()
        for (volPath in activeVolumes) {
            val candidatePaths = mutableListOf<String>()
            if (subPath.isNotEmpty()) {
                candidatePaths.add("$volPath/$subPath")
            }
            candidatePaths.add("$volPath/${sanitizeContainerName(container.name)}/rootfs.img")
            candidatePaths.add("$volPath/${sanitizeContainerName(container.name)}/rootfs")
            if (filename.isNotEmpty() && filename != "rootfs.img" && filename != "rootfs") {
                candidatePaths.add("$volPath/$filename")
            }

            for (cand in candidatePaths.distinct()) {
                val qCand = ContainerCommandBuilder.quote(cand)
                val candExists = Shell.cmd("[ -e $qCand ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes"
                if (candExists) {
                    val targetPath = if (container.useSparseImage) {
                        StorageMountManager.resolveToPhysicalPath(cand)
                    } else {
                        cand
                    }
                    logger?.i("Storage remount detected: container rootfs relocated from $oldPath to $targetPath")
                    val updated = container.copy(rootfsPath = targetPath)
                    updateContainerConfig(context, container.name, updated)
                    return@withContext updated
                }
            }
        }

        logger?.w("Warning: Container rootfs not found at $oldPath and no matching path found on connected storage drives.")
        container
    }

    /**
     * Move a container's rootfs (or rootfs.img) to a different storage location and
     * update its config file to match. This is the "storage relocation" companion to
     * [ContainerInstaller.installContainer]'s initial storage-location choice.
     *
     * The container's small metadata (config file, .env, pidfile) always stays under the
     * internal CONTAINERS_BASE_PATH -- only the (large) rootfs data is relocated.
     *
     * @param context Android context, needed by [updateContainerConfig]
     * @param container The container to move (must be stopped)
     * @param newStorageDir Destination storage root, e.g. "/storage/1234-5678/Droidspaces".
     *   Pass null to move the container back to the default internal location.
     * @param logger Optional logger for progress/diagnostic messages
     * @return Result.success(newRootfsPath) on success, Result.failure with a
     *   human-readable message on error. On failure, no partial state is left behind:
     *   either the move completed and the config was updated, or nothing changed.
     */
    suspend fun moveContainerStorage(
        context: Context,
        container: ContainerInfo,
        newStorageDir: String?,
        logger: ContainerLogger? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Refuse to move a running container -- the rootfs may be actively
            // mounted/in use and moving it out from under a live process is unsafe.
            val (isRunning, _) = checkContainerStatus(container.name)
            if (isRunning) {
                return@withContext Result.failure(
                    Exception("Stop the container before moving its storage.")
                )
            }

            // /mnt/media_rw/<vol> is vold's raw mount of removable media. Writing
            // there directly is unreliable -- see StorageChecker.normalizeStorageDir.
            val normalizedStorageDir = newStorageDir?.let { StorageChecker.normalizeStorageDir(it) }

            val containerPath = getContainerDirectory(container.name)
            val oldRootfsPath = container.rootfsPath
            val newRootfsPath = if (container.useSparseImage) {
                getSparseImagePath(container.name, normalizedStorageDir)
            } else {
                getRootfsPath(container.name, normalizedStorageDir)
            }

            if (oldRootfsPath == newRootfsPath) {
                return@withContext Result.failure(
                    Exception("Container is already at that location.")
                )
            }

            val isExternal = RootNamespace.isExternalStorage(oldRootfsPath) || RootNamespace.isExternalStorage(newRootfsPath)
            val quotedOld = ContainerCommandBuilder.quote(oldRootfsPath)
            val quotedNew = ContainerCommandBuilder.quote(newRootfsPath)

            // Verify the current rootfs actually exists before doing anything.
            val existsCheck = RootNamespace.exec("[ -e $quotedOld ] && echo yes || echo no", forceNsenter = isExternal)
            if (existsCheck.out.firstOrNull()?.trim() != "yes") {
                return@withContext Result.failure(
                    Exception("Current rootfs not found at $oldRootfsPath")
                )
            }

            val newParentDir = newRootfsPath.substringBeforeLast("/", newRootfsPath)

            // Best-effort free-space check at the destination (non-fatal if it can't
            // be determined -- some filesystems/paths don't report du/df cleanly).
            logger?.i("Checking free space at $newParentDir ...")
            val sizeResult = RootNamespace.exec(
                "du -sk $quotedOld 2>/dev/null | ${Constants.BUSYBOX_BINARY_PATH} awk '{print \$1}'",
                forceNsenter = isExternal
            )
            val neededKB = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull()
            val mkdirParentResult = RootNamespace.exec(
                "mkdir -p ${ContainerCommandBuilder.quote(newParentDir)} 2>&1",
                forceNsenter = isExternal
            )
            if (!mkdirParentResult.isSuccess) {
                val err = (mkdirParentResult.out + mkdirParentResult.err).joinToString("\n").trim()
                return@withContext Result.failure(Exception("Failed to create destination directory: $err"))
            }
            if (neededKB != null) {
                val freeGB = StorageChecker.getFreeSpaceGB(newParentDir)
                val neededGB = (neededKB / 1024 / 1024).toInt() + 1 // round up + 1GB margin
                if (freeGB != null && freeGB < neededGB) {
                    return@withContext Result.failure(
                        Exception("Not enough free space at destination (~${neededGB}GB needed, ${freeGB}GB free)")
                    )
                }
            }

            // FAT32 caps any single file at 4 GiB. cp/mv into a FAT32 destination
            // fail mid-copy with "short write: No space left on device" once the
            // destination file crosses that line, even with plenty of free space on
            // the volume -- catch it up front with a clear message instead. exFAT
            // and ext4 have no such limit.
            val fsTypeResult = RootNamespace.exec(
                "stat -f -c '%T' ${ContainerCommandBuilder.quote(newParentDir)} 2>/dev/null",
                forceNsenter = isExternal
            )
            val destFsType = fsTypeResult.out.firstOrNull()?.trim()?.lowercase()
            val isFat32 = destFsType == "msdos" || destFsType == "vfat" || destFsType == "fat"
            if (isFat32 && neededKB != null && neededKB > 4L * 1024 * 1024) {
                return@withContext Result.failure(
                    Exception(
                        "Destination is FAT32, which can't hold a single file over 4GB " +
                        "(this container's data is ~${neededKB / 1024 / 1024}GB). " +
                        "Reformat the drive as exFAT or ext4, or pick a different destination."
                    )
                )
            }

            // Try a plain rename first (instant, but only works within the same
            // filesystem). Falls back to copy-then-delete for cross-filesystem moves,
            // which is the common case (internal storage -> SD card / USB-OTG).
            logger?.i("Moving rootfs from $oldRootfsPath to $newRootfsPath ...")
            val mvResult = RootNamespace.exec("mv $quotedOld $quotedNew 2>&1", forceNsenter = isExternal)
            if (!mvResult.isSuccess) {
                logger?.i("Direct rename unavailable (different filesystem) -- copying instead. This may take a while for large containers.")
                val cpResult = RootNamespace.exec("cp -a $quotedOld $quotedNew 2>&1", forceNsenter = isExternal)
                if (!cpResult.isSuccess) {
                    val err = (cpResult.out + cpResult.err).joinToString("\n").trim()
                    // Clean up any partial copy so a retry starts clean.
                    RootNamespace.exec("rm -rf $quotedNew 2>&1", forceNsenter = isExternal)
                    return@withContext Result.failure(Exception("Failed to copy rootfs to new location: $err"))
                }

                // Data is safely copied to the new location -- now update the config
                // BEFORE deleting the old copy, so a crash here never leaves the
                // container pointing at data that no longer exists.
                val updateResult = updateContainerConfig(
                    context, container.name, container.copy(rootfsPath = newRootfsPath)
                )
                if (updateResult.isFailure) {
                    RootNamespace.exec("rm -rf $quotedNew 2>&1", forceNsenter = isExternal)
                    return@withContext Result.failure(
                        updateResult.exceptionOrNull() ?: Exception("Failed to update container config")
                    )
                }

                logger?.i("Removing old copy at $oldRootfsPath ...")
                val rmResult = RootNamespace.exec("rm -rf $quotedOld 2>&1", forceNsenter = isExternal)
                if (!rmResult.isSuccess) {
                    logger?.w("Move succeeded, but couldn't remove the old data at $oldRootfsPath -- remove it manually to free up space.")
                }
            } else {
                // Plain rename succeeded -- update the config to match.
                val updateResult = updateContainerConfig(
                    context, container.name, container.copy(rootfsPath = newRootfsPath)
                )
                if (updateResult.isFailure) {
                    // Move the data back so we don't leave the container broken.
                    RootNamespace.exec("mv $quotedNew $quotedOld 2>&1", forceNsenter = isExternal)
                    return@withContext Result.failure(
                        updateResult.exceptionOrNull() ?: Exception("Failed to update container config")
                    )
                }
            }

            // Clean up the now-empty old external directory, if any (no-op/safe if
            // it's not empty or was the internal container directory).
            val oldParentDir = oldRootfsPath.substringBeforeLast("/", "")
            if (oldParentDir.isNotEmpty() && oldParentDir != containerPath) {
                RootNamespace.exec("rmdir ${ContainerCommandBuilder.quote(oldParentDir)} 2>/dev/null", forceNsenter = isExternal)
            }

            logger?.i("Move complete. New location: $newRootfsPath")
            Result.success(newRootfsPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uninstall a container by stopping it (if running) and deleting its directory.
     * This function logs all operations using the provided logger callback.
     *
     * @param container Container to uninstall
     * @param logger Logger callback for logging operations
     * @return Result.success on success, Result.failure on error
     */
    suspend fun uninstallContainer(
        container: ContainerInfo,
        logger: ContainerLogger
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            logger.i("Starting uninstallation of container: ${container.name}")
            logger.i("")

            // Step 1: Check if container is running
            logger.i("Step 1: Checking container status...")
            val isRunning = checkContainerStatus(container.name).first

            if (isRunning) {
                logger.i("Container is currently running. Stopping it first...")
                logger.i("")

                // Stop the container using droidspaces command
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                logger.i("Executing: $stopCommand")

                val stopResult = Shell.cmd("$stopCommand 2>&1").exec()

                // Log stop command output
                if (stopResult.out.isNotEmpty()) {
                    stopResult.out.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            logger.i(trimmed)
                        }
                    }
                }
                if (stopResult.err.isNotEmpty()) {
                    stopResult.err.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            logger.e(trimmed)
                        }
                    }
                }

                if (!stopResult.isSuccess) {
                    logger.e("Failed to stop container (exit code: ${stopResult.code})")
                    logger.e("Uninstallation aborted.")
                    return@withContext Result.failure(Exception("Failed to stop container before uninstallation"))
                }

                logger.i("Container stopped successfully.")
                logger.i("")

                // Wait a moment for the container to fully stop
                kotlinx.coroutines.delay(500)
            } else {
                logger.i("Container is not running. Proceeding with deletion...")
                logger.i("")
            }

            // Step 2: Delete the container directory
            logger.i("Step 2: Deleting container directory...")
            // Delete the parent directory (which contains rootfs and config)
            val containerPath = getContainerDirectory(container.name)
            logger.i("Container path: $containerPath")

            // Use rm -rf to recursively delete the entire container directory
            val deleteCommand = "rm -rf ${ContainerCommandBuilder.quote(containerPath)} 2>&1"
            logger.i("Executing: $deleteCommand")

            val deleteResult = Shell.cmd(deleteCommand).exec()

            // Log delete command output
            if (deleteResult.out.isNotEmpty()) {
                deleteResult.out.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        logger.i(trimmed)
                    }
                }
            }
            if (deleteResult.err.isNotEmpty()) {
                deleteResult.err.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        logger.e(trimmed)
                    }
                }
            }

            if (!deleteResult.isSuccess) {
                logger.e("Failed to delete container directory (exit code: ${deleteResult.code})")
                return@withContext Result.failure(Exception("Failed to delete container directory"))
            }

            // Step 2.1: If this container's rootfs lives on external/custom storage
            // (outside the default container directory), it won't have been touched
            // by the rm -rf above -- clean it up separately.
            val rootfsParentDir = container.rootfsPath.substringBeforeLast("/", "")
            if (rootfsParentDir.isNotEmpty() && rootfsParentDir != containerPath) {
                logger.i("Removing external storage location: $rootfsParentDir")
                val extDeleteResult = RootNamespace.exec(
                    "rm -rf ${ContainerCommandBuilder.quote(rootfsParentDir)} 2>&1",
                    targetPath = rootfsParentDir
                )
                if (!extDeleteResult.isSuccess) {
                    logger.w("Warning: failed to remove external storage directory: $rootfsParentDir")
                } else {
                    logger.i("External storage location removed.")
                }
            }

            // Verify deletion
            logger.i("")
            logger.i("Verifying deletion...")
            val verifyResult = Shell.cmd("test -d ${ContainerCommandBuilder.quote(containerPath)} && echo 'exists' || echo 'deleted' 2>&1").exec()
            if (verifyResult.out.any { it.contains("exists") }) {
                logger.e("Warning: Container directory still exists after deletion attempt!")
                return@withContext Result.failure(Exception("Container directory still exists after deletion"))
            }

            logger.i("Container directory successfully deleted.")
            logger.i("")
            logger.i("Uninstallation completed successfully!")

            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Exception during uninstallation: ${e.message}")
            logger.e(e.stackTraceToString())
            Result.failure(e)
        }
    }
}
