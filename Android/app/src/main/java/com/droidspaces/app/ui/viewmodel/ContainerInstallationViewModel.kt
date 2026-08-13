package com.droidspaces.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.droidspaces.app.util.ContainerConfigState
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ContainerStatus
import com.droidspaces.app.util.ExistingImageManager
import com.droidspaces.app.util.ImageInspectionResult
import com.droidspaces.app.util.ValidationUtils
import com.droidspaces.app.util.toConfigState
import com.droidspaces.app.util.withConfig

/**
 * Modes for container rootfs storage.
 */
enum class ImageSourceMode {
    /** Create a new sparse ext4 image (rootfs.img) and extract tarball into it. */
    NEW_SPARSE_IMAGE,
    /** Use an existing sparse ext4 rootfs image (e.g. from USB-OTG/SD card) directly. */
    EXISTING_SPARSE_IMAGE,
    /** Extract tarball directly into a rootfs folder. */
    DIRECTORY
}

class ContainerInstallationViewModel : ViewModel() {
    var tarballUri: Uri? by mutableStateOf(null)
        private set

    var containerName: String by mutableStateOf("")
        private set

    var hostname: String by mutableStateOf("")
        private set

    var imageSourceMode: ImageSourceMode by mutableStateOf(ImageSourceMode.NEW_SPARSE_IMAGE)
        private set

    var sparseImageSizeGB: Int by mutableStateOf(8)
        private set

    // Existing image portability state
    var existingImagePath: String? by mutableStateOf(null)
        private set

    var existingImageInspection: ImageInspectionResult? by mutableStateOf(null)
        private set

    var isInspectingImage: Boolean by mutableStateOf(false)
        private set

    var useEmbeddedConfig: Boolean by mutableStateOf(false)
        private set

    var importedImageConfig: ContainerInfo? by mutableStateOf(null)
        private set

    val useSparseImage: Boolean
        get() = imageSourceMode != ImageSourceMode.DIRECTORY

    val isExistingImage: Boolean
        get() = imageSourceMode == ImageSourceMode.EXISTING_SPARSE_IMAGE

    /**
     * Custom storage root chosen for this container's rootfs (e.g. an SD card or
     * USB-OTG mount point such as "/storage/1234-5678"). Null means "use the default
     * internal location" (CONTAINERS_BASE_PATH).
     */
    var customStorageLocation: String? by mutableStateOf(null)
        private set

    /** All editable networking/security/advanced config, hoisted as one value. */
    var configState: ContainerConfigState by mutableStateOf(ContainerConfigState())
        private set

    fun setTarball(uri: Uri) {
        tarballUri = uri
    }

    fun setName(name: String, hostname: String) {
        this.containerName = name
        this.hostname = hostname
    }

    fun updateImageSourceMode(mode: ImageSourceMode) {
        this.imageSourceMode = mode
    }

    fun setSparseImageConfig(useSparseImage: Boolean, sizeGB: Int) {
        this.imageSourceMode = if (useSparseImage) ImageSourceMode.NEW_SPARSE_IMAGE else ImageSourceMode.DIRECTORY
        this.sparseImageSizeGB = sizeGB
    }

    fun setExistingImage(path: String?, inspection: ImageInspectionResult? = null) {
        this.existingImagePath = path?.trim()?.takeIf { it.isNotEmpty() }
        this.existingImageInspection = inspection
        val embedded = inspection?.embeddedConfig
        if (embedded != null) {
            this.importedImageConfig = embedded
            this.useEmbeddedConfig = true
            this.configState = embedded.toConfigState()
            if (hostname.isEmpty() || hostname == ValidationUtils.sanitizeHostname(containerName)) {
                if (embedded.hostname.isNotEmpty()) {
                    hostname = embedded.hostname
                }
            }
        }
    }

    suspend fun inspectImage(context: Context, path: String) {
        isInspectingImage = true
        try {
            val result = ExistingImageManager.inspect(path, context)
            existingImageInspection = result
            if (result.embeddedConfig != null) {
                importedImageConfig = result.embeddedConfig
                useEmbeddedConfig = true
                configState = result.embeddedConfig.toConfigState()
                if (hostname.isEmpty() || hostname == ValidationUtils.sanitizeHostname(containerName)) {
                    if (result.embeddedConfig.hostname.isNotEmpty()) {
                        hostname = result.embeddedConfig.hostname
                    }
                }
            }
        } finally {
            isInspectingImage = false
        }
    }

    fun applyImportedConfig(apply: Boolean) {
        useEmbeddedConfig = apply
        val imported = importedImageConfig ?: existingImageInspection?.embeddedConfig
        if (apply && imported != null) {
            configState = imported.toConfigState()
            if (hostname.isEmpty() || hostname == ValidationUtils.sanitizeHostname(containerName)) {
                if (imported.hostname.isNotEmpty()) {
                    hostname = imported.hostname
                }
            }
        }
    }

    /** Pass null to use the default internal storage location. */
    fun setStorageLocation(path: String?) {
        this.customStorageLocation = path?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setConfig(config: ContainerConfigState) {
        this.configState = config
    }

    fun buildConfig(): ContainerInfo? {
        if (containerName.isEmpty()) return null
        if (!isExistingImage && tarballUri == null) return null

        val finalRootfsPath = when {
            isExistingImage -> existingImagePath ?: ContainerManager.getSparseImagePath(containerName, customStorageLocation)
            useSparseImage -> ContainerManager.getSparseImagePath(containerName, customStorageLocation)
            else -> ContainerManager.getRootfsPath(containerName, customStorageLocation)
        }

        return ContainerInfo(
            name = containerName,
            hostname = hostname.ifEmpty { ValidationUtils.sanitizeHostname(containerName) },
            rootfsPath = finalRootfsPath,
            status = ContainerStatus.STOPPED,
            useSparseImage = useSparseImage,
            sparseImageSizeGB = if (useSparseImage) (existingImageInspection?.sizeGB ?: sparseImageSizeGB) else null,
        ).withConfig(configState)
    }

    fun reset() {
        tarballUri = null
        containerName = ""
        hostname = ""
        imageSourceMode = ImageSourceMode.NEW_SPARSE_IMAGE
        sparseImageSizeGB = 8
        existingImagePath = null
        existingImageInspection = null
        isInspectingImage = false
        useEmbeddedConfig = false
        importedImageConfig = null
        customStorageLocation = null
        configState = ContainerConfigState()
    }
}
