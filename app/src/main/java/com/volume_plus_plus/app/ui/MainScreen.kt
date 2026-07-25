package com.volume_plus_plus.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.data.MixPrefs
import com.volume_plus_plus.app.ui.theme.ThemeMode

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    VOLUME("Volume", R.drawable.ic_nav_volume),
    MIXING("Mixing", R.drawable.ic_nav_mixing),
    OVERLAY("Overlay", R.drawable.ic_nav_overlay),
}

/**
 * App root: a bottom navigation bar switching between the [VolumePanelScreen] (default landing,
 * works with no privileges) and the Shizuku-backed [MixAudioScreen]. Owns the single shared
 * scaffold + snackbar host that both tabs draw into, plus the top bar's light/dark/auto toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    prefs: MixPrefs,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.VOLUME) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Volume++") },
                actions = {
                    ThemeToggle(current = themeMode, onSelect = onThemeModeChange)
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                painter = painterResource(entry.icon),
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.VOLUME -> VolumePanelScreen(contentPadding = padding, snackbar = snackbar)
            Tab.MIXING -> MixAudioScreen(contentPadding = padding, snackbar = snackbar, prefs = prefs)
            Tab.OVERLAY -> OverlayScreen(contentPadding = padding)
        }
    }
}

/** Top-bar action: an icon button opening a Light / Dark / System default picker, with a check on
 *  the active mode. Applying a choice re-themes the whole app immediately. */
@Composable
private fun ThemeToggle(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_theme),
            contentDescription = "Theme",
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        ThemeMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                onClick = {
                    onSelect(mode)
                    expanded = false
                },
                trailingIcon = {
                    if (mode == current) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Selected",
                        )
                    }
                },
            )
        }
    }
}
