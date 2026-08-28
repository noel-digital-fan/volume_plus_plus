package com.volume_plus_plus.app.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.config.AppConfig
import com.volume_plus_plus.app.data.OverlayCustomizationPrefs
import com.volume_plus_plus.app.data.OverlayPrefs
import com.volume_plus_plus.app.i18n.strings
import com.volume_plus_plus.app.overlay.AppVolumeController
import com.volume_plus_plus.app.overlay.EditOrientation
import com.volume_plus_plus.app.overlay.LiveEditMode
import com.volume_plus_plus.app.overlay.LiveEditSession
import com.volume_plus_plus.app.overlay.OverlayController
import com.volume_plus_plus.app.overlay.OverlayVersion
import com.volume_plus_plus.app.overlay.StepHaptics
import com.volume_plus_plus.app.service.VolumeKeyService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Three 900ms pulses ≈ the ~3s spotlight the Mixing tab asks for, plus the scroll that precedes it. */
private const val HIGHLIGHT_PULSES = 3
private const val HIGHLIGHT_PULSE_MS = 450

/**
 * Setup screen for the volume-key overlay. Guides the user through the three grants it needs —
 * draw-over-other-apps (SYSTEM_ALERT_WINDOW), enabling the accessibility service, and Do Not
 * Disturb / notification-policy access (needed to switch the ringer to vibrate/silent from the
 * overlay) — and shows whether per-app volume (Shizuku + Android 13) is available. State re-reads
 * on resume so returning from a Settings screen reflects immediately.
 *
 * [highlightSystemVolumeSwitch] is the Mixing tab asking us to point at the one setting that blocks
 * mixing; it plays once and then reports back via [onHighlightShown]. [snackbar] carries the short
 * confirmations the per-style edit hub reports back with.
 */
@Composable
fun OverlayScreen(
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    highlightSystemVolumeSwitch: Boolean = false,
    onHighlightShown: () -> Unit = {},
) {
    var editVersion by rememberSaveable { mutableStateOf<String?>(null) }

    val version = editVersion?.let { name -> runCatching { OverlayVersion.valueOf(name) }.getOrNull() }
    if (version == null) {
        OverlaySetup(
            contentPadding = contentPadding,
            onEdit = { editVersion = it.name },
            highlightSystemVolumeSwitch = highlightSystemVolumeSwitch,
            onHighlightShown = onHighlightShown,
        )
        return
    }

    val s = strings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customizationPrefs = remember(context) { OverlayCustomizationPrefs(context) }
    val deviceOrientation =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
            EditOrientation.LANDSCAPE else EditOrientation.PORTRAIT

    // Launch a WYSIWYG on-screen editor (position or colour): keep the app in front, lock it to the
    // target orientation and dim it to a neutral backdrop, then draw the real panel on top to edit
    // directly. Works the same for portrait and landscape (reliable even when the launcher is
    // portrait-locked, because the app itself supplies the surface).
    fun launchLiveEdit(orientation: EditOrientation, mode: LiveEditMode) {
        val activity = context as? Activity ?: return
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        LiveEditSession.launch(
            activity = activity,
            version = version,
            orientation = orientation,
            mode = mode,
            onFinished = {},
        )
    }

    BackHandler { editVersion = null }
    OverlayEditHub(
        version = version,
        contentPadding = contentPadding,
        onBack = { editVersion = null },
        // Colours are shared across orientations, so edit them in whatever way the device is held now.
        onEditColors = { launchLiveEdit(deviceOrientation, LiveEditMode.COLOR) },
        onEditPosition = { orientation -> launchLiveEdit(orientation, LiveEditMode.POSITION) },
        // Both restores write straight through for this version alone; the overlay re-reads its
        // customization every time it renders, so the next panel already comes up restored.
        onRestorePosition = {
            customizationPrefs.restoreDefaultPosition(version)
            scope.launch { snackbar.showSnackbar(s.editRestoredPosition(version.label)) }
        },
        onRestoreColors = {
            customizationPrefs.restoreDefaultColors(version)
            scope.launch { snackbar.showSnackbar(s.editRestoredColours(version.label)) }
        },
    )
}

