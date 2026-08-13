package com.droidspaces.app.util

import android.content.Context
import android.os.Process
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for running root commands with synchronized external storage mounts.
 *
 * Background:
 * 1. Vold (Android's volume manager) mounts external storage (SD cards, USB-OTG drives)
 *    into `/mnt/media_rw/<volId>` in the root namespace and creates per-process mount namespaces.
 * 2. In root's namespace, `/storage` is an empty in-memory tmpfs (~3GB).
 * 3. By ensuring volume mounts are mirrored into `/storage/<volId>` via [StorageMountManager]
 *    and optionally entering the app namespace when available, root commands seamlessly operate
 *    on the real physical block device (e.g. 400GB USB drive) instead of the phantom tmpfs.
 */
object RootNamespace {
    @Volatile
    private var nsenterChecked: Boolean = false
    @Volatile
    private var nsenterCommand: String? = null // resolved invocation, e.g. "nsenter" or "<busybox path> nsenter"

    /**
     * Checks if `nsenter` is available and functioning on the device.
     * Caches the result after the first check.
     */
    suspend fun isNsenterAvailable(): Boolean = withContext(Dispatchers.IO) {
        resolveNsenterCommand() != null
    }

    /**
     * Resolves a working `nsenter` invocation, trying the system binary first and
     * falling back to the app's bundled BusyBox (which includes an `nsenter` applet).
     * `nsenter` isn't a stock Android/toybox applet -- on many devices/ROMs it simply
     * isn't on PATH at all, in which case relying on a bare `nsenter` call silently
     * no-ops (the command falls through unwrapped) even when the caller explicitly
     * asked for it. Falling back to BusyBox's copy, which this app already bundles
     * and uses everywhere else, avoids that silent failure. Result is cached after
     * the first successful/failed resolution.
     */
    private suspend fun resolveNsenterCommand(): String? = withContext(Dispatchers.IO) {
        if (nsenterChecked) return@withContext nsenterCommand

        val pid = Process.myPid()
        val candidates = listOf("nsenter", "${Constants.BUSYBOX_BINARY_PATH} nsenter")
        for (candidate in candidates) {
            // Note: 'echo' is a shell builtin on Android, so we must invoke via sh -c
            val result = Shell.cmd("$candidate -t $pid -m /system/bin/sh -c 'echo ok' 2>/dev/null").exec()
            if (result.isSuccess && result.out.any { it.contains("ok") }) {
                nsenterCommand = candidate
                nsenterChecked = true
                return@withContext candidate
            }
        }
        nsenterChecked = true
        nsenterCommand = null
        null
    }

    /**
     * Determines whether a given path is an external/removable storage path that
     * requires mount namespace wrapping (e.g. `/storage/1234-5678/...`, `/mnt/media_rw/...`).
     */
    fun isExternalStorage(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.trim()
        return (normalized.startsWith("/storage/") && !normalized.startsWith("/storage/emulated")) ||
                normalized.startsWith("/mnt/media_rw/") ||
                normalized.startsWith("/mnt/pass_through/0/")
    }

    /**
     * Wraps a shell command with `nsenter` if targeting external storage or if
     * [forceNsenter] is set, provided `nsenter` is available.
     *
     * @param cmd The shell command string to execute
     * @param targetPath Path being operated on (used to auto-detect if nsenter is needed)
     * @param forceNsenter Explicitly force nsenter wrapping regardless of targetPath
     */
    suspend fun wrapCmd(
        cmd: String,
        targetPath: String? = null,
        forceNsenter: Boolean = false
    ): String {
        val targetsExternal = targetPath != null && isExternalStorage(targetPath)
        if (targetsExternal) {
            StorageMountManager.ensureVolumeMounted(targetPath)
        }
        // Root's own mount namespace only sees external volumes if the bind-mount in
        // ensureVolumeMounted actually took effect, which isn't reliable on every
        // device/ROM. The app's own process already has a working FUSE-backed view of
        // external storage (the same one e.g. the Files app uses) -- entering THAT
        // namespace via nsenter (while keeping root privilege) is the reliable path,
        // so external-storage targets always route through it, not just when a call
        // site remembers to pass forceNsenter explicitly.
        if (forceNsenter || targetsExternal) {
            val nsenterCmd = resolveNsenterCommand()
            if (nsenterCmd != null) {
                val pid = Process.myPid()
                return "$nsenterCmd -t $pid -m /system/bin/sh -c ${ContainerCommandBuilder.quote(cmd)}"
            }
        }
        return cmd
    }

    /**
     * Rewrites [cmd] so any occurrence of `/storage/<volId>` (the path derived
     * from [targetPath]) is replaced with the CURRENT physical mount point for
     * that volume (e.g. `/mnt/media_rw/<volId>`), if one can be found.
     *
     * This is preferred over bind-mounting or nsenter-ing into `/storage/<volId>`:
     * that path is a *view* of the volume (a bind mount in root's namespace, or
     * a FUSE passthrough in the app's namespace) that's only refreshed when
     * something notices the volume changed. On USB disconnect/reconnect, vold
     * tears down and recreates the *physical* mount fresh, but neither of those
     * views necessarily follows along automatically -- root's bind mount can be
     * left pointing at the old, dead mount instance, and even the app's own
     * process's mount namespace was observed to need a full app restart to pick
     * up a replugged drive. `/mnt/media_rw/<volId>` is vold's own live mount in
     * root's *global*, always-current namespace, so operating on it directly
     * sidesteps all of that staleness -- no bind mount, no nsenter, no restart.
     *
     * Returns null if [targetPath]'s volume can't be resolved to a physical
     * mount right now (removed, or a provider we don't recognize), so the
     * caller can fall back to the /storage/<volId> + nsenter path.
     */
    private suspend fun physicalizeCmd(cmd: String, targetPath: String): String? {
        val volId = StorageMountManager.extractVolumeId(targetPath) ?: return null
        val storagePrefix = "/storage/$volId"
        if (!cmd.contains(storagePrefix)) return null
        val physicalBase = StorageMountManager.findPhysicalMountPoint(volId) ?: return null
        return cmd.replace(storagePrefix, physicalBase)
    }

    /**
     * Executes a command using libsu, preferring the volume's physical mount
     * point over `/storage/<volId>` when possible (see [physicalizeCmd]), and
     * falling back to `/storage/<volId>` (bind-mounted/nsenter'd as needed) only
     * when the physical mount can't be resolved.
     */
    suspend fun exec(
        cmd: String,
        targetPath: String? = null,
        forceNsenter: Boolean = false
    ): Shell.Result = withContext(Dispatchers.IO) {
        if (targetPath != null && isExternalStorage(targetPath)) {
            val physicalCmd = physicalizeCmd(cmd, targetPath)
            if (physicalCmd != null) {
                return@withContext Shell.cmd(physicalCmd).exec()
            }
            StorageMountManager.ensureVolumeMounted(targetPath)
        }
        val finalCmd = wrapCmd(cmd, targetPath, forceNsenter)
        Shell.cmd(finalCmd).exec()
    }

    /**
     * Writes a shell script to temporary cache, makes it executable, runs it --
     * preferring the volume's physical mount point over `/storage/<volId>` when
     * possible (see [physicalizeCmd]) -- and cleans it up afterwards.
     */
    suspend fun runScript(
        scriptContent: String,
        context: Context,
        targetPath: String? = null,
        forceNsenter: Boolean = false
    ): Shell.Result = withContext(Dispatchers.IO) {
        var content = scriptContent
        var usedPhysical = false
        if (targetPath != null && isExternalStorage(targetPath)) {
            val physicalContent = physicalizeCmd(scriptContent, targetPath)
            if (physicalContent != null) {
                content = physicalContent
                usedPhysical = true
            } else {
                StorageMountManager.ensureVolumeMounted(targetPath)
            }
        }
        val scriptFile = File("${context.cacheDir}/.ds_ns_${System.currentTimeMillis()}_${Process.myPid()}.sh")
        try {
            FileOutputStream(scriptFile).use { fos ->
                fos.write(content.toByteArray())
            }
            Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(scriptFile.absolutePath)}").exec()

            // Invoke via `sh`, not a direct exec of the file path -- app-private cache
            // directories can be SELinux/mount-labeled to deny direct execution even
            // after chmod 755, failing with a misleading "No such file or directory".
            // `sh "$script"` only needs read access, sidestepping that restriction.
            val runCmd = "sh ${ContainerCommandBuilder.quote(scriptFile.absolutePath)}"
            if (usedPhysical) {
                Shell.cmd(runCmd).exec()
            } else {
                exec(runCmd, targetPath, forceNsenter)
            }
        } finally {
            try {
                scriptFile.delete()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
}
