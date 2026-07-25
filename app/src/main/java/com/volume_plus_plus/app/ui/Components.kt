package com.volume_plus_plus.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Screen title + one-line subtitle, shared by all three tabs. */
@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tonal explainer block — the one "contained" element in the flat design language. */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * The app's slider: thick rounded track with a bar-shaped thumb, in the style of the Android 15
 * system volume panel. [steps] is kept so values still snap to real volume indices, but the tick
 * dots are hidden for a clean look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        thumbColor = MaterialTheme.colorScheme.primary,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = colors,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                enabled = enabled,
                thumbSize = DpSize(width = 5.dp, height = 40.dp),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                enabled = enabled,
                thumbTrackGapSize = 5.dp,
                trackInsideCornerSize = 4.dp,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                modifier = Modifier.height(32.dp),
            )
        },
    )
}
