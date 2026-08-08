package com.bashkevich.scoreboardthemerecognizer.components.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import scoreboardthemerecognizer.shared.generated.resources.Res
import scoreboardthemerecognizer.shared.generated.resources.old_value
import scoreboardthemerecognizer.shared.generated.resources.theme_name

@Composable
fun ThemeNameField(
    themeNameState: TextFieldState,
    oldName: String,
    modifier: Modifier = Modifier,
    showOldValue: Boolean = true,
) {
    val hasChanged = themeNameState.text.trim().toString() != oldName
    Column(modifier = modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        OutlinedTextField(
            state = themeNameState,
            label = { Text(stringResource(Res.string.theme_name)) },
            modifier = Modifier.fillMaxWidth(),
            lineLimits = TextFieldLineLimits.SingleLine
        )
        AnimatedVisibility(
            visible = showOldValue && hasChanged,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.old_value),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = oldName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
