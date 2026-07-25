package com.volume_plus_plus.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.overlay.EditOrientation
import com.volume_plus_plus.app.overlay.OverlayVersion

// ── edit hub ────────────────────────────────────────────────────────────────────────────────────

/**
 * The per-version edit menu. Each [OverlayVersion] (Android 7–15) opens its own hub, from which the
 * two independent on-screen editors are launched: **Edit position** (which first asks which
 * orientation to lay out) and **Edit colours**. Both open the real panel on top of the screen to edit
 * directly, and everything saves against this version alone — no other style is affected.
 */
@Composable
fun OverlayEditHub(
    version: OverlayVersion,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEditColors: () -> Unit,
    onEditPosition: (EditOrientation) -> Unit,
) {
    var chooseOrientation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        EditorHeader(title = "Edit ${version.label}", onBack = onBack)

        Text(
            text = "Customise this style on its own. Position and colours are edited separately, and " +
                "only ${version.label} is changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        FilledTonalButton(
            onClick = { chooseOrientation = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) { Text("Edit position") }

        Text(
            text = "The real panel opens on top of your screen — drag it exactly where you want, then " +
                "Save or Cancel from the floating bar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        FilledTonalButton(
            onClick = onEditColors,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) { Text("Edit colours") }

        Text(
            text = "The panel opens on top of your screen — tap a part of it (or a swatch), dial the " +
                "colour, then Save or Cancel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }

    if (chooseOrientation) {
        OrientationDialog(
            onDismiss = { chooseOrientation = false },
            onPick = {
                chooseOrientation = false
                onEditPosition(it)
            },
        )
    }
}

/** Asks which layout to position — portrait or landscape — before opening the position editor. */
@Composable
private fun OrientationDialog(onDismiss: () -> Unit, onPick: (EditOrientation) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which layout?") },
        text = {
            Text("Portrait and landscape are positioned separately. The screen rotates to the layout " +
                "you pick so you position the panel exactly as it'll appear.")
        },
        confirmButton = {
            TextButton(onClick = { onPick(EditOrientation.LANDSCAPE) }) { Text("Landscape") }
        },
        dismissButton = {
            TextButton(onClick = { onPick(EditOrientation.PORTRAIT) }) { Text("Portrait") }
        },
    )
}

@Composable
private fun EditorHeader(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