@Composable
private fun OverlaySetup(
    contentPadding: PaddingValues,
    onEdit: (OverlayVersion) -> Unit,
    highlightSystemVolumeSwitch: Boolean,
    onHighlightShown: () -> Unit,
) {
    val s = strings()
    val context = LocalContext.current

    val prefs = remember { OverlayPrefs(context) }
    var version by remember { mutableStateOf(OverlayVersion.current(prefs)) }
    var systemVolumePanel by remember { mutableStateOf(prefs.isSystemVolumePanelEnabled()) }
    var settingsOpensApp by remember { mutableStateOf(prefs.isSettingsOpensAppEnabled()) }
    var holdFollowScale by remember { mutableStateOf(prefs.getHoldFollowScale()) }
    var holdSettleScale by remember { mutableStateOf(prefs.getHoldSettleScale()) }
    var holdStepHaptics by remember { mutableStateOf(prefs.isHoldStepHapticsEnabled()) }
    var holdStepHapticIntensity by remember { mutableStateOf(prefs.getHoldStepHapticIntensity()) }

    // The same vibrator the accessibility service ticks through, so the sample the user feels while
    // setting the level is exactly what a held volume key will give them.
    val vibrator = remember(context) { StepHaptics.vibrator(context) }

    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var dndAccessOn by remember { mutableStateOf(hasDndAccess(context)) }

    // A dedicated controller so "Preview" can show the panel without the accessibility service. It
    // gets its own volume store, left unstarted: the preview is transient, so it needs no background
    // re-apply loop (the accessibility service owns the real one once enabled).
    val previewVolume = remember { AppVolumeController(context.applicationContext) }
    val previewController = remember { OverlayController(context.applicationContext, previewVolume) }
    DisposableEffect(Unit) {
        onDispose {
            previewController.destroy()
            previewVolume.destroy()
        }
    }

    LifecycleResumeEffect(Unit) {
        canOverlay = Settings.canDrawOverlays(context)
        accessibilityOn = isAccessibilityEnabled(context)
        dndAccessOn = hasDndAccess(context)
        systemVolumePanel = prefs.isSystemVolumePanelEnabled()
        settingsOpensApp = prefs.isSettingsOpensAppEnabled()
        holdFollowScale = prefs.getHoldFollowScale()
        holdSettleScale = prefs.getHoldSettleScale()
            holdStepHaptics = prefs.isHoldStepHapticsEnabled()
            holdStepHapticIntensity = prefs.getHoldStepHapticIntensity()
        onPauseOrDispose { }
    }

    val scrollState = rememberScrollState()
    // Root-space vertical centres of the scroll viewport and of the "Use system volume control"
    // card. Both are only known after the first layout pass, so the spotlight below waits for them.
    var viewportCenter by remember { mutableStateOf<Float?>(null) }
    var switchCenter by remember { mutableStateOf<Float?>(null) }
    val highlight = remember { Animatable(0f) }
    val markHighlightShown by rememberUpdatedState(onHighlightShown)

    // The Mixing tab's one-shot spotlight: scroll the switch into view, pulse it for ~3s, then tell
    // the caller it has played. Reported from `finally` too, so leaving the tab mid-pulse still
    // counts as shown rather than replaying on the way back.
    LaunchedEffect(highlightSystemVolumeSwitch) {
        if (!highlightSystemVolumeSwitch) return@LaunchedEffect
        try {
            val (cardY, viewportY) = snapshotFlow { switchCenter to viewportCenter }
                .first { (card, viewport) -> card != null && viewport != null }
            // Rest with the switch centred in the viewport; animateScrollBy clamps on its own when
            // the list can't travel that far (e.g. the switch is already near the end).
            scrollState.animateScrollBy(cardY!! - viewportY!!)
            repeat(HIGHLIGHT_PULSES) {
                highlight.animateTo(1f, tween(HIGHLIGHT_PULSE_MS))
                highlight.animateTo(0f, tween(HIGHLIGHT_PULSE_MS))
            }
        } finally {
            markHighlightShown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            // Outside `verticalScroll`, so this measures the viewport and not the scrolled content.
            .onGloballyPositioned {
                viewportCenter = it.positionInRoot().y + it.size.height / 2f
            }
            .verticalScroll(scrollState),
    ) {
        ScreenHeader(title = s.overlayTitle, subtitle = s.overlaySubtitle)

        InfoCard(s.overlayIntro)

        StatusStep(
            title = s.setupStep(1, s.overlayStepDrawOver),
            done = canOverlay,
            actionLabel = s.grant,
            onAction = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )

        StatusStep(
            title = s.setupStep(2, s.overlayStepAccessibility),
            subtitle = s.overlayStepAccessibilityDetail,
            done = accessibilityOn,
            actionLabel = s.openSettings,
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )

        StatusStep(
            title = s.setupStep(3, s.overlayStepDnd),
            subtitle = s.overlayStepDndDetail,
            done = dndAccessOn,
            actionLabel = s.openSettings,
            onAction = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )

        val ready = canOverlay && accessibilityOn && dndAccessOn
        Text(
            text = when {
                // With the system panel in charge the overlay never opens, so don't claim it's ready.
                systemVolumePanel -> s.overlaySystemPanelInUse
                ready -> s.overlayReady
                else -> s.overlayIncomplete
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready && !systemVolumePanel) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        SettingSwitch(
            title = s.overlayUseSystemPanel,
            subtitle = s.overlayUseSystemPanelDetail,
            checked = systemVolumePanel,
            onCheckedChange = {
                systemVolumePanel = it
                prefs.setSystemVolumePanelEnabled(it)
            },
            highlight = highlight.value,
            modifier = Modifier.onGloballyPositioned {
                switchCenter = it.positionInRoot().y + it.size.height / 2f
            },
        )

        // The style only describes the overlay, so it has nothing to drive while the system panel is
        // in charge: the whole section greys out and stops responding until the switch goes back off.
        val styleEnabled = !systemVolumePanel
        // Where the expanded sheet's SETTINGS / SEE MORE button goes. Kept up here with the other
        // overlay switch rather than under the style list, which is long enough to push it out of
        // sight. Only the Android 9–15 panels draw that button, so with the 7–8 style selected there
        // is nothing to redirect and the switch is left out entirely.
        if (version.hasExpanded) {
            SettingSwitch(
                title = s.overlaySettingsOpensApp,
                subtitle = s.overlaySettingsOpensAppDetail,
                checked = settingsOpensApp,
                enabled = styleEnabled,
                onCheckedChange = {
                    settingsOpensApp = it
                    prefs.setSettingsOpensAppEnabled(it)
                },
            )
        }
        Text(
            text = s.overlayStyle,
            style = MaterialTheme.typography.titleMedium,
            color = if (styleEnabled) Color.Unspecified else disabledContentColor(),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        SkinPicker(
            selected = version,
            enabled = styleEnabled,
            onSelect = {
                version = it
                it.apply(prefs)
            },
            onEdit = onEdit,
        )
        Text(
            text = s.overlayMotion,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        InfoCard(s.overlayMotionInfo)
        SettingSlider(
            title = s.overlayHoldFollowSpeed,
            info = s.overlayHoldFollowSpeedInfo,
            value = holdFollowScale,
            onValueChange = {
                holdFollowScale = it
                prefs.setHoldFollowScale(it)
            },
        )
        SettingSlider(
            title = s.overlayHoldSettleSpeed,
            info = s.overlayHoldSettleSpeedInfo,
            value = holdSettleScale,
            onValueChange = {
                holdSettleScale = it
                prefs.setHoldSettleScale(it)
            },
        )
        Text(
            text = s.overlayHaptics,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        InfoCard(s.overlayHapticsInfo)
        SettingSwitch(
            title = s.overlayStepHaptics,
            subtitle = s.overlayStepHapticsDetail,
            info = s.overlayStepHapticsInfo,
            checked = holdStepHaptics,
            onCheckedChange = {
                holdStepHaptics = it
                prefs.setHoldStepHapticsEnabled(it)
                if (it) StepHaptics.play(vibrator, holdStepHapticIntensity)
            },
        )
        SettingSlider(
            title = s.overlayHapticIntensity,
            info = s.overlayHapticIntensityInfo,
            // Nothing to tune while the step haptic is switched off, so the row greys out with it —
            // the ⓘ stays live, so it can still be read to find out what the switch would give you.
            enabled = holdStepHaptics,
            value = holdStepHapticIntensity,
            valueRange = 0.5f..2.0f,
            steps = 150,
            onValueChange = {
                val previous = holdStepHapticIntensity
                holdStepHapticIntensity = it
                prefs.setHoldStepHapticIntensity(it)
                // Sample as it's dragged, but only each 10% of travel — the slider emits far too
                // many values to buzz on every one, and a tick per notch reads as a detent.
                if ((it * 10).toInt() != (previous * 10).toInt()) StepHaptics.play(vibrator, it)
            },
        )
        Button(
            onClick = {
                if (canOverlay) previewController.show()
                else {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(if (canOverlay) s.overlayPreview else s.overlayGrantToPreview)
        }

        // Which build this is. The app's version is centralised in AppConfig, which reads it back
        // from the one place it's declared (app/build.gradle.kts).
        Text(
            text = s.appVersion(AppConfig.APP_NAME, AppConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        )
    }
}

/** A titled slider. [info] adds the ⓘ button that explains, in plain words, what it changes. */
@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0.5f..2.0f,
    steps: Int = 149,
    info: String? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) Color.Unspecified else disabledContentColor(),
                    )
                    Text(
                        text = strings().percent((value * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else disabledContentColor(),
                    )
                }
                if (info != null) InfoButton(title = title, body = info)
            }
            VolumeSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A titled switch row. [highlight] (0..1) tints the card and draws a matching outline, so an
 * animation driving it makes the row pulse — used to point the user at one specific setting.
 * [info] adds the ⓘ button that explains, in plain words, what the switch actually does.
 */
@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Float = 0f,
    info: String? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = lerp(
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.colorScheme.primaryContainer,
                highlight,
            ),
        ),
        border = if (highlight > 0f) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = highlight))
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) Color.Unspecified else disabledContentColor(),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else disabledContentColor(),
                )
            }
            if (info != null) InfoButton(title = title, body = info)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}

