package com.droidspaces.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.DsTextFieldDefaults
import com.droidspaces.app.ui.component.FilePickerDialog
import com.droidspaces.app.ui.component.PrimaryActionBottomBar
import com.droidspaces.app.ui.component.StorageAccessMode
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.ui.viewmodel.ImageSourceMode
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ImageInspectionResult
import com.droidspaces.app.util.SafPathResolver

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SparseImageConfigScreen(
    initialMode: ImageSourceMode = ImageSourceMode.NEW_SPARSE_IMAGE,
    initialSizeGB: Int = 8,
    initialExistingImagePath: String? = null,
    initialInspection: ImageInspectionResult? = null,
    isInspecting: Boolean = false,
    useEmbeddedConfig: Boolean = false,
    onModeChange: (ImageSourceMode) -> Unit = {},
    onInspectImage: (String) -> Unit = {},
    onApplyImportedConfig: (Boolean) -> Unit = {},
    onNext: (mode: ImageSourceMode, sizeGB: Int, existingImagePath: String?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(initialMode) }
    var sizeGB by remember { mutableStateOf(initialSizeGB.toString()) }
    var sizeError by remember { mutableStateOf<String?>(null) }
    var existingImagePath by remember { mutableStateOf(initialExistingImagePath ?: "") }
    var showRootPicker by remember { mutableStateOf(false) }

    val fieldShape = RoundedCornerShape(16.dp)
    val fieldColors = DsTextFieldDefaults.colors()

    // SAF Document picker for picking .img file from external/system documents
    val safDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            SafPathResolver.takePersistablePermission(context, uri)
            val resolved = SafPathResolver.resolvePathFromUri(context, uri) ?: SafPathResolver.normalizePath(uri.path ?: "")
            if (resolved.isNotBlank()) {
                existingImagePath = resolved
                onInspectImage(resolved)
            }
        }
    }

    if (showRootPicker) {
        FilePickerDialog(
            title = stringResource(R.string.browse_existing_image),
            showFiles = true,
            onDismiss = { showRootPicker = false },
            onConfirm = { picked ->
                existingImagePath = picked
                showRootPicker = false
                onInspectImage(picked)
            }
        )
    }

    // Auto-inspect if initial path is provided and inspection is null
    LaunchedEffect(existingImagePath) {
        if (existingImagePath.isNotBlank() && initialInspection == null && !isInspecting) {
            onInspectImage(existingImagePath)
        }
    }

    val isNextEnabled = when (selectedMode) {
        ImageSourceMode.NEW_SPARSE_IMAGE -> sizeGB.toIntOrNull()?.let { it in 4..512 } == true
        ImageSourceMode.EXISTING_SPARSE_IMAGE -> existingImagePath.isNotBlank() && (initialInspection == null || initialInspection.isValid)
        ImageSourceMode.DIRECTORY -> true
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sparse_image_configuration)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        bottomBar = {
            val nextLabel = if (selectedMode == ImageSourceMode.EXISTING_SPARSE_IMAGE) {
                stringResource(R.string.next_configuration)
            } else {
                stringResource(R.string.next_storage)
            }
            PrimaryActionBottomBar(
                label = nextLabel,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = {
                    when (selectedMode) {
                        ImageSourceMode.NEW_SPARSE_IMAGE -> {
                            val s = sizeGB.toIntOrNull() ?: 8
                            onNext(ImageSourceMode.NEW_SPARSE_IMAGE, s, null)
                        }
                        ImageSourceMode.EXISTING_SPARSE_IMAGE -> {
                            val s = initialInspection?.sizeGB ?: 8
                            onNext(ImageSourceMode.EXISTING_SPARSE_IMAGE, s, existingImagePath)
                        }
                        ImageSourceMode.DIRECTORY -> {
                            onNext(ImageSourceMode.DIRECTORY, 8, null)
                        }
                    }
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
                text = stringResource(R.string.storage_configuration),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Mode Selector Cards
            StorageModeOptionCard(
                title = stringResource(R.string.storage_mode_new_image),
                description = stringResource(R.string.storage_mode_new_image_desc),
                icon = Icons.Default.AddCircleOutline,
                isSelected = selectedMode == ImageSourceMode.NEW_SPARSE_IMAGE,
                onClick = {
                    selectedMode = ImageSourceMode.NEW_SPARSE_IMAGE
                    onModeChange(ImageSourceMode.NEW_SPARSE_IMAGE)
                }
            )

            StorageModeOptionCard(
                title = stringResource(R.string.storage_mode_existing_image),
                description = stringResource(R.string.storage_mode_existing_image_desc),
                icon = Icons.Default.SdCard,
                isSelected = selectedMode == ImageSourceMode.EXISTING_SPARSE_IMAGE,
                onClick = {
                    selectedMode = ImageSourceMode.EXISTING_SPARSE_IMAGE
                    onModeChange(ImageSourceMode.EXISTING_SPARSE_IMAGE)
                }
            )

            StorageModeOptionCard(
                title = stringResource(R.string.storage_mode_directory),
                description = stringResource(R.string.storage_mode_directory_desc),
                icon = Icons.Default.Folder,
                isSelected = selectedMode == ImageSourceMode.DIRECTORY,
                onClick = {
                    selectedMode = ImageSourceMode.DIRECTORY
                    onModeChange(ImageSourceMode.DIRECTORY)
                }
            )

            // Configuration section based on mode
            when (selectedMode) {
                ImageSourceMode.NEW_SPARSE_IMAGE -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.image_size),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedTextField(
                                value = sizeGB,
                                onValueChange = { newValue ->
                                    sizeGB = newValue
                                    val sizeInt = newValue.toIntOrNull()
                                    sizeError = when {
                                        newValue.isEmpty() -> context.getString(R.string.size_required)
                                        sizeInt == null -> context.getString(R.string.invalid_number)
                                        sizeInt < 4 -> context.getString(R.string.minimum_size_4gb)
                                        sizeInt > 512 -> context.getString(R.string.maximum_size_512gb)
                                        else -> null
                                    }
                                },
                                label = { Text(stringResource(R.string.size_gb)) },
                                placeholder = { Text(stringResource(R.string.default_size_gb_hint)) },
                                isError = sizeError != null,
                                supportingText = sizeError?.let { { Text(it) } } ?: {
                                    Text(stringResource(R.string.enter_size_between_4_512_gb))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = fieldShape,
                                colors = fieldColors,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            )
                        }
                    }
                }

                ImageSourceMode.EXISTING_SPARSE_IMAGE -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.existing_image_path),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            // Browser Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = false,
                                    onClick = { showRootPicker = true },
                                    label = { Text("Root Explorer") },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        safDocLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                    },
                                    label = { Text("SAF (USB/Files)") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Path Input Field
                            OutlinedTextField(
                                value = existingImagePath,
                                onValueChange = { newValue ->
                                    val normalized = SafPathResolver.normalizePath(newValue)
                                    existingImagePath = normalized
                                    if (normalized.endsWith(".img") && normalized.startsWith("/")) {
                                        onInspectImage(normalized)
                                    }
                                },
                                label = { Text(stringResource(R.string.existing_image_path)) },
                                placeholder = { Text(stringResource(R.string.existing_image_path_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = fieldShape,
                                colors = fieldColors,
                                leadingIcon = {
                                    Icon(Icons.Default.Storage, contentDescription = null)
                                },
                                trailingIcon = {
                                    if (existingImagePath.isNotBlank()) {
                                        IconButton(onClick = { onInspectImage(existingImagePath) }) {
                                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                                        }
                                    }
                                }
                            )

                            // Inspection status & details
                            if (isInspecting) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    LoadingIndicator(size = LoadingSize.Small)
                                    Text(
                                        text = stringResource(R.string.inspecting_image),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (initialInspection != null) {
                                val inspection = initialInspection
                                if (inspection.isValid) {
                                    // Valid Image Card
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.valid_ext4_image),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Text(
                                                text = stringResource(R.string.image_size_info, inspection.sizeGB, inspection.path.substringAfterLast("/")),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            val os = inspection.osName
                                            if (!os.isNullOrBlank()) {
                                                Text(
                                                    text = stringResource(R.string.os_detected, os),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    // Embedded Config Card
                                    if (inspection.embeddedConfig != null) {
                                        val embedded = inspection.embeddedConfig
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.embedded_config_found),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }

                                                Text(
                                                    text = stringResource(R.string.embedded_config_desc),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                                )

                                                // Summary details of embedded config
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text(
                                                            text = "Hostname: ${embedded.hostname} · Net: ${embedded.netMode}",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        if (embedded.portForwards.isNotEmpty()) {
                                                            Text(
                                                                text = "${embedded.portForwards.size} Port Forward(s) configured",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                                            )
                                                        }
                                                    }

                                                    Switch(
                                                        checked = useEmbeddedConfig,
                                                        onCheckedChange = { onApplyImportedConfig(it) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Error Card
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
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
                                                text = inspection.errorMessage ?: stringResource(R.string.invalid_existing_image),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ImageSourceMode.DIRECTORY -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = stringResource(R.string.storage_mode_directory),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Directory mode unpacks the container root filesystem directly into a folder. Note: This requires a POSIX-compliant filesystem (ext4/f2fs). For USB drives formatted as FAT32/exFAT, use a Sparse Image instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StorageModeOptionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                tonalElevation = 0.dp
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
