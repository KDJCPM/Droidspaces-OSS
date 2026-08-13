package com.droidspaces.app.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages external and removable storage mounts (USB-OTG drives, SD cards) across
 * Android namespaces and the root environment.
 *
 * Problem Context:
 * 1. Android's Vold service mounts raw USB/SD block devices to `/mnt/media_rw/<volId>` in the root/global namespace.
 * 2. In root's mount namespace, `/storage` is an in-memory `tmpfs` (~3GB) created by init.
 * 3. Apps access external storage through a FUSE/virtualized layer (`/storage/<volId>` -> `/mnt/user/0/<volId>`).
 * 4. If root executes commands directly on `/storage/<volId>`, it mistakenly writes to the 3GB tmpfs,
 *    causing false space reports (e.g. "3GB free" on a 400GB drive) and causing loop-mount failures.
 *
 * This manager:
 * - Discovers real physical kernel mount points (`/mnt/media_rw/<volId>`, `/mnt/pass_through/0/<volId>`).
 * - Synchronizes root's mount namespace by bind-mounting the real volume to `/storage/<volId>`.
 * - Resolves virtual storage paths to canonical physical paths for low-level kernel disk operations.
 */
object StorageMountManager {

    /**
     * Extracts the volume identifier (e.g. "FFF6-F07B" or "1234-5678") from any storage path.
     * Returns null if path is internal (/data, /storage/emulated/0) or cannot be determined.
     */
    fun extractVolumeId(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = SafPathResolver.normalizePath(path).trim()
        val match = Regex("^(?:/storage|/mnt/media_rw|/mnt/pass_through/0|/mnt/user/0|/mnt/runtime/(?:default|read|write|full)|/documents|/document|/tree)/([^/:]+)").find(normalized)
            ?: Regex("""([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})""").find(normalized)
        val volId = match?.groupValues?.get(1)
        if (volId != null && volId != "emulated" && volId != "self" && volId != "primary") {
            return volId
        }
        return null
    }

    /**
     * Discovers where Vold or the Linux kernel actually mounted the physical block device for [volId].
     */
    suspend fun findPhysicalMountPoint(volId: String): String? = withContext(Dispatchers.IO) {
        if (volId.isBlank() || volId == "emulated" || volId == "self") return@withContext null

        val candidates = listOf(
            "/mnt/media_rw/$volId",
            "/mnt/pass_through/0/$volId",
            "/mnt/runtime/default/$volId",
            "/mnt/runtime/write/$volId",
            "/mnt/runtime/read/$volId",
            "/mnt/user/0/$volId"
        )
        for (cand in candidates) {
            val check = Shell.cmd("[ -d ${ContainerCommandBuilder.quote(cand)} ] && echo ok").exec()
            if (check.isSuccess && check.out.any { it.contains("ok") }) {
                return@withContext cand
            }
        }

        // Case-insensitive search in /mnt/media_rw
        val listMediaRw = Shell.cmd("for d in /mnt/media_rw/*; do [ -d \"${'$'}d\" ] && echo \"${'$'}d\"; done").exec()
        if (listMediaRw.isSuccess && listMediaRw.out.isNotEmpty()) {
            val match = listMediaRw.out.firstOrNull { it.substringAfterLast("/").equals(volId, ignoreCase = true) }
            if (match != null) return@withContext match
        }

        // Fallback: search /proc/mounts for non-tmpfs, non-storage mounts containing volId
        val mounts = Shell.cmd("grep -i -F \"$volId\" /proc/mounts 2>/dev/null | grep -v 'tmpfs' | awk '{print \$2}'").exec()
        if (mounts.isSuccess && mounts.out.isNotEmpty()) {
            val nonStorage = mounts.out.firstOrNull { it.contains(volId, ignoreCase = true) && !it.startsWith("/storage") }
            if (nonStorage != null) return@withContext nonStorage
            return@withContext mounts.out.firstOrNull()
        }

        // Single-volume fallback
        if (listMediaRw.isSuccess && listMediaRw.out.size == 1) {
            val single = listMediaRw.out.first().trim()
            if (single.isNotEmpty() && !single.contains("*")) return@withContext single
        }

        null
    }

    /**
     * Cleans up stale loop devices and stale mount points from previous USB/SD disconnects.
     */
    suspend fun cleanupStaleMountsAndLoopDevices() = withContext(Dispatchers.IO) {
        val script = """
            # 1. Ensure basic loop nodes exist (0-7 only if missing)
            for i in 0 1 2 3 4 5 6 7; do
                [ -e "/dev/block/loop${'$'}i" ] || mknod "/dev/block/loop${'$'}i" b 7 "${'$'}i" 2>/dev/null || true
                [ -e "/dev/loop${'$'}i" ] || mknod "/dev/loop${'$'}i" b 7 "${'$'}i" 2>/dev/null || true
            done

            # 2. Detach stale or dead loop devices whose backing files no longer exist
            for dev in ${'$'}(losetup -a 2>/dev/null | awk -F: '{print ${'$'}1}'); do
                if [ -n "${'$'}dev" ]; then
                    backing=${'$'}(losetup "${'$'}dev" 2>/dev/null | grep -o '([^)]*)' | tr -d '()')
                    if [ -n "${'$'}backing" ] && [ ! -e "${'$'}backing" ]; then
                        losetup -d "${'$'}dev" 2>/dev/null || true
                    fi
                fi
            done

            # 3. Check for stale bind mounts under /storage/*
            for d in /storage/*; do
                if [ -d "${'$'}d" ] && mountpoint -q "${'$'}d" 2>/dev/null; then
                    if ! ls "${'$'}d" >/dev/null 2>&1; then
                        umount -l "${'$'}d" 2>/dev/null || true
                    fi
                fi
            done
        """.trimIndent()

        Shell.cmd(script).exec()
    }