/**
 * The ⓘ next to a setting: a small button that opens the setting's own plain-language explanation.
 * Kept as a dialog rather than a third line of subtitle — these explanations are a paragraph long,
 * and only wanted by someone who went looking for them.
 */
@Composable
private fun InfoButton(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = strings().moreInfo,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(strings().done) }
            },
        )
    }
}

/**
 * The style list. With [enabled] false every row is inert — no selecting, no per-style editor — and
 * greyed to match, so the section reads as unavailable rather than merely unresponsive.
 */
@Composable
private fun SkinPicker(
    selected: OverlayVersion,
    onSelect: (OverlayVersion) -> Unit,
    onEdit: (OverlayVersion) -> Unit,
    enabled: Boolean = true,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            OverlayVersion.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == selected,
                            enabled = enabled,
                            onClick = { onSelect(option) },
                        )
                        .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    RadioButton(
                        selected = option == selected,
                        enabled = enabled,
                        onClick = { onSelect(option) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) Color.Unspecified else disabledContentColor(),
                        modifier = Modifier.weight(1f),
                    )
                    // Each style gets its own independent editor (position + colours).
                    TextButton(onClick = { onEdit(option) }, enabled = enabled) {
                        Text(strings().overlayEdit)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStep(
    title: String,
    subtitle: String? = null,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val s = strings()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(
                if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline,
            ),
            contentDescription = if (done) s.done else s.notDone,
            tint = if (done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!done) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Text colour for a disabled control — Material 3's disabled opacity, as used by the greyed rows
 *  on the Volume tab, so a switched-off section looks the same wherever it appears. */
@Composable
private fun disabledContentColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

/** True if our [VolumeKeyService] appears in the system's enabled-accessibility-services list. */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, VolumeKeyService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

/** True if we hold Do Not Disturb / notification-policy access, needed to change the ringer mode. */
private fun hasDndAccess(context: Context): Boolean {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return notificationManager.isNotificationPolicyAccessGranted
}
