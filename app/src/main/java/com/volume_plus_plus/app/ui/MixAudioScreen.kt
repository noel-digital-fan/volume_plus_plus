package com.volume_plus_plus.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.data.AppInfo
import com.volume_plus_plus.app.data.AppRepository
import com.volume_plus_plus.app.data.MixPrefs
import com.volume_plus_plus.app.shizuku.ShizukuManager
import kotlinx.coroutines.launch

/**
 * The audio-mixing tab. Hosted inside [MainScreen]'s scaffold, so it receives the shared
 * [contentPadding] and [snackbar] rather than owning its own.
 */
@Composable
fun MixAudioScreen(
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    prefs: MixPrefs,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ShizukuManager.status.collectAsState()

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var enabled by remember { mutableStateOf(prefs.enabledPackages()) }
    var query by remember { mutableStateOf("") }
    var hideSystem by remember { mutableStateOf(true) }
    var warningDismissed by remember { mutableStateOf(prefs.isWarningDismissed()) }

    LaunchedEffect(status) {
        if (status == ShizukuManager.Status.READY && apps.isEmpty()) {
            apps = AppRepository.loadInstalledApps(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader(title = "Audio mixing", subtitle = "Let two or more apps play sound at the same time")

        when (status) {
            ShizukuManager.Status.READY -> {
                val filtered = remember(apps, query, hideSystem) {
                    apps.filter { app ->
                        (!hideSystem || !app.isSystem) &&
                            (query.isBlank() || app.label.contains(query, ignoreCase = true))
                    }
                }
                HideSystemToggle(
                    hideSystem = hideSystem,
                    onChange = { hideSystem = it },
                )
                if (!warningDismissed) {
                    WarningBanner(
                        onDismiss = {
                            prefs.setWarningDismissed(true)
                            warningDismissed = true
                        },
                    )
                }
                InfoCard(
                    "Normally, when one app starts playing sound, Android asks whichever app " +
                        "was already playing to pause or go quiet — this is called audio focus. " +
                        "Mixing makes an app ignore those requests, so its sound keeps playing " +
                        "on top of everything else. Turn on the switch for the app you want to " +
                        "keep hearing — for example, enable YouTube to keep hearing it while " +
                        "Spotify plays. Enabling just one of the two apps is enough. Restart " +
                        "playback in that app for the change to take effect.",
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isEnabled = enabled.contains(app.packageName),
                            onToggle = { want ->
                                scope.launch {
                                    val ok =
                                        ShizukuManager.setAudioFocusIgnored(app.packageName, want)
                                    if (ok) {
                                        prefs.setEnabled(app.packageName, want)
                                        enabled = prefs.enabledPackages()
                                    } else {
                                        snackbar.showSnackbar("Couldn't update ${app.label}")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            else -> ConnectState(
                status = status,
                onGrant = { ShizukuManager.requestPermission() },
            )
        }
    }
}

@Composable
private fun WarningBanner(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Apps don't expect to keep playing over others. With mixing on, some " +
                    "may stall, replay ads, or lose their pause and resume controls. If an app " +
                    "misbehaves, turn its switch off to go back to normal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HideSystemToggle(hideSystem: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp),
    ) {
        Text(
            text = "Hide system apps",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = hideSystem,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.75f),
        )
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = isEnabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ConnectState(status: ShizukuManager.Status, onGrant: () -> Unit) {
    val context = LocalContext.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (status) {
                ShizukuManager.Status.CONNECTING -> {
                    CircularProgressIndicator()
                    Text("Connecting to Shizuku…")
                }

                ShizukuManager.Status.NOT_INSTALLED -> {
                    Text(
                        "Shizuku is required",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "This app needs Shizuku to change audio focus without root. " +
                            "Install it, then reopen this screen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/RikkaApps/Shizuku/releases"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }) { Text("Get Shizuku") }
                }

                ShizukuManager.Status.NOT_RUNNING -> {
                    Text(
                        "Shizuku isn't set up",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Open Shizuku and start its service (via wireless debugging or " +
                            "ADB), then come back to this screen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        val launch = context.packageManager
                            .getLaunchIntentForPackage(ShizukuManager.PACKAGE_NAME)
                        if (launch != null) {
                            runCatching { context.startActivity(launch) }
                        } else {
                            onGrant()
                        }
                    }) { Text("Open Shizuku") }
                }

                ShizukuManager.Status.PERMISSION_REQUIRED -> {
                    Text(
                        "Grant Shizuku access",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Allow Volume++ to use Shizuku so it can toggle audio focus for " +
                            "other apps.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onGrant) { Text("Grant access") }
                }

                ShizukuManager.Status.READY -> Unit
            }
        }
    }
}
