package com.volume_plus_plus.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.data.AppInfo
import com.volume_plus_plus.app.data.AppRepository
import com.volume_plus_plus.app.data.MixPrefs
import com.volume_plus_plus.app.data.OverlayPrefs
import com.volume_plus_plus.app.i18n.strings
import com.volume_plus_plus.app.privileged.PrivilegedManager
import com.volume_plus_plus.app.ui.theme.successColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Material 3's disabled opacity, matching the greyed-out sections on the other tabs. */
private const val DISABLED_ALPHA = 0.38f

/**
 * How often the setup checklist re-measures Shizuku while it's on screen. Shizuku tells us when
 * its service starts or dies, but nothing tells us the user finished installing the app or
 * authorised us from inside Shizuku's own UI, and a checklist that needs the screen reopened
 * before it notices isn't a checklist.
 */
private const val SETUP_POLL_MS = 600L

/**
 * The audio-mixing tab. Hosted inside [MainScreen]'s scaffold, so it receives the shared
 * [contentPadding] and [snackbar] rather than owning its own.
 *
 * Mixing rides on the Volume++ panel, so it has nothing to drive while "Use system volume control"
 * is on: the whole page greys out behind [SystemPanelBlocker], whose button calls
 * [onOpenOverlaySettings] to hand the user to the Overlay tab with that switch spotlit.
 */
@Composable
fun MixAudioScreen(
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    prefs: MixPrefs,
    onOpenOverlaySettings: () -> Unit,
) {
    val s = strings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup by PrivilegedManager.setup.collectAsState()
    val overlayPrefs = remember { OverlayPrefs(context) }

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var enabled by remember { mutableStateOf(prefs.enabledPackages()) }
    var query by remember { mutableStateOf("") }
    var hideSystem by remember { mutableStateOf(true) }
    var warningDismissed by remember { mutableStateOf(prefs.isWarningDismissed()) }
    var systemVolumePanel by remember { mutableStateOf(overlayPrefs.isSystemVolumePanelEnabled()) }

    LaunchedEffect(setup.ready) {
        if (setup.ready && apps.isEmpty()) {
            apps = AppRepository.loadInstalledApps(context)
        }
    }

    // Keep the checklist honest while the user is off installing, starting or authorising Shizuku.
    // Stops once mixing is live, while the app is in the background, and while the page is blocked
    // off behind the system-panel switch, none of which have a checklist to keep up to date. Root
    // mode is exempt too: there is nothing on that route the user can go away and change, so a
    // repeating refresh would only re-ask for superuser access they've already answered.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(setup.ready, setup.backend, systemVolumePanel, lifecycleOwner) {
        if (setup.ready || systemVolumePanel) return@LaunchedEffect
        if (setup.backend == PrivilegedManager.Backend.ROOT) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                PrivilegedManager.refresh()
                delay(SETUP_POLL_MS)
            }
        }
    }

    // The switch lives on another tab (and survives the app being backgrounded), so re-read it on
    // resume rather than trusting the value this composition started with.
    LifecycleResumeEffect(Unit) {
        systemVolumePanel = overlayPrefs.isSystemVolumePanelEnabled()
        onPauseOrDispose { }
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (systemVolumePanel) DISABLED_ALPHA else 1f),
        ) {
            ScreenHeader(title = s.mixingTitle, subtitle = s.mixingSubtitle)

            if (!setup.ready) {
                SetupChecklist(setup = setup)
            } else {
                // Sideloaded-from-GitHub builds trip banking apps' "unofficial app store" check.
                // Offered here because relabelling the install source needs the very privileged link
                // this tab has just brought up; the card shows itself only while the source still
                // looks sideloaded, so it clears once fixed and returns after each GitHub update.
                var bankingRecognised by remember {
                    mutableStateOf(PrivilegedManager.installSourceRecognised(context))
                }
                var showBankingConfirm by remember { mutableStateOf(false) }
                var bankingWorking by remember { mutableStateOf(false) }
                if (!bankingRecognised) {
                    BankingFixCard(working = bankingWorking, onFix = { showBankingConfirm = true })
                }
                if (showBankingConfirm) {
                    BankingFixDialog(
                        onDismiss = { showBankingConfirm = false },
                        onConfirm = {
                            showBankingConfirm = false
                            scope.launch {
                                bankingWorking = true
                                val ok = PrivilegedManager.fixInstallSource()
                                bankingWorking = false
                                // Usually unreachable: the reinstall's commit kills this process
                                // first, and the fresh launch simply finds the source recognised.
                                if (ok) {
                                    bankingRecognised = true
                                    snackbar.showSnackbar(s.bankingFixDone)
                                } else {
                                    snackbar.showSnackbar(s.bankingFixFailed)
                                }
                            }
                        },
                    )
                }
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(s.mixingSearchApps) },
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
                                    val ok = PrivilegedManager
                                        .setAudioFocusIgnored(app.packageName, want)
                                    if (ok) {
                                        prefs.setEnabled(app.packageName, want)
                                        enabled = prefs.enabledPackages()
                                    } else {
                                        snackbar.showSnackbar(s.mixingCouldntUpdate(app.label))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (systemVolumePanel) {
            SystemPanelBlocker(onOpenOverlaySettings = onOpenOverlaySettings)
        }
    }
}

