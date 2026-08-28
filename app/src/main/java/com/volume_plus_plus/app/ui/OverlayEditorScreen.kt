package com.volume_plus_plus.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.volume_plus_plus.app.i18n.label
import com.volume_plus_plus.app.i18n.strings
import com.volume_plus_plus.app.overlay.EditOrientation
import com.volume_plus_plus.app.overlay.OverlayVersion

// ── edit hub ────────────────────────────────────────────────────────────────────────────────────

/**
 * The per-version edit menu. Each [OverlayVersion] (Android 7–15) opens its own hub, from which the
 * two independent on-screen editors are launched: **Edit position** (which first asks which
 * orientation to lay out) and **Edit colours**. Both open the real panel on top of the screen to edit
 * directly, and everything saves against this version alone — no other style is affected.
 *
 * Below them sit the matching restores — **Restore default position** and **Restore default
 * colours** — so a style can be put back to how it ships without hunting the edit back by hand.
 * Each asks to confirm first (they throw away work), and each undoes only its own half: restoring the
 * position keeps the colours, and vice versa.
 */
@Composable
fun OverlayEditHub(
    version: OverlayVersion,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEditColors: () -> Unit,
    onEditPosition: (EditOrientation) -> Unit,
    onRestorePosition: () -> Unit,
    onRestoreColors: () -> Unit,
) {
    val s = strings()
    var chooseOrientation by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<RestoreTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        EditorHeader(title = s.editStyleTitle(version.label), onBack = onBack)

        Text(
            text = s.editStyleIntro(version.label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        FilledTonalButton(
            onClick = { chooseOrientation = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) { Text(s.editPosition) }

        Text(
            text = s.editPositionHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        FilledTonalButton(
            onClick = onEditColors,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) { Text(s.editColours) }

        Text(
            text = s.editColoursHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))

        Text(
            text = s.editRestoreDefaults,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Text(
            text = s.editRestoreDefaultsHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        OutlinedButton(
            onClick = { confirmRestore = RestoreTarget.POSITION },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) { Text(s.editRestorePosition) }

        OutlinedButton(
            onClick = { confirmRestore = RestoreTarget.COLORS },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) { Text(s.editRestoreColours) }

        Spacer(Modifier.height(24.dp))
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

    confirmRestore?.let { target ->
        RestoreDialog(
            version = version,
            target = target,
            onDismiss = { confirmRestore = null },
            onConfirm = {
                confirmRestore = null
                when (target) {
                    RestoreTarget.POSITION -> onRestorePosition()
                    RestoreTarget.COLORS -> onRestoreColors()
                }
            },
        )
    }
}

/** Which half of the style the hub is about to restore — also what its confirmation is asking about. */
private enum class RestoreTarget { POSITION, COLORS }

/** Confirms a restore before it throws away that half of the style's edits. */
@Composable
private fun RestoreDialog(
    version: OverlayVersion,
    target: RestoreTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val s = strings()
    val position = target == RestoreTarget.POSITION
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (position) s.editRestorePosition else s.editRestoreColours) },
        text = {
            Text(
                if (position) s.editRestorePositionBody(version.label)
                else s.editRestoreColoursBody(version.label),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(s.editRestoreConfirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Asks which layout to position — portrait or landscape — before opening the position editor. */
@Composable
private fun OrientationDialog(onDismiss: () -> Unit, onPick: (EditOrientation) -> Unit) {
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.editWhichLayout) },
        text = { Text(s.editWhichLayoutBody) },
        confirmButton = {
            TextButton(onClick = { onPick(EditOrientation.LANDSCAPE) }) {
                Text(EditOrientation.LANDSCAPE.label(s))
            }
        },
        dismissButton = {
            TextButton(onClick = { onPick(EditOrientation.PORTRAIT) }) {
                Text(EditOrientation.PORTRAIT.label(s))
            }
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
            Icon(painterResource(R.drawable.ic_close), contentDescription = strings().back)
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
