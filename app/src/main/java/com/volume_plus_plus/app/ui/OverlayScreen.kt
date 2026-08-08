package com.volume_plus_plus.app.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.data.OverlayPrefs
import com.volume_plus_plus.app.overlay.AppVolumeController
import com.volume_plus_plus.app.overlay.EditOrientation
import com.volume_plus_plus.app.overlay.LiveEditMode
import com.volume_plus_plus.app.overlay.LiveEditSession
import com.volume_plus_plus.app.overlay.OverlayController
import com.volume_plus_plus.app.overlay.OverlayVersion
import com.volume_plus_plus.app.service.VolumeKeyService

/**
 * Setup screen for the volume-key overlay. Guides the user through the three grants it needs —
 * draw-over-other-apps (SYSTEM_ALERT_WINDOW), enabling the accessibility service, and Do Not
 * Disturb / notification-policy access (needed to switch the ringer to vibrate/silent from the
 * overlay) — and shows whether per-app volume (Shizuku + Android 13) is available. State re-reads
 * on resume so returning from a Settings screen reflects immediately.
 */
@Composable
fun OverlayScreen(contentPadding: PaddingValues) {
    var editVersion by rememberSaveable { mutableStateOf<String?>(null) }

    val version = editVersion?.let { name -> runCatching { OverlayVersion.valueOf(name) }.getOrNull() }
    if (version == null) {
        OverlaySetup(
            contentPadding = contentPadding,
            onEdit = { editVersion = it.name },
        )
        return
    }

    val context = LocalContext.current
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
    )
}

@Composable
private fun OverlaySetup(
    contentPadding: PaddingValues,
    onEdit: (OverlayVersion) -> Unit,
) {
    val context = LocalContext.current

    val prefs = remember { OverlayPrefs(context) }
    var version by remember { mutableStateOf(OverlayVersion.current(prefs)) }
    var systemVolumePanel by remember { mutableStateOf(prefs.isSystemVolumePanelEnabled()) }
    var holdFollowScale by remember { mutableStateOf(prefs.getHoldFollowScale()) }
    var holdSettleScale by remember { mutableStateOf(prefs.getHoldSettleScale()) }
    var holdStepHaptics by remember { mutableStateOf(prefs.isHoldStepHapticsEnabled()) }
    var holdStepHapticIntensity by remember { mutableStateOf(prefs.getHoldStepHapticIntensity()) }

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
        holdFollowScale = prefs.getHoldFollowScale()
        holdSettleScale = prefs.getHoldSettleScale()
            holdStepHaptics = prefs.isHoldStepHapticsEnabled()
            holdStepHapticIntensity = prefs.getHoldStepHapticIntensity()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Overlay", subtitle = "Replace the system volume panel")

        InfoCard(
            "Press the volume keys anywhere to open Volume++'s own panel with a slider for each " +
                "app that's playing. Needs the two permissions below. Per-app sliders also require " +
                "Shizuku running and Android 13+.",
        )

        StatusStep(
            title = "1. Draw over other apps",
            done = canOverlay,
            actionLabel = "Grant",
            onAction = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )

        StatusStep(
            title = "2. Enable the accessibility service",
            subtitle = "Volume++ overlay — needed to catch the volume keys.",
            done = accessibilityOn,
            actionLabel = "Open settings",
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )

        StatusStep(
            title = "3. Allow Do Not Disturb access",
            subtitle = "Needed to switch the ringer to vibrate/silent from the overlay.",
            done = dndAccessOn,
            actionLabel = "Open settings",
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
                systemVolumePanel -> "Android's own volume panel is in use — the overlay stays off."
                ready -> "Ready — press a volume key to try it."
                else -> "Complete all three steps above to activate the overlay."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready && !systemVolumePanel) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        SettingSwitch(
            title = "Use system volume control",
            subtitle = "Leave the volume keys to Android's built-in panel instead of the overlay.",
            checked = systemVolumePanel,
            onCheckedChange = {
                systemVolumePanel = it
                prefs.setSystemVolumePanelEnabled(it)
            },
        )

        // The style only describes the overlay, so it has nothing to drive while the system panel is
        // in charge: the whole section greys out and stops responding until the switch goes back off.
        val styleEnabled = !systemVolumePanel
        Text(
            text = "Style",
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
            text = "Motion",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        InfoCard(
            "These settings scale the overlay's easing while you hold the volume key. " +
                "Leave both at 100% to keep the current behavior, or nudge them if you want the " +
                "panel to catch up faster or settle more softly.",
        )
        SettingSlider(
            title = "Hold follow speed",
            value = holdFollowScale,
            onValueChange = {
                holdFollowScale = it
                prefs.setHoldFollowScale(it)
            },
        )
        SettingSlider(
            title = "Hold settle speed",
            value = holdSettleScale,
            onValueChange = {
                holdSettleScale = it
                prefs.setHoldSettleScale(it)
            },
        )
        Text(
            text = "Haptics",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        InfoCard(
            "Optional tap feedback for repeated volume steps. The intensity slider keeps the " +
                "same default feel, but you can make it lighter or stronger if you want.",
        )
        SettingSwitch(
            title = "Step haptics while holding",
            subtitle = "Light tap feedback on each repeated volume step.",
            checked = holdStepHaptics,
            onCheckedChange = {
                holdStepHaptics = it
                prefs.setHoldStepHapticsEnabled(it)
            },
        )
        SettingSlider(
            title = "Haptic intensity",
            value = holdStepHapticIntensity,
            valueRange = 0.5f..2.0f,
            steps = 150,
            onValueChange = {
                holdStepHapticIntensity = it
                prefs.setHoldStepHapticIntensity(it)
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
            Text(if (canOverlay) "Preview" else "Grant overlay to preview")
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0.5f..2.0f,
    steps: Int = 149,
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
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${(value * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            VolumeSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
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
                    TextButton(onClick = { onEdit(option) }, enabled = enabled) { Text("Edit") }
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
            contentDescription = if (done) "Done" else "Not done",
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