/**
 * Covers the greyed-out mixing page while Android's own volume panel is in charge. The scrim
 * swallows every pointer event so nothing underneath can be tapped or scrolled; the card on top of
 * it is the only thing left to interact with.
 */
@Composable
private fun SystemPanelBlocker(onOpenOverlaySettings: () -> Unit) {
    val s = strings()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = s.mixingDisabledTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = s.mixingDisabledBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenOverlaySettings) { Text(s.mixingGoToOverlaySettings) }
            }
        }
    }
}

@Composable
private fun WarningBanner(onDismiss: () -> Unit) {
    val s = strings()
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
                text = s.mixingWarning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = s.dismiss,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The call to action that clears a banking app's "downloaded from an unofficial app store" flag.
 * Shown on the mixing tab because the fix reuses the privileged link set up here, and only while the
 * install source still looks sideloaded — so it disappears the moment it succeeds. [working] shows a
 * spinner for the brief window before the reinstall's commit tears the UI down.
 */
@Composable
private fun BankingFixCard(working: Boolean, onFix: () -> Unit) {
    val s = strings()
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = s.bankingFixTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = s.bankingFixBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onFix, enabled = !working) { Text(s.bankingFixAction) }
                if (working) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Confirms the in-place reinstall, warning that the app closes and reopens once. */
@Composable
private fun BankingFixDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = strings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.bankingFixConfirmTitle) },
        text = { Text(s.bankingFixConfirmBody) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(s.bankingFixConfirmButton) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun HideSystemToggle(hideSystem: Boolean, onChange: (Boolean) -> Unit) {
    val s = strings()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp),
    ) {
        Text(
            text = s.mixingHideSystemApps,
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

/**
 * The three things that have to be true before mixing works, as a checklist that ticks itself off
 * while the user goes and does them. A step stays greyed out until the one above it is done —
 * there's nothing useful to do in it yet, and usually nothing to measure either: whether Shizuku
 * has authorised us is unknowable until its service is actually running.
 */
@Composable
private fun SetupChecklist(setup: PrivilegedManager.Setup) {
    val s = strings()
    val context = LocalContext.current
    val openShizuku: () -> Unit = {
        val launch = context.packageManager.getLaunchIntentForPackage(PrivilegedManager.PACKAGE_NAME)
        // No launcher activity means a broken install; the download page beats a dead button.
        if (launch != null) {
            runCatching { context.startActivity(launch) }
        } else {
            openShizukuReleases(context)
        }
    }

    // Strictly cumulative: a step counts as done only if everything above it is, and unlocks only
    // once the step above is done. Keying each step off its own fact instead let a later one report
    // green — or worse, unlock — while an earlier one was still red, which is nonsense to read and
    // nonsense to act on.
    val installed = setup.installed
    val running = installed && setup.serviceRunning
    val granted = running && setup.accessGranted && !setup.connectFailed

    // Root mode replaces the whole Shizuku route rather than adding to it, so it gets the page to
    // itself while it's selected.
    if (setup.backend == PrivilegedManager.Backend.ROOT) {
        RootChecklist(setup = setup)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (setup.rootAvailable) s.setupIntroRooted else s.setupIntroShizuku,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (setup.rootAvailable) RootShortcut()
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                SetupStep(
                    number = 1,
                    title = if (installed) s.setupShizukuInstalled else s.setupShizukuNotInstalled,
                    detail = s.setupShizukuInstallDetail,
                    done = installed,
                    unlocked = true,
                    actionLabel = s.install,
                    onAction = { openShizukuReleases(context) },
                )
                SetupStep(
                    number = 2,
                    title = if (running) s.setupServiceRunning else s.setupServiceNotRunning,
                    detail = if (setup.serverUnusable) s.setupServerUnusableDetail
                    else s.setupServiceStartDetail,
                    done = running,
                    unlocked = installed,
                    actionLabel = if (setup.serverUnusable) s.setupRestartShizuku else s.setupSetUpNow,
                    onAction = openShizuku,
                )
                SetupStep(
                    number = 3,
                    title = if (granted) s.setupAccessGranted else s.setupGrantAccessTitle,
                    detail = when {
                        !running -> s.setupAccessDetail
                        setup.connectFailed -> s.setupConnectFailedDetail
                        setup.connecting -> s.setupConnectingDetail
                        else -> s.setupAccessDetail
                    },
                    done = granted,
                    unlocked = running,
                    actionLabel = if (running && setup.connectFailed) s.tryAgain else s.setupGrantAccess,
                    onAction = {
                        if (setup.connectFailed) {
                            PrivilegedManager.retry()
                        } else if (!PrivilegedManager.requestPermission()) {
                            // Nothing to prompt with — a pre-v11 Shizuku, or an earlier "deny and
                            // don't ask again". Authorising from inside Shizuku is the way through.
                            openShizuku()
                        }
                    },
                    busy = running && setup.connecting,
                )
            }
        }
    }
}

