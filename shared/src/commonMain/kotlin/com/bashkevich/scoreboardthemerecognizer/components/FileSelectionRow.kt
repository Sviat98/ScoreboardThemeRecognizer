package com.bashkevich.scoreboardthemerecognizer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.Close
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.FolderOpen
import org.jetbrains.compose.resources.stringResource
import scoreboardthemerecognizer.shared.generated.resources.Res
import scoreboardthemerecognizer.shared.generated.resources.clear_file
import scoreboardthemerecognizer.shared.generated.resources.open_file_storage

@Composable
fun FileSelectionRow(
    fileName: String,
    placeholder: String,
    onFileStorageOpen: () -> Unit,
    onClearFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFile = fileName.isNotBlank()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        IconButton(onClick = onFileStorageOpen) {
            Icon(
                imageVector = IconGroup.Default.FolderOpen,
                contentDescription = stringResource(Res.string.open_file_storage),
            )
        }
        if (hasFile) {
            Text(text = fileName)
            IconButton(onClick = onClearFile) {
                Icon(
                    imageVector = IconGroup.Default.Close,
                    contentDescription = stringResource(Res.string.clear_file),
                )
            }
        } else {
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
