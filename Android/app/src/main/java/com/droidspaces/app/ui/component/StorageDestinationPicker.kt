package com.droidspaces.app.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidspaces.app.util.SafPathResolver
import com.droidspaces.app.util.StorageChecker

/**
 * How the user wants to browse for an external storage folder.
 */
enum class StorageAccessMode {
    /** Browse using the root shell (libsu), via [FilePickerDialog]. Some
     *  USB-OTG / removable volumes aren't reachable this way until Android has
     *  registered them for the root mount namespace -- [SAF] works around that. */
    ROOT_EXPLORER,

    /** Use Android's system document-tree picker (Storage Access Framework).
     *  Goes through Android's own storage stack, so it can reach USB drives
     *  and SD cards the root browser can't see. */
    SAF
}

/**
 * A storage path field with a folder-browse button that can use either the
 * app's root-shell file browser ([FilePickerDialog]) or Android's Storage
 * Access Framework picker. Shared by the container-creation wizard's
 * StorageLocationScreen and the "Move storage" dialog so both offer the exact
 * same browsing experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDestinationPicker(
    path: String,
    onPathChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Storage path",
    placeholder: String = "/storage/1234-5678/Droidspaces",
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingStatusIcon: (@Composable () -> Unit)? = null,
    colors: TextFieldColors = DsTextFieldDefaults.colors(),
    dialogTitle: String = "Select Storage Location"
) {
    var accessMode by remember { mutableStateOf(StorageAccessMode.ROOT_EXPLORER) }
    var showRootPicker by remember { mutableStateOf(false) }
    var safError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        SafPathResolver.takePersistablePermission(context, uri)
        val resolved = SafPathResolver.resolvePathFromUri(context, uri)
        if (resolved != null) {
            safError = null
            onPathChange(resolved)
        } else {
            safError = "Couldn't get a filesystem path for that pick. Try a different " +
                "folder, or switch to Root Explorer."
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = accessMode == StorageAccessMode.ROOT_EXPLORER,
                onClick = { accessMode = StorageAccessMode.ROOT_EXPLORER; safError = null },
                label = { Text("Root Explorer", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            FilterChip(
                selected = accessMode == StorageAccessMode.SAF,
                onClick = { accessMode = StorageAccessMode.SAF; safError = null },
                label = { Text("SAF (USB access)", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }

        if (accessMode == StorageAccessMode.SAF) {
            Text(
                text = "Use this if your USB drive doesn't show up in Root Explorer. " +
                    "Android will ask you to grant access to the folder you pick, then " +
                    "we'll use that location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        OutlinedTextField(
            value = path,
            onValueChange = {
                onPathChange(it)
                safError = null
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            isError = isError || safError != null,
            supportingText = {
                val error = safError
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                } else {
                    supportingText?.invoke()
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailingStatusIcon?.invoke()
                    IconButton(onClick = {
                        when (accessMode) {
                            StorageAccessMode.ROOT_EXPLORER -> showRootPicker = true
                            StorageAccessMode.SAF -> safLauncher.launch(null)
                        }
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse storage")
                    }
                }
            }
        )
    }

    if (showRootPicker) {
        FilePickerDialog(
            title = dialogTitle,
            showFiles = false,
            onDismiss = { showRootPicker = false },
            onConfirm = { picked ->
                onPathChange(StorageChecker.normalizeStorageDir(picked))
                showRootPicker = false
            }
        )
    }
}