/**
 * The one-tap way past the Shizuku checklist on a rooted device. Offered alongside the three steps
 * rather than instead of them: [PrivilegedManager.Setup.rootAvailable] is a prompt-free guess, so it
 * can be wrong, and the Shizuku route has to stay right there when it is.
 */
@Composable
private fun RootShortcut() {
    val s = strings()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.rootShortcutTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = s.rootShortcutBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { PrivilegedManager.useRoot() }) { Text(s.rootUse) }
        }
    }
}

/**
 * What the mixing page shows while root mode is selected but not yet connected. The Shizuku steps
 * have nothing to say here — there's no app to install and no grant to chase, just a superuser
 * prompt that has been answered or hasn't — so this stands in for the lot of them, and always
 * carries the way back to Shizuku in case root turns out not to be there.
 */
@Composable
private fun RootChecklist(setup: PrivilegedManager.Setup) {
    val s = strings()
    val failed = setup.rootDenied || setup.connectFailed
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = s.rootRunning,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                SetupStep(
                    number = 1,
                    title = when {
                        setup.rootDenied -> s.rootRefused
                        setup.connectFailed -> s.rootHelperFailed
                        else -> s.rootGranting
                    },
                    detail = when {
                        setup.rootDenied -> s.rootRefusedDetail
                        setup.connectFailed -> s.rootHelperFailedDetail
                        else -> s.rootGrantingDetail
                    },
                    done = false,
                    unlocked = true,
                    actionLabel = s.tryAgain,
                    onAction = { PrivilegedManager.useRoot() },
                    busy = !failed && setup.connecting,
                )
            }
        }
        TextButton(
            onClick = { PrivilegedManager.useShizuku() },
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Text(s.rootUseShizukuInstead)
        }
    }
}

/** Shizuku's release page, the only official place to get it. */
private fun openShizukuReleases(context: Context) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://github.com/RikkaApps/Shizuku/releases"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * One checklist row: ticked green when [done], red and carrying its action while it's the step to
 * do next, greyed out with no action at all until [unlocked].
 */
@Composable
private fun SetupStep(
    number: Int,
    title: String,
    detail: String,
    done: Boolean,
    unlocked: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    busy: Boolean = false,
) {
    val accent = when {
        done -> successColor
        unlocked -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (unlocked) 1f else DISABLED_ALPHA)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(
                if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline,
            ),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings().setupStep(number, title),
                style = MaterialTheme.typography.bodyLarge,
                color = accent,
            )
            if (!done || busy) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unlocked && !done && !busy) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