    /**
     * Ensures that in root's mount namespace, `/storage/<volId>` is bind-mounted to the
     * real physical mount point (e.g. `/mnt/media_rw/<volId>`), ensuring that all root operations
     * on `/storage/<volId>` directly hit the physical USB drive with its full capacity.
     *
     * Automatically verifies mount health and cleans up stale dead mounts from previous USB disconnects.
     */
    suspend fun ensureVolumeMounted(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        val volId = extractVolumeId(path) ?: return@withContext
        val physicalMount = findPhysicalMountPoint(volId) ?: "/mnt/media_rw/$volId"

        val qPhysical = ContainerCommandBuilder.quote(physicalMount)
        val qStorage = ContainerCommandBuilder.quote("/storage/$volId")

        val script = """
            # Ensure basic loop device nodes exist
            for i in 0 1 2 3 4 5 6 7; do
                [ -e "/dev/block/loop${'$'}i" ] || mknod "/dev/block/loop${'$'}i" b 7 "${'$'}i" 2>/dev/null || true
                [ -e "/dev/loop${'$'}i" ] || mknod "/dev/loop${'$'}i" b 7 "${'$'}i" 2>/dev/null || true
            done

            # A bind mount pins the specific mount INSTANCE it was created from,
            # not the path. On USB disconnect/reconnect, vold tears down and
            # re-creates the mount at $qPhysical from scratch -- but an existing
            # bind mount at $qStorage still points at the old, now-defunct
            # instance. `mountpoint -q` still reports it as mounted, and `ls`
            # on it can still succeed (just showing an empty/stale directory)
            # instead of failing, so that alone can't detect the staleness.
            # Comparing the underlying device id of the bind-mount target
            # against the CURRENT physical mount catches this reliably: after a
            # reconnect they differ, and we redo the bind mount.
            SRC_DEV=""
            DST_DEV=""
            [ -d $qPhysical ] && SRC_DEV=${'$'}(stat -c '%d' $qPhysical 2>/dev/null)
            [ -d $qStorage ] && DST_DEV=${'$'}(stat -c '%d' $qStorage 2>/dev/null)

            if mountpoint -q $qStorage 2>/dev/null; then
                if [ -z "${'$'}SRC_DEV" ] || [ "${'$'}SRC_DEV" != "${'$'}DST_DEV" ] || ! ls $qStorage >/dev/null 2>&1; then
                    umount -l $qStorage 2>/dev/null || true
                fi
            fi

            # Bind mount physical storage to /storage/$volId if physical exists
            if [ -d $qPhysical ]; then
                mkdir -p $qStorage 2>/dev/null
                if ! mountpoint -q $qStorage 2>/dev/null; then
                    mount --bind $qPhysical $qStorage 2>/dev/null || true
                fi
                chmod 755 $qStorage 2>/dev/null || true
            fi
        """.trimIndent()

        Shell.cmd(script).exec()
    }

    /**
     * Resolves any storage path (such as `/storage/FFF6-F07B/dir`) to its canonical physical mount
     * path (e.g. `/mnt/media_rw/FFF6-F07B/dir`) for direct kernel operations (losetup, stat -f, mke2fs, truncate).
     */
    suspend fun resolveToPhysicalPath(path: String): String = withContext(Dispatchers.IO) {
        val trimmed = path.trim()
        val volId = extractVolumeId(trimmed) ?: return@withContext trimmed
        val physicalBase = findPhysicalMountPoint(volId) ?: return@withContext trimmed

        val prefixRegex = Regex("^(?:/storage|/mnt/media_rw|/mnt/pass_through/0|/mnt/user/0|/mnt/runtime/(?:default|read|write|full))/$volId")
        val subPath = trimmed.replaceFirst(prefixRegex, "")
        "$physicalBase$subPath"
    }

    /**
     * Scans and returns all available mounted external storage volumes.
     * Ensures each found volume is bind-mounted to `/storage/<volId>` in root's namespace.
     */
    suspend fun listAllStorageVolumes(): List<String> = withContext(Dispatchers.IO) {
        val detected = mutableSetOf<String>()

        val scanScript = """
            # Scan /mnt/media_rw
            for d in /mnt/media_rw/*; do
                [ -d "${'$'}d" ] && echo "${'$'}(basename "${'$'}d")"
            done
            # Scan /storage
            for d in /storage/*; do
                [ -d "${'$'}d" ] && echo "${'$'}(basename "${'$'}d")"
            done
            # Scan /mnt/pass_through/0
            for d in /mnt/pass_through/0/*; do
                [ -d "${'$'}d" ] && echo "${'$'}(basename "${'$'}d")"
            done
        """.trimIndent()

        val result = Shell.cmd(scanScript).exec()
        if (result.isSuccess) {
            result.out.forEach { line ->
                val name = line.trim()
                if (name.isNotEmpty() && name != "self" && name != "emulated" && name != "knox" && !name.contains("*")) {
                    detected.add(name)
                }
            }
        }

        // For each detected volume ID, ensure it is mirrored to /storage/<volId>
        val volumePaths = mutableListOf<String>()
        for (volId in detected) {
            val targetStoragePath = "/storage/$volId"
            ensureVolumeMounted(targetStoragePath)
            volumePaths.add(targetStoragePath)
        }

        volumePaths.sorted()
    }
}
