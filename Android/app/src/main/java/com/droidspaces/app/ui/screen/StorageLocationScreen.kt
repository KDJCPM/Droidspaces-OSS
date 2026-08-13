package com.droidspaces.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.DsTextFieldDefaults
import com.droidspaces.app.ui.component.PrimaryActionBottomBar
import com.droidspaces.app.ui.component.StorageDestinationPicker
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.util.FilesystemCapabilityReport
import com.droidspaces.app.util.StorageChecker
import kotlinx.coroutines.launch

/**
 * Lets the user choose where a new container's rootfs (or rootfs.img) is stored:
 * the default internal location, or a custom path such as an SD card / USB-OTG mount point.
 *
 * For external storage:
 * - Sparse image mode is forced by default because FAT32/exFAT/NTFS drives do not
 *   support Linux POSIX permissions, ownership, or symbolic links.
 * - Comprehensive filesystem checks are performed for directory mode, with a
 *   "DANGEROUS TERRITORY" bypass mechanism for advanced users.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StorageLocationScreen(
    initialPath: String?,
    useSparseImage: Boolean,
    onSwitchToSparseImage: () -> Unit = {},
    onNext: (path: String?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var useCustomLocation by remember { mutableStateOf(!initialPath.isNullOrBlank()) }
    var customPath by remember { mutableStateOf(initialPath ?: "") }
    var detectedVolumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var sharedStoragePath by remember { mutableStateOf<String?>(null) }
    var isDetecting by remember { mutableStateOf(false) }

    // Filesystem capability check state
    var fsReport by remember { mutableStateOf<FilesystemCapabilityReport?>(null) }
    var isCheckingFs by remember { mutableStateOf(false) }
    var isBypassed by remember { mutableStateOf(false) }
    var showDangerousDialog by remember { mutableStateOf(false) }
    var bypassInputText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val trimmedCustomPath = StorageChecker.normalizeStorageDir(customPath.trim())
    val isCustomPathValid = trimmedCustomPath.startsWith("/")

    fun detectVolumes() {
        isDetecting = true
        scope.launch {
            detectedVolumes = StorageChecker.listStorageVolumes()
            sharedStoragePath = StorageChecker.getSharedStoragePath()
            isDetecting = false
        }
    }

    fun checkFsCapabilities(path: String) {
        if (path.isNotBlank() && path.startsWith("/")) {
            isCheckingFs = true
            scope.launch {
                fsReport = StorageChecker.inspectFilesystemCapabilities(path, context)
                isCheckingFs = false
            }
        } else {
            fsReport = null
        }
    }

    LaunchedEffect(Unit) {
        detectVolumes()
        if (useCustomLocation && trimmedCustomPath.isNotBlank() && !useSparseImage) {
            checkFsCapabilities(trimmedCustomPath)
        }
    }

    LaunchedEffect(trimmedCustomPath, useSparseImage, useCustomLocation) {
        if (useCustomLocation && !useSparseImage && trimmedCustomPath.startsWith("/")) {
            isBypassed = false
            checkFsCapabilities(trimmedCustomPath)
        }
    }

    // Determine if next is allowed
    val isNextEnabled = when {
        !useCustomLocation -> true
        !isCustomPathValid -> false
        useSparseImage -> true
        isBypassed -> true
        fsReport?.isFullyCompatibleWithDirectoryMode == true -> true
        else -> false
    }

    // Dangerous Territory Confirmation Dialog
    if (showDangerousDialog) {
        val requiredPhrase = stringResource(R.string.i_understand_caps)
        val isConfirmed = bypassInputText.trim() == requiredPhrase

        Dialog(
            onDismissRequest = { showDangerousDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .imePadding(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = stringResource(R.string.fs_compat_bypass_dialog_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.fs_compat_dangerous_desc,
                            fsReport?.fsType ?: "non-native"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.privileged_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    Text(
                        text = stringResource(R.string.privileged_confirm_instruction),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = bypassInputText,
                        onValueChange = { bypassInputText = it },
                        placeholder = { Text(requiredPhrase) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = DsTextFieldDefaults.colors()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDangerousDialog = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }

                        Button(
                            onClick = {
                                isBypassed = true
                                showDangerousDialog = false
                            },
                            enabled = isConfirmed,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Bypass")
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_configuration)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        bottomBar = {
            PrimaryActionBottomBar(
                label = stringResource(R.string.next_configuration),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = {
                    onNext(if (useCustomLocation) trimmedCustomPath else null)
                },
                enabled = isNextEnabled
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Where should this container's data live?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (!useSparseImage) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Directory rootfs mode needs a Linux-native filesystem (ext4/f2fs). " +
                                "Most SD cards and USB drives are FAT32/exFAT, which will break the container. " +
                                "Go back and enable Sparse Image mode if you're using external/USB storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Internal (default) option
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { useCustomLocation = false },
                shape = RoundedCornerShape(20.dp),
                color = if (!useCustomLocation) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    if (!useCustomLocation) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RadioButton(selected = !useCustomLocation, onClick = { useCustomLocation = false })
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Internal storage (recommended)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Data lives in /data/local/Droidspaces on internal flash.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Custom / external option
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { useCustomLocation = true },
                shape = RoundedCornerShape(20.dp),
                color = if (useCustomLocation) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    if (useCustomLocation) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RadioButton(selected = useCustomLocation, onClick = { useCustomLocation = true })
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "External / USB storage",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "SD card, USB-OTG drive, or any custom mounted location.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    if (useCustomLocation) {
                        Spacer(modifier = Modifier.height(16.dp))

                        StorageDestinationPicker(
                            path = customPath,
                            onPathChange = {
                                customPath = it
                                if (!useSparseImage) {
                                    checkFsCapabilities(it)
                                }
                            },
                            placeholder = "/storage/1234-5678/Droidspaces",
                            isError = trimmedCustomPath.isNotEmpty() && !isCustomPathValid,
                            supportingText = {
                                if (trimmedCustomPath.isNotEmpty() && !isCustomPathValid) {
                                    Text("Path must be absolute -- start it with \"/\"")
                                }
                            },
                            colors = DsTextFieldDefaults.colors(),
                            dialogTitle = "Select Storage Location"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Filesystem capability check results for Directory Mode
                        if (!useSparseImage) {
                            if (isCheckingFs) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    LoadingIndicator(size = LoadingSize.Small)
                                    Text(
                                        text = stringResource(R.string.fs_compat_checking),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (fsReport != null) {
                                val report = fsReport!!
                                if (report.isFullyCompatibleWithDirectoryMode) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.fs_compat_valid_title),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = "Filesystem '${report.fsType}' supports POSIX permissions and symbolic links.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                } else if (isBypassed) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.fs_compat_bypassed_tag),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                                Text(
                                                    text = "Checks bypassed. Running directory rootfs on non-native '${report.fsType}' filesystem.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Incompatible card
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ErrorOutline,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.fs_compat_invalid_title),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }

                                            Text(
                                                text = stringResource(R.string.fs_compat_external_force_sparse_msg),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            // Check breakdown
                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(
                                                    text = "• Filesystem: ${report.fsType} (${if (report.isLinuxNativeFs) "POSIX Native" else "Non-native"})",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    text = "• Permissions (chmod/chown): ${if (report.supportsChmodChown) "Supported" else "Not supported"}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    text = "• Symbolic links (symlinks): ${if (report.supportsSymlinks) "Supported" else "Not supported"}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                if (report.isNoExec) {
                                                    Text(
                                                        text = "• Mount flag: noexec (Binaries blocked from execution)",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }

                                            // Action buttons
                                            Button(
                                                onClick = onSwitchToSparseImage,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(stringResource(R.string.fs_compat_switch_sparse))
                                            }

                                            TextButton(
                                                onClick = {
                                                    bypassInputText = ""
                                                    showDangerousDialog = true
                                                },
                                                modifier = Modifier.align(Alignment.CenterHorizontally)
                                            ) {
                                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    stringResource(R.string.fs_compat_bypass_button),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Detected volumes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isDetecting) {
                            Text(
                                text = "Scanning /storage ...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        } else if (detectedVolumes.isEmpty() && sharedStoragePath == null) {
                            Text(
                                text = "No external volumes detected. You can still type a path manually, " +
                                        "or switch to SAF above to grant access to a USB drive Root Explorer can't see.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                sharedStoragePath?.let { shared ->
                                    AssistChip(
                                        onClick = {
                                            customPath = "$shared/Droidspaces"
                                            if (!useSparseImage) checkFsCapabilities("$shared/Droidspaces")
                                        },
                                        label = { Text("This device's storage") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Smartphone,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                }
                                detectedVolumes.forEach { volume ->
                                    AssistChip(
                                        onClick = {
                                            customPath = "$volume/Droidspaces"
                                            if (!useSparseImage) checkFsCapabilities("$volume/Droidspaces")
                                        },
                                        label = { Text(volume.substringAfterLast("/")) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Usb,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { detectVolumes() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Rescan")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
